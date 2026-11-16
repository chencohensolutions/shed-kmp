package com.chencohensolution.shed

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/** Where one file handed to [Shed.put] is. */
@Serializable
enum class UploadState {
    /** Waiting for the next run. */
    WAITING,

    /** The storage has it: sent now, or it had it already. */
    DONE,

    /** The storage won't take it ([UploadStatus.refused] says why), or the file is gone from the device. */
    REFUSED,
}

/** One file in the queue, as the app shows it. */
data class UploadStatus(
    val sha256: String,
    val fileName: String,
    val size: Long,
    /** Bytes the storage has so far. */
    val sent: Long,
    val state: UploadState,
    val refused: String? = null,
    /** What the app named the file by when it handed it over ([Shed.put]'s ref), e.g. a moment's id. */
    val ref: String? = null,
)

/** Where a file the app named by a ref is now, as [FileLocator] finds it. */
class Located(val fileSystem: FileSystem, val path: Path)

/**
 * Finds a file the app handed over by a ref instead of a path: the app moves its files (Audio Logs moves a finished
 * moment to a folder the user picked), so the queue asks where the file is when it sends it.
 */
fun interface FileLocator {
    /** Where the file named [ref] is now; null when it is gone. */
    fun locate(ref: String): Located?
}

/** How a run of the queue ended. */
sealed interface RunResult {
    /** Everything waiting was sent (or refused). */
    data class Done(val sent: Int, val refused: Int) : RunResult

    /** The storage couldn't be reached; the rest waits for the next run. */
    data class Offline(val sent: Int, val message: String) : RunResult

    /** The storage refused this device: pair again. Nothing is lost. */
    data object Unauthorized : RunResult
}

@Serializable
internal data class QueuedFile(
    /** The file on the device, as okio writes the path. */
    val path: String,
    val sha256: String,
    val size: Long,
    val about: FileAbout,
    val addedAt: Long,
    val state: UploadState = UploadState.WAITING,
    val sent: Long = 0,
    val refused: String? = null,
    val doneAt: Long? = null,
    /** Set when the app handed the file over by a ref: [path] is then where it was, and [FileLocator] finds it now. */
    val ref: String? = null,
)

@Serializable
internal data class QueueFile(val v: Int = 1, val files: List<QueuedFile> = emptyList())

/**
 * The files waiting to go to the storage, kept on disk (`uploads.json`), so a file handed over is sent even after the
 * app was killed. [run] sends them in pieces, resuming where the storage says it got to, so a dropped connection costs
 * one piece at most. A file done stays listed until the app that handed it over [acknowledge]s it (owner's call,
 * Nov 17, 2026: shed will one day be one app serving several, and the app must learn every upload's end); it was kept
 * a week before, and the app took a file no longer listed for one sent.
 */
internal class UploadQueue(
    private val fileSystem: FileSystem,
    directory: Path,
    private val now: () -> Long = ::nowMillis,
    private val locator: FileLocator? = null,
) {
    private val file = JsonFile(fileSystem, directory / "uploads.json", QueueFile.serializer()) { QueueFile() }
    private val mutex = Mutex()
    private var files: List<QueuedFile> = file.load().files
    private val _status = MutableStateFlow(files.map(::statusOf))
    val status: StateFlow<List<UploadStatus>> = _status.asStateFlow()

    /**
     * Hands [path] over: hashed now (it must be finished), queued, and returns its sha256. Again for a file already
     * queued: nothing new. With a [ref], the file is found by it when it is sent (it may have moved by then), and
     * [from] is the file system it is in now.
     */
    suspend fun put(path: Path, about: FileAbout, ref: String? = null, from: FileSystem = fileSystem): String {
        val sha256 = sha256Of(from, path)
        val size = from.metadata(path).size ?: error("No size for $path")
        mutex.withLock {
            val known = files.firstOrNull { it.sha256 == sha256 }
            if (known != null && known.state != UploadState.REFUSED) return sha256
            update(files.filterNot { it.sha256 == sha256 } + QueuedFile(path.toString(), sha256, size, about, now(), ref = ref))
        }
        return sha256
    }

    fun waiting(): Int = files.count { it.state == UploadState.WAITING }

    /**
     * Sends what waits, one file at a time, oldest first. A file the storage keeps losing (it gave up after its restarts)
     * stays waiting for the next run and the run goes on with the others: tried again in the same run, it was picked
     * first again and again, and the run never ended.
     */
    suspend fun run(backend: ShedBackend): RunResult {
        var sent = 0
        var refused = 0
        val putOff = mutableSetOf<String>()
        while (true) {
            val next = mutex.withLock { files.firstOrNull { it.state == UploadState.WAITING && it.sha256 !in putOff } } ?: break
            try {
                when (send(backend, next)) {
                    UploadState.DONE -> sent++
                    UploadState.REFUSED -> refused++
                    UploadState.WAITING -> putOff += next.sha256
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendException) {
                if (e.unauthorized) return RunResult.Unauthorized
                return RunResult.Offline(sent, e.message ?: "offline")
            } catch (e: Exception) {
                // The device couldn't read this file (its folder, its provider): it waits for the next run, the rest go on.
                putOff += next.sha256
            }
        }
        return RunResult.Done(sent, refused)
    }

    private suspend fun send(backend: ShedBackend, queued: QueuedFile): UploadState {
        val located = if (queued.ref != null) locator?.locate(queued.ref) else Located(fileSystem, queued.path.toPath())
        val source = located?.fileSystem
        val path = located?.path
        if (source == null || path == null || !source.exists(path)) return finish(queued, UploadState.REFUSED, "The file is gone from the device")
        var restarts = 0
        while (true) {
            when (val start = backend.startUpload(queued.sha256, queued.size, queued.about)) {
                UploadStart.Exists -> return finish(queued, UploadState.DONE)
                is UploadStart.Refused -> return finish(queued, UploadState.REFUSED, start.reason)
                is UploadStart.Ready -> {
                    var offset = start.offset
                    progress(queued, offset)
                    source.openReadOnly(path).use { handle ->
                        while (true) {
                            val length = minOf(start.pieceSize, queued.size - offset).toInt()
                            if (length <= 0) break // the storage says it has it all, yet asks for more: start again
                            val bytes = ByteArray(length)
                            handle.source(offset).buffer().use { it.readFully(bytes) }
                            when (val answer = backend.sendPiece(queued.sha256, offset, bytes, queued.size)) {
                                is PieceAnswer.Accepted -> {
                                    offset = answer.offset
                                    progress(queued, offset)
                                    if (answer.complete) return finish(queued, UploadState.DONE)
                                    if (offset >= queued.size) return finish(queued, UploadState.REFUSED, "The storage took every byte but didn't keep the file")
                                }
                                is PieceAnswer.WrongOffset -> offset = answer.offset
                                PieceAnswer.Gone -> break
                                PieceAnswer.Mismatch -> {
                                    // The file changed after it was handed over: it isn't the file that was finished.
                                    return finish(queued, UploadState.REFUSED, "The file changed after it was handed over")
                                }
                            }
                        }
                    }
                    if (++restarts > 2) return UploadState.WAITING
                }
            }
        }
    }

    private suspend fun progress(queued: QueuedFile, sent: Long) = mutex.withLock {
        update(files.map { if (it.sha256 == queued.sha256) it.copy(sent = sent) else it })
    }

    private suspend fun finish(queued: QueuedFile, state: UploadState, refused: String? = null): UploadState = mutex.withLock {
        update(files.map {
            if (it.sha256 != queued.sha256) it
            else it.copy(state = state, refused = refused, doneAt = now(), sent = if (state == UploadState.DONE) it.size else it.sent)
        })
        state
    }

    /** The app recorded these files as sent: they leave the list. Only files done; others are left as they are. */
    suspend fun acknowledge(sha256s: Collection<String>) = mutex.withLock {
        val gone = sha256s.toSet()
        val kept = files.filterNot { it.state == UploadState.DONE && it.sha256 in gone }
        if (kept.size != files.size) update(kept)
    }

    private fun update(new: List<QueuedFile>) {
        files = new
        file.save(QueueFile(files = new))
        _status.value = new.map(::statusOf)
    }

    private fun statusOf(q: QueuedFile) = UploadStatus(q.sha256, q.about.fileName, q.size, q.sent, q.state, q.refused, q.ref)
}
