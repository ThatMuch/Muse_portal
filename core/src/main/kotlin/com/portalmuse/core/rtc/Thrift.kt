// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Source : linux/src/musegadget/rtc/join.py (_Writer, _Reader)
package com.portalmuse.core.rtc

import java.io.ByteArrayOutputStream

private const val MAX_DEPTH = 8

/** Écriture Thrift « compact », juste ce qu'il faut pour un JOIN. */
class ThriftWriter {
    private val out = ByteArrayOutputStream()
    private val last = ArrayList<Int>().apply { add(0) }

    private fun varint(value: Long) {
        var n = value
        while (true) {
            val byte = (n and 0x7F).toInt()
            n = n ushr 7
            if (n != 0L) out.write(byte or 0x80) else return out.write(byte)
        }
    }

    private fun field(id: Int, kind: Int) {
        val delta = id - last.last()
        if (delta in 1..15) {
            out.write(delta shl 4 or kind)
        } else {
            out.write(kind)
            varint(((id shl 1) xor (id shr 31)).toLong())
        }
        last[last.lastIndex] = id
    }

    fun i32(id: Int, value: Int) {
        field(id, 5)
        varint(((value shl 1) xor (value shr 31)).toLong() and 0xFFFFFFFFL)
    }

    fun boolean(id: Int, value: Boolean) = field(id, if (value) 1 else 2)

    fun binary(id: Int, value: ByteArray) {
        field(id, 8)
        varint(value.size.toLong())
        out.write(value)
    }

    fun binary(id: Int, value: String) = binary(id, value.toByteArray())

    fun begin(id: Int) {
        field(id, 12)
        last.add(0)
    }

    fun end() {
        out.write(0)
        last.removeAt(last.lastIndex)
    }

    /** Termine la structure de tête ; la suivante recommence sa numérotation. */
    fun stop() {
        out.write(0)
        last.clear()
        last.add(0)
    }

    fun bytes(): ByteArray = out.toByteArray()
}

/** Lecture d'un petit message ; toute entrée mal formée lève [IllegalArgumentException]. */
class ThriftReader(private val data: ByteArray) {
    private var position = 0
    private var depth = 0

    private fun byte(): Int {
        require(position < data.size) { "message thrift tronqué" }
        return data[position++].toInt() and 0xFF
    }

    private fun take(length: Long): ByteArray {
        require(length >= 0 && length <= data.size - position) { "message thrift tronqué" }
        return data.copyOfRange(position, position + length.toInt()).also { position += length.toInt() }
    }

    private fun varint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val byte = byte()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("entier thrift trop long")
    }

    private fun zigzag() = varint().let { (it ushr 1) xor -(it and 1) }

    /** Chaque élément prend au moins un octet. */
    private fun count(count: Long): Int {
        require(count >= 0 && count <= data.size - position) { "conteneur thrift plus grand que son message" }
        return count.toInt()
    }

    private fun enter() {
        require(++depth <= MAX_DEPTH) { "message thrift trop imbriqué" }
    }

    /** Les champs par numéro. Les binaires sont des [ByteArray], les structures des [Map]. */
    fun struct(): Map<Int, Any?> {
        enter()
        val fields = HashMap<Int, Any?>()
        var last = 0
        while (true) {
            val header = byte()
            if (header == 0) {
                depth--
                return fields
            }
            val delta = header shr 4
            val id = if (delta != 0) last + delta else zigzag().toInt()
            last = id
            fields[id] = value(header and 0x0F, false)
        }
    }

    private fun value(kind: Int, inContainer: Boolean): Any? = when (kind) {
        // Le booléen d'un champ est dans son type ; celui d'un élément est un octet.
        1, 2 -> if (inContainer) byte() == 1 else kind == 1
        3 -> byte()
        4, 5, 6 -> zigzag()
        7 -> take(8).let { null }
        8 -> take(varint())
        9, 10 -> {
            val header = byte()
            val size = (header shr 4).let { if (it == 15) varint() else it.toLong() }
            enter()
            List(count(size)) { value(header and 0x0F, true) }.also { depth-- }
        }
        11 -> {
            val size = count(varint())
            if (size == 0) {
                emptyMap<Any?, Any?>()
            } else {
                val kinds = byte()
                enter()
                (0 until size).associate { value(kinds shr 4, true) to value(kinds and 0x0F, true) }.also { depth-- }
            }
        }
        12 -> struct()
        else -> throw IllegalArgumentException("type thrift $kind inconnu")
    }
}
