package id.yomic.mobile.viewmodel.controllers

import id.yomic.mobile.model.DownloadQueueItem
import id.yomic.mobile.model.DownloadStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Owns Offline Download Queue mutations (single, unread batch, pause/resume, cancel, delete, clear)
 * and the demo-mode progress ticker used solely when running without AppContainer.
 */
internal class DownloadController(
    private val ctx: ControllerContext
) {
    fun downloadUnreadForMultiple(mangaIds: Set<String>) {
        if (mangaIds.isEmpty()) return
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                if (ctx.state.value.settings.isOfflineMode) {
                    ctx.showMessage(ctx.l("Mode Offline aktif. Nonaktifkan untuk mengunduh.", "Offline mode is active. Disable it to download."))
                    return@launch
                }
                var queued = 0
                for (id in mangaIds) {
                    queued += c.downloadRepository.enqueueUnreadDownloads(id, maxBatch = 25, onlyUnread = true)
                }
                ctx.showMessage(
                    if (queued > 0) ctx.l("$queued bab ditambahkan ke antrean unduhan.", "$queued chapters added to download queue.")
                    else ctx.l("Tidak ada bab baru untuk diunduh.", "No unread chapters to download.")
                )
            }
            return
        }
        var queued = 0
        for (id in mangaIds) {
            val m = ctx.state.value.mangaList.find { it.id == id } ?: continue
            val unread = m.chapters.filter { !it.isRead && !it.isDownloaded }
            if (unread.isNotEmpty()) {
                enqueueUnreadDownloads(id)
                queued += unread.size
            }
        }
        if (queued == 0) {
            ctx.showMessage(ctx.l("Tidak ada bab baru untuk diunduh.", "No unread chapters to download."))
        }
    }

    fun enqueueChapterDownload(mangaId: String, chapterNumber: Double) {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                val (_, message) = c.downloadRepository.enqueueChapterDownload(mangaId, chapterNumber)
                if (message.isNotBlank()) ctx.showMessage(message)
            }
            return
        }
        val manga = ctx.state.value.mangaList.find { it.id == mangaId } ?: return
        val ch = manga.chapters.find { abs(it.number - chapterNumber) < 0.001 } ?: return
        val dlId = "dl_${mangaId}_${ch.formattedNumber}"
        if (ctx.state.value.downloads.any { it.id == dlId }) {
            ctx.showMessage(ctx.l("Bab sudah ada di antrean unduhan.", "Chapter is already in the download queue."))
            return
        }
        val item = DownloadQueueItem(
            id = dlId,
            mangaId = manga.id,
            mangaTitle = manga.title,
            chapterLabel = "Chapter ${ch.formattedNumber}",
            chapterNumber = ch.number,
            sourceName = manga.sourceName,
            progressPercent = 0,
            speedText = "3.8 MB/s",
            sizeText = "15.2 MB",
            status = DownloadStatus.DOWNLOADING
        )
        ctx.state.update {
            it.copy(
                downloads = listOf(item) + it.downloads,
                snackbarMessage = ctx.l("Mengunduh ${manga.title} Ch. ${ch.formattedNumber}...", "Downloading ${manga.title} Ch. ${ch.formattedNumber}...")
            )
        }
    }

    fun enqueueUnreadDownloads(mangaId: String) {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                if (ctx.state.value.settings.isOfflineMode) {
                    ctx.showMessage(ctx.l("Mode Offline aktif. Nonaktifkan untuk mengunduh.", "Offline mode is active. Disable it to download."))
                    return@launch
                }
                val added = c.downloadRepository.enqueueUnreadDownloads(mangaId, maxBatch = 50, onlyUnread = false)
                ctx.showMessage(
                    if (added > 0) ctx.l("$added bab ditambahkan ke antrean unduhan.", "$added chapters added to download queue.")
                    else ctx.l("Semua bab sudah tersimpan offline atau dalam antrean.", "All chapters are already downloaded or queued.")
                )
            }
            return
        }
        val manga = ctx.state.value.mangaList.find { it.id == mangaId } ?: return
        val toDownload = manga.chapters.filter { !it.isDownloaded }.take(3)
        if (toDownload.isEmpty()) {
            ctx.showMessage(ctx.l("Semua bab sudah tersimpan offline.", "All chapters are already downloaded."))
            return
        }
        val existingIds = ctx.state.value.downloads.map { it.id }.toSet()
        val newItems = toDownload.mapNotNull { ch ->
            val dlId = "dl_${mangaId}_${ch.formattedNumber}"
            if (dlId in existingIds) null else DownloadQueueItem(
                id = dlId,
                mangaId = manga.id,
                mangaTitle = manga.title,
                chapterLabel = "Chapter ${ch.formattedNumber}",
                chapterNumber = ch.number,
                sourceName = manga.sourceName,
                progressPercent = 0,
                speedText = "4.1 MB/s",
                sizeText = "16.4 MB",
                status = DownloadStatus.DOWNLOADING
            )
        }
        if (newItems.isNotEmpty()) {
            ctx.state.update {
                it.copy(
                    downloads = newItems + it.downloads,
                    snackbarMessage = ctx.l("${newItems.size} bab ditambahkan ke antrean unduhan.", "${newItems.size} chapters added to download queue.")
                )
            }
        }
    }

    fun removeSingleChapterDownload(mangaId: String, chapterNumber: Double) {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                c.downloadRepository.removeSingleChapterDownload(mangaId, chapterNumber)
                ctx.showMessage(ctx.l("Unduhan bab dihapus.", "Chapter download removed."))
            }
            return
        }
        ctx.state.update { state ->
            state.copy(
                mangaList = state.mangaList.map { m ->
                    if (m.id != mangaId) m else m.copy(
                        chapters = m.chapters.map { ch ->
                            if (abs(ch.number - chapterNumber) < 0.001) ch.copy(isDownloaded = false) else ch
                        }
                    )
                },
                downloads = state.downloads.filterNot {
                    it.mangaId == mangaId && abs(it.chapterNumber - chapterNumber) < 0.001
                },
                snackbarMessage = ctx.l("Unduhan bab dihapus.", "Chapter download removed.")
            )
        }
    }

    fun deleteAllChapterDownloads(mangaId: String) {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                c.downloadRepository.deleteAllChapterDownloads(mangaId)
                ctx.showMessage(ctx.l("Semua unduhan komik ini dihapus.", "All downloads for this title removed."))
            }
            return
        }
        ctx.state.update { state ->
            state.copy(
                mangaList = state.mangaList.map { m ->
                    if (m.id != mangaId) m else m.copy(chapters = m.chapters.map { it.copy(isDownloaded = false) })
                },
                downloads = state.downloads.filterNot { it.mangaId == mangaId },
                snackbarMessage = ctx.l("Semua unduhan komik ini dihapus.", "All downloads for this title removed.")
            )
        }
    }

    fun toggleDownloadPause(dlId: String) {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                c.downloadRepository.toggleDownloadPause(dlId, pausedLabel = ctx.l("Dijeda", "Paused"))
            }
            return
        }
        ctx.state.update { state ->
            state.copy(
                downloads = state.downloads.map { d ->
                    if (d.id != dlId || d.status == DownloadStatus.COMPLETED) d else {
                        if (d.status == DownloadStatus.PAUSED || d.status == DownloadStatus.ERROR) {
                            d.copy(status = DownloadStatus.DOWNLOADING, speedText = "4.2 MB/s", errorMessage = null)
                        } else {
                            d.copy(status = DownloadStatus.PAUSED, speedText = ctx.l("Dijeda", "Paused"))
                        }
                    }
                }
            )
        }
    }

    fun pauseAllDownloads() {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                c.downloadRepository.pauseAllDownloads(pausedLabel = ctx.l("Dijeda", "Paused"))
                ctx.showMessage(ctx.l("Seluruh unduhan aktif dijeda.", "All active downloads paused."))
            }
            return
        }
        ctx.state.update { state ->
            state.copy(
                downloads = state.downloads.map { d ->
                    if (d.status == DownloadStatus.DOWNLOADING || d.status == DownloadStatus.QUEUED) {
                        d.copy(status = DownloadStatus.PAUSED, speedText = ctx.l("Dijeda", "Paused"))
                    } else d
                },
                snackbarMessage = ctx.l("Seluruh unduhan aktif dijeda.", "All active downloads paused.")
            )
        }
    }

    fun resumeAllDownloads() {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                c.downloadRepository.resumeAllDownloads()
                ctx.showMessage(ctx.l("Seluruh unduhan dilanjutkan.", "All downloads resumed."))
            }
            return
        }
        ctx.state.update { state ->
            state.copy(
                downloads = state.downloads.map { d ->
                    if (d.status == DownloadStatus.PAUSED || d.status == DownloadStatus.ERROR) {
                        d.copy(status = DownloadStatus.DOWNLOADING, speedText = "4.6 MB/s", errorMessage = null)
                    } else d
                },
                snackbarMessage = ctx.l("Seluruh unduhan dilanjutkan.", "All downloads resumed.")
            )
        }
    }

    fun removeDownloadEntry(dlId: String) {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch { c.downloadRepository.removeDownloadEntry(dlId) }
            return
        }
        ctx.state.update { state ->
            state.copy(downloads = state.downloads.filterNot { it.id == dlId })
        }
    }

    fun clearCompletedDownloads() {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                c.downloadRepository.clearCompletedDownloads()
                ctx.showMessage(ctx.l("Unduhan selesai dibersihkan.", "Completed downloads cleared."))
            }
            return
        }
        ctx.state.update { state ->
            state.copy(
                downloads = state.downloads.filterNot { it.status == DownloadStatus.COMPLETED },
                snackbarMessage = ctx.l("Unduhan selesai dibersihkan.", "Completed downloads cleared.")
            )
        }
    }

    fun startDemoTicker() {
        ctx.scope.launch {
            while (true) {
                delay(1200L)
                val current = ctx.state.value
                val hasDownloading = current.downloads.any { it.status == DownloadStatus.DOWNLOADING }
                if (!hasDownloading || current.settings.isOfflineMode) continue

                val completedNow = mutableListOf<Pair<String, Double>>()
                val updatedDownloads = current.downloads.map { d ->
                    if (d.status != DownloadStatus.DOWNLOADING) d else {
                        val nextPct = (d.progressPercent + 9).coerceAtMost(100)
                        if (nextPct >= 100) {
                            completedNow += (d.mangaId to d.chapterNumber)
                            d.copy(progressPercent = 100, status = DownloadStatus.COMPLETED, speedText = "")
                        } else {
                            d.copy(progressPercent = nextPct)
                        }
                    }
                }
                ctx.state.update { state ->
                    var nextManga = state.mangaList
                    if (completedNow.isNotEmpty()) {
                        nextManga = state.mangaList.map { m ->
                            val doneChapters = completedNow.filter { it.first == m.id }.map { it.second }
                            if (doneChapters.isEmpty()) m else m.copy(
                                chapters = m.chapters.map { ch ->
                                    if (doneChapters.any { abs(it - ch.number) < 0.001 }) ch.copy(isDownloaded = true) else ch
                                }
                            )
                        }
                    }
                    state.copy(downloads = updatedDownloads, mangaList = nextManga)
                }
            }
        }
    }
}
