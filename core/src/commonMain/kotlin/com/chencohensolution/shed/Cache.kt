package com.chencohensolution.shed

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

@Serializable
internal data class CachedEntry(
    val size: Long,
    val lastUsed: Long,
    val pinned: Boolean = false,
    /** The whole file is in `whole/<sha256>`; otherwise only the blocks in `blocks/<sha256>/`. */
    val whole: Boolean = false,
    /** Bytes this file takes in the cache now. */
    val bytes: Long = 0,
)

@Serializable
internal data class CacheIndex(val v: Int = 1, val files: Map<String, CachedEntry> = emptyMap())

/** A whole-file download running: [got] of [of] bytes. */
data class DownloadStatus(val sha256: String, val got: Long, val of: Long)

/**
 * Files read from the storage, kept on the device up to [limitBytes] (the oldest used go first; pinned ones never).
 * A read of part of a file ([read]) fetches only the [BLOCK]-sized blocks it touches, so playing from the middle of a
 * long recording fetches from the middle. [fetch] brings a whole file, for code that needs a path. Everything here is
 * a copy: losing it only means fetching again.
 */
internal class Cache(
    private val fileSystem: FileSystem,
    private val directory: Path,
    var limitBytes: Long,
    private val now: () -> Long = ::nowMillis,
) {
    private val indexFile = JsonFile(fileSystem, directory / "cache.json", CacheIndex.serializer()) { CacheIndex() }
    private val mutex = Mutex()
    private var index: Map<String, CachedEntry> = indexFile.load().files
    private val _downloads = MutableStateFlow<List<DownloadStatus>>(emptyList())
    val downloads: StateFlow<List<DownloadStatus>> = _downloads.asStateFlow()

    private fun wholePath(sha: String) = directory / "whole" / sha
    private fun blockPath(sha: String, block: Long) = directory / BLOCKS / sha / block.toString()

    init {
        // Blocks of the size before (1 MiB, until Nov 16, 2026) are let go: their numbers mean other places now.
        if (fileSystem.exists(directory / "blocks")) {
            runCatching { fileSystem.deleteRecursively(directory / "blocks") }
            save(index.mapValues { (_, e) -> if (e.whole) e else e.copy(bytes = 0) })
        }
    }

    /** The size of [sha] as the app already knows it (from the storage's list): the first read needn't ask. */
    suspend fun knowSize(sha: String, size: Long) = mutex.withLock {
        if (sha !in index) save(index + (sha to CachedEntry(size, now())))
    }

    /** The size of [sha], from the cache, else from the storage; null when the storage doesn't have it. */
    suspend fun size(backend: ShedBackend, sha: String): Long? {
        mutex.withLock { index[sha]?.let { return it.size } }
        val size = backend.size(sha) ?: return null
        mutex.withLock { if (sha !in index) save(index + (sha to CachedEntry(size, now()))) }
        return size
    }

    /** [length] bytes of [sha] from [position] (fewer at its end, none past it). */
    suspend fun read(backend: ShedBackend, sha: String, position: Long, length: Int): ByteArray {
        val size = size(backend, sha) ?: throw BackendException("The storage doesn't have $sha")
        if (position >= size || length <= 0) return ByteArray(0)
        val end = minOf(size, position + length)
        val out = ByteArray((end - position).toInt())
        val whole = mutex.withLock { index[sha]?.whole == true }
        if (whole) {
            fileSystem.openReadOnly(wholePath(sha)).use { it.read(position, out, 0, out.size) }
            touch(sha)
            return out
        }
        var at = position
        while (at < end) {
            val block = at / BLOCK
            val blockStart = block * BLOCK
            val bytes = block(backend, sha, size, block)
            val from = (at - blockStart).toInt()
            val count = minOf(bytes.size - from, (end - at).toInt())
            bytes.copyInto(out, (at - position).toInt(), from, from + count)
            at += count
        }
        touch(sha)
        return out
    }

    private suspend fun block(backend: ShedBackend, sha: String, size: Long, block: Long): ByteArray {
        val path = blockPath(sha, block)
        if (fileSystem.exists(path)) return fileSystem.read(path) { readByteArray() }
        val start = block * BLOCK
        val bytes = backend.read(sha, start, minOf(BLOCK.toLong(), size - start).toInt())
        path.parent?.let(fileSystem::createDirectories)
        val temp = path.parent!! / "$block.tmp"
        fileSystem.write(temp) { write(bytes) }
        fileSystem.atomicMove(temp, path)
        mutex.withLock {
            val entry = index[sha] ?: CachedEntry(size, now())
            save(index + (sha to entry.copy(bytes = entry.bytes + bytes.size)))
        }
        evict()
        return bytes
    }

    /** The whole of [sha] as a file on the device, fetched now if it isn't; [progress] gets bytes so far and the size. */
    suspend fun fetch(backend: ShedBackend, sha: String, progress: (got: Long, of: Long) -> Unit = { _, _ -> }): Path {
        val target = wholePath(sha)
        if (mutex.withLock { index[sha]?.whole == true } && fileSystem.exists(target)) {
            touch(sha)
            return target
        }
        val size = size(backend, sha) ?: throw BackendException("The storage doesn't have $sha")
        target.parent?.let(fileSystem::createDirectories)
        val temp = target.parent!! / "$sha.tmp"
        try {
            fileSystem.sink(temp).buffer().use { out ->
                var at = 0L
                while (at < size) {
                    val block = at / BLOCK
                    val path = blockPath(sha, block)
                    val bytes = if (fileSystem.exists(path)) fileSystem.read(path) { readByteArray() }
                    else backend.read(sha, at, minOf(BLOCK.toLong(), size - at).toInt())
                    if (bytes.isEmpty()) throw BackendException("The storage sent nothing at $at of $sha")
                    out.write(bytes)
                    at += bytes.size
                    progress(at, size)
                    _downloads.value = _downloads.value.filterNot { it.sha256 == sha } + DownloadStatus(sha, at, size)
                }
            }
            fileSystem.atomicMove(temp, target)
        } finally {
            _downloads.value = _downloads.value.filterNot { it.sha256 == sha }
            fileSystem.delete(temp)
        }
        fileSystem.deleteRecursively(directory / BLOCKS / sha)
        mutex.withLock {
            val entry = index[sha] ?: CachedEntry(size, now())
            save(index + (sha to entry.copy(whole = true, bytes = size, lastUsed = now())))
        }
        evict()
        return target
    }

    /** Whether the whole of [sha] is on the device. */
    suspend fun isWhole(sha: String): Boolean = mutex.withLock { index[sha]?.whole == true }

    suspend fun pin(sha: String, pinned: Boolean) = mutex.withLock {
        index[sha]?.let { save(index + (sha to it.copy(pinned = pinned))) }
    }

    /** Bytes kept now. */
    suspend fun bytes(): Long = mutex.withLock { index.values.sumOf { it.bytes } }

    /** Drops the files used longest ago, unpinned, until the cache is within [limitBytes]. */
    suspend fun evict() = mutex.withLock {
        var total = index.values.sumOf { it.bytes }
        if (total <= limitBytes) return@withLock
        var kept = index
        for ((sha, entry) in index.entries.filter { !it.value.pinned && it.value.bytes > 0 }.sortedBy { it.value.lastUsed }) {
            if (total <= limitBytes) break
            fileSystem.delete(wholePath(sha))
            fileSystem.deleteRecursively(directory / BLOCKS / sha)
            kept = kept + (sha to entry.copy(whole = false, bytes = 0))
            total -= entry.bytes
        }
        save(kept)
    }

    /** Marks [sha] used now; written to disk with the next change, not on every read (a player reads often). */
    private suspend fun touch(sha: String) = mutex.withLock {
        index[sha]?.let { index = index + (sha to it.copy(lastUsed = now())) }
    }

    private fun save(new: Map<String, CachedEntry>) {
        index = new
        indexFile.save(CacheIndex(files = new))
    }

    companion object {
        /**
         * Reads are fetched and kept in blocks of 256 KiB: small enough that a player's first read (the start of a
         * recording, then its index at the end) comes in a second on a slow link.
         */
        const val BLOCK = 1 shl 18
        private const val BLOCKS = "parts"
    }
}
