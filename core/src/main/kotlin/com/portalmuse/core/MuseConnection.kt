// Kotlin port of muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0). Source:
// linux/src/musegadget/service.py
package com.portalmuse.core

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private const val BACKOFF_BASE_MS = 2_000L
private const val BACKOFF_MAX_MS = 60_000L
private const val AUTH_BACKOFF_MIN_MS = 15_000L
private const val HEALTHY_SESSION_MS = 30_000L
private const val HEALTHY_RECONNECT_MS = 1_000L
private const val UNPAIRED_POLL_MS = 30_000L
/** Device tokens live about 4 hours; rotation at 3. */
private const val TOKEN_REFRESH_AGE_S = 3 * 3600L
private const val TOKEN_RETRY_MS = 300_000L

interface PairingStore {
    fun load(): PairingRecord?

    /**
     * Replaces (or erases, if [updated] is null) the pairing, only if it is still [expected]: a pairing redone
     * in the meantime must never be overwritten.
     */
    fun replace(expected: PairingRecord, updated: PairingRecord?): Boolean
}

sealed class ConnectionState {
    object NotPaired : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    class Waiting(val delayMillis: Long, val reason: String) : ConnectionState()
    /** Muse revoked or removed the pairing. */
    object Revoked : ConnectionState()
}

fun interface SessionFactory {
    @Throws(IOException::class)
    fun open(url: String, bearer: String, nodeId: String, onRegistered: () -> Unit): ActiveSession
}

internal class Backoff {
    private var failures = 0
    var floor = 0L

    fun nextDelay(): Long {
        val delay = minOf(BACKOFF_BASE_MS shl minOf(failures, 16), BACKOFF_MAX_MS)
        failures++
        return maxOf(delay, floor)
    }

    fun reset() {
        failures = 0
        floor = 0
    }
}

/** Interruptible wait; returns true if [wake] cut it short. */
private class Pause : (Long) -> Boolean {
    private val lock = Object()
    private var woken = false

    override fun invoke(millis: Long): Boolean = synchronized(lock) {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (!woken) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) break
            lock.wait(left)
        }
        woken.also { woken = false }
    }

    fun wake() = synchronized(lock) {
        woken = true
        lock.notifyAll()
    }
}

/** Permanent loop: token rotation, VM selection, session, reconnection. */
class MuseConnection(
    private val store: PairingStore,
    private val api: MuseApi,
    private val sessions: SessionFactory,
    private val sdkToken: () -> String?,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    sleeper: ((Long) -> Boolean)? = null,
    private val onState: (ConnectionState) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val pause = Pause()
    private val sleeper = sleeper ?: pause
    private val backoff = Backoff()
    private var lastRefreshAttempt: Long? = null

    @Volatile private var stopped = false
    @Volatile private var current: ActiveSession? = null
    /** Counts the requests to cut, so as not to keep a session open during one of them. */
    private val drops = AtomicInteger()

    fun stop() {
        stopped = true
        drop()
    }

    /** The network is back or the pairing changed: start again at once. */
    fun wake() {
        backoff.reset()
        pause.wake()
    }

    /** The network is lost or the pairing changed: close the current session. */
    fun drop() {
        drops.incrementAndGet()
        current?.stop()
        pause.wake()
    }

    fun run() {
        var tokenRejected = false
        while (!stopped) {
            try {
                var pairing = store.load()
                if (pairing == null) {
                    onState(ConnectionState.NotPaired)
                    sleeper(UNPAIRED_POLL_MS)
                    continue
                }
                onState(ConnectionState.Connecting)
                pairing = maybeRefresh(pairing, force = false)
                if (pairing == null) {
                    if (store.load() != null) wait(TOKEN_RETRY_MS, tr(
                        "token refresh failed",
                        "renouvellement des jetons en échec",
                    ))
                    continue
                }
                val lookup = api.fetchVms(pairing.accessToken, pairing.apiUrlV2, log)
                if (lookup.status == 401) {
                    log("device token refused; refreshing")
                    if (maybeRefresh(pairing, force = true) == null) {
                        if (store.load() != null) wait(TOKEN_RETRY_MS, tr("token refused", "jeton refusé"))
                    } else if (tokenRejected) {
                        // A single immediate attempt: repeated refusals must not spin.
                        backoff.floor = AUTH_BACKOFF_MIN_MS
                        wait(backoff.nextDelay(), tr("token refused", "jeton refusé"))
                    }
                    tokenRejected = true
                    continue
                }
                tokenRejected = false
                val vm = lookup.vms.firstOrNull { it.isDefault } ?: lookup.vms.firstOrNull()
                if (vm == null) {
                    wait(backoff.nextDelay(), tr("no Muse machine available", "aucune machine Muse disponible"))
                    continue
                }
                session(pairing, vm)
            } catch (e: RuntimeException) {
                // The loop never dies; the type only in the log.
                log("connection error: ${e.javaClass.simpleName}")
                if (!stopped) wait(backoff.nextDelay(), tr("internal error", "erreur interne"))
            }
        }
    }

    private fun session(pairing: PairingRecord, vm: Vm) {
        var registeredAt: Long? = null
        val dropsBefore = drops.get()
        val outcome = try {
            val session = sessions.open(NoiseUrl.of(pairing, vm), vm.authToken, pairing.nodeId) {
                registeredAt = monotonicMillis()
                onState(ConnectionState.Connected)
            }
            current = session
            if (stopped || drops.get() != dropsBefore) session.stop()
            session.run()
        } catch (e: UpgradeRejected) {
            log("the VM refused the connection: HTTP ${e.status}")
            if (e.status == 401) Outcome.AUTH_REJECTED else Outcome.FORBIDDEN
        } catch (e: IOException) {
            log("session failed: ${e.message}")
            Outcome.CLOSED
        } finally {
            current = null
        }
        val healthy = registeredAt?.let { monotonicMillis() - it >= HEALTHY_SESSION_MS } == true
        log("session ended: ${outcome.name}")
        if (stopped) return
        if (outcome == Outcome.UNPAIRED) {
            if (store.replace(pairing, null)) onState(ConnectionState.Revoked)
            return
        }
        if (healthy) backoff.reset()
        // Session cut on purpose (network, pairing): the loop starts again without penalty.
        if (outcome == Outcome.STOPPED) return wait(HEALTHY_RECONNECT_MS, "reconnexion")
        if (outcome == Outcome.AUTH_REJECTED || outcome == Outcome.FORBIDDEN) backoff.floor = AUTH_BACKOFF_MIN_MS
        val delay = if (healthy && outcome == Outcome.CLOSED) HEALTHY_RECONNECT_MS else backoff.nextDelay()
        wait(delay, if (outcome == Outcome.CLOSED) tr("connection cut", "connexion coupée") else tr(
            "connection refused",
            "connexion refusée",
        ))
    }

    private fun wait(delay: Long, reason: String) {
        onState(ConnectionState.Waiting(delay, reason))
        sleeper(delay)
    }

    /**
     * The current pairing, after rotating the tokens if due. `null`: do not use the old token for now (the
     * pairing is erased if it was revoked).
     */
    private fun maybeRefresh(pairing: PairingRecord, force: Boolean): PairingRecord? {
        if (!force) {
            if (pairing.accessTokenSavedAt > nowSeconds() - TOKEN_REFRESH_AGE_S) return pairing
            val last = lastRefreshAttempt
            if (last != null && monotonicMillis() - last < TOKEN_RETRY_MS) return pairing
        }
        lastRefreshAttempt = monotonicMillis()
        val result = api.refresh(pairing.refreshToken, pairing.nodeId, pairing.apiUrlV2, sdkToken(), log)
        val tokens = result.tokens
        if (tokens != null) {
            val updated = pairing.rotated(tokens.accessToken, tokens.refreshToken, nowSeconds())
            if (!store.replace(pairing, updated)) {
                log("refreshed tokens not saved (pairing changed or storage failed)")
                return null
            }
            log("device tokens refreshed")
            return updated
        }
        if (result.status == 401) {
            if (store.replace(pairing, null)) {
                log("pairing revoked: pair again")
                onState(ConnectionState.Revoked)
            }
            return null
        }
        // Passing failure: keep the current token while it works.
        return if (force) null else pairing
    }
}
