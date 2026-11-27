// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Source : linux/src/musegadget/noise/_proto.py, framing.py
package com.portalmuse.core

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/** Sous-ensemble de protobuf utilisé par les enveloppes Muse. */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        raw((field.toLong() shl 3) or 0)
        raw(value)
        return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        raw((field.toLong() shl 3) or 2)
        raw(value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun raw(value: Long) {
        var rest = value
        while (true) {
            val low = (rest and 0x7F).toInt()
            rest = rest ushr 7
            if (rest == 0L) {
                out.write(low)
                return
            }
            out.write(low or 0x80)
        }
    }
}

/** Un champ lu : [number] vaut `null` pour un champ entier, [data] pour un champ délimité. */
internal class ProtoField(val id: Int, val number: Long?, val data: ByteArray?) {
    fun asLong(): Long = number ?: throw NoiseError("protobuf : champ $id de mauvais type")
    fun asBytes(): ByteArray = data ?: throw NoiseError("protobuf : champ $id de mauvais type")
    fun asString(): String = String(asBytes())
}

internal object ProtoReader {
    /** Les champs de largeur fixe, jamais utilisés ici, sont sautés. */
    fun read(message: ByteArray): List<ProtoField> {
        var offset = 0
        fun varint(): Long {
            var value = 0L
            for (i in 0 until 10) {
                if (offset >= message.size) throw NoiseError("protobuf : entier tronqué")
                val byte = message[offset++].toInt() and 0xFF
                if (i == 9 && byte and 0xFE != 0) break
                value = value or ((byte and 0x7F).toLong() shl (7 * i))
                if (byte and 0x80 == 0) return value
            }
            throw NoiseError("protobuf : entier mal formé")
        }

        fun take(length: Long): ByteArray {
            if (length < 0 || length > message.size - offset) throw NoiseError("protobuf : champ tronqué")
            return message.copyOfRange(offset, offset + length.toInt()).also { offset += length.toInt() }
        }

        val fields = ArrayList<ProtoField>()
        while (offset < message.size) {
            val key = varint()
            val id = key ushr 3
            if (id == 0L || id > (1 shl 29) - 1) throw NoiseError("protobuf : numéro de champ invalide")
            when ((key and 7).toInt()) {
                0 -> fields.add(ProtoField(id.toInt(), varint(), null))
                2 -> fields.add(ProtoField(id.toInt(), null, take(varint())))
                1 -> take(8)
                5 -> take(4)
                else -> throw NoiseError("protobuf : type de champ invalide")
            }
        }
        return fields
    }
}

/** Découpage d'un message en trames de transport, chacune chiffrée séparément. */
object NoiseFraming {
    const val MAX_CHUNK_PAYLOAD = 65489
    const val MAX_TOTAL_CHUNKS = 256

    fun encode(data: ByteArray, chunkId: Long = SecureRandom().nextLong()): List<ByteArray> {
        val total = maxOf(1, (data.size + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD)
        require(total <= MAX_TOTAL_CHUNKS) { "message trop grand pour le transport" }
        return List(total) { index ->
            val start = index * MAX_CHUNK_PAYLOAD
            val payload = data.copyOfRange(start, minOf(start + MAX_CHUNK_PAYLOAD, data.size))
            val writer = ProtoWriter()
            if (chunkId != 0L) writer.varint(1, chunkId)
            if (index != 0) writer.varint(2, index.toLong())
            writer.varint(3, total.toLong())
            if (payload.isNotEmpty()) writer.bytes(4, payload)
            writer.toByteArray()
        }
    }
}

/** Réassemble les trames. Toute incohérence le rend inutilisable. */
class NoiseFrameDecoder(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private class Assembly(val total: Int, val createdAt: Long) {
        val chunks = HashMap<Int, ByteArray>()
        var bytes = 0
    }

    private val pending = HashMap<Long, Assembly>()
    private var poisoned = false

    fun decode(frame: ByteArray): ByteArray? {
        if (poisoned) throw NoiseError("décodeur de trames inutilisable")
        try {
            var chunkId = 0L
            var index = 0L
            var total = 1L
            var payload = ByteArray(0)
            for (field in ProtoReader.read(frame)) {
                when (field.id) {
                    1 -> chunkId = field.asLong()
                    2 -> index = field.asLong()
                    3 -> total = field.asLong()
                    4 -> payload = field.asBytes()
                }
            }
            if (total < 1 || total > NoiseFraming.MAX_TOTAL_CHUNKS) throw NoiseError("nombre de trames invalide")
            if (index < 0 || index >= total) throw NoiseError("index de trame hors limites")
            if (payload.size > NoiseFraming.MAX_CHUNK_PAYLOAD) throw NoiseError("trame trop grande")
            val now = clock()
            pending.values.removeAll { now - it.createdAt > TTL_MILLIS }
            val assembly = pending[chunkId] ?: run {
                if (pending.size >= MAX_PENDING) throw NoiseError("trop de messages en cours")
                Assembly(total.toInt(), now).also { pending[chunkId] = it }
            }
            if (assembly.total != total.toInt()) throw NoiseError("nombre de trames incohérent")
            if (assembly.chunks.put(index.toInt(), payload) != null) throw NoiseError("trame en double")
            assembly.bytes += payload.size
            if (assembly.bytes > MAX_ASSEMBLY_BYTES) throw NoiseError("message trop grand")
            if (assembly.chunks.size < assembly.total) return null
            pending.remove(chunkId)
            val whole = ByteArrayOutputStream(assembly.bytes)
            for (i in 0 until assembly.total) whole.write(assembly.chunks.getValue(i))
            return whole.toByteArray()
        } catch (e: NoiseError) {
            poisoned = true
            throw e
        }
    }

    private companion object {
        const val MAX_PENDING = 16
        const val MAX_ASSEMBLY_BYTES = 16 * 1024 * 1024
        const val TTL_MILLIS = 60_000L
    }
}
