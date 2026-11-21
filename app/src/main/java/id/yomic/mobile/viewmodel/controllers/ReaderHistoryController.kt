package id.yomic.mobile.viewmodel.controllers

import id.yomic.mobile.data.local.DailyReadingLogEntity
import id.yomic.mobile.data.repository.HistoryRepository
import id.yomic.mobile.data.repository.ReaderLoadResult
import id.yomic.mobile.model.GenreStatItem
import id.yomic.mobile.model.ReadingStatistics
import id.yomic.mobile.model.YomicUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Owns Reader page loading, Cloudflare interactive recovery, next-chapter preloading,
 * post-reading automation (delete-after-read / auto-download-next / MAL auto-sync),
 * Reading History mutations, and 14-day Reading Insights statistics.
 */
internal class ReaderHistoryController(
    private val ctx: ControllerContext,
    private val downloadController: DownloadController
) {
    private var readerLoadJob: Job? = null
    private var preloadJob: Job? = null

    @Volatile
    var latestDailyLogs: List<DailyReadingLogEntity> = emptyList()

    fun loadReaderPages(mangaId: String, chapterNumber: Double, forceReload: Boolean = false) {
        val c = ctx.container ?: return
        readerLoadJob?.cancel()
        readerLoadJob = ctx.scope.launch {
            ctx.state.update {
                it.copy(
                    readerPages = if (forceReload) emptyList() else it.readerPages,
                    isLoadingReaderPages = true,
                    readerError = null,
                    readerRequiresCloudflareUrl = null
                )
            }
            when (val res = c.readerRepository.loadChapterPages(mangaId, chapterNumber)) {
                is ReaderLoadResult.Success -> {
                    ctx.state.update {
                        it.copy(
                            readerPages = res.pages,
                            isLoadingReaderPages = false,
                            readerError = null,
                            readerRequiresCloudflareUrl = null
                        )
                    }
                    if (ctx.state.value.settings.preloadNextChapter && !ctx.state.value.settings.isOfflineMode) {
                        val manga = ctx.state.value.mangaList.find { it.id == mangaId }
                        val asc = manga?.chapters?.sortedBy { it.number }.orEmpty()
                        val curIdx = asc.indexOfFirst { abs(it.number - chapterNumber) < 0.001 }
                        val nextCh = asc.getOrNull(curIdx + 1)
                        if (nextCh != null) {
                            preloadJob?.cancel()
                            preloadJob = ctx.scope.launch {
                                c.readerRepository.preloadNextChapter(mangaId, nextCh.number)
                            }
                        }
                    }
                }
                is ReaderLoadResult.RequiresCloudflare -> {
                    ctx.state.update {
                        it.copy(
                            readerPages = emptyList(),
                            isLoadingReaderPages = false,
                            readerError = ctx.l("Sumber memerlukan verifikasi Cloudflare.", "Source requires Cloudflare verification."),
                            readerRequiresCloudflareUrl = res.challengeUrl
                        )
                    }
                }
                is ReaderLoadResult.Error -> {
                    ctx.state.update {
                        it.copy(
                            readerPages = emptyList(),
                            isLoadingReaderPages = false,
                            readerError = res.message,
                            readerRequiresCloudflareUrl = null
                        )
                    }
                }
            }
        }
    }

    fun solveCloudflareAndReloadReader(challengeUrl: String, mangaId: String, chapterNumber: Double) {
        val c = ctx.container ?: return
        ctx.scope.launch {
            val result = c.networkClient.solveCloudflareInteractive(challengeUrl)
            if (result.solved) {
                ctx.showMessage(ctx.l("Verifikasi Cloudflare berhasil.", "Cloudflare verification passed."))
                loadReaderPages(mangaId, chapterNumber, forceReload = true)
            } else {
                ctx.showMessage(ctx.l("Verifikasi Cloudflare dibatalkan.", "Cloudflare verification cancelled."))
            }
        }
    }

    fun onChapterFinishedReading(mangaId: String, chapterNumber: Double) {
        val settings = ctx.state.value.settings
        val manga = ctx.state.value.mangaList.find { it.id == mangaId } ?: return
        if (settings.autoDeleteAfterRead) {
            val currentCh = manga.chapters.find { abs(it.number - chapterNumber) < 0.001 }
            if (currentCh?.isDownloaded == true) {
                downloadController.removeSingleChapterDownload(mangaId, chapterNumber)
            }
        }
        if (settings.autoDownloadNextChapter && !settings.isOfflineMode) {
            val asc = manga.chapters.sortedBy { it.number }
            val idx = asc.indexOfFirst { abs(it.number - chapterNumber) < 0.001 }
            val nextCh = asc.getOrNull(idx + 1)
            if (nextCh != null && !nextCh.isDownloaded) {
                downloadController.enqueueChapterDownload(mangaId, nextCh.number)
            }
        }
        val c = ctx.container
        if (c != null && settings.autoSyncTracker && settings.isMalConnected && !settings.isOfflineMode) {
            ctx.scope.launch {
                c.malTrackerService.autoSyncAfterReading(mangaId, chapterNumber)
            }
        }
    }

    fun setHistorySearch(query: String) {
        ctx.state.update { it.copy(historySearchQuery = query) }
    }

    fun toggleHistoryViewMode() {
        ctx.state.update { it.copy(historyIsGridMode = !it.historyIsGridMode) }
    }

    fun removeHistoryItem(mangaId: String) {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                c.historyRepository.removeHistoryItem(mangaId)
                ctx.showMessage(ctx.l("Dihapus dari riwayat.", "Removed from history."))
            }
            return
        }
        ctx.state.update {
            it.copy(
                removedHistoryIds = it.removedHistoryIds + mangaId,
                snackbarMessage = ctx.l("Dihapus dari riwayat.", "Removed from history.")
            )
        }
    }

    fun restoreHistoryItem(mangaId: String) {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                c.historyRepository.restoreHistoryItem(mangaId)
                ctx.showMessage(ctx.l("Riwayat dipulihkan.", "History entry restored."))
            }
            return
        }
        ctx.state.update {
            it.copy(
                removedHistoryIds = it.removedHistoryIds - mangaId,
                snackbarMessage = ctx.l("Riwayat dipulihkan.", "History entry restored.")
            )
        }
    }

    fun clearAllHistory() {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                c.historyRepository.clearAllHistory()
                ctx.showMessage(ctx.l("Seluruh riwayat membaca dibersihkan.", "All reading history cleared."))
            }
            return
        }
        ctx.state.update { state ->
            state.copy(
                removedHistoryIds = state.mangaList.map { it.id }.toSet(),
                snackbarMessage = ctx.l("Seluruh riwayat membaca dibersihkan.", "All reading history cleared.")
            )
        }
    }

    fun updateReadingPage(mangaId: String, chapterNumber: Double, page: Int, totalPages: Int) {
        if (ctx.state.value.settings.isIncognitoMode) return
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                c.historyRepository.updateReadingPage(mangaId, chapterNumber, page, totalPages)
            }
            return
        }
        ctx.state.update { state ->
            state.copy(
                mangaList = state.mangaList.map { m ->
                    if (m.id != mangaId) m else m.copy(
                        lastReadChapter = chapterNumber,
                        lastReadPage = page,
                        lastReadTotalPages = totalPages,
                        chapters = m.chapters.map { ch ->
                            if (abs(ch.number - chapterNumber) < 0.001) {
                                ch.copy(lastPageRead = page)
                            } else ch
                        }
                    )
                }
            )
        }
    }

    fun recordChapterOpened(mangaId: String, chapterNumber: Double, page: Int, totalPages: Int) {
        if (ctx.state.value.settings.isIncognitoMode) return
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                c.historyRepository.recordChapterOpened(mangaId, chapterNumber, page, totalPages)
            }
            return
        }
        val now = System.currentTimeMillis()
        ctx.state.update { state ->
            val updatedDaily = state.baseDailyActivity.mapIndexed { idx, day ->
                if (idx == state.baseDailyActivity.lastIndex) {
                    val nextCh = day.chaptersRead + 1
                    day.copy(chaptersRead = nextCh, minutesRead = (nextCh * 2.5f).roundToInt())
                } else day
            }
            state.copy(
                removedHistoryIds = state.removedHistoryIds - mangaId,
                baseDailyActivity = updatedDaily,
                mangaList = state.mangaList.map { m ->
                    if (m.id != mangaId) m else m.copy(
                        lastReadChapter = chapterNumber,
                        lastReadPage = page,
                        lastReadTotalPages = totalPages,
                        lastReadTimeAgo = ctx.l("Baru saja", "Just now"),
                        lastViewedEpoch = now,
                        chapters = m.chapters.map { ch ->
                            if (abs(ch.number - chapterNumber) < 0.001) {
                                ch.copy(isRead = true, lastPageRead = page)
                            } else ch
                        }
                    )
                },
                activityUpdates = state.activityUpdates.map { upd ->
                    if (upd.mangaId == mangaId && abs(upd.chapterNumber - chapterNumber) < 0.001) {
                        upd.copy(isNew = false)
                    } else upd
                }
            )
        }
    }

    fun recordReadingSeconds(mangaId: String, deltaSeconds: Int) {
        if (ctx.state.value.settings.isIncognitoMode) return
        val c = ctx.container ?: return
        ctx.scope.launch {
            c.historyRepository.recordReadingDuration(mangaId, deltaSeconds)
        }
    }

    fun computeReadingStatistics(state: YomicUiState): ReadingStatistics {
        val library = state.mangaList.filter { it.inLibrary }
        var reading = 0
        var completed = 0
        var unread = 0
        var totalLibCh = 0
        var readLibCh = 0
        var downloaded = 0

        for (m in library) {
            totalLibCh += m.totalChapters
            readLibCh += m.readCount
            downloaded += m.downloadedCount
            when {
                m.totalChapters > 0 && m.unreadCount == 0 -> completed++
                m.readCount > 0 -> reading++
                else -> unread++
            }
        }

        val genreMap = mutableMapOf<String, Pair<Int, Int>>()
        for (m in library) {
            for (g in m.genres.distinct()) {
                val prev = genreMap[g] ?: (0 to 0)
                genreMap[g] = (prev.first + 1) to (prev.second + m.readCount)
            }
        }
        val topGenres = genreMap.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Pair<Int, Int>>> { it.value.first }
                    .thenByDescending { it.value.second }
            )
            .take(8)
            .map { GenreStatItem(it.key, it.value.first, it.value.second) }

        val daily = state.baseDailyActivity
        val completionPct = if (totalLibCh > 0) (readLibCh * 100f / totalLibCh) else 0f

        if (ctx.container != null) {
            val streakSummary = HistoryRepository.computeStreakStats(latestDailyLogs, System.currentTimeMillis())
            val totalMins = if (streakSummary.totalReadingMinutes > 0) {
                streakSummary.totalReadingMinutes
            } else if (readLibCh > 0) {
                (readLibCh * 2.5f).roundToInt().coerceAtLeast(1)
            } else 0
            return ReadingStatistics(
                totalLibraryManga = library.size,
                readingMangaCount = reading,
                completedMangaCount = completed,
                unreadMangaCount = unread,
                totalChaptersRead = readLibCh,
                totalLibraryChapters = totalLibCh,
                completionRatePercent = completionPct,
                totalDownloadedChapters = downloaded,
                currentStreakDays = streakSummary.currentStreakDays,
                longestStreakDays = streakSummary.longestStreakDays,
                totalReadingMinutes = totalMins,
                activeDaysCount = streakSummary.activeDaysCount,
                topGenres = topGenres,
                dailyActivity = daily
            )
        }

        val activeDays = daily.count { it.chaptersRead > 0 }
        var curStreak = 0
        for (i in daily.lastIndex downTo 0) {
            if (daily[i].chaptersRead > 0) curStreak++ else break
        }
        var longest = 0
        var run = 0
        for (d in daily) {
            if (d.chaptersRead > 0) {
                run++
                if (run > longest) longest = run
            } else {
                run = 0
            }
        }

        return ReadingStatistics(
            totalLibraryManga = library.size,
            readingMangaCount = reading,
            completedMangaCount = completed,
            unreadMangaCount = unread,
            totalChaptersRead = readLibCh,
            totalLibraryChapters = totalLibCh,
            completionRatePercent = completionPct,
            totalDownloadedChapters = downloaded,
            currentStreakDays = curStreak,
            longestStreakDays = longest.coerceAtLeast(curStreak),
            totalReadingMinutes = if (readLibCh > 0) (readLibCh * 2.5f).roundToInt().coerceAtLeast(1) else 0,
            activeDaysCount = activeDays,
            topGenres = topGenres,
            dailyActivity = daily
        )
    }
}
