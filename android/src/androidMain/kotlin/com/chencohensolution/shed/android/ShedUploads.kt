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
import androidx.work.workDataOf
import com.chencohensolution.shed.RunResult
import java.util.concurrent.TimeUnit

/** What the app hands shed's background work: its [AndroidShed]. The app's Application implements it. */
interface ShedHost {
    val androidShed: AndroidShed
}

/**
 * Sends what is waiting in the background, with WorkManager: when there is a network ([wifiOnly]: an unmetered one),
 * again later when the server doesn't answer, and after the phone restarts. With [chargingMinBattery] it waits for the
 * charger too, and starts only once the battery is at that percent or more.
 */
object ShedUploads {
    private const val WORK = "shed-uploads"
    internal const val MIN_BATTERY = "minBattery"

    /**
     * [chargingMinBattery]: null sends whenever the network allows; else only on the charger with at least this much
     * battery. [rulesChanged]: the user changed Wi‑Fi only or the charger rule, so work waiting under the old rules is
     * replaced instead of being followed.
     */
    fun schedule(context: Context, wifiOnly: Boolean, chargingMinBattery: Int? = null, rulesChanged: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresCharging(chargingMinBattery != null)
                    .build(),
            )
            .setInputData(workDataOf(MIN_BATTERY to (chargingMinBattery ?: 0)))
            // On the charger below the battery wanted: looked at again every quarter hour while it charges.
            .setBackoffCriteria(
                if (chargingMinBattery != null) BackoffPolicy.LINEAR else BackoffPolicy.EXPONENTIAL,
                if (chargingMinBattery != null) 15L else 1L, TimeUnit.MINUTES,
            )
            .build()
        // After a run already going (it may have read the queue before this file came), one more; a failed one is replaced.
        val policy = if (rulesChanged) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}

/** One pass of the upload queue. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? ShedHost ?: return Result.failure()
        val minBattery = inputData.getInt(ShedUploads.MIN_BATTERY, 0)
        if (minBattery > 0) {
            val battery = applicationContext.getSystemService(android.os.BatteryManager::class.java)
            val percent = battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            // Not yet: the charger is in (the work asks for it) but the battery is still low.
            if (percent < minBattery) return Result.retry()
        }
        return when (host.androidShed.shed.runUploads()) {
            is RunResult.Done -> Result.success()
            is RunResult.Offline -> Result.retry()
            // Not paired, or the server revoked this device: nothing to do until the user pairs again.
            RunResult.Unauthorized -> Result.failure()
        }
    }
}
