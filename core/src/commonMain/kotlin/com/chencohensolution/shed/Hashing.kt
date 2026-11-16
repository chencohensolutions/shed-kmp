package com.chencohensolution.shed

import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.blackholeSink
import okio.buffer
import okio.use

/** The sha256 of the file at [path], in lower-case hex, as the storage names files. Reads the whole file. */
fun sha256Of(fileSystem: FileSystem, path: Path): String {
    val sink = HashingSink.sha256(blackholeSink())
    sink.buffer().use { out -> fileSystem.source(path).use { out.writeAll(it) } }
    return sink.hash.hex()
}
