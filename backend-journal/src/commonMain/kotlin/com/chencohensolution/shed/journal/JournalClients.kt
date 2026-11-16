package com.chencohensolution.shed.journal

import io.ktor.client.HttpClient

/**
 * Makes the HTTP clients that talk to the journal server, per platform: OkHttp on Android and the JVM
 * ([OkHttpJournalClients]), URLSession on iOS (not built yet). Each trusts only the server [ServerTrust] describes and,
 * given an [alias], presents the device's certificate from its key store (mTLS).
 */
fun interface JournalClients {
    fun create(trust: ServerTrust, alias: String?): HttpClient
}
