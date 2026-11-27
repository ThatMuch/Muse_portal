// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Source : linux/src/musegadget/rtc/stun.py
package com.portalmuse.core.rtc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Les vérifications de connexion et de consentement ICE (RFC 5389 et 8445). */
object Stun {
    private const val MAGIC = 0x2112A442
    private const val BINDING_REQUEST = 0x0001
    const val BINDING_SUCCESS = 0x0101
    private const val USERNAME = 0x0006
    private const val MESSAGE_INTEGRITY = 0x0008
    private const val PRIORITY = 0x0024
    private const val USE_CANDIDATE = 0x0025
    private const val FINGERPRINT = 0x8028
    private const val ICE_CONTROLLING = 0x802A
    private val random = SecureRandom()

    class Request(val packet: ByteArray, val transaction: ByteArray)

    fun isStun(data: ByteArray) = data.size >= 20 && (data[0].toInt() and 0xFF) < 2 && ByteBuffer.wrap(data, 4, 4).int == MAGIC

    private fun attribute(kind: Int, value: ByteArray): ByteArray {
        val padded = value.size + (4 - value.size % 4) % 4
        return ByteBuffer.allocate(4 + padded).putShort(kind.toShort()).putShort(value.size.toShort()).put(value).array()
    }

    private fun header(kind: Int, length: Int, transaction: ByteArray) = ByteBuffer.allocate(20)
        .putShort(kind.toShort()).putShort(length.toShort()).putInt(MAGIC).put(transaction).array()

    private fun hmac(password: String, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA1").run {
        init(SecretKeySpec(password.toByteArray(), "HmacSHA1"))
        doFinal(data)
    }

    /** Une vérification en rôle « contrôlant » ; [nominate] désigne le chemin retenu. */
    fun bindingRequest(
        username: String, password: String, priority: Int, tieBreaker: Long, nominate: Boolean,
        transaction: ByteArray = ByteArray(12).also(random::nextBytes),
    ): Request {
        val attributes = ByteArrayOutputStream()
        attributes.write(attribute(USERNAME, username.toByteArray()))
        attributes.write(attribute(PRIORITY, ByteBuffer.allocate(4).putInt(priority).array()))
        attributes.write(attribute(ICE_CONTROLLING, ByteBuffer.allocate(8).putLong(tieBreaker).array()))
        if (nominate) attributes.write(attribute(USE_CANDIDATE, ByteArray(0)))
        val signed = header(BINDING_REQUEST, attributes.size() + 24, transaction) + attributes.toByteArray()
        attributes.write(attribute(MESSAGE_INTEGRITY, hmac(password, signed)))
        val checked = header(BINDING_REQUEST, attributes.size() + 8, transaction) + attributes.toByteArray()
        val crc = CRC32().apply { update(checked) }.value.toInt() xor 0x5354554E
        attributes.write(attribute(FINGERPRINT, ByteBuffer.allocate(4).putInt(crc).array()))
        return Request(
            header(BINDING_REQUEST, attributes.size(), transaction) + attributes.toByteArray(), transaction,
        )
    }

    /** Vrai pour une réponse de succès authentifiée à [transaction]. */
    fun isSuccess(data: ByteArray, transaction: ByteArray, password: String): Boolean {
        if (!isStun(data) || !data.copyOfRange(8, 20).contentEquals(transaction)) return false
        val buffer = ByteBuffer.wrap(data)
        val kind = buffer.getShort(0).toInt() and 0xFFFF
        val length = buffer.getShort(2).toInt() and 0xFFFF
        if (kind != BINDING_SUCCESS || data.size < 20 + length) return false
        var offset = 20
        while (offset + 4 <= 20 + length) {
            val attribute = buffer.getShort(offset).toInt() and 0xFFFF
            val size = buffer.getShort(offset + 2).toInt() and 0xFFFF
            if (attribute == MESSAGE_INTEGRITY) {
                if (offset + 24 > data.size) return false
                val prefix = data.copyOfRange(0, offset)
                ByteBuffer.wrap(prefix).putShort(2, (offset - 20 + 24).toShort())
                return MessageDigest.isEqual(hmac(password, prefix), data.copyOfRange(offset + 4, offset + 24))
            }
            offset += 4 + size + (4 - size % 4) % 4
        }
        return false
    }
}
