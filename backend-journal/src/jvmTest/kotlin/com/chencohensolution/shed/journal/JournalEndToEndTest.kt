package com.chencohensolution.shed.journal

import com.chencohensolution.shed.FileAbout
import com.chencohensolution.shed.FileKind
import com.chencohensolution.shed.RunResult
import com.chencohensolution.shed.Shed
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.net.URLEncoder
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Audio Logs against a real journal server: pairing by the enrollment link, uploading a recording through the queue,
 * reading ranges of it back, finding it in the change feed and verifying it. Runs only when scripts/e2e.sh started a
 * server and made a code (SHED_E2E_LINK, SHED_E2E_ORIGIN); skipped otherwise.
 */
class JournalEndToEndTest {
    @Test
    fun audioLogsPairsUploadsAndReadsBack() = runBlocking {
        val link = System.getenv("SHED_E2E_LINK") ?: return@runBlocking println("SHED_E2E_LINK not set: skipped")
        val origin = System.getenv("SHED_E2E_ORIGIN")!!
        // The server names its address on the network; the test reaches it on this machine.
        val text = link.replace(Regex("server=[^&]+"), "server=" + URLEncoder.encode(origin, "UTF-8"))

        val folder = Files.createTempDirectory("shed-e2e").toOkioPath()
        val fs = FileSystem.SYSTEM
        val keys = MemoryDeviceKeys()
        val backend = JournalBackend("audio-logs", fs, folder / "journal", keys, OkHttpJournalClients(keys))

        val preview = backend.preview(text)
        assertEquals(origin, preview.server)
        val paired = backend.pair(text, "Shed e2e")
        assertEquals(preview.user, paired.user)
        assertIs<JournalState.Paired>(backend.state.value)

        val shed = Shed(backend, fs, folder / "shed", keepListed = { it.about.kind == FileKind.AUDIO })
        val bytes = Random.nextBytes(3 * 1024 * 1024 + 17)
        val file = folder / "moment.m4a"
        fs.write(file) { write(bytes) }
        val sha = shed.put(file, FileAbout("moment.m4a", "audio/mp4", FileKind.AUDIO, takenAt = "2026-11-16T09:00:00Z", source = "recording"))
        val run = shed.runUploads()
        assertIs<RunResult.Done>(run, "upload: $run")
        assertEquals(1, run.sent)

        assertTrue(shed.verify(sha), "the server holds it")
        val reader = shed.open(sha)
        assertEquals(bytes.size.toLong(), reader.size)
        assertContentEquals(bytes.copyOfRange(1_048_000, 1_049_000), reader.read(1_048_000, 1000))
        assertContentEquals(bytes, fs.read(shed.fetch(sha)) { readByteArray() })

        // Audio Logs sees sound recordings, which the server hides from journal by default.
        val listed = shed.sync()
        assertTrue(listed.any { it.sha256 == sha }, "listed: $listed")

        // Putting it again sends nothing.
        shed.put(file, FileAbout("moment.m4a", "audio/mp4", FileKind.AUDIO))
        assertEquals(RunResult.Done(0, 0), shed.runUploads())

        backend.unpair()
        assertEquals(JournalState.NotPaired, backend.state.value)
    }
}
