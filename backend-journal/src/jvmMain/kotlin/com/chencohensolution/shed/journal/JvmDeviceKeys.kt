package com.chencohensolution.shed.journal

import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/** A device's identity as the JVM's TLS stack takes it: the private key (maybe in hardware) and its certificate. */
class Identity(val privateKey: PrivateKey, val chain: Array<X509Certificate>)

/** [DeviceKeys] on the JVM and Android, which also hand the TLS stack the identity under an alias. */
interface JvmDeviceKeys : DeviceKeys {
    /** The key and certificate under [alias]; null when there is none. May block (Android's Keystore). */
    fun identity(alias: String): Identity?
}

/**
 * Keys kept in memory, in software: for tests and tools on a computer. They are lost when the process ends, so a
 * pairing made with them lasts only that long.
 */
class MemoryDeviceKeys : JvmDeviceKeys {
    private val keys = mutableMapOf<String, PrivateKey>()
    private val certificates = mutableMapOf<String, X509Certificate>()

    override fun generate(alias: String): DeviceKey {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        keys[alias] = pair.private
        certificates.remove(alias)
        return object : DeviceKey {
            override val publicKeyInfo: ByteArray = pair.public.encoded
            override val protection = KeyProtection.SOFTWARE
            override fun sign(data: ByteArray): ByteArray =
                Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }
        }
    }

    override fun saveCertificate(alias: String, certificateDer: ByteArray) {
        check(alias in keys) { "No key under $alias" }
        certificates[alias] = x509(certificateDer)
    }

    override fun delete(alias: String) {
        keys.remove(alias)
        certificates.remove(alias)
    }

    override fun has(alias: String) = alias in keys && alias in certificates

    override fun identity(alias: String): Identity? {
        val key = keys[alias] ?: return null
        val certificate = certificates[alias] ?: return null
        return Identity(key, arrayOf(certificate))
    }
}

internal fun x509(der: ByteArray): X509Certificate =
    CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

internal fun x509s(pem: String): List<X509Certificate> =
    CertificateFactory.getInstance("X.509").generateCertificates(pem.byteInputStream()).map { it as X509Certificate }

