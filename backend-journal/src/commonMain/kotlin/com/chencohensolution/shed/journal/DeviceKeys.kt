package com.chencohensolution.shed.journal

/** Where the device keeps its private key, as it tells the server at pairing (the server's `KeyProtection`). */
enum class KeyProtection { STRONGBOX, TEE, SECURE_ENCLAVE, SOFTWARE }

/** A key pair made for pairing: the private half never leaves the device's key store. */
interface DeviceKey {
    /** DER SubjectPublicKeyInfo of the EC P-256 public key. */
    val publicKeyInfo: ByteArray
    val protection: KeyProtection

    /** SHA-256/ECDSA signature of [data], DER. */
    fun sign(data: ByteArray): ByteArray
}

/**
 * The device's key store, per platform: Android's Keystore (in StrongBox when there is one), the iPhone's Secure
 * Enclave. Each app has its own: Android ties a key to the app that made it, which is why two apps pair as two
 * devices (README.md, "Pairing").
 */
interface DeviceKeys {
    /** A new EC P-256 key under [alias], replacing any there. */
    fun generate(alias: String): DeviceKey

    /** Keeps the certificate the server issued for the key under [alias], making it an identity mTLS can present. */
    fun saveCertificate(alias: String, certificateDer: ByteArray)

    fun delete(alias: String)

    /** Whether there is an identity (key and certificate) under [alias]. */
    fun has(alias: String): Boolean
}
