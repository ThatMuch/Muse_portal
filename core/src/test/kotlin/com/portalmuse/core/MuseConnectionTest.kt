package com.portalmuse.core

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val VM_LIST = """{"vm_list":[{"vm_ws_url":"w","vm_auth_token":"autre","vm_id":"v0"},
  {"vm_ws_url":"w","vm_auth_token":"jeton-vm","vm_id":"v 1","default":true}]}"""
private const val NEW_TOKENS = """{"payload":{"access_token":"a2","refresh_token":"r2"}}"""

class MuseConnectionTest {
    private var now = 100_000L
    private var millis = 0L

    private class Store(var record: PairingRecord?) : PairingStore {
        override fun load() = record
        override fun replace(expected: PairingRecord, updated: PairingRecord?): Boolean {
            if (record?.refreshToken != expected.refreshToken) return false
            record = updated
            return true
        }
    }

    private fun record(savedAt: Long = now, refresh: String = "hatch_refresh:r1") =
        PairingRecord("a1", refresh, "u", "", "", "", savedAt, "homelink-abcdef")

    /** Successive HTTP answers by path; the last one repeats. 0 = network failure. */
    private class Server(val fetch: MutableList<Pair<Int, String>>, val refresh: MutableList<Pair<Int, String>>) {
        val calls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val api = MuseApi(
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                calls.add("$path ${request.header("Authorization")}")
                request.body?.let { body -> bodies.add(Buffer().also(body::writeTo).readUtf8()) }
                val queue = if (path == "/fetch_vms") fetch else refresh
                val (code, text) = if (queue.size > 1) queue.removeAt(0) else queue.first()
                if (code == 0) throw IOException("hors ligne")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
                    .body(text.toResponseBody("application/json".toMediaType())).build()
            }).build(),
            "test",
        )
    }

    private fun server(fetch: List<Pair<Int, String>> = listOf(200 to VM_LIST), refresh: List<Pair<Int, String>> = listOf(200 to NEW_TOKENS)) =
        Server(fetch.toMutableList(), refresh.toMutableList())

    private class Run(val delays: MutableList<Long>, val connects: MutableList<Pair<String, String>>, val states: MutableList<ConnectionState>)

    /** Runs the loop up to [sleeps] waits. [session] plays each successful connection. */
    private fun run(
        store: Store, server: Server, sleeps: Int,
        connect: (Int) -> Outcome = { Outcome.CLOSED }, healthyFor: (Int) -> Long = { 0 },
    ): Run {
        val result = Run(mutableListOf(), mutableListOf(), mutableListOf())
        lateinit var connection: MuseConnection
        var attempt = 0
        connection = MuseConnection(
            store = store,
            api = server.api,
            sessions = { url, bearer, _, onRegistered ->
                result.connects.add(url to bearer)
                val outcome = connect(attempt)
                val healthy = healthyFor(attempt)
                attempt++
                object : ActiveSession {
                    override fun run(): Outcome {
                        if (healthy > 0) { onRegistered(); millis += healthy }
                        return outcome
                    }
                    override fun stop() = Unit
                }
            },
            sdkToken = { "jeton-sdk" },
            nowSeconds = { now },
            monotonicMillis = { millis },
            sleeper = { delay ->
                result.delays.add(delay)
                if (result.delays.size >= sleeps) connection.stop()
                false
            },
            onState = { result.states.add(it) },
        )
        connection.run()
        return result
    }

    @Test fun unpairedPollsEveryThirtySeconds() {
        val result = run(Store(null), server(), sleeps = 2)
        assertEquals(listOf(30_000L, 30_000L), result.delays)
        assertTrue(result.connects.isEmpty())
        assertEquals(ConnectionState.NotPaired, result.states.first())
    }

    @Test fun connectsToTheDefaultVmWithItsOwnBearer() {
        val result = run(Store(record()), server(), sleeps = 1, healthyFor = { 31_000 })
        assertEquals("wss://hatch.metaaivm.com/v1/noise?vm_id=v%201" to "jeton-vm", result.connects.single())
        assertTrue(ConnectionState.Connected in result.states)
        assertEquals("après une session saine", listOf(1_000L), result.delays)
    }

    @Test fun backoffDoublesUpToOneMinute() {
        val result = run(Store(record()), server(), sleeps = 8)
        assertEquals(listOf(2L, 4, 8, 16, 32, 60, 60, 60).map { it * 1000 }, result.delays)
    }

    @Test fun refusedBearerWaitsAtLeastFifteenSeconds() {
        val result = run(Store(record()), server(), sleeps = 5, connect = { Outcome.AUTH_REJECTED })
        assertEquals(listOf(15L, 15, 15, 16, 32).map { it * 1000 }, result.delays)
    }

    @Test fun aHealthySessionResetsTheBackoff() {
        val result = run(Store(record()), server(), sleeps = 4, healthyFor = { if (it == 2) 40_000 else 0 })
        assertEquals(listOf(2L, 4, 1, 2).map { it * 1000 }, result.delays)
    }

    @Test fun oldTokensRotateBeforeConnecting() {
        val store = Store(record(savedAt = now - 3 * 3600 - 1))
        val server = server()
        run(store, server, sleeps = 1)
        assertEquals("/device_token/refresh Bearer hatch_refresh:r1", server.calls.first())
        assertEquals("/fetch_vms Bearer a2", server.calls[1])
        val body = JSONObject(server.bodies.single())
        assertEquals("homelink-abcdef", body.getString("device_id"))
        assertEquals("jeton-sdk", body.getString("sdk_token"))
        assertEquals("r2", store.record!!.refreshToken)
        assertEquals(now, store.record!!.accessTokenSavedAt)
        assertEquals("homelink-abcdef", store.record!!.nodeId)
    }

    @Test fun aRevokedPairingIsDeleted() {
        val store = Store(record(savedAt = 0))
        val result = run(store, server(refresh = listOf(401 to "")), sleeps = 2)
        assertNull(store.record)
        assertTrue(ConnectionState.Revoked in result.states)
        assertTrue(result.connects.isEmpty())
    }

    @Test fun aTransientRefreshFailureKeepsTheCurrentToken() {
        val store = Store(record(savedAt = 0))
        val server = server(refresh = listOf(0 to ""))
        val result = run(store, server, sleeps = 2)
        assertEquals("a1", store.record!!.accessToken)
        assertEquals(2, result.connects.size)
        assertEquals("un seul essai tant que le délai n'est pas écoulé", 1, server.calls.count { "refresh" in it })
    }

    @Test fun rejectedAccessTokenForcesOneRefresh() {
        val store = Store(record())
        val server = server(fetch = listOf(401 to "", 200 to VM_LIST))
        val result = run(store, server, sleeps = 1)
        assertEquals("a2", store.record!!.accessToken)
        assertEquals(1, result.connects.size)
    }

    @Test fun repeatedRejectionDoesNotSpin() {
        val server = server(fetch = listOf(401 to ""))
        val result = run(Store(record()), server, sleeps = 2)
        assertEquals(listOf(15_000L, 15_000L), result.delays)
    }

    @Test fun failedForcedRefreshWaitsFiveMinutes() {
        val result = run(Store(record()), server(fetch = listOf(401 to ""), refresh = listOf(0 to "")), sleeps = 1)
        assertEquals(listOf(300_000L), result.delays)
    }

    @Test fun removalByMuseDeletesThePairing() {
        val store = Store(record())
        val result = run(store, server(), sleeps = 1, connect = { Outcome.UNPAIRED })
        assertNull(store.record)
        assertTrue(ConnectionState.Revoked in result.states)
    }

    @Test fun aNewPairingMadeMeanwhileIsNeverOverwritten() {
        val old = record(savedAt = 0)
        val store = Store(old)
        val server = server()
        val racing = object : PairingStore by store {
            override fun replace(expected: PairingRecord, updated: PairingRecord?): Boolean {
                store.record = record(refresh = "nouveau")
                return store.replace(expected, updated)
            }
        }
        lateinit var connection: MuseConnection
        connection = MuseConnection(
            racing, server.api, { _, _, _, _ -> throw IOException("x") }, { null }, { now }, { millis },
            sleeper = { connection.stop(); false },
        )
        connection.run()
        assertEquals("nouveau", store.record!!.refreshToken)
    }

    @Test fun connectionErrorsAreRetriedAndNeverFatal() {
        val result = run(Store(record()), server(), sleeps = 3, connect = { if (it == 0) throw IOException("coupé") else Outcome.CLOSED })
        assertEquals(3, result.delays.size)
    }

    /** Pairing erased during opening: the session must not stay in place. */
    @Test fun aDropWhileConnectingStopsTheNewSession() {
        var stops = 0
        lateinit var connection: MuseConnection
        connection = MuseConnection(
            Store(record()), server().api,
            { _, _, _, _ ->
                connection.drop()
                object : ActiveSession {
                    override fun run() = Outcome.STOPPED
                    override fun stop() { stops++ }
                }
            },
            { null }, { now }, { millis }, sleeper = { connection.stop(); false },
        )
        connection.run()
        assertTrue(stops >= 1)
    }

    @Test fun foreignNoiseHostIsNeverDialled() {
        val foreign = PairingRecord("a1", "r1", "u", "", "", "evil.example", now, "homelink-abcdef")
        val result = run(Store(foreign), server(), sleeps = 1)
        assertTrue(result.connects.single().first.startsWith("wss://hatch.metaaivm.com/"))
    }
}
