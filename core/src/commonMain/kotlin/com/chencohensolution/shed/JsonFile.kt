package com.chencohensolution.shed

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

internal val shedJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

/**
 * One of shed's own files, as JSON: written to a temp file and renamed, so a crash never leaves half a file. A file
 * that can't be read is kept beside as `<name>.damaged` and [default] is used, so a damaged file never stops the rest.
 */
internal class JsonFile<T>(
    private val fileSystem: FileSystem,
    private val path: Path,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    fun load(): T {
        if (!fileSystem.exists(path)) return default()
        return try {
            shedJson.decodeFromString(serializer, fileSystem.read(path) { readUtf8() })
        } catch (e: Exception) {
            runCatching { fileSystem.atomicMove(path, path.parent!! / "${path.name}.damaged") }
            default()
        }
    }

    fun save(value: T) {
        path.parent?.let(fileSystem::createDirectories)
        val temp = path.parent!! / "${path.name}.tmp"
        fileSystem.write(temp) { writeUtf8(shedJson.encodeToString(serializer, value)) }
        fileSystem.atomicMove(temp, path)
    }
}
