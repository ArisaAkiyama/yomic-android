package id.yomic.mobile.data.service

import id.yomic.mobile.data.local.CategoryEntity
import id.yomic.mobile.data.local.ChapterDao
import id.yomic.mobile.data.local.ChapterEntity
import id.yomic.mobile.data.local.LocalEntityMappers
import id.yomic.mobile.data.local.MangaDao
import id.yomic.mobile.data.local.MangaEntity
import id.yomic.mobile.data.local.MangaWithRelations
import id.yomic.mobile.data.preferences.SettingsRepository
import id.yomic.mobile.data.repository.LibraryRepository
import id.yomic.mobile.engine.js.JsExtensionManager
import id.yomic.mobile.model.ActivityUpdateItem
import id.yomic.mobile.model.MangaStatus
import id.yomic.mobile.model.SourceLang
import id.yomic.mobile.model.SyncProgressState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class LibrarySyncSummary(
    val checkedCount: Int = 0,
    val skippedCount: Int = 0,
    val notModifiedCount: Int = 0,
    val totalNewChapters: Int = 0,
    val updatedMangaTitles: List<String> = emptyList(),
    val failedCount: Int = 0,
    val skippedOffline: Boolean = false,
    val alreadyRunning: Boolean = false
)

/**
 * Real Library Update / Sync Engine:
 * - Smart Update filtering (skips completed titles, titles with unread chapters, and excluded categories)
 * - HTTP Conditional Caching (ETag / Last-Modified -> 304 Not Modified)
 * - Per-source rate-limiting (max 2 concurrent per domain, max 5 global)
 * - Reactive Updates feed grouped by manga & day
 */
class LibraryUpdateService(
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val extensionManager: JsExtensionManager,
    private val settingsRepository: SettingsRepository
) {
    private val isSyncing = AtomicBoolean(false)
    private val globalSemaphore = Semaphore(5)
    private val perSourceSemaphores = ConcurrentHashMap<String, Semaphore>()

    @Volatile
    private var lastSyncEpochMs: Long = 0L

    private val _syncProgress = MutableStateFlow(SyncProgressState())
    val syncProgress: StateFlow<SyncProgressState> = _syncProgress.asStateFlow()

    fun observeActivityUpdates(isEn: () -> Boolean): Flow<List<ActivityUpdateItem>> =
        combine(
            chapterDao.observeRecentLibraryUpdates(limit = 300),
            mangaDao.observeLibraryWithRelations()
        ) { recentChapters, libraryMangas ->
            groupChaptersIntoUpdates(
                recentChapters = recentChapters,
                libraryMap = libraryMangas.associateBy { it.manga.id },
                isEn = isEn()
            )
        }

    suspend fun clearAllUpdates() = withContext(Dispatchers.IO) {
        chapterDao.clearAllUpdateBadges()
    }

    suspend fun triggerStartupSyncIfEligible(): LibrarySyncSummary? {
        val settings = settingsRepository.getCurrentSettings()
        if (!settings.autoSyncStartup || settings.isOfflineMode) return null
        val elapsed = System.currentTimeMillis() - lastSyncEpochMs
        if (lastSyncEpochMs > 0L && elapsed < STARTUP_COOLDOWN_MS) return null
        return syncLibrary(respectSmartUpdate = settings.smartUpdate)
    }

    suspend fun syncLibrary(respectSmartUpdate: Boolean? = null): LibrarySyncSummary {
        val settings = settingsRepository.getCurrentSettings()
        if (settings.isOfflineMode) {
            return LibrarySyncSummary(skippedOffline = true)
        }
        if (!isSyncing.compareAndSet(false, true)) {
            return LibrarySyncSummary(alreadyRunning = true)
        }

        val useSmartUpdate = respectSmartUpdate ?: settings.smartUpdate
        return try {
            withContext(Dispatchers.IO) {
                val librarySnapshot = mangaDao.getLibraryMangasSnapshot()
                if (librarySnapshot.isEmpty()) {
                    return@withContext LibrarySyncSummary()
                }

                // Load relations for Smart Update evaluation
                val withRelations = librarySnapshot.mapNotNull { mangaDao.getWithRelations(it.id) }
                val targets = mutableListOf<MangaWithRelations>()
                var skipped = 0

                for (entry in withRelations) {
                    val skip = shouldSkipForSmartUpdate(
                        smartUpdateEnabled = useSmartUpdate,
                        manga = entry.manga,
                        chapters = entry.chapters,
                        categories = entry.categories
                    )
                    if (skip) {
                        skipped++
                    } else {
                        targets.add(entry)
                    }
                }

                if (targets.isEmpty()) {
                    lastSyncEpochMs = System.currentTimeMillis()
                    return@withContext LibrarySyncSummary(skippedCount = skipped)
                }

                val total = targets.size
                val completedCounter = AtomicInteger(0)
                val notModCounter = AtomicInteger(0)
                val newChCounter = AtomicInteger(0)
                val failedCounter = AtomicInteger(0)
                val updatedTitles = java.util.Collections.synchronizedList(mutableListOf<String>())

                _syncProgress.value = SyncProgressState(active = true, current = 0, total = total)

                coroutineScope {
                    targets.map { rel ->
                        async {
                            val srcKey = rel.manga.sourcePkg.ifBlank { rel.manga.numericSourceId.toString() }
                            val hostSem = perSourceSemaphores.getOrPut(srcKey) { Semaphore(2) }
                            globalSemaphore.withPermit {
                                hostSem.withPermit {
                                    try {
                                        val source = extensionManager.getSource(rel.manga.sourcePkg)
                                            ?: extensionManager.getSourceByNumericId(rel.manga.numericSourceId)
                                        if (source == null || rel.manga.url.isBlank()) {
                                            failedCounter.incrementAndGet()
                                        } else {
                                            val cacheRes = source.getLatestChaptersWithCache(
                                                mangaUrl = rel.manga.url,
                                                eTag = rel.manga.responseETag,
                                                lastModified = rel.manga.responseLastModified
                                            )
                                            val now = System.currentTimeMillis()
                                            if (cacheRes.isNotModified) {
                                                notModCounter.incrementAndGet()
                                            } else if (cacheRes.chapters.isNotEmpty()) {
                                                val (merged, newCount) = LibraryRepository.mergeChapters(
                                                    mangaId = rel.manga.id,
                                                    existing = rel.chapters,
                                                    incoming = cacheRes.chapters,
                                                    markNewAsUpdates = true,
                                                    nowEpoch = now
                                                )
                                                if (merged.isNotEmpty()) {
                                                    chapterDao.upsertAll(merged)
                                                }
                                                val nextUpdateEpoch = if (newCount > 0) now else rel.manga.lastUpdateEpoch
                                                mangaDao.updateSyncMetadata(
                                                    mangaId = rel.manga.id,
                                                    epoch = nextUpdateEpoch,
                                                    eTag = cacheRes.eTag ?: rel.manga.responseETag,
                                                    lastModified = cacheRes.lastModified ?: rel.manga.responseLastModified
                                                )
                                                if (newCount > 0) {
                                                    newChCounter.addAndGet(newCount)
                                                    updatedTitles.add(rel.manga.title)
                                                }
                                            }
                                        }
                                    } catch (_: Exception) {
                                        failedCounter.incrementAndGet()
                                    } finally {
                                        val done = completedCounter.incrementAndGet()
                                        _syncProgress.value = SyncProgressState(active = true, current = done, total = total)
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }

                lastSyncEpochMs = System.currentTimeMillis()
                LibrarySyncSummary(
                    checkedCount = total,
                    skippedCount = skipped,
                    notModifiedCount = notModCounter.get(),
                    totalNewChapters = newChCounter.get(),
                    updatedMangaTitles = updatedTitles.toList(),
                    failedCount = failedCounter.get()
                )
            }
        } finally {
            _syncProgress.value = SyncProgressState(active = false, current = 0, total = 0)
            isSyncing.set(false)
        }
    }

    companion object {
        private const val STARTUP_COOLDOWN_MS = 15L * 60_000L

        fun shouldSkipForSmartUpdate(
            smartUpdateEnabled: Boolean,
            manga: MangaEntity,
            chapters: List<ChapterEntity>,
            categories: List<CategoryEntity>
        ): Boolean {
            if (!smartUpdateEnabled) return false
            if (manga.status.equals(MangaStatus.COMPLETED.name, ignoreCase = true)) return true
            if (categories.isNotEmpty() && categories.all { it.updateExcluded }) return true
            val hasUnread = chapters.any { !it.isRead }
            if (chapters.isNotEmpty() && hasUnread) return true
            return false
        }

        fun groupChaptersIntoUpdates(
            recentChapters: List<ChapterEntity>,
            libraryMap: Map<String, MangaWithRelations>,
            isEn: Boolean
        ): List<ActivityUpdateItem> {
            if (recentChapters.isEmpty()) return emptyList()

            val grouped = LinkedHashMap<Pair<String, Long>, MutableList<ChapterEntity>>()
            for (ch in recentChapters) {
                if (ch.dateFetchEpoch <= 0L) continue
                if (!libraryMap.containsKey(ch.mangaId)) continue
                val dayBucket = dayStartEpoch(ch.dateFetchEpoch)
                grouped.getOrPut(ch.mangaId to dayBucket) { mutableListOf() }.add(ch)
            }

            return grouped.values.mapNotNull { list ->
                val newest = list.maxWithOrNull(compareBy<ChapterEntity> { it.dateFetchEpoch }.thenBy { it.number })
                    ?: return@mapNotNull null
                val manga = libraryMap[newest.mangaId]?.manga ?: return@mapNotNull null
                val extra = (list.size - 1).coerceAtLeast(0)
                val anyUnreadNew = list.any { it.isNew && !it.isRead }
                ActivityUpdateItem(
                    id = "upd_${newest.id}",
                    mangaId = manga.id,
                    mangaTitle = manga.title,
                    chapterNumber = newest.number,
                    chapterTitle = newest.title,
                    sourceName = manga.sourceName,
                    lang = runCatching { SourceLang.valueOf(manga.lang.uppercase()) }.getOrDefault(SourceLang.ID),
                    timeAgo = LocalEntityMappers.formatRelativeTime(newest.dateFetchEpoch, isEn),
                    dateFetchEpoch = newest.dateFetchEpoch,
                    isNew = anyUnreadNew,
                    extraChaptersCount = extra
                )
            }.sortedByDescending { it.dateFetchEpoch }
        }

        private fun dayStartEpoch(epochMs: Long): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = epochMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis
        }
    }
}
