package com.chencohensolution.shed.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.chencohensolution.shed.journal.DeviceKey
import com.chencohensolution.shed.journal.Identity
import com.chencohensolution.shed.journal.JvmDeviceKeys
import com.chencohensolution.shed.journal.KeyProtection
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * The device's keys in the app's Android Keystore: in StrongBox when the phone has one, otherwise in the TEE. The
 * private key never leaves it; the TLS stack signs with it there. Ported from journal-client-kmp's
 * `AndroidDeviceKeyStore`.
 */
class AndroidDeviceKeys : JvmDeviceKeys {
    override fun generate(alias: String): DeviceKey {
        delete(alias)
        val pair = try {
            generateKeyPair(alias, strongBox = true)
        } catch (e: ProviderException) {
            // StrongBoxUnavailableException, or a StrongBox that cannot make this key.
            generateKeyPair(alias, strongBox = false)
        }
        return KeystoreKey(pair, protectionOf(pair.private))
    }

    override fun saveCertificate(alias: String, certificateDer: ByteArray) {
        val store = keyStore()
        val key = store.getKey(alias, null) ?: error("No key under $alias")
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(certificateDer.inputStream())
        // For a Keystore key this replaces only the placeholder certificate the Keystore made with it.
        store.setKeyEntry(alias, key, null, arrayOf(certificate))
    }

    override fun delete(alias: String) {
        val store = keyStore()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    override fun has(alias: String): Boolean {
        val certificate = keyStore().getCertificate(alias) as? X509Certificate ?: return false
        // The Keystore's placeholder is self-signed; the server's is signed by its CA.
        return certificate.issuerX500Principal != certificate.subjectX500Principal
    }

    override fun identity(alias: String): Identity? {
        if (!has(alias)) return null
        val store = keyStore()
        val key = store.getKey(alias, null) as? PrivateKey ?: return null
        val chain = store.getCertificateChain(alias)?.map { it as X509Certificate }?.toTypedArray() ?: return null
        return Identity(key, chain)
    }

    private fun generateKeyPair(alias: String, strongBox: Boolean): KeyPair {
        // StrongBox only supports SHA-256; the TLS stack may also ask for other digests or none.
        val digests = if (strongBox) {
            arrayOf(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
        } else {
            arrayOf(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
        }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(*digests)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    private fun protectionOf(key: PrivateKey): KeyProtection {
        val info = KeyFactory.getInstance(key.algorithm, ANDROID_KEY_STORE).getKeySpec(key, KeyInfo::class.java)
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyProtection.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyProtection.TEE
            else -> KeyProtection.SOFTWARE
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"

        fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }
}

private class KeystoreKey(private val pair: KeyPair, override val protection: KeyProtection) : DeviceKey {
    override val publicKeyInfo: ByteArray get() = pair.public.encoded

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(data)
            sign()
        }
}
