package com.chencohensolution.shed

import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheTest {
    private val fs = FakeFileSystem()
    private val about = FileAbout("audio.aac", "audio/aac", FileKind.AUDIO)
    private val block = Cache.BLOCK

    @Test
    fun aRangeReadFetchesOnlyTheBlocksItTouchesOnce() = runTest {
        val backend = FakeBackend()
        val data = Random(1).nextBytes(block * 3 + 100)
        val sha = backend.add(data, about)
        val shed = Shed(backend, fs, "/shed".toPath())
        val reader = shed.open(sha)
        assertEquals(data.size.toLong(), reader.size)

        // From the middle of the second block into the third.
        val position = block + block / 2L
        assertContentEquals(data.copyOfRange(position.toInt(), position.toInt() + block), reader.read(position, block))
        assertEquals(listOf(block.toLong() until 2L * block, 2L * block until 3L * block), backend.reads)

        reader.read(position, 10)
        assertEquals(2, backend.reads.size, "read again from the cache")
        assertContentEquals(data.copyOfRange(data.size - 100, data.size), reader.read(data.size - 100L, 500))
        assertEquals(0, reader.read(data.size.toLong(), 10).size)
    }

    @Test
    fun fetchMakesTheWholeFileFromBlocksAlreadyKept() = runTest {
        val backend = FakeBackend()
        val data = Random(2).nextBytes(block * 2 + 7)
        val sha = backend.add(data, about)
        val shed = Shed(backend, fs, "/shed".toPath())
        shed.open(sha).read(0, 10)
        val seen = mutableListOf<Long>()
        val path = shed.fetch(sha) { got, _ -> seen += got }
        assertContentEquals(data, fs.read(path) { readByteArray() })
        assertEquals(listOf(block.toLong(), 2L * block, 2L * block + 7), seen)
        assertEquals(listOf(0L until block, block.toLong() until 2L * block, 2L * block until 2L * block + 7), backend.reads, "the first block once")
        assertTrue(shed.isCached(sha))
        assertEquals(emptyList(), shed.downloads.value)
    }

    @Test
    fun theOldestUnpinnedFilesGoFirstOverTheLimit() = runTest {
        var clock = 0L
        val backend = FakeBackend()
        val one = backend.add(Random(3).nextBytes(block), about)
        val two = backend.add(Random(4).nextBytes(block), about)
        val three = backend.add(Random(5).nextBytes(block), about)
        val shed = Shed(backend, fs, "/shed".toPath(), cacheLimitBytes = 2L * block, now = { clock++ })
        shed.fetch(one)
        shed.pin(one)
        shed.fetch(two)
        shed.fetch(three)
        assertTrue(shed.isCached(one), "pinned")
        assertFalse(shed.isCached(two), "the oldest unpinned")
        assertTrue(shed.isCached(three))
        assertEquals(2L * block, shed.cacheBytes())
    }

    @Test
    fun theRemoteListFollowsTheChangeList() = runTest {
        val backend = FakeBackend()
        val audio = backend.add("a recording".encodeToByteArray(), about)
        backend.add("a photo".encodeToByteArray(), FileAbout("a.jpg", "image/jpeg", FileKind.PHOTO))
        val shed = Shed(backend, fs, "/shed".toPath(), keepListed = { it.about.kind == FileKind.AUDIO })
        assertEquals(listOf(audio), shed.sync().map { it.sha256 })
        val later = backend.add("another".encodeToByteArray(), about)
        backend.remove(audio)
        assertEquals(listOf(later), shed.sync().map { it.sha256 })
        assertEquals(listOf(later), Shed(backend, fs, "/shed".toPath()).remote.value.map { it.sha256 }, "kept on disk")
    }
}
