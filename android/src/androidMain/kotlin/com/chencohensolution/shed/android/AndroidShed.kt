package com.chencohensolution.shed.android

import android.content.Context
import android.os.Build
import com.chencohensolution.shed.FileKind
import com.chencohensolution.shed.FileLocator
import com.chencohensolution.shed.RemoteFile
import com.chencohensolution.shed.Shed
import com.chencohensolution.shed.journal.JournalBackend
import com.chencohensolution.shed.journal.OkHttpJournalClients
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Shed for one app on Android, with the journal server as its storage. Everything it keeps is in the app's own
 * `files/shed/` (the queue, the cache, the list of what the server holds, the pairing); the key is in the app's
 * Keystore. Make one per process (the app's Application, or a lazy singleton) and hand it to [ShedUploads].
 *
 * Until the app is paired nothing goes out: [Shed.put] queues and [Shed.runUploads] reports that it isn't paired.
 */
class AndroidShed(
    context: Context,
    app: String,
    keepListed: (RemoteFile) -> Boolean = { true },
    locator: FileLocator? = null,
    /** The kinds the list of what the server holds is asked for (Audio Logs: sound); empty for all. */
    listedKinds: Set<FileKind> = emptySet(),
) {
    private val folder = context.applicationContext.filesDir.toOkioPath() / "shed"
    private val keys = AndroidDeviceKeys()
    val clients = OkHttpJournalClients(keys)
    val journal = JournalBackend(app, FileSystem.SYSTEM, folder / "journal", keys, clients)
    val shed = Shed(journal, FileSystem.SYSTEM, folder, keepListed = keepListed, listedKinds = listedKinds, locator = locator)

    /** The name the server lists this device by, until the user names it: the phone's model. */
    val defaultDeviceName: String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    companion object {
        /** Audio Logs keeps only sound recordings in its list of what the server holds. */
        val AUDIO_ONLY: (RemoteFile) -> Boolean = { it.about.kind == FileKind.AUDIO }
    }
}
