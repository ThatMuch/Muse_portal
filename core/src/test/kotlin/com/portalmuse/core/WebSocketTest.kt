package com.portalmuse.core

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Vraie WebSocket contre un serveur local. */
class WebSocketTest {
    @Test fun sendsTheBearerWithoutCompressionAndEchoesBinaryFrames() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    webSocket.send(bytes.toByteArray().reversedArray().toByteString())
                    webSocket.close(1000, null)
                }
            }))
            val url = server.url("/v1/noise?vm_id=v1").toString().replace("http:", "ws:")
            val socket = OkHttpFrameSocket.connect(OkHttpClient(), url, "jeton-vm", "portal-test")
            val request = server.takeRequest()
            assertEquals("Bearer jeton-vm", request.getHeader("Authorization"))
            assertEquals("portal-test", request.getHeader("User-Agent"))
            assertNull(request.getHeader("Sec-WebSocket-Extensions"))
            assertEquals(true, socket.send(byteArrayOf(1, 2, 3)))
            assertArrayEquals(byteArrayOf(3, 2, 1), socket.receive(5_000))
            assertNull(socket.receive(5_000))
            socket.close()
        }
    }

    @Test fun refusedUpgradesReportTheirStatus() {
        for (code in listOf(401, 403)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(code))
                val url = server.url("/").toString().replace("http:", "ws:")
                val error = assertThrows(UpgradeRejected::class.java) { OkHttpFrameSocket.connect(OkHttpClient(), url, "t", "u") }
                assertEquals(code, error.status)
            }
        }
    }
}
