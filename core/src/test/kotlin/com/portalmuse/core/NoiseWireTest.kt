package com.portalmuse.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bytes produced by the SDK's Python code (core/src/test/tools/noise_wire_vectors.py). */
class NoiseWireTest {
    private val vectors = resource("noise_wire_sdk.json")
    private fun vector(name: String) = vectors.getString(name)
    private fun big() = ByteArray(70_000) { (it % 251).toByte() }

    @Test fun requestsMatchTheSdkByteForByte() {
        val open = ServiceFrame.Request(1, "POST", "/api/gadget/control")
        assertEquals(vector("open_control"), Envelope.encodeRequest(ServiceType.GADGET, open).toHex())
        val prefixed = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putInt(2).put("{}".toByteArray()).array()
        assertEquals(vector("body_chunk"), Envelope.encodeRequest(ServiceType.GADGET, ServiceFrame.BodyChunk(1, prefixed)).toHex())
        val daemon = ServiceFrame.Request(300, "GET", "/x", listOf("a" to "b"), "hi".toByteArray(), endBody = true)
        assertEquals(vector("daemon_request"), Envelope.encodeRequest(ServiceType.DAEMON, daemon).toHex())
        val reset = ServiceFrame.Reset(1, 1, "stop")
        assertEquals(vector("reset"), Envelope.encodeRequest(ServiceType.GADGET, reset).toHex())
    }

    @Test fun sdkResponsesDecode() {
        val ok = Envelope.decodeResponse(hex(vector("response_ok"))) as ServiceFrame.Response
        assertEquals(1L, ok.streamId)
        assertEquals(200, ok.status)
        assertEquals("abc", String(ok.body))
        assertEquals(false, ok.endBody)
        val chunk = Envelope.decodeResponse(hex(vector("response_chunk_end"))) as ServiceFrame.BodyChunk
        assertEquals("zz", String(chunk.data))
        assertEquals(true, chunk.endBody)
        val reset = Envelope.decodeResponse(hex(vector("response_reset"))) as ServiceFrame.Reset
        assertEquals(4, reset.code)
        assertEquals("non", reset.reason)
    }

    @Test fun transportFramesMatchTheSdk() {
        assertEquals(vector("frame_small"), NoiseFraming.encode("hello".toByteArray(), chunkId = -5).single().toHex())
        assertEquals(vector("frame_empty"), NoiseFraming.encode(ByteArray(0), chunkId = 7).single().toHex())
        val expected = vectors.getJSONArray("frame_big")
        val frames = NoiseFraming.encode(big(), chunkId = 1234567890123)
        assertEquals(expected.length(), frames.size)
        frames.forEachIndexed { i, frame -> assertEquals(expected.getString(i), frame.toHex()) }
    }

    @Test fun framesReassembleInAnyOrder() {
        val decoder = NoiseFrameDecoder()
        val frames = NoiseFraming.encode(big(), chunkId = 3)
        assertNull(decoder.decode(frames[1]))
        assertArrayEquals(big(), decoder.decode(frames[0]))
    }

    @Test fun badFramesPoisonTheDecoder() {
        val frames = NoiseFraming.encode(big(), chunkId = 3)
        val decoder = NoiseFrameDecoder()
        decoder.decode(frames[0])
        assertThrows(NoiseError::class.java) { decoder.decode(frames[0]) }
        assertThrows(NoiseError::class.java) { decoder.decode(frames[1]) }
        for (bad in listOf("2000", "188202", "ff", "2005ab")) {
            assertThrows(bad, NoiseError::class.java) { NoiseFrameDecoder().decode(hex(bad)) }
        }
    }

    @Test fun truncatedEnvelopesAreRejected() {
        val whole = hex(vector("response_ok"))
        assertThrows(NoiseError::class.java) { Envelope.decodeResponse(whole.copyOf(whole.size - 1)) }
        assertThrows(NoiseError::class.java) { Envelope.decodeResponse(ByteArray(0)) }
    }
}
