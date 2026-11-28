package com.portalmuse.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NoiseXXTest {
    private val vector = resource("noise_xx_cacophony.json")
    private fun field(name: String) = hex(vector.getString(name))
    private fun message(table: String, i: Int, key: String) = hex(vector.getJSONArray(table).getJSONObject(i).getString(key))

    private fun initiator(prologue: ByteArray = field("prologue")) = NoiseXXInitiator(
        prologue = prologue,
        generateEphemeral = { X25519KeyPair.fromPrivate(field("init_ephemeral")) },
        generateStatic = { X25519KeyPair.fromPrivate(field("init_static")) },
    )

    @Test fun cacophonyVectorByteForByte() {
        val init = initiator()
        val msg1 = init.writeMessage1(message("handshake", 0, "payload"))
        assertEquals(vector.getJSONArray("handshake").getJSONObject(0).getString("ciphertext"), msg1.toHex())

        val payload2 = init.readMessage2(message("handshake", 1, "ciphertext"))
        assertArrayEquals(message("handshake", 1, "payload"), payload2)
        assertEquals(X25519KeyPair.fromPrivate(field("resp_static")).publicKey.toHex(), init.remoteStaticKey()!!.toHex())

        val msg3 = init.writeMessage3(message("handshake", 2, "payload"))
        assertEquals(vector.getJSONArray("handshake").getJSONObject(2).getString("ciphertext"), msg3.toHex())
        assertEquals(vector.getString("handshake_hash"), init.handshakeHash().toHex())

        val (send, receive) = init.split()
        val noAd = ByteArray(0)
        // Transport messages: the responder starts, then they alternate.
        assertArrayEquals(message("transport", 0, "payload"), receive.decryptWithAd(noAd, message("transport", 0, "ciphertext")))
        assertArrayEquals(message("transport", 1, "ciphertext"), send.encryptWithAd(noAd, message("transport", 1, "payload")))
        assertArrayEquals(message("transport", 2, "payload"), receive.decryptWithAd(noAd, message("transport", 2, "ciphertext")))
    }

    /** RFC 7748, section 6.1. */
    @Test fun x25519Rfc7748() {
        val alice = X25519KeyPair.fromPrivate(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        val bob = X25519KeyPair.fromPrivate(hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"))
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", alice.publicKey.toHex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", bob.publicKey.toHex())
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, X25519.dh(alice, bob.publicKey).toHex())
        assertEquals(shared, X25519.dh(bob, alice.publicKey).toHex())
    }

    @Test fun lowOrderPointsAreRejected() {
        val key = X25519KeyPair.generate()
        for (point in listOf(ByteArray(32), ByteArray(32).also { it[0] = 1 })) {
            assertThrows(NoiseError::class.java) { X25519.dh(key, point) }
        }
    }

    @Test fun tamperedMessage2KillsTheHandshake() {
        val init = initiator()
        init.writeMessage1(message("handshake", 0, "payload"))
        val bad = message("handshake", 1, "ciphertext").also { it[it.size - 1] = (it.last() + 1).toByte() }
        assertThrows(NoiseError::class.java) { init.readMessage2(bad) }
        assertThrows(NoiseError::class.java) { init.writeMessage3() }
    }

    @Test fun wrongPrologueFailsAuthentication() {
        val init = initiator(prologue = ByteArray(0))
        init.writeMessage1(message("handshake", 0, "payload"))
        assertThrows(NoiseError::class.java) { init.readMessage2(message("handshake", 1, "ciphertext")) }
    }

    @Test fun shortMessage2IsRejected() {
        val init = initiator()
        init.writeMessage1()
        assertThrows(NoiseError::class.java) { init.readMessage2(ByteArray(95)) }
    }

    @Test fun callsOutOfOrderAreRejected() {
        assertThrows(NoiseError::class.java) { NoiseXXInitiator().writeMessage3() }
        assertThrows(NoiseError::class.java) { NoiseXXInitiator().split() }
    }

    @Test fun cipherStateIsPoisonedAfterAFailedDecrypt() {
        val state = CipherState(ByteArray(32) { 7 })
        assertThrows(NoiseError::class.java) { state.decryptWithAd(ByteArray(0), ByteArray(32)) }
        assertThrows(NoiseError::class.java) { state.encryptWithAd(ByteArray(0), ByteArray(1)) }
    }

    @Test fun defaultInitiatorSendsOnlyItsEphemeralKey() {
        assertEquals(32, NoiseXXInitiator().writeMessage1().size)
    }
}
