package com.portalmuse.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothTest {
    private class Radio : BleRadio {
        var broken: String? = null
        val connected = mutableListOf<String>()
        val written = mutableListOf<ByteArray>()
        var lastTarget: BleTarget? = null
        override fun problem() = broken
        override fun scan(durationMillis: Long, active: Boolean, name: String?, limit: Int) = listOf(
            BleDevice("AA:BB:CC:DD:EE:01", -80, null, false),
            BleDevice("AA:BB:CC:DD:EE:02", -40, "Lampe", true, listOf("180f"), byteArrayOf(1, 2)),
        )
        override fun nameOf(address: String) = "Lam\u0007pe"
        override fun connect(address: String, timeoutMillis: Long) = BleConnection(1, address, 23).also { connected.add(address) }
        override fun connections() = connected.mapIndexed { i, a -> BleConnection(i + 1, a, 23) }
        override fun disconnect(conn: Int) {
            if (conn != 1) throw BleError("not_connected", "no such connection")
        }
        override fun discover(conn: Int) = listOf(BleCharacteristic("180f", "2a19", 12, listOf("read", "notify")))
        override fun read(conn: Int, target: BleTarget) = BleValue(12, "ok".toByteArray()).also { lastTarget = target }
        override fun write(conn: Int, target: BleTarget, data: ByteArray, withResponse: Boolean?) = 12.also { written.add(data) }
        override fun subscribe(conn: Int, target: BleTarget, durationMillis: Long) =
            listOf(BleNotification(5, byteArrayOf(0x64)))
    }

    private class Approved : ApprovedDevices {
        val set = mutableSetOf<String>()
        override fun contains(address: String) = address in set
        override fun add(address: String, name: String?) { set.add(address) }
    }

    private val radio = Radio()
    private val approved = Approved()
    private var answer = AskResult.APPROVED
    private val questions = mutableListOf<Question>()
    private val commands = BluetoothCommands(radio, approved) { questions.add(it); answer }

    private fun run(name: String, params: JSONObject = JSONObject()) = commands.run(name, params)!!
    private fun payload(name: String, params: JSONObject = JSONObject()): JSONObject {
        val result = run(name, params)
        assertTrue(result.toString(), result.getBoolean("ok"))
        return result.getJSONObject("payload")
    }
    private fun params(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }

    @Test fun theCommandsFollowTheSdkNamesAndNeverTouchThePortalRadio() {
        assertEquals(
            setOf("ble.scan", "ble.connect", "ble.state", "ble.disconnect", "ble.discover", "ble.read", "ble.write", "ble.subscribe"),
            commands.specs().keys,
        )
        assertNull(commands.run("ble.power", JSONObject()))
        assertNull(commands.run("ble.advertise", JSONObject()))
    }

    @Test fun aScanListsTheStrongestFirst() {
        val devices = payload("ble.scan").getJSONArray("devices")
        assertEquals("Lampe", devices.getJSONObject(0).getString("name"))
        assertEquals("0102", devices.getJSONObject(0).getString("manufacturer_data"))
        assertFalse(devices.getJSONObject(1).has("name"))
        assertFalse(run("ble.scan", params("duration_ms" to 99_999)).getBoolean("ok"))
    }

    @Test fun aNewDeviceNeedsTheOwnersTapOnce() {
        val address = params("address" to "aa:bb:cc:dd:ee:02")
        for (refusal in listOf(AskResult.REJECTED, AskResult.NO_ANSWER, AskResult.BUSY)) {
            answer = refusal
            assertTrue(run("ble.connect", address).getString("error").startsWith("not_approved"))
        }
        assertTrue(radio.connected.isEmpty())
        answer = AskResult.APPROVED
        assertEquals(1, payload("ble.connect", address).getInt("conn"))
        assertTrue("l'adresse est montrée", "AA:BB:CC:DD:EE:02" in questions.last().details!!)
        assertTrue("le nom est nettoyé", "Lampe" in questions.last().details!!)
        payload("ble.connect", address)
        assertEquals(4, questions.size)
        assertFalse(run("ble.connect", params("address" to "pas une adresse")).getBoolean("ok"))
    }

    @Test fun characteristicsAreReadWrittenAndFollowed() {
        assertEquals("2a19", payload("ble.discover", params("conn" to 1)).getJSONArray("characteristics").getJSONObject(0).getString("uuid"))
        val read = payload("ble.read", params("conn" to 1, "uuid" to "2A19"))
        assertEquals("6f6b", read.getString("hex"))
        assertEquals("ok", read.getString("text"))
        assertEquals(BleTarget(null, "2a19", null), radio.lastTarget)
        payload("ble.write", params("conn" to 1, "handle" to 12, "hex" to "01:ff"))
        assertArrayEquals(byteArrayOf(1, -1), radio.written.single())
        assertEquals("64", payload("ble.subscribe", params("conn" to 1, "handle" to 12)).getJSONArray("notifications").getJSONObject(0).getString("hex"))
        for (bad in listOf(
            params("conn" to 1, "handle" to 12), params("conn" to 1, "handle" to 12, "hex" to "0"),
            params("conn" to 1, "handle" to 12, "hex" to "01", "text" to "a"), params("conn" to 1, "hex" to "01"),
            params("handle" to 12, "hex" to "01"),
        )) {
            assertFalse(bad.toString(), run("ble.write", bad).getBoolean("ok"))
        }
        assertEquals("not_connected: no such connection", run("ble.disconnect", params("conn" to 2)).getString("error"))
    }

    @Test fun nothingRunsWhileBluetoothIsUnusable() {
        radio.broken = "Bluetooth is off"
        assertEquals("unavailable: Bluetooth is off", run("ble.scan").getString("error"))
    }
}
