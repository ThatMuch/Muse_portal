// Kotlin port of muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0). Source:
// linux/src/musegadget/rtc/srtp.py
package com.portalmuse.core.rtc

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG_LENGTH = 10
private const val MAX_STREAMS = 16
private const val WINDOW = 64

class SrtpError(message: String) : Exception(message)

/**
 * SRTP and SRTCP with AES_CM_128_HMAC_SHA1_80 (RFC 3711), with the JOIN's 30 byte master key for both
 * directions; streams are told apart by their SSRC.
 */
class Srtp(keyAndSalt: ByteArray) {
    private class Keys(masterKey: ByteArray, masterSalt: ByteArray, label: Int) {
        val cipherKey = SecretKeySpec(derive(masterKey, masterSalt, label, 16), "AES")
        val authKey = SecretKeySpec(derive(masterKey, masterSalt, label + 1, 20), "HmacSHA1")
        val salt = derive(masterKey, masterSalt, label + 2, 14)
    }

    /** RFC 3711, 3.3.2: rejects a packet already seen or more than 64 old. */
    private class Replay {
        private var top = -1L
        private var seen = 0L

        fun check(index: Long) {
            when {
                top < 0 -> {
                    top = index
                    seen = 1
                }
                index > top -> {
                    val shift = index - top
                    seen = if (shift < WINDOW) (seen shl shift.toInt()) or 1 else 1
                    top = index
                }
                else -> {
                    val behind = top - index
                    if (behind >= WINDOW || (seen ushr behind.toInt()) and 1L == 1L) {
                        throw SrtpError("replayed or stale SRTP packet")
                    }
                    seen = seen or (1L shl behind.toInt())
                }
            }
        }
    }

    private class Roll(val counter: Long, val last: Int)

    private val rtp: Keys
    private val rtcp: Keys
    private val sent = HashMap<Int, Roll>()
    private val received = HashMap<Int, Roll>()
    private val sentControl = HashMap<Int, Int>()
    private val rtpReplay = HashMap<Int, Replay>()
    private val rtcpReplay = HashMap<Int, Replay>()

    init {
        if (keyAndSalt.size != 30) throw SrtpError("the SRTP key must be 30 bytes")
        val key = keyAndSalt.copyOfRange(0, 16)
        val salt = keyAndSalt.copyOfRange(16, 30)
        rtp = Keys(key, salt, 0)
        rtcp = Keys(key, salt, 3)
    }

    /** Encrypts or decrypts the bytes from [from], in counter mode. */
    private fun crypt(keys: Keys, ssrc: Int, index: Long, packet: ByteArray, from: Int): ByteArray {
        // IV = salt × 2^16, xor SSRC × 2^64, xor index × 2^16.
        val iv = keys.salt.copyOf(16)
        for (i in 0 until 4) iv[4 + i] = (iv[4 + i].toInt() xor (ssrc ushr (24 - 8 * i))).toByte()
        for (i in 0 until 6) iv[8 + i] = (iv[8 + i].toInt() xor (index ushr (40 - 8 * i)).toInt()).toByte()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys.cipherKey, IvParameterSpec(iv))
        val out = packet.copyOf()
        if (packet.size > from) cipher.doFinal(packet, from, packet.size - from).copyInto(out, from)
        return out
    }

    private fun tag(keys: Keys, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA1").run {
        init(keys.authKey)
        parts.forEach(::update)
        doFinal().copyOf(TAG_LENGTH)
    }

    private fun int(value: Long) = ByteBuffer.allocate(4).putInt(value.toInt()).array()

    private fun headerLength(packet: ByteArray, size: Int): Int {
        if (size < 12) throw SrtpError("RTP packet too short")
        var length = 12 + 4 * (packet[0].toInt() and 0x0F)
        if (packet[0].toInt() and 0x10 != 0) {
            if (size < length + 4) throw SrtpError("RTP extension too short")
            length += 4 + 4 * (ByteBuffer.wrap(packet).getShort(length + 2).toInt() and 0xFFFF)
        }
        if (size < length) throw SrtpError("RTP header too short")
        return length
    }

    private fun <T> bounded(map: HashMap<Int, T>, ssrc: Int, create: () -> T): T = map[ssrc] ?: create().also {
        if (map.size >= MAX_STREAMS) throw SrtpError("too many SRTP streams")
        map[ssrc] = it
    }

    @Synchronized
    fun protectRtp(packet: ByteArray): ByteArray {
        val header = headerLength(packet, packet.size)
        val buffer = ByteBuffer.wrap(packet)
        val sequence = buffer.getShort(2).toInt() and 0xFFFF
        val ssrc = buffer.getInt(8)
        val previous = sent[ssrc] ?: Roll(0, sequence)
        var counter = previous.counter
        if (sequence < previous.last && previous.last - sequence > 0x8000) counter = (counter + 1) and 0xFFFFFFFFL
        sent[ssrc] = Roll(counter, sequence)
        val body = crypt(rtp, ssrc, (counter shl 16) or sequence.toLong(), packet, header)
        return body + tag(rtp, body, int(counter))
    }

    @Synchronized
    fun unprotectRtp(packet: ByteArray): ByteArray {
        if (packet.size < 12 + TAG_LENGTH) throw SrtpError("SRTP packet too short")
        val body = packet.copyOf(packet.size - TAG_LENGTH)
        val header = headerLength(body, body.size)
        val buffer = ByteBuffer.wrap(body)
        val sequence = buffer.getShort(2).toInt() and 0xFFFF
        val ssrc = buffer.getInt(8)
        val previous = received[ssrc] ?: Roll(0, sequence)
        var guess = previous.counter
        if (previous.last < 0x8000) {
            if (sequence - previous.last > 0x8000) guess = (guess - 1) and 0xFFFFFFFFL
        } else if (previous.last - 0x8000 > sequence) {
            guess = (guess + 1) and 0xFFFFFFFFL
        }
        if (!MessageDigest.isEqual(tag(rtp, body, int(guess)), packet.copyOfRange(body.size, packet.size))) {
            throw SrtpError("SRTP packet not authentic")
        }
        bounded(rtpReplay, ssrc, ::Replay).check((guess shl 16) or sequence.toLong())
        val newer = guess == previous.counter && sequence > previous.last ||
            guess == ((previous.counter + 1) and 0xFFFFFFFFL)
        if (newer || ssrc !in received) received[ssrc] = Roll(guess, sequence)
        return crypt(rtp, ssrc, (guess shl 16) or sequence.toLong(), body, header)
    }

    @Synchronized
    fun protectRtcp(packet: ByteArray): ByteArray {
        if (packet.size < 8) throw SrtpError("RTCP packet too short")
        val ssrc = ByteBuffer.wrap(packet).getInt(4)
        // Like libsrtp, the first SRTCP index sent is 1.
        val index = ((sentControl[ssrc] ?: 0) + 1) and 0x7FFFFFFF
        sentControl[ssrc] = index
        val body = crypt(rtcp, ssrc, index.toLong(), packet, 8) + int(0x80000000L or index.toLong())
        return body + tag(rtcp, body)
    }

    companion object {
        internal fun derive(masterKey: ByteArray, masterSalt: ByteArray, label: Int, length: Int): ByteArray {
            // x = salt xor (label × 2^48), then the keystream from x × 2^16.
            val iv = masterSalt.copyOf(16)
            iv[7] = (iv[7].toInt() xor label).toByte()
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(ByteArray(length))
        }
    }
}
