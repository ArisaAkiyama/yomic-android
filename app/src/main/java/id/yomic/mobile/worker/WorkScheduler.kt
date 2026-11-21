package id.yomic.mobile.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WorkScheduler {
    const val WORK_NAME_LIBRARY_UPDATE = "yomic_periodic_library_update"
    const val WORK_NAME_DOWNLOAD_QUEUE = "yomic_download_queue"

    fun resolveDownloadWorkPolicy(replaceConstraints: Boolean): ExistingWorkPolicy =
        if (replaceConstraints) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE

    fun enqueueDownloadQueue(
        context: Context,
        onlyOnWifi: Boolean,
        replaceConstraints: Boolean = false
    ) {
        val workManager = runCatching { WorkManager.getInstance(context.applicationContext) }.getOrNull() ?: return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (onlyOnWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            WORK_NAME_DOWNLOAD_QUEUE,
            resolveDownloadWorkPolicy(replaceConstraints),
            request
        )
    }

    fun parseUpdateIntervalHours(intervalKey: String): Long? = when (intervalKey.trim().lowercase()) {
        "6h" -> 6L
        "12h" -> 12L
        "24h" -> 24L
        "0", "manual", "off", "" -> null
        else -> 12L
    }

    fun scheduleLibraryUpdates(context: Context, intervalKey: String) {
        val workManager = runCatching { WorkManager.getInstance(context.applicationContext) }.getOrNull() ?: return
        val hours = parseUpdateIntervalHours(intervalKey)
        if (hours == null) {
            workManager.cancelUniqueWork(WORK_NAME_LIBRARY_UPDATE)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<LibraryUpdateWorker>(hours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME_LIBRARY_UPDATE,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
