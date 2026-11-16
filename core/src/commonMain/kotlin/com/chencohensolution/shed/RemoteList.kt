package com.chencohensolution.shed

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.Path

@Serializable
internal data class StoredRemote(val sha256: String, val size: Long, val about: FileAbout, val storedAt: String? = null, val tucked: Boolean = false, val note: String? = null)

@Serializable
internal data class RemoteListFile(val v: Int = 1, val cursor: String? = null, val files: List<StoredRemote> = emptyList())

/**
 * What the storage holds that this app keeps a list of (`remote.json`), brought up to date by [sync] from the storage's
 * change list. [keep] says which files the app wants listed (Audio Logs: sound recordings only).
 */
internal class RemoteList(fileSystem: FileSystem, directory: Path, private val keep: (RemoteFile) -> Boolean) {
    private val file = JsonFile(fileSystem, directory / "remote.json", RemoteListFile.serializer()) { RemoteListFile() }
    private val mutex = Mutex()
    private var stored = file.load()
    private val _files = MutableStateFlow(stored.files.map { it.toRemote() })
    val files: StateFlow<List<RemoteFile>> = _files.asStateFlow()

    /** Reads what changed since the last sync, page by page, keeping the cursor with each page. Returns what was added. */
    suspend fun sync(backend: ShedBackend): List<RemoteFile> = mutex.withLock {
        if (!backend.capabilities.changeList) return emptyList()
        val added = mutableListOf<RemoteFile>()
        do {
            val page = backend.changes(stored.cursor)
            val gone = page.removed.toSet() + page.added.map { it.sha256 }
            val new = page.added.filter(keep)
            added += new
            stored = RemoteListFile(cursor = page.cursor, files = stored.files.filterNot { it.sha256 in gone } +
                new.map { StoredRemote(it.sha256, it.size, it.about, it.storedAt, it.tucked, it.note) })
            file.save(stored)
            _files.value = stored.files.map { it.toRemote() }
        } while (page.hasMore)
        added
    }

    /** Forgets the list and its cursor: the next [sync] reads everything (after pairing with another storage). */
    suspend fun clear() = mutex.withLock {
        stored = RemoteListFile()
        file.save(stored)
        _files.value = emptyList()
    }

    private fun StoredRemote.toRemote() = RemoteFile(sha256, size, about, storedAt, tucked, note)
}
