package com.chencohensolution.shed

import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UploadQueueTest {
    private val fs = FakeFileSystem()
    private val dir = "/shed".toPath()
    private val about = FileAbout("audio.aac", "audio/aac", FileKind.AUDIO, takenAt = "2026-11-02T10:00:00+02:00", source = "recording")

    private fun file(name: String, text: String) = "/moments/$name".toPath().also {
        fs.createDirectories(it.parent!!)
        fs.write(it) { writeUtf8(text) }
    }

    @Test
    fun sendsInPiecesAndResumesAfterADroppedConnectionAndARestart() = runTest {
        val backend = FakeBackend(pieceSize = 4)
        val path = file("a/audio.aac", "the sound of a moment")
        val sha = Shed(backend, fs, dir).put(path, about)
        assertEquals("the sound of a moment".encodeToByteArray().toByteString().sha256().hex(), sha)

        backend.dropAfterPieces = 2
        val first = Shed(backend, fs, dir)
        assertIs<RunResult.Offline>(first.runUploads())
        assertEquals(8, first.uploads.value.single().sent)

        // A new Shed reads the queue from disk, as after the app was killed.
        val second = Shed(backend, fs, dir)
        assertEquals(1, second.waiting())
        assertEquals(RunResult.Done(sent = 1, refused = 0), second.runUploads())
        assertContentEquals("the sound of a moment".encodeToByteArray(), backend.files[sha])
        assertEquals(UploadState.DONE, second.uploads.value.single().state)
        assertEquals(0, second.waiting())
        assertEquals(true, second.verify(sha))
    }

    @Test
    fun aFileTheStorageKeepsLosingWaitsForTheNextRunAndTheRestIsSent() = runTest {
        val backend = FakeBackend(pieceSize = 4)
        val shed = Shed(backend, fs, dir)
        backend.losing = shed.put(file("l/audio.aac", "lost every time"), about)
        val kept = shed.put(file("m/audio.aac", "sent"), about)
        assertEquals(RunResult.Done(sent = 1, refused = 0), shed.runUploads())
        assertEquals(listOf(UploadState.WAITING, UploadState.DONE), shed.uploads.value.map { it.state })
        assertContentEquals("sent".encodeToByteArray(), backend.files[kept])
    }

    @Test
    fun aFileTheDeviceCantReadDoesNotStopTheRun() = runTest {
        val backend = FakeBackend(pieceSize = 4)
        val unreadable = object : okio.ForwardingFileSystem(fs) {
            override fun openReadOnly(file: okio.Path): okio.FileHandle =
                if (file.name == "x.aac") throw UnsupportedOperationException("read as streams") else super.openReadOnly(file)
        }
        val shed = Shed(backend, fs, dir)
        shed.put(file("x/x.aac", "unreadable"), about, ref = "x", from = fs)
        val ok = shed.put(file("y/y.aac", "readable"), about)
        val reading = Shed(backend, fs, dir, locator = { ref -> if (ref == "x") Located(unreadable, "/moments/x/x.aac".toPath()) else null })
        assertEquals(RunResult.Done(sent = 1, refused = 0), reading.runUploads())
        assertContentEquals("readable".encodeToByteArray(), backend.files[ok])
    }

    @Test
    fun aFileTheStorageHasIsNotSentAgain() = runTest {
        val backend = FakeBackend()
        val shed = Shed(backend, fs, dir)
        backend.add("same bytes".encodeToByteArray(), about)
        shed.put(file("b/audio.aac", "same bytes"), about)
        assertEquals(RunResult.Done(sent = 1, refused = 0), shed.runUploads())
        assertEquals(UploadState.DONE, shed.uploads.value.single().state)
        assertEquals(emptyList(), backend.reads)
    }

    @Test
    fun refusalsAndMissingFilesAreReportedAndTheRestGoesOn() = runTest {
        val backend = FakeBackend()
        val shed = Shed(backend, fs, dir)
        val gone = file("c/audio.aac", "will be deleted")
        shed.put(gone, about)
        shed.put(file("d/audio.aac", "stays"), about)
        fs.delete(gone)
        assertEquals(RunResult.Done(sent = 1, refused = 1), shed.runUploads())
        assertEquals(listOf(UploadState.REFUSED, UploadState.DONE), shed.uploads.value.map { it.state })

        backend.refuse = "NO_SPACE"
        shed.put(file("e/audio.aac", "too much"), about)
        shed.runUploads()
        assertEquals("NO_SPACE", shed.uploads.value.last().refused)
    }

    @Test
    fun aRefusedDeviceStopsTheRunWithNothingLost() = runTest {
        val backend = FakeBackend()
        val shed = Shed(backend, fs, dir)
        shed.put(file("f/audio.aac", "later"), about)
        backend.unauthorized = true
        assertEquals(RunResult.Unauthorized, shed.runUploads())
        assertEquals(1, shed.waiting())
    }

    @Test
    fun aFileHandedOverByRefIsFoundWhereItMovedTo() = runTest {
        val backend = FakeBackend()
        val library = FakeFileSystem()
        var where = Located(fs, file("m1/audio.aac", "a moment that moves"))
        val shed = Shed(backend, fs, dir, locator = { ref -> if (ref == "m1") where else null })
        val sha = shed.put(where.path, about, ref = "m1")

        // The app moves the finished moment to its library before the upload runs.
        val moved = "/library/moments/m1/audio.aac".toPath()
        library.createDirectories(moved.parent!!)
        library.write(moved) { writeUtf8("a moment that moves") }
        fs.delete(where.path)
        where = Located(library, moved)

        assertEquals(RunResult.Done(sent = 1, refused = 0), shed.runUploads())
        assertContentEquals("a moment that moves".encodeToByteArray(), backend.files[sha])
        assertEquals("m1", shed.uploads.value.single().ref)
    }
}
