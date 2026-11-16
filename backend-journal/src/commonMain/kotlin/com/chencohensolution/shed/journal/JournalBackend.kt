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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path
import kotlin.io.encoding.Base64

/** Whether an app is paired with a journal server. */
sealed interface JournalState {
    data object NotPaired : JournalState

    data class Paired(val server: String, val user: String, val deviceName: String) : JournalState
}

/** Who an enrollment code is for, before it is used ([JournalBackend.preview]). */
data class PairingPreview(val user: String, val expiresAt: String, val server: String)

/** Why pairing failed. */
sealed class PairingException(message: String) : Exception(message) {
    /** The text has no enrollment link with the server's fingerprint in it. */
    class NotALink : PairingException("Not an enrollment link from the server")

    /** Wrong, expired or used code (`INVALID_CODE`), too many tries (`RATE_LIMITED`), or another reason the server gave. */
    class Rejected(val reason: String) : PairingException("The server refused: $reason")

    /** None of the link's addresses answered. */
    class Unreachable(cause: String) : PairingException("The server didn't answer: $cause")
}

/**
 * The journal server (journal-server-go) as shed's storage. It pairs the app as a device of its own ([pair], with the
 * app's name, so Audio Logs is told apart from journal and sees sound recordings), then sends and reads files over
 * mTLS with the device's key, trusting the server by its key's fingerprint.
 *
 * What it keeps is `pairing.json` in [directory]; the key and certificate are in the platform's key store ([keys]).
 */
class JournalBackend(
    /** The app's name as the server records it: `audio-logs`, `journal`. */
    private val app: String,
    private val fileSystem: FileSystem,
    directory: Path,
    private val keys: DeviceKeys,
    private val clients: JournalClients,
) : ShedBackend {
    private val pairingFile = PairingFile(fileSystem, directory / "pairing.json")
    private val mutex = Mutex()
    private var pairing: Pairing? = pairingFile.load()?.takeIf { keys.has(it.alias) }
    private var client: Pair<Pairing, HttpClient>? = null
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
    suspend fun pair(text: String, deviceName: String): JournalState.Paired = mutex.withLock {
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
            server = origin, addresses = link.addresses, fingerprint = link.fingerprint, alias = alias,
            user = result.str("user")!!, deviceName = result.str("deviceName")!!, expiresAt = result.str("expiresAt")!!,
        )
        save(paired)
        // The server's own CA, so it is trusted by it too after its key changes (a new server certificate).
        runCatching { http().graphql(origin, "{ serverInfo { caCertificate } }").obj("serverInfo").str("caCertificate") }
            .getOrNull()?.let { ca -> paired = paired.copy(serverCa = ca).also(::save) }
        stateOf(paired) as JournalState.Paired
    }

    /** Forgets the server and deletes the device's key. The server still lists the device until it is revoked there. */
    suspend fun unpair() = mutex.withLock {
        pairing?.let { keys.delete(it.alias) }
        fileSystem.delete(pairingFile.path)
        client?.second?.close()
        client = null
        pairing = null
        _state.value = JournalState.NotPaired
    }

    private fun save(new: Pairing) {
        pairingFile.save(new)
        pairing = new
        _state.value = stateOf(new)
    }

    /** Runs [block] without a certificate at the link's addresses in turn, trusting the server by the link's fingerprint. */
    private suspend fun <T> anonymous(link: EnrollmentLink, block: suspend (HttpClient, String) -> T): Pair<String, T> {
        var last: Exception? = null
        for (origin in link.addresses) {
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
            http().graphql(paired().server, query, variables)
        } catch (e: GraphQLException) {
            throw BackendException("The server answered: ${e.message}", cause = e)
        }

    /** Sends an HTTP request, turning failures into [BackendException]s. */
    private suspend fun request(send: suspend HttpClient.(String) -> HttpResponse): HttpResponse {
        val response = try {
            http().send(paired().server)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendException) {
            throw e
        } catch (e: Exception) {
            throw BackendException("The server didn't answer: ${e.message ?: e::class.simpleName}", offline = true, cause = e)
        }
        if (response.status.value == 401) throw BackendException("The server refused this device", unauthorized = true)
        return response
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
               media { sha256 kind fileName mimeType size takenAt durationMs source uploadedAt } } } }""",
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
            )
        }
        return ChangePage(added, removed, page.str("cursor")!!, page.str("hasMore") == "true")
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
        const val ENROLL = "mutation(\$i: EnrollDeviceInput!) { enrollDevice(input: \$i) { __typename " +
            "... on EnrolledDevice { user deviceName certificate expiresAt } ... on EnrollmentRejected { reason } } }"
    }
}
