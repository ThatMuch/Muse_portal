package com.portalmuse.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

class PairingTest {
    private val app = pairingVector("community_app_v5")
    private val gadget = pairingVector("community_app_gadget_v5")

    private fun confirmed(clock: FakeClock = FakeClock(), token: String? = null): Pair<PairingSession, Int> {
        val device = makeDevice(app, clock, token)
        device.handleHello(hello(app))
        val command = JSONObject(device.decrypt(clientFinishedRecord(app)))
        return device to device.handleClientFinished(command)
    }

    @Test fun transcriptMatchesEveryVector() {
        for (v in pairingVectors()) {
            val transcript = Pairing.buildTranscript(
                community = v.getBoolean("community"),
                authEpoch = v.getInt("pairing_auth_epoch"),
                policy = v.getString("pairing_policy"),
                deviceId = v.getString("device_id"),
                nodeId = v.getString("node_id"),
                mac = v.getString("mac"),
                firmwareVersion = v.getString("firmware_version"),
                mobilePub = v.getString("mobile_pub"),
                devicePub = v.getString("device_pub"),
                mobileNonce = v.getString("mobile_nonce"),
                deviceNonce = v.getString("device_nonce"),
            )
            assertEquals(v.getString("name"), v.getString("transcript"), transcript)
            val digest = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray())
            assertEquals(v.getString("name"), v.getString("transcript_hash"), b64(digest))
        }
    }

    @Test fun keyScheduleMatchesEveryVector() {
        for (v in pairingVectors()) {
            val keys = Pairing.deriveSessionKeys(
                hex(v.getString("ecdh_secret_hex")),
                unb64(v.getString("mobile_nonce")),
                unb64(v.getString("device_nonce")),
                unb64(v.getString("transcript_hash")),
            )
            assertEquals(v.getString("mobile_tx_key_hex"), keys.mobileTx.toHex())
            assertEquals(v.getString("mobile_rx_key_hex"), keys.mobileRx.toHex())
            assertEquals(v.getString("session_id"), b64(keys.sessionId))
        }
    }

    @Test fun ecdhMatchesEveryVectorFromBothSides() {
        for (v in pairingVectors()) {
            val device = P256.privateKeyFromScalar(BigInteger(v.getString("device_private_scalar_hex"), 16))
            val mobile = P256.privateKeyFromScalar(BigInteger(v.getString("mobile_private_scalar_hex"), 16))
            val expected = v.getString("ecdh_secret_hex")
            assertEquals(expected, P256.ecdh(device, unb64(v.getString("mobile_pub"))).toHex())
            assertEquals(expected, P256.ecdh(mobile, unb64(v.getString("device_pub"))).toHex())
        }
    }

    @Test fun generatedKeysAgree() {
        val a = P256.generate()
        val b = P256.generate()
        assertEquals(65, a.publicPoint.size)
        assertEquals(P256.ecdh(a.privateKey, b.publicPoint).toHex(), P256.ecdh(b.privateKey, a.publicPoint).toHex())
    }

    @Test fun offCurvePointIsRejected() {
        val point = unb64(app.getString("mobile_pub")).also { it[64] = (it[64] + 1).toByte() }
        val message = hello(app).put("mobile_pub", b64(point))
        val error = assertThrows(PairingError::class.java) { makeDevice(app).handleHello(message) }
        assertEquals("error_pairing_invalid_hello", error.status)
    }

    @Test fun helloProducesTheVectorSession() {
        for (v in listOf(app, gadget)) {
            val ready = makeDevice(v).handleHello(hello(v))
            assertEquals("pairing_ready", ready.getString("type"))
            assertEquals(v.getString("node_id"), ready.getString("node_id"))
            assertEquals("hatch_link", ready.getString("model"))
            assertEquals(v.getString("device_pub"), ready.getString("device_pub"))
            assertEquals(v.getString("transcript_hash"), ready.getString("transcript_hash"))
            assertEquals(v.getString("session_id"), ready.getString("session_id"))
        }
    }

    @Test fun deviceInfoAdvertisesCommunityAppConfirmation() {
        val info = makeDevice(app).deviceInfo()
        assertEquals(
            setOf("device_id", "mac", "model", "pairing_protocol", "pairing_auth", "pairing_auth_epoch", "pairing_policy"),
            info.keys().asSequence().toSet(),
        )
        assertEquals("hatch_link", info.getString("model"))
        assertEquals(5, info.getInt("pairing_protocol"))
        assertEquals("none", info.getString("pairing_auth"))
        assertEquals("confirm_app", info.getString("pairing_policy"))
    }

    @Test fun invalidHellosAreRejected() {
        val bad = listOf(
            hello(app).put("version", 4),
            hello(app).put("version", "5"),
            hello(app).put("version", true),
            hello(app).put("pairing_auth", "fleet_ecdsa_p256_v1"),
            hello(app).put("pairing_policy", "confirm_press"),
            hello(app).put("mobile_pub", "AAAA"),
            hello(app).put("mobile_nonce", "AAAA"),
            hello(app).put("mobile_nonce", app.getString("mobile_nonce") + "=="),
        )
        for (message in bad) {
            val device = makeDevice(app)
            assertThrows(message.toString(), PairingError::class.java) { device.handleHello(message) }
            assertEquals(PairingState.IDLE, device.state)
        }
    }

    @Test fun clientFinishedConfirmsAndSdkTokenIsSealed() {
        val (device, generation) = confirmed(token = "jeton-de-test")
        assertNotEquals(0, generation)
        assertTrue(device.confirmed)
        val message = Mobile(app).open(device.encryptStatus("pairing_confirmed", generation)!!)
        assertEquals("pairing_confirmed", message.getString("status"))
        assertEquals("jeton-de-test", message.getString("sdk_token"))
    }

    @Test fun sdkTokenOnlyTravelsWithPairingConfirmed() {
        val (device, generation) = confirmed(token = "jeton-de-test")
        val mobile = Mobile(app)
        assertFalse(mobile.open(device.encryptStatus("wifi_connected", generation)!!).has("sdk_token"))
    }

    @Test fun recordCountersIncrease() {
        val (device, generation) = confirmed()
        val first = device.encryptStatus("wifi_connecting", generation)!!
        val second = device.encryptStatus("wifi_connected", generation)!!
        assertEquals("0", first.getString("counter"))
        assertEquals("1", second.getString("counter"))
        val mobile = Mobile(app).apply { txCounter = 1 }
        assertEquals("wifi_scan", JSONObject(device.decrypt(mobile.seal(JSONObject().put("action", "wifi_scan")))).getString("action"))
    }

    @Test fun replayClearsTheSession() {
        val (device, _) = confirmed()
        assertThrows(PairingError::class.java) { device.decrypt(clientFinishedRecord(app)) }
        assertEquals(PairingState.IDLE, device.state)
    }

    @Test fun badTagSkippedCounterAndWrongSessionClearTheSession() {
        val tampered = listOf(
            clientFinishedRecord(app).put("tag", b64(ByteArray(16))),
            clientFinishedRecord(app).put("counter", "1"),
            clientFinishedRecord(app).put("counter", "18446744073709551616"),
            clientFinishedRecord(app).put("counter", "-0"),
            clientFinishedRecord(app).put("session_id", "AAAA"),
        )
        for (record in tampered) {
            val device = makeDevice(app)
            device.handleHello(hello(app))
            assertThrows(record.toString(), PairingError::class.java) { device.decrypt(record) }
            assertEquals(PairingState.IDLE, device.state)
        }
    }

    @Test fun clientFinishedMustBeExact() {
        val device = makeDevice(app)
        device.handleHello(hello(app))
        device.decrypt(clientFinishedRecord(app))
        val extra = JSONObject().put("action", "pairing_client_finished").put("x", 1)
        assertEquals(0, device.handleClientFinished(extra))
        assertEquals(PairingState.IDLE, device.state)
    }

    @Test fun sessionsExpire() {
        val clock = FakeClock()
        val waiting = makeDevice(app, clock)
        waiting.handleHello(hello(app))
        clock.now += 60_001
        assertThrows(PairingError::class.java) { waiting.decrypt(clientFinishedRecord(app)) }

        val (device, generation) = confirmed(clock)
        clock.now += 120_001
        assertEquals(PairingState.IDLE, device.state)
        assertNull(device.encryptStatus("auth_ok", generation))
        assertTrue("la génération survit à l'expiration", device.isCurrent(generation))
    }

    @Test fun provisioningCommitsOnlyForTheCurrentGeneration() {
        val (device, confirmedGeneration) = confirmed()
        val generation = device.markProvisioning()
        assertNotEquals(0, generation)
        assertFalse(device.commitProvisioning(confirmedGeneration) { true })
        assertTrue(device.commitProvisioning(generation) { true })
        device.reset()
        assertFalse(device.commitProvisioning(generation) { true })
        assertNull(device.encryptStatus("auth_ok", generation))
    }
}
