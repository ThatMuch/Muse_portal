// Kotlin port of muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0). Source:
// linux/src/musegadget/link_client.py (_Request, HttpStream)
package com.portalmuse.core

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger


/** HTTP requests carried by a registered Muse session. */
interface MuseStreams {
    @Throws(IOException::class)
    fun open(service: ServiceType, verb: String, path: String, headers: List<Pair<String, String>>, body: ByteArray): MuseStream
}

/**
 * The answer to a request: status then body in chunks. The session feeds it without ever blocking; [close]
 * cancels the request if it is not finished.
 */
class MuseStream internal constructor(
    val id: Long, private val maxBufferedBytes: Int, private val cancel: (MuseStream) -> Unit,
) {
    private sealed class Item {
        class Head(val status: Int) : Item()
        class Data(val bytes: ByteArray) : Item()
        object End : Item()
        class Broken(val reason: String) : Item()
    }

    private val items = LinkedBlockingQueue<Item>()
    private val buffered = AtomicInteger()
    private var headSeen = false

    @Volatile var ended = false
        private set

    /** The response headers, names in lower case; available after [awaitStatus]. */
    @Volatile var headers: Map<String, String> = emptyMap()
        private set

    /** Called by the receive loop only. */
    internal fun receive(frame: ServiceFrame) {
        if (ended) return
        val (data, last) = when (frame) {
            is ServiceFrame.Response -> {
                if (headSeen || frame.status !in 100..599) return fail("invalid response header")
                headSeen = true
                headers = frame.headers.associate { it.first.lowercase() to it.second }
                items.add(Item.Head(frame.status))
                frame.body to frame.endBody
            }
            is ServiceFrame.BodyChunk -> {
                if (!headSeen) return fail("body received before the header")
                frame.data to frame.endBody
            }
            is ServiceFrame.Reset -> {
                ended = true
                items.add(Item.Broken("stream reset (code ${frame.code})"))
                return
            }
            is ServiceFrame.Request -> return fail("unexpected request")
        }
        if (data.isNotEmpty()) {
            if (buffered.addAndGet(data.size) > maxBufferedBytes) return fail("response not consumed")
            items.add(Item.Data(data))
        }
        if (last) {
            ended = true
            items.add(Item.End)
        }
    }

    internal fun fail(reason: String) {
        items.add(Item.Broken(reason))
    }

    @Throws(IOException::class)
    fun awaitStatus(timeoutMillis: Long): Int {
        val item = items.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: throw SocketTimeoutException("no response")
        return when (item) {
            is Item.Head -> item.status
            is Item.Broken -> throw IOException(item.reason)
            else -> throw IOException("response without header")
        }
    }

    /** The next chunk, empty if nothing arrived in time, `null` at the end of the body. */
    @Throws(IOException::class)
    fun read(timeoutMillis: Long): ByteArray? =
        when (val item = items.poll(timeoutMillis, TimeUnit.MILLISECONDS)) {
            null -> ByteArray(0)
            is Item.Data -> item.bytes.also { buffered.addAndGet(-it.size) }
            Item.End -> null.also { items.add(Item.End) }
            is Item.Broken -> throw IOException(item.reason)
            is Item.Head -> throw IOException("second header")
        }

    fun close() = cancel(this)

    companion object {
        const val DEFAULT_BUFFER_BYTES = 1024 * 1024
    }
}
