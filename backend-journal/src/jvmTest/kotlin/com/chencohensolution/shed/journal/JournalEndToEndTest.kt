package com.chencohensolution.shed.journal

import com.chencohensolution.shed.AppDataWrite
import com.chencohensolution.shed.BackendException
import com.chencohensolution.shed.FileAbout
import com.chencohensolution.shed.FileKind
import com.chencohensolution.shed.RunResult
import com.chencohensolution.shed.Shed
import com.chencohensolution.shed.journal.discovery.ServerFinder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.net.URLEncoder
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

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

        // One enrollment per phone: journal pairs with a link Audio Logs asked the server for.
        val journalKeys = MemoryDeviceKeys()
        val journal = JournalBackend("journal", fs, folder / "journal-app", journalKeys, OkHttpJournalClients(journalKeys))
        val sibling = backend.siblingLink("journal")
        assertTrue(sibling.startsWith("journal://enroll?"), sibling)
        val joined = journal.pair(sibling, "ignored: the phone keeps its name")
        assertEquals(paired.user, joined.user)
        assertEquals("Shed e2e", joined.deviceName)
        // The code was for journal, and is used up.
        val other = JournalBackend("notes", fs, folder / "notes-app", MemoryDeviceKeys(), OkHttpJournalClients(MemoryDeviceKeys()))
        assertFailsWith<PairingException.Rejected> { other.pair(sibling, "x") }

        // Calls and voice messages are off until the user turns them on in Audio Logs.
        assertEquals(SoundRecordingsShown(calls = false, voiceMessages = false), backend.soundRecordingsShown())
        assertEquals(SoundRecordingsShown(calls = true, voiceMessages = false), backend.setSoundRecordingsShown(SoundRecordingsShown(true, false)))
        assertEquals(null, journal.soundRecordingsShown(), "journal follows the hidden collections")

        // Tucked away, a note kept with it, then marked private: Audio Logs never gets it again.
        backend.setTucked(listOf(sha), true)
        shed.sync()
        assertTrue(shed.remote.value.single { it.sha256 == sha }.tucked, "tucked: ${shed.remote.value}")
        assertTrue(backend.setNote(sha, buildJsonObject { put("audioLogs", buildJsonObject { put("title", "Class") }) }))
        shed.sync()
        assertTrue(shed.remote.value.single { it.sha256 == sha }.note!!.contains("Class"), "the note comes with the file")
        backend.setPrivate(listOf(sha), true)
        shed.sync()
        assertTrue(shed.remote.value.none { it.sha256 == sha }, "a private recording left Audio Logs")
        // Only an app that sees private files can bring it back (journal, on a phone not set to withhold them).
        assertFailsWith<BackendException> { backend.setPrivate(listOf(sha), false) }
        journal.setPrivate(listOf(sha), false)
        assertTrue(shed.sync().any { it.sha256 == sha })

        // Recordings by pages, newest first, without the whole change feed.
        val page = shed.page(FileKind.AUDIO, limit = 10)
        assertTrue(page.files.any { it.sha256 == sha && it.note!!.contains("Class") }, "page: $page")
        assertEquals(null, page.newer)
        assertTrue(backend.setNote(sha, buildJsonObject { put("title", "Class 2") }), "a newer note is sent on the version it replaces")
        // Each app's own data: Audio Logs' labels never show in journal.
        val saved = shed.setAppData("labels", "{\"labels\":[\"Psychology\"]}", null)
        assertTrue(saved is AppDataWrite.Saved, "$saved")
        assertEquals(null, journal.appData("labels").content)
        assertTrue(shed.setAppData("labels", "{}", "stale") is AppDataWrite.Conflict)
        assertTrue(shed.appData("labels").content!!.contains("Psychology"))

        // The server's address changes: it is found at its other address, then by its DHT record.
        val pairingPath = folder / "journal" / "pairing.json"
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val kept = json.decodeFromString(Pairing.serializer(), fs.read(pairingPath) { readUtf8() })
        val gone = "https://127.0.0.1:9"
        fun moved(pairing: Pairing, dht: suspend (String, String) -> List<String>?): JournalBackend {
            fs.write(pairingPath) { writeUtf8(json.encodeToString(Pairing.serializer(), pairing)) }
            return JournalBackend("audio-logs", fs, folder / "journal", keys, OkHttpJournalClients(keys), ServerFinder(dht, stagger = Duration.ZERO))
        }
        val atOther = moved(kept.copy(server = gone, addresses = listOf(origin))) { _, _ -> null }
        assertEquals(sha, atOther.changes(null).added.single { it.sha256 == sha }.sha256)
        assertEquals(origin, (atOther.state.value as JournalState.Paired).server)

        val dhtKey = "ab".repeat(32)
        var askedDht: Pair<String, String>? = null
        val inDht = moved(kept.copy(server = gone, addresses = emptyList(), key = dhtKey)) { key, fingerprint ->
            askedDht = key to fingerprint
            listOf(origin)
        }
        assertTrue(inDht.holds(sha))
        assertEquals(dhtKey to kept.fingerprint, askedDht)
        val found = json.decodeFromString(Pairing.serializer(), fs.read(pairingPath) { readUtf8() })
        assertEquals(origin, found.server)
        assertTrue(gone in found.addresses, "the old address is kept to try: ${found.addresses}")

        backend.unpair()
        assertEquals(JournalState.NotPaired, backend.state.value)
    }
}
