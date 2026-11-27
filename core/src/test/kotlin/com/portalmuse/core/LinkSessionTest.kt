package com.portalmuse.core

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LinkSessionTest {
    private val executor = Executors.newCachedThreadPool()
    private val registered = CountDownLatch(1)
    private val device = DeviceDescription("homelink-abcdef", "Portal Muse", "0.3.0", "portalmuse-portal-10", "Portal 10 (DIY)")
    private val commands = DeviceCommands({ JSONObject().put("version", "0.3.0") }, "# Portal Muse\n\nEcran.")

    @After fun stop() {
        executor.shutdownNow()
    }

    private fun accepting(reply: FakeVm.(JSONObject) -> Unit = {}) = FakeVm(onMessage = { message ->
        if (message.optString("method") == "link.register") send(JSONObject().put("id", message.getString("id")).put("ok", true))
        else reply(message)
    })

    private fun start(vm: FakeVm): Pair<LinkSession, CompletableFuture<Outcome>> {
        val session = LinkSession(vm, device, commands, executor, onRegistered = { registered.countDown() })
        return session to CompletableFuture.supplyAsync({ session.run() }, executor)
    }

    private fun CompletableFuture<Outcome>.outcome() = get(5, TimeUnit.SECONDS)
    private fun nextOf(vm: FakeVm): JSONObject = checkNotNull(vm.received.poll(5, TimeUnit.SECONDS)) { "aucun message" }

    @Test fun aReportGoesOnTheControlStreamOnlyOnceRegistered() {
        val vm = accepting()
        val (session, _) = start(vm)
        nextOf(vm)
        assertTrue(registered.await(5, TimeUnit.SECONDS))
        assertTrue(session.report(JSONObject().put("method", "link.error_report").put("report", JSONObject())))
        assertEquals("link.error_report", nextOf(vm).getString("method"))
        session.stop()
        assertFalse(session.report(JSONObject().put("method", "link.error_report")))
    }

    @Test fun registersOnTheGadgetServiceWithOnlyTwoCommands() {
        val vm = accepting()
        val (session, done) = start(vm)
        val message = nextOf(vm)
        assertTrue(registered.await(5, TimeUnit.SECONDS))
        val (service, request) = vm.requests.single()
        assertEquals(ServiceType.GADGET, service)
        assertEquals("POST", request.verb)
        assertEquals("/api/gadget/control", request.path)
        assertFalse(request.endBody)
        assertEquals("req", message.getString("type"))
        assertEquals("link.register", message.getString("method"))
        val params = message.getJSONObject("params")
        assertEquals("homelink-abcdef", params.getString("node_id"))
        assertEquals("gadget", params.getString("device_family"))
        assertEquals("linux", params.getString("platform"))
        assertEquals("portalmuse-portal-10", params.getString("model_id"))
        assertEquals("Portal 10 (DIY)", params.getJSONObject("metadata").getString("model_name"))
        assertEquals(false, params.getBoolean("is_wakeup_supported"))
        assertEquals(setOf("device.health", "device.guide"), params.getJSONObject("commands_v2").keys().asSequence().toSet())
        session.stop()
        assertEquals(Outcome.STOPPED, done.outcome())
        assertTrue(vm.closed)
    }

    @Test fun answersHealthAndGuideAndRefusesEverythingElse() {
        val vm = accepting()
        val (session, _) = start(vm)
        nextOf(vm)
        assertTrue(registered.await(5, TimeUnit.SECONDS))
        fun invoke(id: String, command: Any): JSONObject {
            vm.send(JSONObject().put("method", "link.invoke").put("id", id).put("command", command).put("params", JSONObject()))
            return nextOf(vm).also {
                assertEquals("link.result", it.getString("method"))
                assertEquals(id, it.getString("id"))
            }
        }
        assertEquals("0.3.0", invoke("1", "device.health").getJSONObject("payload").getString("version"))
        assertEquals("markdown", invoke("2", "device.guide").getJSONObject("payload").getString("format"))
        for ((i, name) in listOf("system.run", "file.read", "file.write", "").withIndex()) {
            assertFalse(name, invoke("x$i", name).getBoolean("ok"))
        }
        assertFalse(invoke("9", 12).getBoolean("ok"))
        session.stop()
    }

    @Test fun aMessageMaySpanSeveralBodyChunks() {
        val vm = FakeVm(onMessage = { message ->
            val reply = prefixed(JSONObject().put("id", message.getString("id")).put("ok", true))
            sendFrame(ServiceFrame.Response(controlStream, 200, reply.copyOfRange(0, 3)))
            sendFrame(ServiceFrame.BodyChunk(controlStream, reply.copyOfRange(3, reply.size)))
        })
        val (session, _) = start(vm)
        assertTrue(registered.await(5, TimeUnit.SECONDS))
        session.stop()
    }

    private fun endsWith(expected: Outcome, reaction: FakeVm.(JSONObject) -> Unit) {
        val vm = FakeVm(onMessage = reaction)
        assertEquals(expected, start(vm).second.outcome())
        assertTrue(vm.closed)
    }

    @Test fun unpairedByMuse() {
        for (event in listOf("link.unpaired", "node.unpaired")) {
            endsWith(Outcome.UNPAIRED) { send(JSONObject().put("event", event)) }
        }
    }

    @Test fun rejectedRegistrationIsForbidden() {
        endsWith(Outcome.FORBIDDEN) { send(JSONObject().put("id", it.getString("id")).put("ok", false)) }
        endsWith(Outcome.FORBIDDEN) { send(JSONObject().put("id", it.getString("id")).put("error", "non")) }
    }

    @Test fun controlStreamFailuresEndTheSession() {
        endsWith(Outcome.FORBIDDEN) { sendFrame(ServiceFrame.Response(controlStream, 403)) }
        endsWith(Outcome.CLOSED) { sendFrame(ServiceFrame.Response(controlStream, 500)) }
        endsWith(Outcome.CLOSED) { sendFrame(ServiceFrame.Reset(controlStream, 5, "x")) }
        endsWith(Outcome.CLOSED) { sendFrame(ServiceFrame.BodyChunk(controlStream, ByteArray(0), endBody = true)) }
        endsWith(Outcome.CLOSED) { close() }
    }

    @Test fun otherStreamsAreIgnored() {
        val vm = accepting()
        val (session, done) = start(vm)
        nextOf(vm)
        vm.sendFrame(ServiceFrame.Reset(vm.controlStream + 7, 5, "x"))
        assertTrue(registered.await(5, TimeUnit.SECONDS))
        assertFalse(done.isDone)
        session.stop()
    }

    @Test fun descriptionFollowsTheDeviceContract() {
        for (bad in listOf("Majuscule", "a b", "", "x".repeat(65))) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { DeviceDescription("n", "d", "v", bad, "m") }
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { DeviceDescription("n", "d", "v", "ok", "10\"") }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { DeviceCommands({ JSONObject() }, "x".repeat(4097)) }
    }
}
