package com.chencohensolution.shed.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.chencohensolution.shed.journal.JournalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Another app on the phone that uses shed, and may already be paired. */
data class SiblingApp(val packageName: String, val label: String)

/**
 * One enrollment per phone (journal-server-go docs/authentication.md, "One enrollment per phone"): an app not paired
 * yet asks an app that is, which asks the user and answers with an enrollment link only this app can use. Any app
 * built with shed answers ([SharePairingActivity], in shed's manifest), so the user enrolls the phone once, in
 * whichever app comes first.
 *
 * ```
 * val siblings = ShedSiblings.find(context)          // "Use the server from Journal"
 * startActivityForResult(ShedSiblings.ask(siblings[0], "audio-logs"), REQUEST)
 * // onActivityResult: ShedSiblings.linkOf(data) -> journal.pair(link, deviceName)
 * ```
 */
object ShedSiblings {
    const val ACTION = "com.chencohensolution.shed.action.SHARE_PAIRING"
    const val EXTRA_APP = "com.chencohensolution.shed.extra.APP"
    const val EXTRA_LINK = "com.chencohensolution.shed.extra.LINK"
    /** Why it answered without a link: [NOT_PAIRED], [FAILED] (with the server's words in [EXTRA_MESSAGE]). */
    const val EXTRA_REASON = "com.chencohensolution.shed.extra.REASON"
    const val EXTRA_MESSAGE = "com.chencohensolution.shed.extra.MESSAGE"
    const val NOT_PAIRED = "not-paired"
    const val FAILED = "failed"

    /** The other apps on the phone built with shed. */
    fun find(context: Context): List<SiblingApp> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(ACTION), PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { SiblingApp(it.packageName, it.applicationInfo.loadLabel(pm).toString()) }
    }

    /** The intent that asks [sibling] for a pairing for [app] (this app's name on the server, e.g. `audio-logs`). */
    fun ask(sibling: SiblingApp, app: String): Intent =
        Intent(ACTION).setPackage(sibling.packageName).putExtra(EXTRA_APP, app)

    /** The enrollment link in the answer; null when the user declined or the other app couldn't make one. */
    fun linkOf(result: Intent?): String? = result?.getStringExtra(EXTRA_LINK)

    /** Why there is no link: [NOT_PAIRED], [FAILED], or null when the user said no. */
    fun reasonOf(result: Intent?): String? = result?.getStringExtra(EXTRA_REASON)
}

/**
 * Answers another app's [ShedSiblings.ACTION]: when this app is paired, it asks the user whether that app (by its
 * name on the phone) may use the same server, and answers with a link made for it ([com.chencohensolution.shed.journal.JournalBackend.siblingLink]).
 * Any app may ask; the user decides, seeing which app asks.
 */
class SharePairingActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = application as? ShedHost
        val app = intent.getStringExtra(ShedSiblings.EXTRA_APP)?.takeIf { it.isNotBlank() }
        val caller = callingPackage
        val state = host?.androidShed?.journal?.state?.value
        if (host == null || app == null || caller == null || state !is JournalState.Paired) {
            answer(RESULT_CANCELED, Intent().putExtra(ShedSiblings.EXTRA_REASON, ShedSiblings.NOT_PAIRED))
            return
        }
        val words = Words.of(Locale.getDefault())
        val me = applicationInfo.loadLabel(packageManager).toString()
        val them = runCatching {
            packageManager.getApplicationInfo(caller, 0).loadLabel(packageManager).toString()
        }.getOrDefault(caller)
        val server = state.server.removePrefix("https://")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(words.title(them))
            .setMessage(words.message(them, me, server, state.user))
            .setPositiveButton(words.allow) { _, _ -> share(host, app) }
            .setNegativeButton(words.deny) { _, _ -> answer(RESULT_CANCELED, null) }
            .setOnCancelListener { answer(RESULT_CANCELED, null) }
            .show()
    }

    private fun share(host: ShedHost, app: String) {
        scope.launch {
            val link = runCatching { withContext(Dispatchers.IO) { host.androidShed.journal.siblingLink(app) } }
            link.onSuccess { answer(RESULT_OK, Intent().putExtra(ShedSiblings.EXTRA_LINK, it)) }
                .onFailure {
                    answer(
                        RESULT_CANCELED,
                        Intent().putExtra(ShedSiblings.EXTRA_REASON, ShedSiblings.FAILED).putExtra(ShedSiblings.EXTRA_MESSAGE, it.message),
                    )
                }
        }
    }

    private fun answer(code: Int, data: Intent?) {
        setResult(code, data)
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** The dialog's words, in Hebrew on a Hebrew phone (shed has no resources of its own). */
    private class Words(
        val title: (String) -> String,
        val message: (them: String, me: String, server: String, user: String) -> String,
        val allow: String,
        val deny: String,
    ) {
        companion object {
            fun of(locale: Locale) = if (locale.language == "iw" || locale.language == "he") Words(
                title = { "לחבר את $it לשרת?" },
                message = { them, me, server, user ->
                    "$me מחובר אל $server בתור $user. $them יתחבר לאותו שרת בתור הטלפון הזה, בלי קוד רישום חדש, ויוכל לקרוא ולהעלות קבצים שלך שם."
                },
                allow = "חיבור",
                deny = "לא עכשיו",
            ) else Words(
                title = { "Connect $it to your server?" },
                message = { them, me, server, user ->
                    "$me is paired with $server as $user. $them will join the same server as this phone, with no new " +
                        "enrollment code, and can read and upload your files there."
                },
                allow = "Connect",
                deny = "Not now",
            )
        }
    }
}

