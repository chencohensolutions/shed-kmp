package com.chencohensolution.shed

import kotlinx.serialization.Serializable

/** What kind of file it is. Storage that knows kinds (the journal server) files it by this. */
@Serializable
enum class FileKind { PHOTO, VIDEO, AUDIO }

/** What the app says about a file when it hands it to shed ([Shed.put]). */
@Serializable
data class FileAbout(
    /** The file's name as the app shows it, e.g. `audio.aac`. */
    val fileName: String,
    val mimeType: String,
    val kind: FileKind,
    /** When it was taken or started, RFC 3339 with the offset of where it was (`2026-11-02T10:00:00+02:00`). */
    val takenAt: String? = null,
    val durationMillis: Long? = null,
    /** What the file is, in the storage's words: the journal server's source, such as `recording`. */
    val source: String? = null,
    /** Where it was on the device, as the device names it. */
    val folder: String? = null,
)

/** What a storage can do, so an app shows only what works (the journal server's `serverInfo`, for any storage). */
data class Capabilities(
    /** The storage makes previews of photos itself. */
    val previews: Boolean = false,
    /** The storage says what changed since a cursor ([ShedBackend.changes]). */
    val changeList: Boolean = false,
    /** The storage keeps small files that change, by version (not in this first version). */
    val appFiles: Boolean = false,
)

/** A file on the storage. */
data class RemoteFile(
    val sha256: String,
    val size: Long,
    val about: FileAbout,
    /** When the storage got it, RFC 3339; null when it doesn't say. */
    val storedAt: String? = null,
    /** The user tucked it away: apps leave it out of their lists until the user asks to see it. */
    val tucked: Boolean = false,
    /** What the apps wrote about it, a JSON object as text (the journal server's note); null when none. */
    val note: String? = null,
)

/** What changed on the storage since a cursor. */
data class ChangePage(
    /** Files added or changed. */
    val added: List<RemoteFile>,
    /** Files gone, or hidden from this device, by sha256. */
    val removed: List<String>,
    /** Pass to the next call; keep it only after applying this page. */
    val cursor: String,
    val hasMore: Boolean,
)

/** The storage's answer to starting an upload. */
sealed interface UploadStart {
    /** Send from [offset] (bytes an earlier try left there), in pieces of [pieceSize]. */
    data class Ready(val offset: Long, val pieceSize: Long) : UploadStart

    /** The storage has the file already: nothing to send. */
    data object Exists : UploadStart

    /** The storage won't take it, for [reason] (its words: `TOO_LARGE`, `NO_SPACE`, …). */
    data class Refused(val reason: String) : UploadStart
}

/** The storage's answer to one piece. */
sealed interface PieceAnswer {
    /** [offset] bytes have arrived; [complete] once the storage checked and kept the whole file. */
    data class Accepted(val offset: Long, val complete: Boolean) : PieceAnswer

    /** The storage has [offset] bytes: go on from there. */
    data class WrongOffset(val offset: Long) : PieceAnswer

    /** The upload is gone (it expired): start again. */
    data object Gone : PieceAnswer

    /** The bytes don't hash to the sha256 they were sent as: the file changed while it was read. */
    data object Mismatch : PieceAnswer
}

/**
 * Something the storage can't do now. [offline]: it couldn't be reached (try later, nothing is wrong with the file);
 * [unauthorized]: it refused this device (pair again).
 */
class BackendException(message: String, val offline: Boolean = false, val unauthorized: Boolean = false, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * One storage: the journal server (module backend-journal) first; others later as modules of their own. Everything shed
 * sends or reads goes through it, so the apps call the same [Shed] whatever the storage is.
 */
interface ShedBackend {
    val capabilities: Capabilities

    /** Starts (or resumes) sending the file [sha256] of [size] bytes. */
    suspend fun startUpload(sha256: String, size: Long, about: FileAbout): UploadStart

    /** Sends [bytes] of the file [sha256] from [offset]; the whole file is [total] bytes. */
    suspend fun sendPiece(sha256: String, offset: Long, bytes: ByteArray, total: Long): PieceAnswer

    /** The size of the file [sha256] on the storage; null when the storage doesn't have it (or hides it from this device). */
    suspend fun size(sha256: String): Long?

    /** [length] bytes of the file [sha256] from [from] (fewer at its end). */
    suspend fun read(sha256: String, from: Long, length: Int): ByteArray

    /** What changed since [cursor]; null for everything. Only when [Capabilities.changeList]. */
    suspend fun changes(cursor: String?): ChangePage

    /** Whether the storage holds the file [sha256] whole, checked, so the device may delete its copy. */
    suspend fun holds(sha256: String): Boolean
}
