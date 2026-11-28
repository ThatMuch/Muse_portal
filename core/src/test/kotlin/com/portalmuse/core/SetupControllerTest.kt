package com.portalmuse.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Main cases of linux/tests/test_ble_setup.py. */
class SetupControllerTest {
    private val v = pairingVector("community_app_v5")

    private class FakeTransport(var mtuValue: Int = 185) : SetupTransport {
        val messages = mutableListOf<String>()
        var disconnects = 0
        private val assembler = ChunkAssembler()
        override fun mtu() = mtuValue
        override fun sendPackets(packets: List<ByteArray>) {
            for (packet in packets) {
                assertTrue(packet.size <= maxOf(mtuValue - 3, 20))
                assembler.feed(packet)?.let { messages.add(String(it)) }
            }
        }
        override fun disconnect(delayMillis: Long) { disconnects++ }
    }

    private inner class Harness(var online: Boolean = true, failWith: String? = null, crash: Boolean = false) {
        val transport = FakeTransport()
        val saved = mutableListOf<Credentials>()
        var completed = false
        val pairing = makeDevice(v)
        val mobile = Mobile(v)
        val controller = SetupController(
            pairing = pairing,
            identity = Identity(v.getString("mac")),
            version = "0.1.0",
            transport = transport,
            network = object : SetupNetwork {
                override fun isOnline() = online
                override fun currentConnectionEntry() = JSONObject().put("ssid", "Maison").put("rssi", -40).put("secure", false)
            },
            provision = { credentials, commit ->
                if (failWith != null) throw ProvisionFailed(failWith)
                check(!crash) { "panne simulée" }
                if (!commit { saved.add(credentials) }) throw ProvisionFailed("error_storage")
            },
            onComplete = { completed = true },
            provisionExecutor = { it.run() },
        )

        fun send(message: JSONObject) = controller.handleMessage(message.toString().toByteArray())
        fun action(name: String) = send(JSONObject().put("action", name))
        fun last() = JSONObject(transport.messages.last())
        fun lastDecrypted() = mobile.open(last())
        fun pair() {
            send(hello(v))
            transport.messages.clear()
            send(clientFinishedRecord(v))
            mobile.txCounter = 1
            assertEquals("pairing_confirmed", lastDecrypted().getString("status"))
        }

        fun provisionCommand() = JSONObject()
            .put("action", "provision_v2").put("ssid", "Maison").put("password", "")
            .put("access_token", "acces-test").put("refresh_token", "rafraichir-test").put("token_type", "device")
            .put("username", "test").put("api_url", "https://hatch-api.meta.ai")
            .put("api_url_v2", "https://api.muse.ai").put("noise_host", "hatch.metaaivm.com")
    }

    @Test fun deviceInfoDescribesTheGadget() {
        val h = Harness()
        h.action("get_device_info")
        val info = h.last()
        assertEquals("device_info", info.getString("type"))
        assertEquals("homelink-000001", info.getString("node_id"))
        assertEquals("hatch_link", info.getString("model"))
        assertEquals("0.1.0", info.getString("version"))
        assertTrue(info.getBoolean("network_ready"))
    }

    @Test fun sensitivePlaintextActionsAreRefused() {
        val h = Harness()
        h.action("provision_v2")
        assertEquals("error_encryption_required", h.transport.messages.last())
    }

    @Test fun deviceInfoIsAnsweredInPlaintextAfterPairingStarted() {
        val h = Harness()
        h.pair()
        h.action("get_device_info")
        assertEquals("device_info", h.last().getString("type"))
    }

    @Test fun otherPlaintextIsIgnoredAfterPairingStarted() {
        val h = Harness()
        h.pair()
        val before = h.transport.messages.size
        h.action("wifi_scan")
        assertEquals(before, h.transport.messages.size)
    }

    @Test fun fullSetupSavesCredentialsAndReportsAuthOk() {
        val h = Harness()
        h.pair()
        h.send(h.mobile.seal(JSONObject().put("action", "wifi_scan")))
        val scan = h.lastDecrypted()
        assertEquals("wifi_scan_result", scan.getString("type"))
        assertEquals(1, (scan.get("networks") as JSONArray).length())

        h.transport.messages.clear()
        h.send(h.mobile.seal(h.provisionCommand()))
        val statuses = h.transport.messages.map { h.mobile.open(JSONObject(it)).getString("status") }
        assertEquals(listOf("wifi_connecting", "wifi_connected", "auth_ok"), statuses)
        assertEquals("acces-test", h.saved.single().accessToken)
        assertTrue(h.completed)
    }

    @Test fun credentialsNeverPrintTheirTokens() {
        val text = Credentials("secret-a", "secret-r", "u", "", "", "").toString()
        assertTrue("secret" !in text)
    }

    @Test fun foreignServerIsRefusedBeforeAnythingIsSaved() {
        val h = Harness()
        h.pair()
        for ((key, value) in listOf("noise_host" to "evil.example", "api_url_v2" to "https://api.muse.ai.evil.example", "api_url" to "http://api.muse.ai")) {
            h.send(h.mobile.seal(h.provisionCommand().put(key, value)))
            assertEquals("error_invalid_host", h.lastDecrypted().getString("status"))
        }
        assertTrue(h.saved.isEmpty())
    }

    @Test fun missingCredentialsAreRefused() {
        val h = Harness()
        h.pair()
        h.send(h.mobile.seal(h.provisionCommand().put("token_type", "user")))
        assertEquals("error_missing_credentials", h.lastDecrypted().getString("status"))
    }

    @Test fun offlineReportsWifiFailedAndKeepsTheSession() {
        val h = Harness()
        h.pair()
        h.online = false
        h.send(h.mobile.seal(h.provisionCommand()))
        assertEquals("wifi_failed", h.lastDecrypted().getString("status"))
        assertEquals(PairingState.PROVISIONING, h.pairing.state)
    }

    @Test fun provisioningFailureIsReportedAndDisconnects() {
        val h = Harness(failWith = "error_storage")
        h.pair()
        h.send(h.mobile.seal(h.provisionCommand()))
        assertEquals("error_storage", h.lastDecrypted().getString("status"))
        assertEquals(1, h.transport.disconnects)
    }

    /** On Android, an exception escaping a thread stops the whole application. */
    @Test fun unexpectedProvisioningCrashIsContained() {
        val h = Harness(crash = true)
        h.pair()
        h.send(h.mobile.seal(h.provisionCommand()))
        assertEquals("error_storage", h.lastDecrypted().getString("status"))
        assertEquals(1, h.transport.disconnects)
    }

    @Test fun badRecordDisconnects() {
        val h = Harness()
        h.send(hello(v))
        h.send(clientFinishedRecord(v).put("tag", b64(ByteArray(16))))
        assertEquals(1, h.transport.disconnects)
    }

    @Test fun disconnectClearsTheSession() {
        val h = Harness()
        h.pair()
        h.controller.onDisconnect()
        assertEquals(PairingState.IDLE, h.pairing.state)
        h.action("provision_v2")
        assertEquals("error_encryption_required", h.transport.messages.last())
    }

    /** Like the reference: without a session, this status may not go out in clear. */
    @Test fun invalidJsonIsReportedOnlyInsideASession() {
        val h = Harness()
        h.controller.handleMessage("pas du json".toByteArray())
        assertTrue(h.transport.messages.isEmpty())
        h.pair()
        for (raw in listOf("pas du json", "[1]", "{} suite")) {
            h.controller.handleMessage(raw.toByteArray())
            assertEquals("error_invalid_command", h.lastDecrypted().getString("status"))
        }
    }

    @Test fun identityFollowsTheConventions() {
        val identity = Identity("02:00:00:ab:cd:ef")
        assertEquals("homelink-abcdef", identity.nodeId)
        assertEquals("MuseGadgetABCDEF", identity.bleName)
        assertEquals("hatch-link:02:00:00:ab:cd:ef", identity.deviceId)
        val generated = Identity.generate { ByteArray(it) { 0xFF.toByte() } }
        assertEquals("fe:ff:ff:ff:ff:ff", generated.mac)
    }
}
