package com.portalmuse.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of linux/tests/test_ble_framing.py. */
class BleFramingTest {
    private fun bytes(c: Char, n: Int) = ByteArray(n) { c.code.toByte() }

    @Test fun chunkPayloadFollowsMtu() {
        for ((mtu, perChunk) in listOf(23 to 17, 185 to 157, 256 to 157, 3 to 17, 517 to 157)) {
            val chunks = BleFraming.encodeChunks(bytes('x', 1000), mtu)
            assertTrue(chunks.dropLast(1).all { it.size - 3 == perChunk })
            assertArrayEquals(bytes('x', 1000), chunks.fold(ByteArray(0)) { acc, c -> acc + c.copyOfRange(3, c.size) })
        }
    }

    @Test fun chunkHeaders() {
        val headers = BleFraming.encodeChunks(bytes('a', 40), 23).map { it.copyOfRange(0, 3).toHex() }
        assertEquals(listOf("fe0003", "fe0103", "fe0203"), headers)
    }

    @Test fun emptyMessageIsOneChunk() {
        assertEquals(listOf("fe0001"), BleFraming.encodeChunks(ByteArray(0)).map { it.toHex() })
    }

    @Test fun tooManyChunksIsAnError() {
        assertThrows(IllegalArgumentException::class.java) { BleFraming.encodeChunks(bytes('x', 17 * 255 + 1), 23) }
    }

    @Test fun roundTrip() {
        val message = ByteArray(2560) { it.toByte() }
        val assembler = ChunkAssembler()
        val results = BleFraming.encodeChunks(message, 185).map(assembler::feed)
        assertArrayEquals(message, results.last())
        assertTrue(results.dropLast(1).all { it == null })
    }

    @Test fun plainWritePassesThrough() {
        val plain = """{"action":"get_device_info"}""".toByteArray()
        assertArrayEquals(plain, ChunkAssembler().feed(plain))
    }

    @Test fun outOfOrderChunkDiscardsTheMessage() {
        val chunks = BleFraming.encodeChunks(bytes('y', 60), 23)
        val assembler = ChunkAssembler()
        for (i in listOf(0, 2, 1, 3)) assertNull(assembler.feed(chunks[i]))
    }

    @Test fun indexZeroRestartsTheMessage() {
        val old = BleFraming.encodeChunks(bytes('o', 60), 23)
        val new = BleFraming.encodeChunks(bytes('n', 30), 23)
        val assembler = ChunkAssembler()
        assembler.feed(old[0])
        assembler.feed(old[1])
        assertArrayEquals(bytes('n', 30), new.map(assembler::feed).last())
    }

    @Test fun changedTotalMidMessageDiscardsIt() {
        val assembler = ChunkAssembler()
        assembler.feed(hex("fe0003") + "abc".toByteArray())
        assertNull(assembler.feed(hex("fe0102") + "def".toByteArray()))
        assertNull(assembler.feed(hex("fe0203") + "ghi".toByteArray()))
    }

    @Test fun zeroTotalIsIgnored() {
        assertNull(ChunkAssembler().feed(hex("fe0000") + "abc".toByteArray()))
    }

    @Test fun oversizeMessageIsDiscarded() {
        val assembler = ChunkAssembler(maxBytes = 20)
        assertTrue(BleFraming.encodeChunks(bytes('z', 30), 23).all { assembler.feed(it) == null })
    }
}
