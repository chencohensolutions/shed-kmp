package com.chencohensolution.shed.journal

import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/** `pairing.json`: written to a temp file and renamed; null when missing or unreadable (the app pairs again). */
internal class PairingFile(private val fileSystem: FileSystem, val path: Path) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun load(): Pairing? = runCatching { json.decodeFromString(Pairing.serializer(), fileSystem.read(path) { readUtf8() }) }.getOrNull()

    fun save(pairing: Pairing) {
        path.parent?.let(fileSystem::createDirectories)
        val temp = path.parent!! / "${path.name}.tmp"
        fileSystem.write(temp) { writeUtf8(json.encodeToString(Pairing.serializer(), pairing)) }
        fileSystem.atomicMove(temp, path)
    }
}
