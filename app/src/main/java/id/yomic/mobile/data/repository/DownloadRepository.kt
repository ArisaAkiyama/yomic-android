package id.yomic.mobile.data.repository

import android.content.Context
import id.yomic.mobile.data.local.ChapterDao
import id.yomic.mobile.data.local.DownloadTaskDao
import id.yomic.mobile.data.local.DownloadTaskEntity
import id.yomic.mobile.data.local.LocalEntityMappers.toModel
import id.yomic.mobile.data.local.MangaDao
import id.yomic.mobile.data.preferences.SettingsRepository
import id.yomic.mobile.model.DownloadQueueItem
import id.yomic.mobile.model.DownloadStatus
import id.yomic.mobile.worker.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Persistent Offline Download Queue Manager backed by Room `download_tasks`,
 * atomic `.tmp` staging directories, optional `.cbz` packing, and WorkManager foreground worker.
 */
class DownloadRepository(
    private val context: Context?,
    private val downloadsDir: File,
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val downloadTaskDao: DownloadTaskDao,
    private val settingsRepository: SettingsRepository
) {

    fun observeDownloads(): Flow<List<DownloadQueueItem>> =
        downloadTaskDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun enqueueChapterDownload(mangaId: String, chapterNumber: Double): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val settings = settingsRepository.getCurrentSettings()
            if (settings.isOfflineMode) {
                return@withContext false to "Mode Offline aktif. Nonaktifkan untuk mengunduh."
            }
            val manga = mangaDao.getById(mangaId) ?: return@withContext false to "Komik tidak ditemukan."
            val ch = chapterDao.getByMangaAndNumber(mangaId, chapterNumber)
                ?: return@withContext false to "Bab tidak ditemukan."
            if (ch.isDownloaded && ch.localPath.isNotBlank() && File(ch.localPath).exists()) {
                return@withContext false to "Bab sudah tersimpan offline."
            }

            val dlId = "dl_${mangaId}_${ch.id}"
            val existing = downloadTaskDao.getById(dlId)
            if (existing != null && existing.status in listOf(DownloadStatus.QUEUED.name, DownloadStatus.DOWNLOADING.name)) {
                return@withContext false to "Bab sudah ada di antrean unduhan."
            }

            val formattedNum = if (ch.number % 1.0 == 0.0) ch.number.toInt().toString() else ch.number.toString()
            downloadTaskDao.upsert(
                DownloadTaskEntity(
                    id = dlId,
                    mangaId = manga.id,
                    chapterId = ch.id,
                    mangaTitle = manga.title,
                    chapterLabel = "Chapter $formattedNum",
                    chapterNumber = ch.number,
                    sourcePkg = manga.sourcePkg,
                    sourceName = manga.sourceName,
                    progressPercent = 0,
                    status = DownloadStatus.QUEUED.name,
                    createdEpoch = System.currentTimeMillis()
                )
            )
            triggerWorker(settings.downloadOnlyOnWifi)
            true to "Mengunduh ${manga.title} Ch. $formattedNum..."
        }

    suspend fun enqueueUnreadDownloads(
        mangaId: String,
        maxBatch: Int = 50,
        onlyUnread: Boolean = false
    ): Int = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getCurrentSettings()
        if (settings.isOfflineMode) return@withContext 0
        val manga = mangaDao.getById(mangaId) ?: return@withContext 0
        val allChapters = chapterDao.getChaptersForManga(mangaId)
        val unreadUndownloaded = allChapters
            .filter { !it.isRead && !it.isDownloaded }
            .sortedBy { it.number }
        val chapters = if (onlyUnread) {
            unreadUndownloaded.take(maxBatch)
        } else {
            unreadUndownloaded.ifEmpty {
                allChapters.filter { !it.isDownloaded }.sortedBy { it.number }
            }.take(maxBatch)
        }

        if (chapters.isEmpty()) return@withContext 0

        val now = System.currentTimeMillis()
        val tasks = mutableListOf<DownloadTaskEntity>()
        chapters.forEachIndexed { idx, ch ->
            val dlId = "dl_${mangaId}_${ch.id}"
            val existing = downloadTaskDao.getById(dlId)
            if (existing == null || existing.status in listOf(DownloadStatus.ERROR.name, DownloadStatus.PAUSED.name)) {
                val formattedNum = if (ch.number % 1.0 == 0.0) ch.number.toInt().toString() else ch.number.toString()
                tasks.add(
                    DownloadTaskEntity(
                        id = dlId,
                        mangaId = manga.id,
                        chapterId = ch.id,
                        mangaTitle = manga.title,
                        chapterLabel = "Chapter $formattedNum",
                        chapterNumber = ch.number,
                        sourcePkg = manga.sourcePkg,
                        sourceName = manga.sourceName,
                        progressPercent = 0,
                        status = DownloadStatus.QUEUED.name,
                        createdEpoch = now + idx
                    )
                )
            }
        }
        if (tasks.isNotEmpty()) {
            downloadTaskDao.upsertAll(tasks)
            triggerWorker(settings.downloadOnlyOnWifi)
        }
        tasks.size
    }

    suspend fun toggleDownloadPause(dlId: String, pausedLabel: String) = withContext(Dispatchers.IO) {
        val task = downloadTaskDao.getById(dlId) ?: return@withContext
        when (task.status) {
            DownloadStatus.COMPLETED.name -> return@withContext
            DownloadStatus.PAUSED.name, DownloadStatus.ERROR.name -> {
                downloadTaskDao.updateStatus(dlId, DownloadStatus.QUEUED.name, "")
                val settings = settingsRepository.getCurrentSettings()
                if (!settings.isOfflineMode) {
                    triggerWorker(settings.downloadOnlyOnWifi, replaceConstraints = true)
                }
            }
            else -> {
                downloadTaskDao.updateStatus(dlId, DownloadStatus.PAUSED.name, pausedLabel)
            }
        }
    }

    suspend fun pauseAllDownloads(pausedLabel: String) = withContext(Dispatchers.IO) {
        downloadTaskDao.pauseAllActive(pausedLabel)
    }

    suspend fun resumeAllDownloads() = withContext(Dispatchers.IO) {
        downloadTaskDao.resumeAllPaused()
        val settings = settingsRepository.getCurrentSettings()
        if (!settings.isOfflineMode) {
            triggerWorker(settings.downloadOnlyOnWifi, replaceConstraints = true)
        }
    }

    suspend fun reapplyWifiConstraintIfPending(onlyOnWifi: Boolean) = withContext(Dispatchers.IO) {
        val pending = downloadTaskDao.getPendingTasks()
        if (pending.isNotEmpty() && !settingsRepository.getCurrentSettings().isOfflineMode) {
            triggerWorker(onlyOnWifi = onlyOnWifi, replaceConstraints = true)
        }
    }

    suspend fun removeDownloadEntry(dlId: String) = withContext(Dispatchers.IO) {
        val task = downloadTaskDao.getById(dlId)
        downloadTaskDao.deleteById(dlId)
        if (task != null && task.status != DownloadStatus.COMPLETED.name) {
            val tmp = resolveChapterTmpDir(downloadsDir, task.sourcePkg, task.mangaId, task.chapterId)
            runCatching { tmp.deleteRecursively() }
        }
    }

    suspend fun clearCompletedDownloads() = withContext(Dispatchers.IO) {
        downloadTaskDao.clearCompleted()
    }

    suspend fun removeSingleChapterDownload(mangaId: String, chapterNumber: Double) = withContext(Dispatchers.IO) {
        val ch = chapterDao.getByMangaAndNumber(mangaId, chapterNumber)
        if (ch != null) {
            if (ch.localPath.isNotBlank()) {
                runCatching { File(ch.localPath).deleteRecursively() }
            }
            val manga = mangaDao.getById(mangaId)
            if (manga != null) {
                runCatching { resolveChapterTmpDir(downloadsDir, manga.sourcePkg, mangaId, ch.id).deleteRecursively() }
                runCatching { resolveFinalChapterDir(downloadsDir, manga.sourcePkg, mangaId, ch.id).deleteRecursively() }
                runCatching { resolveFinalCbzFile(downloadsDir, manga.sourcePkg, mangaId, ch.id).delete() }
            }
            chapterDao.setDownloadState(ch.id, downloaded = false, localPath = "")
        }
        downloadTaskDao.deleteByMangaAndChapter(mangaId, chapterNumber)
    }

    suspend fun deleteAllChapterDownloads(mangaId: String) = withContext(Dispatchers.IO) {
        val chapters = chapterDao.getChaptersForManga(mangaId)
        for (ch in chapters) {
            if (ch.localPath.isNotBlank()) {
                runCatching { File(ch.localPath).deleteRecursively() }
            }
        }
        val manga = mangaDao.getById(mangaId)
        if (manga != null) {
            val mangaDir = File(File(downloadsDir, sanitizeSegment(manga.sourcePkg)), sanitizeSegment(mangaId))
            runCatching { mangaDir.deleteRecursively() }
        }
        chapterDao.clearAllDownloadsForManga(mangaId)
        downloadTaskDao.deleteByMangaId(mangaId)
    }

    suspend fun deleteAllDownloadsOnDevice() = withContext(Dispatchers.IO) {
        downloadTaskDao.deleteAll()
        downloadsDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun triggerWorker(onlyOnWifi: Boolean, replaceConstraints: Boolean = false) {
        val ctx = context ?: return
        WorkScheduler.enqueueDownloadQueue(ctx, onlyOnWifi, replaceConstraints = replaceConstraints)
    }

    companion object {
        fun sanitizeSegment(raw: String): String =
            raw.trim()
                .replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
                .take(80)
                .ifBlank { "unknown" }

        fun resolveChapterTmpDir(downloadsDir: File, sourcePkg: String, mangaId: String, chapterId: String): File =
            File(File(File(downloadsDir, sanitizeSegment(sourcePkg)), sanitizeSegment(mangaId)), "${sanitizeSegment(chapterId)}.tmp")

        fun resolveFinalChapterDir(downloadsDir: File, sourcePkg: String, mangaId: String, chapterId: String): File =
            File(File(File(downloadsDir, sanitizeSegment(sourcePkg)), sanitizeSegment(mangaId)), sanitizeSegment(chapterId))

        fun resolveFinalCbzFile(downloadsDir: File, sourcePkg: String, mangaId: String, chapterId: String): File =
            File(File(File(downloadsDir, sanitizeSegment(sourcePkg)), sanitizeSegment(mangaId)), "${sanitizeSegment(chapterId)}.cbz")

        /**
         * Atomically promotes a staged `.tmp` chapter directory either to its final directory
         * or into a `.cbz` ZIP archive when `saveAsCbz == true`.
         */
        fun promoteTempDownload(
            tmpDir: File,
            finalDir: File,
            cbzFile: File,
            saveAsCbz: Boolean
        ): File {
            val imageFiles = tmpDir.listFiles()
                ?.filter { it.isFile && ReaderRepository.isImageFileName(it.name) }
                ?.sortedWith { a, b -> ReaderRepository.naturalCompare(a.name, b.name) }
                .orEmpty()
            require(imageFiles.isNotEmpty()) { "No downloaded image pages found in ${tmpDir.absolutePath}" }

            if (saveAsCbz) {
                cbzFile.parentFile?.mkdirs()
                val tmpCbz = File(cbzFile.parentFile, "${cbzFile.name}.tmp")
                if (tmpCbz.exists()) tmpCbz.delete()
                ZipOutputStream(tmpCbz.outputStream().buffered()).use { zos ->
                    zos.setLevel(Deflater.NO_COMPRESSION) // Images are already compressed (JPG/WEBP/PNG)
                    for (img in imageFiles) {
                        zos.putNextEntry(ZipEntry(img.name))
                        img.inputStream().buffered().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                if (cbzFile.exists()) cbzFile.delete()
                if (!tmpCbz.renameTo(cbzFile)) {
                    tmpCbz.copyTo(cbzFile, overwrite = true)
                    tmpCbz.delete()
                }
                if (finalDir.exists()) finalDir.deleteRecursively()
                tmpDir.deleteRecursively()
                return cbzFile
            } else {
                if (finalDir.exists()) finalDir.deleteRecursively()
                if (cbzFile.exists()) cbzFile.delete()
                finalDir.parentFile?.mkdirs()
                if (!tmpDir.renameTo(finalDir)) {
                    tmpDir.copyRecursively(finalDir, overwrite = true)
                    tmpDir.deleteRecursively()
                }
                return finalDir
            }
        }

        fun guessImageExtension(url: String, contentType: String?): String {
            val ct = contentType?.lowercase(Locale.US).orEmpty()
            return when {
                ct.contains("webp") -> "webp"
                ct.contains("png") -> "png"
                ct.contains("gif") -> "gif"
                ct.contains("avif") -> "avif"
                url.substringBefore('|').substringBefore('?').lowercase(Locale.US).endsWith(".webp") -> "webp"
                url.substringBefore('|').substringBefore('?').lowercase(Locale.US).endsWith(".png") -> "png"
                url.substringBefore('|').substringBefore('?').lowercase(Locale.US).endsWith(".gif") -> "gif"
                url.substringBefore('|').substringBefore('?').lowercase(Locale.US).endsWith(".avif") -> "avif"
                else -> "jpg"
            }
        }

        fun formatSpeed(bytesPerSec: Double): String {
            if (bytesPerSec <= 0.0) return ""
            val mbps = bytesPerSec / (1024.0 * 1024.0)
            return if (mbps >= 1.0) {
                String.format(Locale.US, "%.1f MB/s", mbps)
            } else {
                String.format(Locale.US, "%.0f KB/s", (bytesPerSec / 1024.0).coerceAtLeast(1.0))
            }
        }

        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0L) return "0.0 MB"
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 0.1) {
                String.format(Locale.US, "%.1f MB", mb)
            } else {
                String.format(Locale.US, "%.0f KB", (bytes / 1024.0).coerceAtLeast(1.0))
            }
        }
    }
}
