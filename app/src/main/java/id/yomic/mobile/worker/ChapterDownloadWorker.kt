package id.yomic.mobile.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import id.yomic.mobile.MainActivity
import id.yomic.mobile.R
import id.yomic.mobile.YomicApplication
import id.yomic.mobile.data.local.DownloadTaskEntity
import id.yomic.mobile.data.repository.DownloadRepository
import id.yomic.mobile.data.repository.ReaderLoadResult
import id.yomic.mobile.model.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Background Foreground WorkManager worker that drains the Room `download_tasks` queue.
 * Features:
 * - Atomic `.tmp` chapter staging + optional `.cbz` ZIP packing
 * - Per-page concurrency (max 3) via `YomicNetworkClient.imageClient` (DoH + Cookies + PipeHeader + AES)
 * - Live progress, real MB/s speed, and size updates persisted to Room & foreground notification
 * - Respects `downloadOnlyOnWifi`, `isOfflineMode`, Pause, and Cancel mid-download
 */
class ChapterDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? YomicApplication ?: return@withContext Result.success()
        val container = app.container
        val downloadDao = container.database.downloadTaskDao()
        val chapterDao = container.database.chapterDao()

        if (container.jsExtensionManager.getAllSources().isEmpty()) {
            container.jsExtensionManager.loadAllInstalled()
        }

        ensureNotificationChannel(applicationContext)

        while (true) {
            if (isStopped) break
            val settings = container.settingsRepository.getCurrentSettings()
            if (settings.isOfflineMode) break

            val pending = downloadDao.getPendingTasks()
            if (pending.isEmpty()) break

            if (settings.downloadOnlyOnWifi && !isOnUnmeteredNetwork(applicationContext)) {
                val pausedText = if (settings.appLanguage.code == "en") "Waiting for Wi-Fi" else "Menunggu Wi-Fi"
                downloadDao.pauseAllActive(pausedText)
                break
            }

            val task = pending.first()
            runCatching {
                setForeground(
                    createForegroundInfo(
                        title = "${task.mangaTitle} — ${task.chapterLabel}",
                        progress = task.progressPercent,
                        indeterminate = task.progressPercent == 0
                    )
                )
            }

            processSingleTask(task, container, settings.saveDownloadsAsCbz, settings.downloadOnlyOnWifi, settings.appLanguage.code == "en")
        }

        Result.success()
    }

    private suspend fun processSingleTask(
        task: DownloadTaskEntity,
        container: id.yomic.mobile.di.AppContainer,
        saveAsCbz: Boolean,
        onlyOnWifi: Boolean,
        isEn: Boolean
    ) {
        val downloadDao = container.database.downloadTaskDao()
        val chapterDao = container.database.chapterDao()
        val tmpDir = DownloadRepository.resolveChapterTmpDir(container.downloadsDir, task.sourcePkg, task.mangaId, task.chapterId)
        val finalDir = DownloadRepository.resolveFinalChapterDir(container.downloadsDir, task.sourcePkg, task.mangaId, task.chapterId)
        val cbzFile = DownloadRepository.resolveFinalCbzFile(container.downloadsDir, task.sourcePkg, task.mangaId, task.chapterId)

        try {
            downloadDao.updateProgress(
                id = task.id,
                pct = 2,
                bytes = 0L,
                total = 0L,
                speed = if (isEn) "Fetching pages..." else "Memuat daftar halaman...",
                size = "",
                status = DownloadStatus.DOWNLOADING.name,
                error = null
            )

            val pageResult = container.readerRepository.loadChapterPages(task.mangaId, task.chapterNumber)
            val pages = when (pageResult) {
                is ReaderLoadResult.Success -> pageResult.pages
                is ReaderLoadResult.RequiresCloudflare -> {
                    markTaskError(downloadDao, task.id, if (isEn) "Cloudflare verification required" else "Perlu verifikasi Cloudflare")
                    return
                }
                is ReaderLoadResult.Error -> {
                    markTaskError(downloadDao, task.id, pageResult.message)
                    return
                }
            }

            if (pages.isEmpty()) {
                markTaskError(downloadDao, task.id, if (isEn) "No pages found" else "Halaman kosong")
                return
            }

            if (tmpDir.exists()) tmpDir.deleteRecursively()
            tmpDir.mkdirs()

            val totalPages = pages.size
            val completedPages = AtomicInteger(0)
            val totalBytes = AtomicLong(0L)
            val startTimeMs = System.currentTimeMillis()
            val pageSemaphore = Semaphore(3)
            val failedPages = AtomicInteger(0)
            val aborted = AtomicBoolean(false)

            coroutineScope {
                pages.map { page ->
                    async {
                        pageSemaphore.withPermit {
                            if (aborted.get()) return@withPermit
                            // Check pause/cancel/wifi/offline mid-download
                            val freshTask = downloadDao.getById(task.id)
                            val curSettings = container.settingsRepository.getCurrentSettings()
                            if (isStopped || freshTask == null || freshTask.status == DownloadStatus.PAUSED.name || curSettings.isOfflineMode) {
                                aborted.set(true)
                                return@withPermit
                            }
                            if (onlyOnWifi && !isOnUnmeteredNetwork(applicationContext)) {
                                aborted.set(true)
                                downloadDao.updateStatus(task.id, DownloadStatus.PAUSED.name, if (isEn) "Waiting for Wi-Fi" else "Menunggu Wi-Fi")
                                return@withPermit
                            }

                            val ok = if (page.isLocalFile && !page.isCbzEntry) {
                                copyLocalPage(page.urlOrPath, page.index, tmpDir, totalBytes)
                            } else {
                                downloadPageWithRetry(
                                    imageClientProvider = { container.networkClient.imageClient },
                                    pageUrl = page.urlOrPath,
                                    pageIndex = page.index,
                                    tmpDir = tmpDir,
                                    bytesAccumulator = totalBytes
                                )
                            }
                            if (!ok) {
                                failedPages.incrementAndGet()
                            }

                            val done = completedPages.incrementAndGet()
                            val pct = ((done * 95) / totalPages).coerceIn(3, 96)
                            val elapsedSec = ((System.currentTimeMillis() - startTimeMs).coerceAtLeast(250L)) / 1000.0
                            val bytesNow = totalBytes.get()
                            val speedStr = DownloadRepository.formatSpeed(bytesNow / elapsedSec)
                            val sizeStr = DownloadRepository.formatFileSize(bytesNow)

                            if (!aborted.get()) {
                                downloadDao.updateProgress(
                                    id = task.id,
                                    pct = pct,
                                    bytes = bytesNow,
                                    total = bytesNow,
                                    speed = speedStr,
                                    size = sizeStr,
                                    status = DownloadStatus.DOWNLOADING.name,
                                    error = null
                                )
                                runCatching {
                                    setForeground(
                                        createForegroundInfo(
                                            title = "${task.mangaTitle} — ${task.chapterLabel}",
                                            progress = pct,
                                            indeterminate = false
                                        )
                                    )
                                }
                            }
                        }
                    }
                }.awaitAll()
            }

            if (aborted.get()) {
                runCatching { tmpDir.deleteRecursively() }
                return
            }

            val failedCount = failedPages.get()
            if (!shouldPromoteDownload(totalPages, completedPages.get(), failedCount, aborted.get())) {
                runCatching { tmpDir.deleteRecursively() }
                val errMsg = if (isEn) {
                    "$failedCount of $totalPages pages failed · Tap to retry"
                } else {
                    "$failedCount dari $totalPages halaman gagal diunduh · Ketuk untuk ulang"
                }
                markTaskError(downloadDao, task.id, errMsg)
                return
            }

            // Atomic promotion to final folder or .cbz
            val finalTarget = DownloadRepository.promoteTempDownload(
                tmpDir = tmpDir,
                finalDir = finalDir,
                cbzFile = cbzFile,
                saveAsCbz = saveAsCbz
            )
            val finalSizeBytes = if (finalTarget.isFile) {
                finalTarget.length()
            } else {
                finalTarget.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }

            chapterDao.setDownloadState(
                chapterId = task.chapterId,
                downloaded = true,
                localPath = finalTarget.absolutePath
            )
            downloadDao.updateProgress(
                id = task.id,
                pct = 100,
                bytes = finalSizeBytes,
                total = finalSizeBytes,
                speed = "",
                size = DownloadRepository.formatFileSize(finalSizeBytes),
                status = DownloadStatus.COMPLETED.name,
                error = null
            )
        } catch (e: Exception) {
            runCatching { tmpDir.deleteRecursively() }
            markTaskError(downloadDao, task.id, e.message ?: (if (isEn) "Download failed" else "Unduhan gagal"))
        }
    }

    private fun copyLocalPage(
        sourcePath: String,
        pageIndex: Int,
        tmpDir: File,
        bytesAccumulator: AtomicLong
    ): Boolean {
        return try {
            val src = File(sourcePath)
            if (!src.isFile || !src.exists()) return false
            val ext = src.extension.lowercase(Locale.US).ifBlank { "jpg" }
            val dst = File(tmpDir, String.format(Locale.US, "%03d.%s", pageIndex, ext))
            src.copyTo(dst, overwrite = true)
            bytesAccumulator.addAndGet(dst.length())
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun downloadPageWithRetry(
        imageClientProvider: () -> okhttp3.OkHttpClient,
        pageUrl: String,
        pageIndex: Int,
        tmpDir: File,
        bytesAccumulator: AtomicLong
    ): Boolean {
        val safeUrl = pageUrl.replace("|", "%7C").replace(" ", "%20")
        repeat(3) { attempt ->
            try {
                val req = Request.Builder().url(safeUrl).get().build()
                imageClientProvider().newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val ext = DownloadRepository.guessImageExtension(pageUrl, resp.body?.contentType()?.toString())
                            val fileName = String.format(Locale.US, "%03d.%s", pageIndex, ext)
                            File(tmpDir, fileName).writeBytes(bytes)
                            bytesAccumulator.addAndGet(bytes.size.toLong())
                            return true
                        }
                    }
                }
            } catch (_: Exception) {
                // Retry with short backoff
            }
            if (attempt < 2) {
                delay(400L * (attempt + 1))
            }
        }
        return false
    }

    private suspend fun markTaskError(
        dao: id.yomic.mobile.data.local.DownloadTaskDao,
        taskId: String,
        message: String
    ) {
        dao.updateProgress(
            id = taskId,
            pct = 0,
            bytes = 0L,
            total = 0L,
            speed = message,
            size = "",
            status = DownloadStatus.ERROR.name,
            error = message
        )
    }

    private fun isOnUnmeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun createForegroundInfo(title: String, progress: Int, indeterminate: Boolean): ForegroundInfo {
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, "downloads")
        }
        val pi = PendingIntent.getActivity(
            applicationContext,
            1002,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_DOWNLOADS_ID)
            .setSmallIcon(R.drawable.ic_stat_yomic)
            .setContentTitle(title)
            .setContentText(if (indeterminate) "Mempersiapkan unduhan..." else "$progress%")
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_DOWNLOAD_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_DOWNLOAD_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_DOWNLOADS_ID = "yomic_downloads"
        const val NOTIFICATION_DOWNLOAD_ID = 4002

        /**
         * Strict chapter download integrity guard:
         * A chapter may only be promoted to COMPLETED (folder or `.cbz`) if it was not aborted,
         * has at least 1 page, all pages completed, and ZERO pages failed after retries.
         */
        fun shouldPromoteDownload(
            totalPages: Int,
            completedPages: Int,
            failedPages: Int,
            aborted: Boolean
        ): Boolean = !aborted && totalPages > 0 && failedPages == 0 && completedPages >= totalPages

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                val channel = NotificationChannel(
                    CHANNEL_DOWNLOADS_ID,
                    "Antrean Unduhan Komik",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Progres unduhan bab komik offline Yomic"
                }
                nm.createNotificationChannel(channel)
            }
        }
    }
}
