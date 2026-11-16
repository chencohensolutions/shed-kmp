package com.chencohensolution.shed.journal

import io.ktor.http.URLProtocol
import io.ktor.http.Url

/**
 * What the server's enrollment QR code holds (journal-server-go docs/authentication.md, `enroll-code`):
 * `journal://enroll?code=K7QM-4XPD&server=https%3A%2F%2Fhost%3A56398&fingerprint=…&key=…&address=…`. [fingerprint]
 * is the sha256 of the server's key: shed trusts the server by it from the first connection, whatever address it is
 * reached at, so a link without one is refused (an older server's: pair with journal first).
 */
data class EnrollmentLink(
    val code: String,
    /** The server's origins, `https://host:port`, the one to try first first. */
    val addresses: List<String>,
    val fingerprint: String,
    /** The server's ed25519 key in the BitTorrent DHT (64 hex digits), to find it there when no address answers. */
    val key: String? = null,
)

private val LINK_IN_TEXT = Regex("""journal://enroll\?[^\s<>"']+""")

/** The first enrollment link in [text] (a scanned code, or pasted text around one); null when there is none. */
fun findEnrollmentLink(text: String): EnrollmentLink? =
    LINK_IN_TEXT.findAll(text).firstNotNullOfOrNull { parseEnrollmentLink(it.value.trimEnd('.', ',', ';', ')', ']', '!', '?')) }

/** [link] read as an enrollment link; null for any other link, or one without the server's fingerprint or an address. */
fun parseEnrollmentLink(link: String): EnrollmentLink? {
    val url = runCatching { Url(link.trim()) }.getOrNull() ?: return null
    if (url.protocol.name != "journal" || url.host != "enroll") return null
    val code = url.parameters["code"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val fingerprint = url.parameters["fingerprint"]?.trim()?.lowercase()
        ?.takeIf(::isKey) ?: return null
    val addresses = (listOfNotNull(url.parameters["server"]) + url.parameters.getAll("address").orEmpty())
        .mapNotNull(::originOf).distinct()
    if (addresses.isEmpty()) return null
    val key = url.parameters["key"]?.trim()?.lowercase()?.takeIf(::isKey)
    return EnrollmentLink(code, addresses, fingerprint, key)
}

/** `https://host:port` of [address] (an origin, or a URL with a path such as `/query`); null unless it is https. */
fun originOf(address: String): String? {
    val url = runCatching { Url(address.trim()) }.getOrNull() ?: return null
    if (url.protocol != URLProtocol.HTTPS || url.host.isEmpty()) return null
    val host = if (':' in url.host && !url.host.startsWith("[")) "[${url.host}]" else url.host
    return "https://$host:${url.port}"
}

/** Whether [text] is 64 lower-case hex digits: a fingerprint or a DHT key. */
internal fun isKey(text: String): Boolean = text.length == 64 && text.all { it in "0123456789abcdef" }
