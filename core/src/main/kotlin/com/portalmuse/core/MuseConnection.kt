// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Source : linux/src/musegadget/service.py
package com.portalmuse.core

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private const val BACKOFF_BASE_MS = 2_000L
private const val BACKOFF_MAX_MS = 60_000L
private const val AUTH_BACKOFF_MIN_MS = 15_000L
private const val HEALTHY_SESSION_MS = 30_000L
private const val HEALTHY_RECONNECT_MS = 1_000L
private const val UNPAIRED_POLL_MS = 30_000L
/** Les jetons d'appareil vivent environ 4 heures ; rotation à 3. */
private const val TOKEN_REFRESH_AGE_S = 3 * 3600L
private const val TOKEN_RETRY_MS = 300_000L

interface PairingStore {
    fun load(): PairingRecord?

    /**
     * Remplace (ou efface, si [updated] est nul) l'appairage, seulement s'il est encore
     * [expected] : un appairage refait entre-temps ne doit jamais être écrasé.
     */
    fun replace(expected: PairingRecord, updated: PairingRecord?): Boolean
}

sealed class ConnectionState {
    object NotPaired : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    class Waiting(val delayMillis: Long, val reason: String) : ConnectionState()
    /** Muse a révoqué ou retiré l'appairage. */
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

/** Attente interruptible ; renvoie vrai si [wake] l'a écourtée. */
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

/** Boucle permanente : rotation des jetons, choix de la VM, session, reconnexion. */
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
    /** Compte les demandes de coupure, pour ne pas garder une session ouverte pendant l'une d'elles. */
    private val drops = AtomicInteger()

    fun stop() {
        stopped = true
        drop()
    }

    /** Le réseau est revenu ou l'appairage a changé : repartir tout de suite. */
    fun wake() {
        backoff.reset()
        pause.wake()
    }

    /** Le réseau est perdu ou l'appairage a changé : fermer la session en cours. */
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
                    if (store.load() != null) wait(TOKEN_RETRY_MS, "renouvellement des jetons en échec")
                    continue
                }
                val lookup = api.fetchVms(pairing.accessToken, pairing.apiUrlV2, log)
                if (lookup.status == 401) {
                    log("jeton d'appareil refusé ; renouvellement")
                    if (maybeRefresh(pairing, force = true) == null) {
                        if (store.load() != null) wait(TOKEN_RETRY_MS, "jeton refusé")
                    } else if (tokenRejected) {
                        // Un seul essai immédiat : des refus répétés ne doivent pas tourner à vide.
                        backoff.floor = AUTH_BACKOFF_MIN_MS
                        wait(backoff.nextDelay(), "jeton refusé")
                    }
                    tokenRejected = true
                    continue
                }
                tokenRejected = false
                val vm = lookup.vms.firstOrNull { it.isDefault } ?: lookup.vms.firstOrNull()
                if (vm == null) {
                    wait(backoff.nextDelay(), "aucune machine Muse disponible")
                    continue
                }
                session(pairing, vm)
            } catch (e: RuntimeException) {
                // La boucle ne meurt jamais ; le type seulement dans le journal.
                log("erreur de connexion : ${e.javaClass.simpleName}")
                if (!stopped) wait(backoff.nextDelay(), "erreur interne")
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
            log("la VM a refusé la connexion : HTTP ${e.status}")
            if (e.status == 401) Outcome.AUTH_REJECTED else Outcome.FORBIDDEN
        } catch (e: IOException) {
            log("session en échec : ${e.message}")
            Outcome.CLOSED
        } finally {
            current = null
        }
        val healthy = registeredAt?.let { monotonicMillis() - it >= HEALTHY_SESSION_MS } == true
        log("session terminée : ${outcome.name}")
        if (stopped) return
        if (outcome == Outcome.UNPAIRED) {
            if (store.replace(pairing, null)) onState(ConnectionState.Revoked)
            return
        }
        if (healthy) backoff.reset()
        // Session coupée volontairement (réseau, appairage) : la boucle repart sans pénalité.
        if (outcome == Outcome.STOPPED) return wait(HEALTHY_RECONNECT_MS, "reconnexion")
        if (outcome == Outcome.AUTH_REJECTED || outcome == Outcome.FORBIDDEN) backoff.floor = AUTH_BACKOFF_MIN_MS
        val delay = if (healthy && outcome == Outcome.CLOSED) HEALTHY_RECONNECT_MS else backoff.nextDelay()
        wait(delay, if (outcome == Outcome.CLOSED) "connexion coupée" else "connexion refusée")
    }

    private fun wait(delay: Long, reason: String) {
        onState(ConnectionState.Waiting(delay, reason))
        sleeper(delay)
    }

    /**
     * L'appairage courant, après rotation des jetons si elle est due. `null` : ne pas se servir de
     * l'ancien jeton pour l'instant (l'appairage est effacé s'il a été révoqué).
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
                log("jetons renouvelés non enregistrés (appairage modifié ou stockage en échec)")
                return null
            }
            log("jetons d'appareil renouvelés")
            return updated
        }
        if (result.status == 401) {
            if (store.replace(pairing, null)) {
                log("appairage révoqué : appairer à nouveau")
                onState(ConnectionState.Revoked)
            }
            return null
        }
        // Échec passager : garder le jeton actuel tant qu'il marche.
        return if (force) null else pairing
    }
}
