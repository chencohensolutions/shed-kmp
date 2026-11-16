package com.chencohensolution.shed.journal

import kotlinx.serialization.Serializable

/** What a paired app keeps of its server (`journal/pairing.json` in shed's folder). */
@Serializable
data class Pairing(
    val v: Int = 1,
    /** The origin the server answered at last, `https://host:port`. */
    val server: String,
    /** Its other addresses, from the enrollment link, tried when [server] doesn't answer. */
    val addresses: List<String> = emptyList(),
    /** The sha256 of the server's key: the server is trusted by it, whatever its address. */
    val fingerprint: String,
    /** The CA that signs the server's certificates (serverInfo.caCertificate), PEM; null until asked. */
    val serverCa: String? = null,
    /** Where the device's key and certificate are in its key store. */
    val alias: String,
    val user: String,
    val deviceName: String,
    /** When the certificate expires, RFC 3339. */
    val expiresAt: String,
)

/** What trusting a server takes: its key's [fingerprint], or a certificate its own CA ([caPem]) signed for a server. */
data class ServerTrust(val fingerprint: String, val caPem: String? = null)
