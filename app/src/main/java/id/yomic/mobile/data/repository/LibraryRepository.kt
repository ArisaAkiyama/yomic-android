package id.yomic.mobile.data.repository

import id.yomic.mobile.data.local.CategoryDao
import id.yomic.mobile.data.local.CategoryEntity
import id.yomic.mobile.data.local.ChapterDao
import id.yomic.mobile.data.local.ChapterEntity
import id.yomic.mobile.data.local.LocalEntityMappers
import id.yomic.mobile.data.local.LocalEntityMappers.toModel
import id.yomic.mobile.data.local.MangaCategoryCrossRef
import id.yomic.mobile.data.local.MangaDao
import id.yomic.mobile.data.local.MangaEntity
import id.yomic.mobile.engine.js.JsExtensionManager
import id.yomic.mobile.engine.js.RawJsChapter
import id.yomic.mobile.model.CategoryItem
import id.yomic.mobile.model.MangaItem
import id.yomic.mobile.model.MangaStatus
import id.yomic.mobile.model.MangaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.math.abs

data class DetailRefreshResult(
    val success: Boolean,
    val newChaptersCount: Int = 0,
    val error: String? = null
)

/**
 * Persistent Library, Category, Manga Detail, and Chapter repository backed by Room SQLite WAL
 * and live JS extension scrapers (`JsExtensionManager`).
 */
class LibraryRepository(
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val categoryDao: CategoryDao,
    private val extensionManager: JsExtensionManager
) {

    fun observeAllManga(isEn: () -> Boolean): Flow<List<MangaItem>> =
        mangaDao.observeAllWithRelations().map { rows ->
            val en = isEn()
            rows.map { it.toModel(isEn = en) }
        }

    fun observeCategories(): Flow<List<CategoryItem>> =
        categoryDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun ensureMangaCached(manga: MangaItem): MangaEntity = withContext(Dispatchers.IO) {
        val existing = mangaDao.getById(manga.id)
            ?: if (manga.numericSourceId != 0L && manga.url.isNotBlank()) {
                mangaDao.getBySourceAndUrl(manga.numericSourceId, manga.url)
            } else null

        if (existing != null) {
            val updated = existing.copy(
                title = manga.title.ifBlank { existing.title },
                thumbnailUrl = manga.thumbnailUrl.ifBlank { existing.thumbnailUrl },
                sourcePkg = manga.sourceId.ifBlank { existing.sourcePkg },
                sourceName = manga.sourceName.ifBlank { existing.sourceName }
            )
            mangaDao.upsert(updated)
            updated
        } else {
            val fresh = MangaEntity(
                id = manga.id,
                numericSourceId = manga.numericSourceId,
                sourcePkg = manga.sourceId,
                sourceName = manga.sourceName,
                lang = manga.lang.name,
                url = manga.url,
                title = sanitizeTitle(manga.title),
                altTitle = manga.altTitle,
                thumbnailUrl = manga.thumbnailUrl,
                author = manga.author.takeUnless { it == "-" }.orEmpty(),
                artist = manga.artist,
                status = manga.status.name,
                type = manga.type.name,
                rating = manga.rating,
                synopsis = manga.synopsis,
                genresJson = LocalEntityMappers.encodeGenres(manga.genres),
                inLibrary = manga.inLibrary,
                isExplicit = manga.isExplicit,
                dateAddedEpoch = if (manga.inLibrary) System.currentTimeMillis() else 0L,
                lastUpdateEpoch = manga.lastUpdateEpoch,
                lastViewedEpoch = manga.lastViewedEpoch,
                initialized = manga.initialized
            )
            mangaDao.upsert(fresh)
            fresh
        }
    }

    suspend fun toggleInLibrary(mangaId: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val existing = mangaDao.getById(mangaId) ?: return@withContext false to ""
        val nextInLib = !existing.inLibrary
        val now = System.currentTimeMillis()
        val cleanTitle = sanitizeTitle(existing.title)

        mangaDao.upsert(
            existing.copy(
                title = cleanTitle,
                inLibrary = nextInLib,
                dateAddedEpoch = if (nextInLib && existing.dateAddedEpoch == 0L) now else existing.dateAddedEpoch
            )
        )

        if (nextInLib) {
            val currentCats = categoryDao.getCategoryIdsForManga(mangaId)
            if (currentCats.isEmpty()) {
                val defaultCat = categoryDao.getDefaultCategory()
                if (defaultCat != null) {
                    categoryDao.addMangaToCategory(MangaCategoryCrossRef(mangaId = mangaId, categoryId = defaultCat.id))
                }
            }
        }
        nextInLib to cleanTitle
    }

    suspend fun refreshMangaDetailAndChapters(
        mangaId: String,
        forceRefresh: Boolean = false
    ): DetailRefreshResult = withContext(Dispatchers.IO) {
        val existing = mangaDao.getById(mangaId)
            ?: return@withContext DetailRefreshResult(false, error = "Komik tidak ditemukan di database.")
        val existingChapters = chapterDao.getChaptersForManga(mangaId)

        if (!forceRefresh && existing.initialized && existingChapters.isNotEmpty()) {
            return@withContext DetailRefreshResult(true, newChaptersCount = 0)
        }

        val source = extensionManager.getSource(existing.sourcePkg)
            ?: extensionManager.getSourceByNumericId(existing.numericSourceId)
            ?: return@withContext DetailRefreshResult(
                false,
                error = "Ekstensi '${existing.sourceName.ifBlank { existing.sourcePkg }}' belum terpasang."
            )

        if (existing.url.isBlank()) {
            return@withContext DetailRefreshResult(false, error = "URL komik kosong.")
        }

        return@withContext try {
            val (rawDetail, rawChapters) = coroutineScope {
                val detailDef = async { runCatching { source.getMangaDetails(existing.url) }.getOrNull() }
                val chaptersDef = async { runCatching { source.getChapterList(existing.url) }.getOrDefault(emptyList()) }
                detailDef.await() to chaptersDef.await()
            }

            if (rawDetail == null && rawChapters.isEmpty()) {
                return@withContext DetailRefreshResult(false, error = "Gagal mengambil detail & daftar bab dari ${source.name}.")
            }

            val mergedManga = existing.copy(
                title = sanitizeTitle(rawDetail?.title?.takeIf { it.isNotBlank() } ?: existing.title),
                thumbnailUrl = rawDetail?.thumbnailUrl?.takeIf { it.isNotBlank() } ?: existing.thumbnailUrl,
                author = rawDetail?.author?.takeIf { it.isNotBlank() } ?: existing.author,
                artist = rawDetail?.artist?.takeIf { it.isNotBlank() } ?: existing.artist,
                synopsis = rawDetail?.description?.takeIf { it.isNotBlank() } ?: existing.synopsis,
                status = rawDetail?.let { mapJsStatus(it.status).name } ?: existing.status,
                type = rawDetail?.type?.takeIf { it.isNotBlank() }?.let { mapJsType(it).name } ?: existing.type,
                genresJson = if (!rawDetail?.genres.isNullOrEmpty()) {
                    LocalEntityMappers.encodeGenres(rawDetail!!.genres)
                } else existing.genresJson,
                numericSourceId = source.id,
                sourceName = source.name.ifBlank { existing.sourceName },
                initialized = true
            )

            val now = System.currentTimeMillis()
            val markNewAsUpdates = existing.inLibrary && existing.initialized && existingChapters.isNotEmpty()
            val (mergedChapters, newCount) = mergeChapters(
                mangaId = mangaId,
                existing = existingChapters,
                incoming = rawChapters,
                markNewAsUpdates = markNewAsUpdates,
                nowEpoch = now
            )

            mangaDao.upsert(
                mergedManga.copy(
                    lastUpdateEpoch = if (newCount > 0) now else mergedManga.lastUpdateEpoch
                )
            )
            if (mergedChapters.isNotEmpty()) {
                chapterDao.upsertAll(mergedChapters)
            }

            DetailRefreshResult(success = true, newChaptersCount = newCount)
        } catch (e: Exception) {
            DetailRefreshResult(false, error = e.message ?: "Terjadi kesalahan saat memuat komik.")
        }
    }

    // =====================================================================
    // CATEGORIES
    // =====================================================================

    suspend fun addCategory(rawName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val name = rawName.trim()
        if (name.isEmpty()) return@withContext false to ""
        val existing = categoryDao.getAllSnapshot()
        if (existing.any { it.name.equals(name, ignoreCase = true) }) {
            return@withContext false to name
        }
        val nextSort = (existing.maxOfOrNull { it.sortOrder } ?: 0) + 1
        categoryDao.insert(CategoryEntity(name = name, sortOrder = nextSort, isDefault = false))
        true to name
    }

    suspend fun deleteCategory(categoryId: Int): String? = withContext(Dispatchers.IO) {
        val target = categoryDao.getAllSnapshot().find { it.id == categoryId } ?: return@withContext null
        categoryDao.deleteById(categoryId)
        target.name
    }

    suspend fun toggleMangaCategory(mangaId: String, categoryId: Int) = withContext(Dispatchers.IO) {
        val current = categoryDao.getCategoryIdsForManga(mangaId)
        if (categoryId in current) {
            categoryDao.removeMangaFromCategory(mangaId, categoryId)
        } else {
            categoryDao.addMangaToCategory(MangaCategoryCrossRef(mangaId, categoryId))
        }
    }

    suspend fun assignCategoryToMultiple(mangaIds: Set<String>, categoryId: Int): String = withContext(Dispatchers.IO) {
        if (mangaIds.isEmpty()) return@withContext ""
        val refs = mangaIds.map { MangaCategoryCrossRef(mangaId = it, categoryId = categoryId) }
        categoryDao.addMangasToCategory(refs)
        categoryDao.getAllSnapshot().find { it.id == categoryId }?.name.orEmpty()
    }

    // =====================================================================
    // CHAPTERS & BATCH ACTIONS
    // =====================================================================

    suspend fun toggleChapterBookmark(mangaId: String, chapterNumber: Double) = withContext(Dispatchers.IO) {
        val ch = chapterDao.getByMangaAndNumber(mangaId, chapterNumber) ?: return@withContext
        chapterDao.setBookmarked(mangaId, chapterNumber, !ch.isBookmarked)
    }

    suspend fun toggleChapterRead(mangaId: String, chapterNumber: Double) = withContext(Dispatchers.IO) {
        val ch = chapterDao.getByMangaAndNumber(mangaId, chapterNumber) ?: return@withContext
        chapterDao.setRead(mangaId, chapterNumber, !ch.isRead)
    }

    suspend fun markPreviousChaptersRead(mangaId: String, chapterNumber: Double) = withContext(Dispatchers.IO) {
        chapterDao.markPreviousRead(mangaId, chapterNumber)
    }

    suspend fun markAllChaptersRead(mangaId: String) = withContext(Dispatchers.IO) {
        chapterDao.markAllReadForManga(mangaId)
    }

    suspend fun markMultipleRead(mangaIds: Set<String>) = withContext(Dispatchers.IO) {
        if (mangaIds.isEmpty()) return@withContext
        chapterDao.markAllReadForMangas(mangaIds.toList())
    }

    suspend fun removeMultipleFromLibrary(mangaIds: Set<String>) = withContext(Dispatchers.IO) {
        if (mangaIds.isEmpty()) return@withContext
        mangaDao.removeMultipleFromLibrary(mangaIds.toList())
    }

    suspend fun updateChapterPageProgress(mangaId: String, chapterNumber: Double, page: Int, totalPages: Int) =
        withContext(Dispatchers.IO) {
            chapterDao.updatePageProgress(mangaId, chapterNumber, page, totalPages)
        }

    suspend fun markChapterOpened(mangaId: String, chapterNumber: Double, page: Int, totalPages: Int) =
        withContext(Dispatchers.IO) {
            chapterDao.markOpened(mangaId, chapterNumber, page, totalPages)
        }

    companion object {
        fun sanitizeTitle(raw: String): String {
            val trimmed = raw.trim()
            return when {
                trimmed.startsWith("Komik ", ignoreCase = true) -> trimmed.substring(6).trim()
                trimmed.startsWith("Manga ", ignoreCase = true) -> trimmed.substring(6).trim()
                else -> trimmed
            }.ifBlank { trimmed }
        }

        fun mapJsStatus(code: Int): MangaStatus = when (code) {
            2, 4 -> MangaStatus.COMPLETED
            6 -> MangaStatus.HIATUS
            else -> MangaStatus.ONGOING
        }

        fun mapJsType(raw: String): MangaType = when {
            raw.contains("manga", ignoreCase = true) -> MangaType.MANGA
            raw.contains("manhua", ignoreCase = true) -> MangaType.MANHUA
            else -> MangaType.MANHWA
        }

        fun generateChapterId(mangaId: String, chapterUrl: String): String {
            val digest = MessageDigest.getInstance("MD5")
                .digest("$mangaId|$chapterUrl".toByteArray(Charsets.UTF_8))
            val hex = digest.take(8).joinToString("") { "%02x".format(it) }
            return "ch_${mangaId}_$hex"
        }

        /**
         * Safely merges freshly scraped chapters with persisted Room chapters:
         * - Preserves `isRead`, `isBookmarked`, `isDownloaded`, `lastPageRead`, `totalPages`, `localPath`.
         * - Marks newly discovered chapters as `isNew = true` + `dateFetchEpoch = nowEpoch` ONLY when
         *   `markNewAsUpdates == true` (so initial detail open does not flood the Updates feed).
         */
        fun mergeChapters(
            mangaId: String,
            existing: List<ChapterEntity>,
            incoming: List<RawJsChapter>,
            markNewAsUpdates: Boolean,
            nowEpoch: Long
        ): Pair<List<ChapterEntity>, Int> {
            if (incoming.isEmpty()) return emptyList<ChapterEntity>() to 0

            val byUrl = existing.associateBy { it.url }
            val usedUrls = HashSet<String>(incoming.size)
            val result = ArrayList<ChapterEntity>(incoming.size)
            var newCount = 0

            val totalIncoming = incoming.size
            incoming.forEachIndexed { idx, raw ->
                val url = raw.url.trim()
                if (url.isEmpty() || !usedUrls.add(url)) return@forEachIndexed

                val resolvedNum = if (raw.chapterNumber >= 0.0) {
                    raw.chapterNumber
                } else {
                    // Fallback numbering assuming typical newest-first list order
                    (totalIncoming - idx).toDouble()
                }

                val match = byUrl[url]
                    ?: existing.firstOrNull { abs(it.number - resolvedNum) < 0.001 }

                if (match != null) {
                    result.add(
                        match.copy(
                            url = url,
                            title = raw.name.ifBlank { match.title },
                            number = resolvedNum,
                            dateUploadEpoch = if (raw.dateUploadEpoch > 0L) raw.dateUploadEpoch else match.dateUploadEpoch,
                            scanlator = raw.scanlator.ifBlank { match.scanlator }
                        )
                    )
                } else {
                    newCount++
                    result.add(
                        ChapterEntity(
                            id = generateChapterId(mangaId, url),
                            mangaId = mangaId,
                            url = url,
                            title = raw.name,
                            number = resolvedNum,
                            dateUploadEpoch = raw.dateUploadEpoch,
                            dateFetchEpoch = if (markNewAsUpdates) nowEpoch else 0L,
                            isRead = false,
                            isBookmarked = false,
                            isDownloaded = false,
                            isNew = markNewAsUpdates,
                            lastPageRead = 1,
                            totalPages = 0,
                            localPath = "",
                            scanlator = raw.scanlator
                        )
                    )
                }
            }
            return result to newCount
        }
    }
}
