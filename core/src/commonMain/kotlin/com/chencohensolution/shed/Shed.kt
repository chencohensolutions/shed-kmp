package com.chencohensolution.shed

import kotlinx.coroutines.flow.StateFlow
import okio.FileSystem
import okio.Path

/**
 * The one object an app talks to (docs/plan.md "Contract API"). It sends files to the storage and reads them back,
 * whatever the storage is: [backend] is the journal server's module, or another storage's later.
 *
 * Everything shed keeps is under [directory], in its own files: the upload queue (`uploads.json`), the cache
 * (`cache/`) and the list of files on the storage (`remote.json`). Nothing there is the only copy of anything: losing
 * it means hashing, sending or fetching again.
 */
class Shed(
    val backend: ShedBackend,
    fileSystem: FileSystem,
    directory: Path,
    /** Most bytes the cache keeps; the oldest used go first. */
    cacheLimitBytes: Long = DEFAULT_CACHE_LIMIT,
    /** Which files on the storage [remote] lists: all by default; Audio Logs keeps sound recordings. */
    keepListed: (RemoteFile) -> Boolean = { true },
    now: () -> Long = ::nowMillis,
) {
    private val queue = UploadQueue(fileSystem, directory, now)
    private val cache = Cache(fileSystem, directory / "cache", cacheLimitBytes, now)
    private val remoteList = RemoteList(fileSystem, directory, keepListed)

    // Sending

    /** The files handed over, waiting, done or refused, with bytes sent so far. */
    val uploads: StateFlow<List<UploadStatus>> get() = queue.status

    /**
     * Hands [file] to shed to send: hashed now and queued on disk, so it is sent even if the app is killed. The file must
     * be finished: shed never looks at it again but to read it while sending. Returns its sha256, the name the storage
     * knows it by. The next [runUploads] sends it.
     */
    suspend fun put(file: Path, about: FileAbout): String = queue.put(file, about)

    /** How many files wait to be sent. */
    fun waiting(): Int = queue.waiting()

    /** Sends what waits. Called by the app's background job (WorkManager on Android) with the user's limits met. */
    suspend fun runUploads(): RunResult = queue.run(backend)

    /** Whether the storage holds [sha256] whole and checked, so the device may delete its own copy. */
    suspend fun verify(sha256: String): Boolean = backend.holds(sha256)

    // Reading

    /** A reader of [sha256] by ranges, for a player: it fetches only what is read and keeps it in the cache. */
    suspend fun open(sha256: String): ShedReader {
        val size = cache.size(backend, sha256) ?: throw BackendException("The storage doesn't have $sha256")
        return ShedReader(sha256, size) { position, length -> cache.read(backend, sha256, position, length) }
    }

    /** The whole file [sha256] on the device, fetched now if it isn't yet, for code that needs a path. */
    suspend fun fetch(sha256: String, progress: (got: Long, of: Long) -> Unit = { _, _ -> }): Path =
        cache.fetch(backend, sha256, progress)

    /** Whole-file downloads running now, with bytes so far. */
    val downloads: StateFlow<List<DownloadStatus>> get() = cache.downloads

    /** Whether the whole of [sha256] is in the cache. */
    suspend fun isCached(sha256: String): Boolean = cache.isWhole(sha256)

    /** Keeps [sha256] in the cache whatever its limit (Keep on this phone), or lets it go again. */
    suspend fun pin(sha256: String, pinned: Boolean = true) = cache.pin(sha256, pinned)

    var cacheLimitBytes: Long
        get() = cache.limitBytes
        set(value) {
            cache.limitBytes = value
        }

    /** Bytes the cache keeps now. */
    suspend fun cacheBytes(): Long = cache.bytes()

    // What the storage holds

    /** The files on the storage this app lists, as of the last [sync]. */
    val remote: StateFlow<List<RemoteFile>> get() = remoteList.files

    /** Brings [remote] up to date from the storage's change list; returns the files new since the last sync. */
    suspend fun sync(): List<RemoteFile> = remoteList.sync(backend)

    /** Forgets [remote] (after pairing with another storage), so the next [sync] reads it all again. */
    suspend fun forgetRemote() = remoteList.clear()

    companion object {
        const val DEFAULT_CACHE_LIMIT = 2L shl 30
    }
}

/** Reads one file of the storage by ranges ([Shed.open]). */
class ShedReader internal constructor(
    val sha256: String,
    val size: Long,
    private val reader: suspend (position: Long, length: Int) -> ByteArray,
) {
    /** [length] bytes from [position]; fewer at the end of the file, none past it. */
    suspend fun read(position: Long, length: Int): ByteArray = reader(position, length)
}
