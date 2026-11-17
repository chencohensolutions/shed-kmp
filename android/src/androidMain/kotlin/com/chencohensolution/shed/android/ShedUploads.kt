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
 * charger too, and starts only once the battery is at that percent or more. With [wifiNetworks], only on those Wi‑Fi
 * networks (by name; reading the name needs location permission, "all the time" for the background work).
 */
object ShedUploads {
    private const val WORK = "shed-uploads"
    internal const val MIN_BATTERY = "minBattery"
    internal const val WIFI_NETWORKS = "wifiNetworks"

    /**
     * [chargingMinBattery]: null sends whenever the network allows; else only on the charger with at least this much
     * battery. [wifiNetworks]: empty sends on any network [wifiOnly] allows; else only on a Wi‑Fi network of these
     * names. [rulesChanged]: the user changed Wi‑Fi only, the networks or the charger rule, so work waiting under the old rules is
     * replaced instead of being followed.
     */
    fun schedule(
        context: Context, wifiOnly: Boolean, chargingMinBattery: Int? = null, rulesChanged: Boolean = false,
        wifiNetworks: Set<String> = emptySet(),
    ) {
        val waits = chargingMinBattery != null || wifiNetworks.isNotEmpty()
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly || wifiNetworks.isNotEmpty()) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresCharging(chargingMinBattery != null)
                    .build(),
            )
            .setInputData(workDataOf(MIN_BATTERY to (chargingMinBattery ?: 0), WIFI_NETWORKS to wifiNetworks.toTypedArray()))
            // Below the battery wanted, or on another network: looked at again every quarter hour.
            .setBackoffCriteria(if (waits) BackoffPolicy.LINEAR else BackoffPolicy.EXPONENTIAL, if (waits) 15L else 1L, TimeUnit.MINUTES)
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
        val networks = inputData.getStringArray(ShedUploads.WIFI_NETWORKS)?.toSet().orEmpty()
        // Not one of the networks chosen (or its name can't be read: no location permission): later.
        if (networks.isNotEmpty() && WifiName.current(applicationContext) !in networks) return Result.retry()
        return when (host.androidShed.shed.runUploads()) {
            is RunResult.Done -> Result.success()
            is RunResult.Offline -> Result.retry()
            // Not paired, or the server revoked this device: nothing to do until the user pairs again.
            RunResult.Unauthorized -> Result.failure()
        }
    }
}

/** The name (SSID) of the Wi‑Fi network the phone is on, without quotes; null when not on Wi‑Fi or it can't be read. */
object WifiName {
    @Suppress("DEPRECATION")
    fun current(context: Context): String? {
        val wifi = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java) ?: return null
        val ssid = runCatching { wifi.connectionInfo?.ssid }.getOrNull() ?: return null
        // Without location permission (or with location off) Android hides the name as "<unknown ssid>".
        if (ssid == android.net.wifi.WifiManager.UNKNOWN_SSID || ssid.isBlank()) return null
        return ssid.removeSurrounding("\"")
    }
}
