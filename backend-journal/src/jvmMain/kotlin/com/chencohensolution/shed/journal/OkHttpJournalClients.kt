package com.chencohensolution.shed.journal

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import java.net.Socket
import java.security.Principal
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * [JournalClients] over OkHttp, for Android and the JVM. The server is trusted by its key's fingerprint or by its own
 * CA, whatever address it answers at (so no host name check), and the device's certificate under the alias is
 * presented. Ported from journal-client-kmp's `AndroidMtlsHttpClientFactory`.
 */
class OkHttpJournalClients(private val keys: JvmDeviceKeys) : JournalClients {
    override fun create(trust: ServerTrust, alias: String?): HttpClient {
        val okHttp = okHttpClient(trust, alias)
        return HttpClient(OkHttp) {
            engine { preconfigured = okHttp }
            install(HttpTimeout)
            expectSuccess = false
        }
    }

    /** The OkHttp client under [create], for players that stream with OkHttp directly. May block (the key store). */
    fun okHttpClient(trust: ServerTrust, alias: String?): OkHttpClient {
        val trustManager = KeptServerTrustManager(trust.caPem?.let(::x509s).orEmpty(), trust.fingerprint)
        val keyManagers = alias?.let { keys.identity(it) }?.let { arrayOf(SingleKeyManager(alias, it)) }
        val sslContext = SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(trustManager), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            // The server answers an upload piece only after writing and syncing it, and the last only after hashing
            // the whole file.
            .readTimeout(2, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES)
            .build()
    }
}

/**
 * Accepts the server the app paired with: one whose certificate chains to the server's own CA and is for a server
 * (serverAuth), or whose key hashes to [fingerprint]. Any other is refused.
 */
internal class KeptServerTrustManager(serverCas: List<X509Certificate>, private val fingerprint: String?) : X509TrustManager {
    private val byCa: X509TrustManager? = serverCas.takeIf { it.isNotEmpty() }?.let { cas ->
        HandshakeCertificates.Builder().apply { cas.forEach { addTrustedCertificate(it) } }.build().trustManager
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("The server sent no certificate")
        val byCa = byCa
        if (byCa != null && runCatching { byCa.checkServerTrusted(chain, authType) }.isSuccess &&
            leaf.extendedKeyUsage?.contains(SERVER_AUTH) == true
        ) return
        if (fingerprint != null && ServerPin.fingerprintOfSpki(leaf.publicKey.encoded).equals(fingerprint, ignoreCase = true)) return
        throw CertificateException("The server isn't the one this app paired with")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit =
        throw CertificateException("Not a server")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private companion object {
        const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
    }
}

/** Presents one identity; its key may be in hardware and not exportable, so it is handed over as it is. */
private class SingleKeyManager(private val alias: String, private val identity: Identity) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
    override fun getCertificateChain(alias: String?) = identity.chain
    override fun getPrivateKey(alias: String?) = identity.privateKey
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
}
