package com.chencohensolution.shed

import okio.ByteString.Companion.toByteString

/** A storage in memory, with switches for what can go wrong. */
class FakeBackend(var pieceSize: Long = 4) : ShedBackend {
    override val capabilities = Capabilities(changeList = true)
    val files = mutableMapOf<String, ByteArray>()
    val abouts = mutableMapOf<String, FileAbout>()
    private val partial = mutableMapOf<String, ByteArray>()
    var offline = false
    var unauthorized = false
    /** After this many more pieces, the connection drops (once). */
    var dropAfterPieces: Int? = null
    val reads = mutableListOf<LongRange>()
    var refuse: String? = null
    private val log = mutableListOf<Pair<String, Boolean>>() // sha, removed

    private fun check() {
        if (offline) throw BackendException("offline", offline = true)
        if (unauthorized) throw BackendException("refused", unauthorized = true)
    }

    override suspend fun startUpload(sha256: String, size: Long, about: FileAbout): UploadStart {
        check()
        refuse?.let { return UploadStart.Refused(it) }
        if (sha256 in files) return UploadStart.Exists
        abouts[sha256] = about
        return UploadStart.Ready(partial[sha256]?.size?.toLong() ?: 0, pieceSize)
    }

    override suspend fun sendPiece(sha256: String, offset: Long, bytes: ByteArray, total: Long): PieceAnswer {
        check()
        dropAfterPieces?.let {
            if (it == 0) {
                dropAfterPieces = null
                throw BackendException("dropped", offline = true)
            }
            dropAfterPieces = it - 1
        }
        val have = partial[sha256] ?: ByteArray(0)
        if (offset != have.size.toLong()) return PieceAnswer.WrongOffset(have.size.toLong())
        val now = have + bytes
        if (now.size.toLong() < total) {
            partial[sha256] = now
            return PieceAnswer.Accepted(now.size.toLong(), complete = false)
        }
        partial.remove(sha256)
        if (now.toByteString().sha256().hex() != sha256) return PieceAnswer.Mismatch
        files[sha256] = now
        log += sha256 to false
        return PieceAnswer.Accepted(now.size.toLong(), complete = true)
    }

    override suspend fun size(sha256: String): Long? {
        check()
        return files[sha256]?.size?.toLong()
    }

    override suspend fun read(sha256: String, from: Long, length: Int): ByteArray {
        check()
        val data = files[sha256] ?: throw BackendException("missing")
        val end = minOf(data.size.toLong(), from + length)
        reads += from until end
        return data.copyOfRange(from.toInt(), end.toInt())
    }

    fun add(data: ByteArray, about: FileAbout): String {
        val sha = data.toByteString().sha256().hex()
        files[sha] = data
        abouts[sha] = about
        log += sha to false
        return sha
    }

    fun remove(sha: String) {
        files.remove(sha)
        log += sha to true
    }

    override suspend fun changes(cursor: String?): ChangePage {
        check()
        val from = cursor?.toInt() ?: 0
        val page = log.drop(from).take(2)
        return ChangePage(
            added = page.filter { !it.second && it.first in files }.map { RemoteFile(it.first, files[it.first]!!.size.toLong(), abouts[it.first]!!) },
            removed = page.filter { it.second }.map { it.first },
            cursor = (from + page.size).toString(),
            hasMore = from + page.size < log.size,
        )
    }

    override suspend fun holds(sha256: String): Boolean {
        check()
        return sha256 in files
    }
}
