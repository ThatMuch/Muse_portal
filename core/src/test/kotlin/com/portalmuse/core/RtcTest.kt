package com.portalmuse.core

import com.portalmuse.core.rtc.H264Depacketizer
import com.portalmuse.core.rtc.Join
import com.portalmuse.core.rtc.JoinSecrets
import com.portalmuse.core.rtc.Rtp
import com.portalmuse.core.rtc.Srtp
import com.portalmuse.core.rtc.SrtpError
import com.portalmuse.core.rtc.Stun
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

private fun rtcHex(text: String) = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
private fun ByteArray.rtcHexed() = joinToString("") { "%02x".format(it) }

class RtcTest {
    private val sdk = JSONObject(javaClass.getResource("/rtc_sdk.json")!!.readText())
    private val join = sdk.getJSONObject("join")
    private val stun = sdk.getJSONObject("stun")

    @Test fun theJoinOfferMatchesTheSdkByteForByte() {
        val secrets = JoinSecrets(
            "cufr", "server-ufrag-016", "client-pwd-" + "c".repeat(13), "server-pwd-" + "s".repeat(13),
            ByteArray(30) { it.toByte() }, "sha-256 AA:BB", "SERVER KEY PEM", "SERVER CERT PEM",
            join.getString("session"), join.getInt("transaction"),
        )
        assertEquals(join.getString("offer"), Join.build("edge.example", 3478, 8, "thread-7", secrets).payload.rtcHexed())
    }

    @Test fun theJoinAnswerIsReadAndBadOnesAreRefused() {
        val response = rtcHex(join.getString("response"))
        val answer = Join.parseResponse(response)
        assertEquals("answer-ufrag", answer.serverUfrag)
        assertEquals("192.0.2.7", answer.ipv4)
        assertEquals("", answer.ipv6)
        for (cut in listOf(0, 3, response.size - 4)) {
            assertThrows(IllegalArgumentException::class.java) { Join.parseResponse(response.copyOf(cut)) }
        }
        assertThrows(IllegalArgumentException::class.java) { Join.parseResponse(ByteArray(20_000)) }
    }

    @Test fun theDtlsIdentityIsAValidSelfSignedCertificate() {
        val identity = Join.identity()
        fun der(pem: String) = Base64.getMimeDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(der(identity.certificatePem).inputStream()) as X509Certificate
        certificate.checkValidity()
        certificate.verify(certificate.publicKey)
        assertEquals("CN=musegadget", certificate.subjectX500Principal.name)
        assertNotNull(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der(identity.keyPem))))
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        assertEquals("sha-256 " + digest.joinToString(":") { "%02X".format(it) }, identity.fingerprint)
        assertEquals(30, Join.newSecrets().srtpKey.size)
    }

    @Test fun stunRequestsAndAnswersMatchTheSdk() {
        val transaction = rtcHex(stun.getString("transaction"))
        for ((name, nominate) in listOf("check" to false, "nominate" to true)) {
            val request = Stun.bindingRequest(
                "srv:cli", "secret-password", 1862270975, 0x0102030405060708, nominate, transaction,
            )
            assertEquals(stun.getString(name), request.packet.rtcHexed())
        }
        val success = rtcHex(stun.getString("success"))
        assertTrue(Stun.isSuccess(success, transaction, "secret-password"))
        assertFalse(Stun.isSuccess(success, transaction, "autre"))
        assertFalse(Stun.isSuccess(success, ByteArray(12), "secret-password"))
        assertFalse(Stun.isSuccess(success.copyOf(30), transaction, "secret-password"))
    }

    // Vectors: RFC 3711 B.3, then libsrtp's answers taken from tests/test_rtc_srtp.py.
    @Test fun srtpKeyDerivationMatchesTheRfc() {
        val key = rtcHex("E1F97A0D3E018BE0D64FA32C06DE4139")
        val salt = rtcHex("0EC675AD498AFEEBB6960B3AABE6")
        assertEquals("c61e7a93744f39ee10734afe3ff7a087", Srtp.derive(key, salt, 0, 16).rtcHexed())
        assertEquals("30cbbc08863d8c85d49db34a9ae1", Srtp.derive(key, salt, 2, 14).rtcHexed())
    }

    private val key = ByteArray(30) { it.toByte() }
    private fun rtp(sequence: Int, ssrc: Int = 201, payload: ByteArray = ByteArray(100) { 7 }) =
        Rtp.build(127, sequence, sequence * 3000L, ssrc, payload)

    @Test fun srtpMatchesLibsrtp() {
        val packet = Rtp.build(111, 4660, 960L * 7, 100, "opus-payload-0123456789".toByteArray())
        val protected = "806f123400001a40000000649c5fa8537a5563ed445b2da0c83e0b8d1fa94d9f7f3e264882c67696106f276ab8"
        assertEquals(protected, Srtp(key).protectRtp(packet).rtcHexed())
        assertArrayEquals(packet, Srtp(key).unprotectRtp(rtcHex(protected)))
        val pli = Rtp.pictureLoss(101, 201)
        assertEquals("81ce000200000065270b4d4180000001179c9c93e63b478aa918", Srtp(key).protectRtcp(pli).rtcHexed())
    }

    @Test fun srtpSurvivesTheSequenceWrapAndRefusesForgeries() {
        val sender = Srtp(key)
        val receiver = Srtp(key)
        for (sequence in listOf(65530, 65531, 65535, 0, 1, 2)) {
            assertArrayEquals(rtp(sequence), receiver.unprotectRtp(sender.protectRtp(rtp(sequence))))
        }
        val tampered = sender.protectRtp(rtp(7)).also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertThrows(SrtpError::class.java) { receiver.unprotectRtp(tampered) }
        val first = sender.protectRtp(rtp(10))
        receiver.unprotectRtp(first)
        assertThrows(SrtpError::class.java) { receiver.unprotectRtp(first) }
        val late = (11 until 100).map { sender.protectRtp(rtp(it)) }
        receiver.unprotectRtp(late.last())
        receiver.unprotectRtp(late[late.size - 2])
        assertThrows(SrtpError::class.java) { receiver.unprotectRtp(late.first()) }
        assertThrows(SrtpError::class.java) { Srtp(ByteArray(16)) }
    }

    private fun video(sequence: Int, payload: ByteArray, marker: Boolean) = Rtp.parse(
        Rtp.build(127, sequence, 0, 201, payload).also { if (marker) it[1] = (it[1].toInt() or 0x80).toByte() },
    )

    @Test fun rtpIsParsedWithExtensionAndPadding() {
        val packet = Rtp.parse(Rtp.build(111, 65535, 0xFFFFFFFFL, 200, byteArrayOf(1, 2, 3)))
        assertEquals(listOf(111, 65535, 200), listOf(packet.payloadType, packet.sequence, packet.ssrc))
        assertEquals(0xFFFFFFFFL, packet.timestamp)
        val extended = ByteBuffer.allocate(24).put(0xB0.toByte()).put(111).putShort(1).putInt(2).putInt(200)
            .putShort(0x10).putShort(1).putInt(0).put(byteArrayOf(9, 8, 0, 2)).array()
        assertArrayEquals(byteArrayOf(9, 8), Rtp.parse(extended).payload)
        assertThrows(IllegalArgumentException::class.java) { Rtp.parse(ByteArray(8)) }
    }

    @Test fun h264IsReassembledAndWaitsForAKeyframeAfterALoss() {
        val depacketizer = H264Depacketizer()
        // An ordinary picture before any key frame is not shown.
        assertNull(depacketizer.push(video(1, byteArrayOf(0x41, 1), true)))
        // SPS and PPS grouped (STAP-A), then a key frame in two fragments.
        assertNull(depacketizer.push(video(2, byteArrayOf(24, 0, 2, 0x67, 5, 0, 2, 0x68, 6), false)))
        assertNull(depacketizer.push(video(3, byteArrayOf(0x7C, 0x85.toByte(), 1, 2), false)))
        val unit = depacketizer.push(video(4, byteArrayOf(0x7C, 0x45, 3), true))!!
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 5, 0, 0, 0, 1, 0x68, 6, 0, 0, 0, 1, 0x65, 1, 2, 3), unit)
        assertFalse(depacketizer.needsKeyframe)
        assertNotNull(depacketizer.push(video(5, byteArrayOf(0x41, 1), true)))
        // A packet is missing: nothing more until the next key frame.
        assertNull(depacketizer.push(video(7, byteArrayOf(0x41, 1), true)))
        assertTrue(depacketizer.needsKeyframe)
        assertNull(depacketizer.push(video(8, byteArrayOf(0x41, 1), true)))
        assertNotNull(depacketizer.push(video(9, byteArrayOf(0x65, 1), true)))
    }
}
