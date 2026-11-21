package id.yomic.mobile.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import id.yomic.mobile.MainActivity
import id.yomic.mobile.R
import id.yomic.mobile.YomicApplication

/**
 * Periodic background worker that checks library manga for newly released chapters
 * using Smart Update + HTTP 304 conditional caching, and notifies the user when updates arrive.
 */
class LibraryUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? YomicApplication ?: return Result.success()
        val container = app.container
        val settings = container.settingsRepository.getCurrentSettings()
        if (settings.isOfflineMode) {
            return Result.success()
        }

        return try {
            if (container.jsExtensionManager.getAllSources().isEmpty()) {
                container.jsExtensionManager.loadAllInstalled()
            }
            val summary = container.libraryUpdateService.syncLibrary(respectSmartUpdate = settings.smartUpdate)
            if (summary.totalNewChapters > 0) {
                postUpdateNotification(
                    totalNewChapters = summary.totalNewChapters,
                    titles = summary.updatedMangaTitles,
                    isEn = settings.appLanguage.code == "en"
                )
            }
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    private fun postUpdateNotification(totalNewChapters: Int, titles: List<String>, isEn: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        ensureNotificationChannel(applicationContext)

        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, "updates")
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            1001,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isEn) {
            "$totalNewChapters new chapter(s) available"
        } else {
            "$totalNewChapters bab baru tersedia"
        }
        val previewTitles = titles.distinct().take(4).joinToString(", ")
        val moreSuffix = if (titles.distinct().size > 4) "…" else ""
        val body = if (previewTitles.isNotBlank()) "$previewTitles$moreSuffix" else title

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_UPDATES_ID)
            .setSmallIcon(R.drawable.ic_stat_yomic)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_UPDATES_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_UPDATES_ID = "yomic_updates"
        const val NOTIFICATION_UPDATES_ID = 4001

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                val channel = NotificationChannel(
                    CHANNEL_UPDATES_ID,
                    "Pembaruan Bab Komik",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifikasi saat bab baru ditemukan pada Pustaka Yomic"
                }
                nm.createNotificationChannel(channel)
            }
        }
    }
}
