// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Source : linux/src/musegadget/ble_framing.py
package com.portalmuse.core

import java.io.ByteArrayOutputStream

/** Trames BLE : `0xFE`, index, total, fragment. Sans `0xFE`, le paquet est un message complet. */
object BleFraming {
    const val CHUNK_MAGIC: Byte = 0xFE.toByte()
    const val HEADER_BYTES = 3
    const val MAX_PACKET_BYTES = 160
    const val MAX_CHUNKS = 255
    const val MAX_MESSAGE_BYTES = 8192
    const val DEFAULT_ATT_MTU = 23
    const val CHUNK_STAGGER_MS = 50L

    /** Découpe [data] en paquets qui tiennent chacun dans une notification au [mtu] donné. */
    fun encodeChunks(data: ByteArray, mtu: Int = DEFAULT_ATT_MTU): List<ByteArray> {
        val notifyMax = minOf(if (mtu > 3) mtu - 3 else 20, MAX_PACKET_BYTES)
        val usable = notifyMax - HEADER_BYTES
        val total = maxOf(1, (data.size + usable - 1) / usable)
        require(total <= MAX_CHUNKS) { "le message demande $total fragments (max $MAX_CHUNKS)" }
        return List(total) { index ->
            val start = index * usable
            byteArrayOf(CHUNK_MAGIC, index.toByte(), total.toByte()) +
                data.copyOfRange(start, minOf(start + usable, data.size))
        }
    }
}

/** Réassemble les écritures fragmentées, strictement dans l'ordre. */
class ChunkAssembler(private val maxBytes: Int = BleFraming.MAX_MESSAGE_BYTES) {
    private val buffer = ByteArrayOutputStream()
    private var total = 0
    private var next = 0

    @Synchronized
    fun reset() {
        buffer.reset()
        total = 0
        next = 0
    }

    /** Ajoute une écriture ; renvoie le message dès qu'il est complet. */
    @Synchronized
    fun feed(packet: ByteArray): ByteArray? {
        if (packet.size < BleFraming.HEADER_BYTES || packet[0] != BleFraming.CHUNK_MAGIC) return packet.copyOf()
        val index = packet[1].toInt() and 0xFF
        val count = packet[2].toInt() and 0xFF
        val length = packet.size - BleFraming.HEADER_BYTES
        if (count == 0) {
            reset()
            return null
        }
        if (index == 0 || count != total) {
            reset()
            total = count
        }
        if (index != next || index >= total || buffer.size() + length > maxBytes) {
            reset()
            return null
        }
        buffer.write(packet, BleFraming.HEADER_BYTES, length)
        next = index + 1
        if (next < total) return null
        return buffer.toByteArray().also { reset() }
    }
}
