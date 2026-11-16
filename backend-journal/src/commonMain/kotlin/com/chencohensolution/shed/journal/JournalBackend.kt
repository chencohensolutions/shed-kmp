package com.chencohensolution.shed.journal

import com.chencohensolution.shed.BackendException
import com.chencohensolution.shed.Capabilities
import com.chencohensolution.shed.ChangePage
import com.chencohensolution.shed.FileAbout
import com.chencohensolution.shed.FileKind
import com.chencohensolution.shed.PieceAnswer
import com.chencohensolution.shed.RemoteFile
import com.chencohensolution.shed.ShedBackend
import com.chencohensolution.shed.UploadStart
import com.chencohensolution.shed.journal.discovery.ServerFinder
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path
import kotlin.io.encoding.Base64

/** Whether an app is paired with a journal server. */
sealed interface JournalState {
    data object NotPaired : JournalState

    data class Paired(val server: String, val user: String, val deviceName: String) : JournalState
}

/** What an app made for sound recordings shows beside recordings ([JournalBackend.soundRecordingsShown]). */
data class SoundRecordingsShown(val calls: Boolean, val voiceMessages: Boolean)

/** Who an enrollment code is for, before it is used ([JournalBackend.preview]). */
data class PairingPreview(val user: String, val expiresAt: String, val server: String)

/** Why pairing failed. */
sealed class PairingException(message: String) : Exception(message) {
    /** The text has no enrollment link with the server's fingerprint in it. */
    class NotALink : PairingException("Not an enrollment link from the server")

    /** Wrong, expired or used code (`INVALID_CODE`), too many tries (`RATE_LIMITED`), or another reason the server gave. */
    class Rejected(val reason: String) : PairingException("The server refused: $reason")

    /** None of the link's addresses answered. */
    class Unreachable(val detail: String) : PairingException("The server didn't answer: $detail")
}

/**
 * The journal server (journal-server-go) as shed's storage. It pairs the app as a device of its own ([pair], with the
 * app's name, so Audio Logs is told apart from journal and sees sound recordings), then sends and reads files over
 * mTLS with the device's key, trusting the server by its key's fingerprint.
 *
 * When the server stops answering where it was (its address changed), it is found again as the journal app finds it:
 * at its other addresses, then in the BitTorrent DHT under its key ("Finding the server" in README.md). No DNS name
 * and no relay are involved.
 *
 * What it keeps is `pairing.json` in [directory]; the key and certificate are in the platform's key store ([keys]).
 */
class JournalBackend internal constructor(
    /** The app's name as the server records it: `audio-logs`, `journal`. */
    private val app: String,
    private val fileSystem: FileSystem,
    directory: Path,
    private val keys: DeviceKeys,
    private val clients: JournalClients,
    private val finder: ServerFinder,
) : ShedBackend {
    constructor(app: String, fileSystem: FileSystem, directory: Path, keys: DeviceKeys, clients: JournalClients) :
        this(app, fileSystem, directory, keys, clients, ServerFinder())

    private val pairingFile = PairingFile(fileSystem, directory / "pairing.json")
    private val mutex = Mutex()
    private var pairing: Pairing? = pairingFile.load()?.takeIf { keys.has(it.alias) }
    private var client: Pair<Pairing, HttpClient>? = null
    /** Whether this run asked the server where else it can be reached (`serverInfo.discovery`) yet. */
    private var discoveryAsked = false
    private val _state = MutableStateFlow(stateOf(pairing))
    val state: StateFlow<JournalState> = _state.asStateFlow()

    override val capabilities = Capabilities(previews = true, changeList = true, appFiles = false)

    // Pairing

    /** Who the enrollment link in [text] is for, without using its code. */
    suspend fun preview(text: String): PairingPreview {
        val link = findEnrollmentLink(text) ?: throw PairingException.NotALink()
        val (origin, data) = anonymous(link) { http, origin ->
            http.graphql(origin, "query(\$c: String!) { enrollmentPreview(code: \$c) { __typename ... on EnrollmentPreview { user expiresAt } ... on EnrollmentRejected { reason } } }",
                mapOf("c" to JsonPrimitive(link.code)))
        }
        val result = data.obj("enrollmentPreview")
        result.str("reason")?.let { throw PairingException.Rejected(it) }
        return PairingPreview(result.str("user")!!, result.str("expiresAt")!!, origin)
    }

    /**
     * Pairs this app with the server the enrollment link in [text] names: a new key in the device's key store, a
     * certificate for it from the server (using up the link's code), then the server's own CA, asked over mTLS.
     */
    suspend fun pair(text: String, deviceName: String): JournalState.Paired {
        mutex.withLock { pairLocked(text, deviceName) }
        // Where else the server can be reached and its DHT key, to find it when its address changes.
        askDiscovery()
        return stateOf(pairing) as JournalState.Paired
    }

    private suspend fun pairLocked(text: String, deviceName: String) {
        val link = findEnrollmentLink(text) ?: throw PairingException.NotALink()
        val alias = "shed-$app"
        val key = keys.generate(alias)
        val csr = CertificateRequest.build(deviceName, key.publicKeyInfo, key::sign)
        val input = buildMap {
            put("code", JsonPrimitive(link.code))
            put("deviceName", JsonPrimitive(deviceName))
            put("csr", JsonPrimitive(Base64.encode(csr)))
            put("keyProtection", JsonPrimitive(key.protection.name))
            put("app", JsonPrimitive(app))
        }
        val (origin, data) = anonymous(link) { http, origin ->
            try {
                http.graphql(origin, ENROLL, mapOf("i" to JsonObject(input)))
            } catch (e: GraphQLException) {
                // A server from before Nov 16, 2026 doesn't know the app: enroll without it (it then hides recordings).
                if (e.messages.none { "app" in it }) throw e
                http.graphql(origin, ENROLL, mapOf("i" to JsonObject(input - "app")))
            }
        }
        val result = data.obj("enrollDevice")
        result.str("reason")?.let {
            keys.delete(alias)
            throw PairingException.Rejected(it)
        }
        keys.saveCertificate(alias, Base64.decode(result.str("certificate")!!))
        var paired = Pairing(
            server = origin, addresses = link.addresses, key = link.key, fingerprint = link.fingerprint, alias = alias,
            user = result.str("user")!!, deviceName = result.str("deviceName")!!, expiresAt = result.str("expiresAt")!!,
        )
        save(paired)
        // The server's own CA, so it is trusted by it too after its key changes (a new server certificate).
        runCatching { http().graphql(origin, "{ serverInfo { caCertificate } }").obj("serverInfo").str("caCertificate") }
            .getOrNull()?.let { ca -> paired = paired.copy(serverCa = ca).also(::save) }
    }

    /**
     * An enrollment link for another app on this phone ([app], e.g. `journal`), so the user enrolls the phone once: the
     * server makes a code that only that app can use, for 2 minutes, and the other app pairs with the link as with
     * one from `enroll-code` ([pair]), joining this phone on the server. Ask the user first: the link lets that app
     * read their journal. Android hands it over with shed's `ShedSiblings`.
     */
    suspend fun siblingLink(app: String): String {
        val p = paired()
        val result = query(
            "mutation(\$a: String!) { siblingEnrollmentCode(app: \$a) { __typename ... on SiblingCode { code } ... on EnrollmentRejected { reason } } }",
            mapOf("a" to JsonPrimitive(app)),
        ).obj("siblingEnrollmentCode")
        result.str("reason")?.let { throw PairingException.Rejected(it) }
        val code = result.str("code")!!
        val addresses = (listOf(p.server) + p.addresses).distinct()
        return "journal://enroll?code=" + code.encodeURLParameter() + "&fingerprint=" + p.fingerprint +
            (p.key?.let { "&key=$it" } ?: "") + addresses.joinToString("") { (if (it == addresses.first()) "&server=" else "&address=") + it.encodeURLParameter() }
    }

    /** Forgets the server and deletes the device's key. The server still lists the device until it is revoked there. */
    suspend fun unpair() = mutex.withLock {
        pairing?.let { keys.delete(it.alias) }
        fileSystem.delete(pairingFile.path)
        client?.second?.close()
        client = null
        pairing = null
        discoveryAsked = false
        _state.value = JournalState.NotPaired
    }

    private fun save(new: Pairing) {
        pairingFile.save(new)
        pairing = new
        _state.value = stateOf(new)
    }

    /**
     * Runs [block] without a certificate at the link's addresses in turn, trusting the server by the link's
     * fingerprint; when none answers and the link has the server's DHT key, at the addresses its DHT record names.
     */
    private suspend fun <T> anonymous(link: EnrollmentLink, block: suspend (HttpClient, String) -> T): Pair<String, T> {
        var last: Exception? = null
        suspend fun at(origins: List<String>): Pair<String, T>? {
            for (origin in origins) {
                val http = clients.create(ServerTrust(link.fingerprint), alias = null)
                try {
                    return origin to block(http, origin)
                } catch (e: BackendException) {
                    if (!e.offline) throw PairingException.Unreachable(e.message ?: "")
                    last = e
                } finally {
                    http.close()
                }
            }
            return null
        }
        at(link.addresses)?.let { return it }
        link.key?.let { key -> finder.lookUp(key, link.fingerprint) }?.filter { it !in link.addresses }
            ?.let { at(it) }?.let { return it }
        throw PairingException.Unreachable(last?.message ?: "no address")
    }

    // Storage

    private fun paired(): Pairing = pairing ?: throw BackendException("Not paired with a journal server", unauthorized = true)

    private suspend fun http(): HttpClient {
        val p = paired()
        client?.takeIf { it.first == p }?.let { return it.second }
        client?.second?.close()
        return clients.create(ServerTrust(p.fingerprint, p.serverCa), p.alias).also { client = p to it }
    }

    private suspend fun query(query: String, variables: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()): JsonObject =
        try {
            atServer { http, origin -> http.graphql(origin, query, variables) }
        } catch (e: GraphQLException) {
            throw BackendException("The server answered: ${e.message}", cause = e)
        }

    /** Sends an HTTP request, turning failures into [BackendException]s. */
    private suspend fun request(send: suspend HttpClient.(String) -> HttpResponse): HttpResponse {
        val response = atServer { http, origin ->
            try {
                http.send(origin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendException) {
                throw e
            } catch (e: Exception) {
                throw BackendException("The server didn't answer: ${e.message ?: e::class.simpleName}", offline = true, cause = e)
            }
        }
        if (response.status.value == 401) throw BackendException("The server refused this device", unauthorized = true)
        return response
    }

    /**
     * Runs [send] at the server; when the server can't be reached there, finds it again ([ServerFinder]: its other
     * addresses, then the DHT) and runs [send] once more where it answers now, which becomes [Pairing.server].
     */
    private suspend fun <T> atServer(send: suspend (HttpClient, String) -> T): T {
        val origin = paired().server
        val result = try {
            send(http(), origin)
        } catch (e: BackendException) {
            if (!e.unreached) throw e
            val moved = findServer(origin) ?: throw e
            send(http(), moved)
        }
        if (!discoveryAsked) askDiscovery()
        return result
    }

    /** Where the server answers now, after a request to [failed] couldn't reach it; null when it wasn't found. */
    private suspend fun findServer(failed: String): String? {
        val p = paired()
        val found = finder.find(
            failed = failed,
            current = { pairing?.server ?: failed },
            addresses = { pairing?.let { listOf(it.server) + it.addresses }.orEmpty().distinct() },
            key = p.key,
            fingerprint = p.fingerprint,
        ) { candidate -> answersAt(candidate) } ?: return null
        mutex.withLock {
            val now = pairing ?: return null
            if (now.server != found.origin) {
                save(now.copy(server = found.origin, addresses = keptAddresses(found.fromDht + now.server + now.addresses, found.origin)))
            }
        }
        discoveryAsked = false
        return found.origin
    }

    /** Whether the server answers at [origin] at all: any HTTP answer means the TLS handshake, and the pin, passed. */
    private suspend fun answersAt(origin: String): Boolean = withTimeout(ServerFinder.PROBE_TIMEOUT) {
        http().get("$origin/query")
        true
    }

    /**
     * Asks the server where else it can be reached and its DHT key (`serverInfo.discovery`), and keeps them, so it can
     * be found when its address changes. Once a run, and again after it was found elsewhere; a server without
     * discovery, or one that doesn't answer now, is asked again next time.
     */
    private suspend fun askDiscovery() {
        val p = pairing ?: return
        discoveryAsked = true
        val discovery = try {
            http().graphql(p.server, "{ serverInfo { discovery { key addresses } } }").obj("serverInfo")["discovery"] as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            discoveryAsked = false
            return
        } ?: return
        val addresses = (discovery["addresses"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull()?.let(::originOf) }.orEmpty()
        val key = discovery.str("key")?.lowercase()?.takeIf(::isKey)
        mutex.withLock {
            val now = pairing?.takeIf { it.alias == p.alias } ?: return
            val updated = now.copy(addresses = keptAddresses(addresses + now.addresses, now.server), key = key ?: now.key)
            if (updated != now) save(updated)
        }
    }

    override suspend fun startUpload(sha256: String, size: Long, about: FileAbout): UploadStart {
        val input = buildJsonObject {
            put("sha256", sha256)
            put("size", size)
            put("fileName", about.fileName)
            put("mimeType", about.mimeType)
            put("kind", about.kind.name)
            about.takenAt?.let { put("takenAt", it) }
            about.durationMillis?.let { put("durationMs", it) }
            about.source?.let { put("source", it) }
            about.folder?.let { put("folder", it) }
        }
        val result = query(
            "mutation(\$i: StartUploadInput!) { startUpload(input: \$i) { __typename ... on UploadReady { url offset chunkSize } ... on UploadRejected { reason } } }",
            mapOf("i" to input),
        ).obj("startUpload")
        return when (result.str("__typename")) {
            "UploadReady" -> UploadStart.Ready(result.long("offset") ?: 0, result.long("chunkSize") ?: DEFAULT_PIECE)
            "MediaExists" -> UploadStart.Exists
            else -> UploadStart.Refused(result.str("reason") ?: "UNKNOWN")
        }
    }

    override suspend fun sendPiece(sha256: String, offset: Long, bytes: ByteArray, total: Long): PieceAnswer {
        val response = request { origin ->
            put("$origin/upload/$sha256") {
                header(HttpHeaders.ContentRange, "bytes $offset-${offset + bytes.size - 1}/$total")
                setBody(bytes)
            }
        }
        val at = response.headers["Upload-Offset"]?.toLongOrNull()
        return when (response.status.value) {
            200 -> PieceAnswer.Accepted(at ?: (offset + bytes.size), complete = false)
            201 -> PieceAnswer.Accepted(at ?: total, complete = true)
            400, 416 -> at?.let { PieceAnswer.WrongOffset(it) } ?: throw BackendException("The server refused the piece: ${response.bodyAsText()}")
            404 -> PieceAnswer.Gone
            409 -> PieceAnswer.Mismatch
            else -> throw BackendException("The server answered ${response.status.value}", offline = response.status.value >= 500)
        }
    }

    override suspend fun size(sha256: String): Long? {
        val response = request { origin -> head("$origin/media/$sha256") }
        return when (response.status.value) {
            200 -> response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            404 -> null
            else -> throw BackendException("The server answered ${response.status.value}", offline = response.status.value >= 500)
        }
    }

    override suspend fun read(sha256: String, from: Long, length: Int): ByteArray {
        val response = request { origin ->
            get("$origin/media/$sha256") { header(HttpHeaders.Range, "bytes=$from-${from + length - 1}") }
        }
        return when (response.status.value) {
            206 -> response.readRawBytes()
            200 -> response.readRawBytes().let { all -> all.copyOfRange(from.toInt().coerceAtMost(all.size), (from + length).toInt().coerceAtMost(all.size)) }
            416 -> ByteArray(0)
            404 -> throw BackendException("The server doesn't have $sha256")
            else -> throw BackendException("The server answered ${response.status.value}", offline = response.status.value >= 500)
        }
    }

    override suspend fun changes(cursor: String?): ChangePage {
        val page = query(
            """query(${'$'}s: String) { changes(since: ${'$'}s, limit: 2000) { cursor hasMore changes { path deleted
               media { sha256 kind fileName mimeType size takenAt durationMs source uploadedAt tucked note } } } }""",
            mapOf("s" to (cursor?.let(::JsonPrimitive) ?: JsonNull)),
        ).obj("changes")
        val added = mutableListOf<RemoteFile>()
        val removed = mutableListOf<String>()
        for (change in page["changes"]!!.jsonArray.map { it.jsonObject }) {
            val sha = change.str("path")!!.removePrefix("media/")
            val media = change["media"] as? JsonObject
            if (change.str("deleted") == "true" || media == null) {
                removed += sha
                continue
            }
            val kind = runCatching { FileKind.valueOf(media.str("kind")!!) }.getOrNull() ?: continue
            added += RemoteFile(
                sha256 = media.str("sha256")!!,
                size = media.long("size") ?: 0,
                about = FileAbout(
                    fileName = media.str("fileName") ?: sha,
                    mimeType = media.str("mimeType") ?: "application/octet-stream",
                    kind = kind,
                    takenAt = media.str("takenAt"),
                    durationMillis = media.long("durationMs"),
                    source = media.str("source"),
                ),
                storedAt = media.str("uploadedAt"),
                tucked = media.str("tucked") == "true",
                note = media.str("note"),
            )
        }
        return ChangePage(added, removed, page.str("cursor")!!, page.str("hasMore") == "true")
    }

    // The user's marks

    /**
     * What this app shows of sound recordings beside recordings (Audio Logs: calls and voice messages, off until the
     * user turns them on); null for an app that follows the user's hidden collections, or a server from before
     * Nov 16, 2026.
     */
    suspend fun soundRecordingsShown(): SoundRecordingsShown? {
        val data = try {
            query("{ soundRecordingsShown { calls voiceMessages } }")
        } catch (e: BackendException) {
            if (e.cause is GraphQLException) return null
            throw e
        }
        val shown = data["soundRecordingsShown"] as? JsonObject ?: return null
        return SoundRecordingsShown(shown.str("calls") == "true", shown.str("voiceMessages") == "true")
    }

    /** Sets what this app shows of sound recordings; the next [changes] bring the calls or voice messages, or take them away. */
    suspend fun setSoundRecordingsShown(shown: SoundRecordingsShown): SoundRecordingsShown {
        val result = query(
            "mutation(\$c: Boolean!, \$v: Boolean!) { setSoundRecordingsShown(calls: \$c, voiceMessages: \$v) { calls voiceMessages } }",
            mapOf("c" to JsonPrimitive(shown.calls), "v" to JsonPrimitive(shown.voiceMessages)),
        ).obj("setSoundRecordingsShown")
        return SoundRecordingsShown(result.str("calls") == "true", result.str("voiceMessages") == "true")
    }

    /**
     * Marks files private, or not, on the server, with every file that goes with them. Audio Logs never gets a private
     * file again (the server withholds them from it), so mark one private only once the app is done with it.
     */
    suspend fun setPrivate(sha256s: List<String>, private: Boolean) {
        query(
            "mutation(\$s: [Sha256!]!, \$p: Boolean!) { setPrivate(sha256s: \$s, private: \$p) { sha256 } }",
            mapOf("s" to JsonArray(sha256s.map(::JsonPrimitive)), "p" to JsonPrimitive(private)),
        )
    }

    /** Tucks files away on the server, or brings them back, on every device of the user. */
    suspend fun setTucked(sha256s: List<String>, tucked: Boolean) {
        query(
            "mutation(\$s: [Sha256!]!, \$t: Boolean!) { setTucked(sha256s: \$s, tucked: \$t) { sha256 } }",
            mapOf("s" to JsonArray(sha256s.map(::JsonPrimitive)), "t" to JsonPrimitive(tucked)),
        )
    }

    /**
     * Keeps [note] (a JSON object, at most 256 KiB) with the file [sha256] on the server, replacing what was there:
     * the app's own words about the file, synced with it (journal-server-go `setNote`). Returns false when the server
     * refused it (too large, or the file isn't there).
     */
    suspend fun setNote(sha256: String, note: JsonObject): Boolean {
        val result = query(
            "mutation(\$s: Sha256!, \$n: String) { setNote(sha256: \$s, note: \$n) { __typename } }",
            mapOf("s" to JsonPrimitive(sha256), "n" to JsonPrimitive(note.toString())),
        ).obj("setNote")
        return result.str("__typename") == "NoteSaved"
    }

    override suspend fun holds(sha256: String): Boolean {
        val response = request { origin -> post("$origin/media/$sha256/verify") }
        if (response.status.value != 200) return false
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.str("result") == "ok"
    }

    private fun stateOf(p: Pairing?): JournalState =
        p?.let { JournalState.Paired(it.server, it.user, it.deviceName) } ?: JournalState.NotPaired

    private companion object {
        const val DEFAULT_PIECE = 16L shl 20
        const val MAX_ADDRESSES = 16

        /** [addresses] without repeats or [server] (kept apart), at most [MAX_ADDRESSES]. */
        fun keptAddresses(addresses: List<String>, server: String): List<String> =
            addresses.filter { it != server }.distinct().take(MAX_ADDRESSES)

        /** The server couldn't be reached at all (no answer, refused, timed out), as against an answer that was an error. */
        val BackendException.unreached: Boolean get() = offline && cause != null && cause !is GraphQLException
        const val ENROLL = "mutation(\$i: EnrollDeviceInput!) { enrollDevice(input: \$i) { __typename " +
            "... on EnrolledDevice { user deviceName certificate expiresAt } ... on EnrollmentRejected { reason } } }"
    }
}
