package com.chencohensolution.shed.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.chencohensolution.shed.RunResult
import java.util.concurrent.TimeUnit

/** What the app hands shed's background work: its [AndroidShed]. The app's Application implements it. */
interface ShedHost {
    val androidShed: AndroidShed
}

/**
 * Sends what is waiting in the background, with WorkManager: when there is a network ([wifiOnly]: an unmetered one),
 * again later when the server doesn't answer, and after the phone restarts.
 */
object ShedUploads {
    private const val WORK = "shed-uploads"

    fun schedule(context: Context, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        // After a run already going (it may have read the queue before this file came), one more; a failed one is replaced.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}

/** One pass of the upload queue. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? ShedHost ?: return Result.failure()
        return when (host.androidShed.shed.runUploads()) {
            is RunResult.Done -> Result.success()
            is RunResult.Offline -> Result.retry()
            // Not paired, or the server revoked this device: nothing to do until the user pairs again.
            RunResult.Unauthorized -> Result.failure()
        }
    }
}
