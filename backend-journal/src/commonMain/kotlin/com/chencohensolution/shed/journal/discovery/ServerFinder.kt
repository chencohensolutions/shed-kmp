package com.chencohensolution.shed.journal.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Where the server answered when [ServerFinder] looked: [origin], and the addresses its DHT record named, if it was read. */
internal data class Found(val origin: String, val fromDht: List<String> = emptyList())

/**
 * Finds the journal server again when it stops answering where it was, as the journal app does (journal-client-kmp
 * `discovery/ServerFinder.kt`, docs/plans/discovery.md "Phones"): tries the other addresses kept (each started
 * [stagger] after the one before; the first to answer wins), then looks the server up in the BitTorrent DHT under its
 * key, at most every [dhtEvery]. There is no relay and no DNS name: the server publishes where it is in the DHT, sealed
 * with its fingerprint, and the clients pin its key, so an address that answers is the server, whatever it is.
 */
internal class ServerFinder(
    private val dht: suspend (key: String, fingerprint: String) -> List<String>? = { key, fingerprint ->
        DhtClient().lookup(key, fingerprint)?.addresses
    },
    private val stagger: Duration = 400.milliseconds,
    private val dhtEvery: Duration = 2.minutes,
    /** After a round of the kept addresses found nothing, how long before the next failure tries them again. */
    private val addressesEvery: Duration = 10.seconds,
) {
    private val mutex = Mutex()
    private var lastRound: TimeSource.Monotonic.ValueTimeMark? = null
    private var lastDht: TimeSource.Monotonic.ValueTimeMark? = null

    /**
     * A request to [failed] (`https://host:port`) failed on the network: where the server answers now, or null when
     * nowhere does (or it was looked for too recently). [addresses] are the others kept, [key] the server's DHT key
     * (null when it doesn't publish there). [current] is where the app sends now, read after waiting for another
     * search to end: when another request already found the server elsewhere, that is the answer.
     */
    suspend fun find(
        failed: String,
        current: () -> String,
        addresses: () -> List<String>,
        key: String?,
        fingerprint: String,
        probe: suspend (origin: String) -> Boolean,
    ): Found? = mutex.withLock {
        current().takeIf { it != failed }?.let { return Found(it) }
        if (lastRound?.let { it.elapsedNow() < addressesEvery } == true) return null
        lastRound = TimeSource.Monotonic.markNow()

        firstToAnswer(addresses().filter { it != failed }, stagger, probe)?.let { return Found(it) }

        if (key == null) return null
        if (lastDht?.let { it.elapsedNow() < dhtEvery } == true) return null
        lastDht = TimeSource.Monotonic.markNow()
        val found = lookUp(key, fingerprint) ?: return null
        firstToAnswer(found.filter { it != failed }, stagger, probe)?.let { Found(it, found) }
    }

    /** The addresses the server's DHT record names (origins), or null when none was found. */
    suspend fun lookUp(key: String, fingerprint: String): List<String>? {
        val found = try {
            dht(key, fingerprint)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return found?.mapNotNull(::originOf)?.distinct()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        /** How long an address may take to answer a probe. */
        val PROBE_TIMEOUT = 6.seconds
    }
}

/** `https://host:port` of an address the DHT record names; null unless it is https. */
private fun originOf(address: String): String? = com.chencohensolution.shed.journal.originOf(address)

/** The first of [origins] for which [probe] says the server answers, each started [stagger] after the one before. */
internal suspend fun firstToAnswer(origins: List<String>, stagger: Duration, probe: suspend (String) -> Boolean): String? {
    if (origins.isEmpty()) return null
    return coroutineScope {
        val winner = CompletableDeferred<String?>()
        val tries = origins.mapIndexed { i, origin ->
            launch {
                delay(stagger * i)
                val answered = try {
                    // On its own job: a failed request may fail the job it runs in, which mustn't stop the others.
                    supervisorScope { async { probe(origin) }.await() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (answered) winner.complete(origin)
            }
        }
        launch {
            tries.joinAll()
            winner.complete(null)
        }
        winner.await().also { coroutineContext.cancelChildren() }
    }
}
