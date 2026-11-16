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
)

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
)

@Serializable
internal data class QueueFile(val v: Int = 1, val files: List<QueuedFile> = emptyList())

/**
 * The files waiting to go to the storage, kept on disk (`uploads.json`), so a file handed over is sent even after the
 * app was killed. [run] sends them in pieces, resuming where the storage says it got to, so a dropped connection costs
 * one piece at most. Files done stay listed for a while ([KEEP_DONE]) so the app can show them as backed up.
 */
internal class UploadQueue(
    private val fileSystem: FileSystem,
    directory: Path,
    private val now: () -> Long = ::nowMillis,
) {
    private val file = JsonFile(fileSystem, directory / "uploads.json", QueueFile.serializer()) { QueueFile() }
    private val mutex = Mutex()
    private var files: List<QueuedFile> = file.load().files
    private val _status = MutableStateFlow(files.map(::statusOf))
    val status: StateFlow<List<UploadStatus>> = _status.asStateFlow()

    /** Hands [path] over: hashed now (it must be finished), queued, and returns its sha256. Again for a file already queued: nothing new. */
    suspend fun put(path: Path, about: FileAbout): String {
        val sha256 = sha256Of(fileSystem, path)
        val size = fileSystem.metadata(path).size ?: error("No size for $path")
        mutex.withLock {
            val known = files.firstOrNull { it.sha256 == sha256 }
            if (known != null && known.state != UploadState.REFUSED) return sha256
            update(files.filterNot { it.sha256 == sha256 } + QueuedFile(path.toString(), sha256, size, about, now()))
        }
        return sha256
    }

    fun waiting(): Int = files.count { it.state == UploadState.WAITING }

    /** Sends what waits, one file at a time, oldest first. */
    suspend fun run(backend: ShedBackend): RunResult {
        var sent = 0
        var refused = 0
        while (true) {
            val next = mutex.withLock { files.firstOrNull { it.state == UploadState.WAITING } } ?: break
            try {
                when (send(backend, next)) {
                    UploadState.DONE -> sent++
                    UploadState.REFUSED -> refused++
                    UploadState.WAITING -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendException) {
                if (e.unauthorized) return RunResult.Unauthorized
                return RunResult.Offline(sent, e.message ?: "offline")
            }
        }
        prune()
        return RunResult.Done(sent, refused)
    }

    private suspend fun send(backend: ShedBackend, queued: QueuedFile): UploadState {
        val path = queued.path.toPath()
        if (!fileSystem.exists(path)) return finish(queued, UploadState.REFUSED, "The file is gone from the device")
        var restarts = 0
        while (true) {
            when (val start = backend.startUpload(queued.sha256, queued.size, queued.about)) {
                UploadStart.Exists -> return finish(queued, UploadState.DONE)
                is UploadStart.Refused -> return finish(queued, UploadState.REFUSED, start.reason)
                is UploadStart.Ready -> {
                    var offset = start.offset
                    progress(queued, offset)
                    fileSystem.openReadOnly(path).use { handle ->
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

    /** Drops files done more than [KEEP_DONE] ago. */
    private suspend fun prune() = mutex.withLock {
        val cutoff = now() - KEEP_DONE
        val kept = files.filterNot { it.state == UploadState.DONE && (it.doneAt ?: 0) < cutoff }
        if (kept.size != files.size) update(kept)
    }

    private fun update(new: List<QueuedFile>) {
        files = new
        file.save(QueueFile(files = new))
        _status.value = new.map(::statusOf)
    }

    private fun statusOf(q: QueuedFile) = UploadStatus(q.sha256, q.about.fileName, q.size, q.sent, q.state, q.refused)

    companion object {
        /** How long files done stay listed: a week. */
        const val KEEP_DONE = 7L * 24 * 60 * 60 * 1000
    }
}
