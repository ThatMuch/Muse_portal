// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Source : linux/src/musegadget/rtc/rtp.py
package com.portalmuse.core.rtc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

private const val MAX_UNIT_BYTES = 2 * 1024 * 1024
private const val MAX_UNIT_NALS = 256

class RtpPacket(
    val payloadType: Int, val marker: Boolean, val sequence: Int, val timestamp: Long, val ssrc: Int,
    val payload: ByteArray,
)

object Rtp {
    fun isRtcp(data: ByteArray) = data.size >= 8 && (data[1].toInt() and 0xFF) in 192..223

    fun parse(data: ByteArray): RtpPacket {
        require(data.size >= 12 && (data[0].toInt() and 0xFF) shr 6 == 2) { "pas un paquet RTP" }
        val buffer = ByteBuffer.wrap(data)
        var offset = 12 + 4 * (data[0].toInt() and 0x0F)
        if (data[0].toInt() and 0x10 != 0) {
            require(offset + 4 <= data.size) { "longueurs RTP invalides" }
            offset += 4 + 4 * (buffer.getShort(offset + 2).toInt() and 0xFFFF)
        }
        var end = data.size
        if (data[0].toInt() and 0x20 != 0) end -= data[data.size - 1].toInt() and 0xFF
        require(offset <= end) { "longueurs RTP invalides" }
        return RtpPacket(
            data[1].toInt() and 0x7F, data[1].toInt() and 0x80 != 0, buffer.getShort(2).toInt() and 0xFFFF,
            buffer.getInt(4).toLong() and 0xFFFFFFFFL, buffer.getInt(8), data.copyOfRange(offset, end),
        )
    }

    fun build(payloadType: Int, sequence: Int, timestamp: Long, ssrc: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(12 + payload.size).put(0x80.toByte()).put(payloadType.toByte())
            .putShort(sequence.toShort()).putInt(timestamp.toInt()).putInt(ssrc).put(payload).array()

    /** Demande une image clé. */
    fun pictureLoss(senderSsrc: Int, mediaSsrc: Int): ByteArray = ByteBuffer.allocate(12)
        .put(0x81.toByte()).put(206.toByte()).putShort(2).putInt(senderSsrc).putInt(mediaSsrc).array()
}

/**
 * Réassemble le H.264 reçu en RTP en unités « Annexe B » ; une perte ou un dépassement jette
 * l'unité, et plus rien ne sort avant la prochaine image clé.
 */
class H264Depacketizer {
    private val units = ArrayList<ByteArray>()
    private var bytes = 0
    private var fragment: ByteArrayOutputStream? = null
    private var expected: Int? = null
    private var broken = false

    var needsKeyframe = true
        private set

    fun push(packet: RtpPacket): ByteArray? {
        if (expected != null && packet.sequence != expected) drop()
        expected = (packet.sequence + 1) and 0xFFFF
        if (!broken) {
            consume(packet.payload)
            if (bytes > MAX_UNIT_BYTES || units.size > MAX_UNIT_NALS) drop()
        }
        if (!packet.marker) return null
        if (fragment != null) drop() // un fragment resté inachevé à la fin de l'image
        val complete = units.toList()
        val wasBroken = broken
        units.clear()
        bytes = 0
        fragment = null
        broken = false
        if (wasBroken || complete.isEmpty()) return null
        if (complete.any { it[0].toInt() and 0x1F == 5 }) needsKeyframe = false
        if (needsKeyframe) return null
        val out = ByteArrayOutputStream()
        for (unit in complete) {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(unit)
        }
        return out.toByteArray()
    }

    private fun drop() {
        units.clear()
        bytes = 0
        fragment = null
        broken = true
        needsKeyframe = true
    }

    private fun consume(payload: ByteArray) {
        if (payload.isEmpty()) return
        bytes += payload.size
        when (val kind = payload[0].toInt() and 0x1F) {
            in 1..23 -> units.add(payload)
            24 -> { // STAP-A : plusieurs unités dans un paquet
                var offset = 1
                while (offset + 2 <= payload.size) {
                    val size = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
                    offset += 2
                    if (size > 0 && offset + size <= payload.size) units.add(payload.copyOfRange(offset, offset + size))
                    offset += size
                }
            }
            28 -> if (payload.size >= 2) { // FU-A : une unité en fragments
                val start = payload[1].toInt() and 0x80 != 0
                val end = payload[1].toInt() and 0x40 != 0
                if (start) {
                    fragment = ByteArrayOutputStream().apply {
                        write((payload[0].toInt() and 0xE0) or (payload[1].toInt() and 0x1F))
                    }
                }
                val open = fragment ?: return drop() // un fragment sans son début
                open.write(payload, 2, payload.size - 2)
                if (end) {
                    units.add(open.toByteArray())
                    fragment = null
                }
            }
            else -> check(kind >= 0)
        }
    }
}
