// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Source : linux/src/musegadget/link_client.py (connexion WebSocket)
package com.portalmuse.core

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val MAX_MESSAGE_BYTES = 128 * 1024
private const val CONNECT_TIMEOUT_S = 20L
private const val PING_INTERVAL_S = 20L
private val CLOSED = ByteArray(0)

/** La VM a refusé le jeton à l'ouverture (401 ou 403) : ne pas réessayer le même. */
class UpgradeRejected(val status: Int) : IOException("ouverture refusée : HTTP $status")

class OkHttpFrameSocket private constructor() : FrameSocket {
    private val inbox = LinkedBlockingQueue<ByteArray>()
    private lateinit var socket: WebSocket

    override fun send(data: ByteArray): Boolean = socket.send(data.toByteString())

    override fun receive(timeoutMillis: Long): ByteArray? {
        val data = if (timeoutMillis > 0) {
            inbox.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: throw SocketTimeoutException("aucune réponse")
        } else {
            inbox.take()
        }
        if (data === CLOSED) {
            inbox.add(CLOSED)
            return null
        }
        return data
    }

    override fun close() {
        socket.cancel()
        inbox.add(CLOSED)
    }

    companion object {
        /** Bloque jusqu'à l'ouverture. Le jeton voyage dans l'en-tête `Authorization`. */
        @Throws(IOException::class)
        fun connect(client: OkHttpClient, url: String, bearer: String, userAgent: String): OkHttpFrameSocket {
            val result = OkHttpFrameSocket()
            val opened = CountDownLatch(1)
            var failure: IOException? = null
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size > MAX_MESSAGE_BYTES) {
                        webSocket.cancel()
                        result.inbox.add(CLOSED)
                    } else if (bytes.size > 0) {
                        result.inbox.add(bytes.toByteArray())
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    result.inbox.add(CLOSED)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    failure = if (code == 401 || code == 403) {
                        UpgradeRejected(code)
                    } else {
                        // Le type seulement : le message peut contenir l'adresse complète.
                        IOException("connexion impossible : ${t.javaClass.simpleName}${code?.let { " (HTTP $it)" } ?: ""}")
                    }
                    result.inbox.add(CLOSED)
                    opened.countDown()
                }
            }
            // Pas de compression, comme le client du SDK ; pas de redirection avec un jeton.
            val noCompression = Interceptor { chain ->
                chain.proceed(chain.request().newBuilder().removeHeader("Sec-WebSocket-Extensions").build())
            }
            val webSocketClient = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
                .addInterceptor(noCompression)
                .build()
            val request = try {
                Request.Builder().url(url)
                    .header("Authorization", "Bearer $bearer")
                    .header("User-Agent", userAgent)
                    .build()
            } catch (e: IllegalArgumentException) {
                throw IOException("adresse ou jeton mal formé")
            }
            result.socket = webSocketClient.newWebSocket(request, listener)
            if (!opened.await(CONNECT_TIMEOUT_S + 5, TimeUnit.SECONDS)) {
                result.close()
                throw SocketTimeoutException("ouverture trop longue")
            }
            failure?.let { throw it }
            return result
        }
    }
}
