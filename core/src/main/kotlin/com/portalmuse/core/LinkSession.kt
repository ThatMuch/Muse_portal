// Kotlin port of muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0). Sources:
// linux/src/musegadget/link_client.py, noise/transport.py
package com.portalmuse.core

import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore

private const val CONTROL_PATH = "/api/gadget/control"
private const val HANDSHAKE_TIMEOUT_MS = 20_000L
private const val MAX_CONCURRENT_INVOKES = 4
private const val RESET_CANCELLED = 1
private val NO_DATA = ByteArray(0)
private const val MAX_INLINE_REQUEST_BYTES = 64 * 1024
private const val REQUEST_CHUNK_BYTES = 16 * 1024

/** Binary messages of a WebSocket. */
interface FrameSocket {
    fun send(data: ByteArray): Boolean

    /** `null`: connection closed. [timeoutMillis] at 0 waits without limit. */
    @Throws(IOException::class)
    fun receive(timeoutMillis: Long = 0): ByteArray?

    /** Closes at once and unblocks [receive]. */
    fun close()
}

enum class Outcome {
    /** Connection over: reconnect normally. */
    CLOSED,
    /** VM token refused: ask for the VM list again. */
    AUTH_REJECTED,
    /** Authenticated but not allowed for now. */
    FORBIDDEN,
    /** Muse removed this device. */
    UNPAIRED,
    STOPPED,
}

interface ActiveSession {
    fun run(): Outcome
    fun stop()
}

/**
 * A control session with a Muse VM: Noise XX handshake, `POST /api/gadget/control` stream on the Gadget
 * service, `link.register`, then answers to `link.invoke`. The logs never carry parameters, results or tokens.
 */
class LinkSession(
    private val socket: FrameSocket,
    private val device: DeviceDescription,
    private val commands: DeviceCommands,
    private val commandExecutor: Executor,
    private val onRegistered: () -> Unit = {},
    private val log: (String) -> Unit = {},
    /**
     * False for a connection reserved for voice: no control stream and no `link.register`, so as not to replace
     * the permanent session of the same device. [onRegistered] then signals that it is ready.
     */
    private val registerDevice: Boolean = true,
) : ActiveSession, MuseStreams {
    private val sendLock = Any()
    private val streams = ConcurrentHashMap<Long, MuseStream>()
    private var nextStreamId = 2L
    private val invokes = Semaphore(MAX_CONCURRENT_INVOKES)
    private val registerId = UUID.randomUUID().toString()
    private val messages = LengthPrefixedDecoder(log)
    private lateinit var sendCipher: CipherState
    private var streamId = 0L

    @Volatile private var stopped = false
    @Volatile private var registered = false
    @Volatile private var ended = false

    /** True between registration and the end of the session. */
    val isOpen: Boolean get() = registered && !ended && !stopped

    override fun run(): Outcome = try {
        val receiveCipher = handshake()
        if (registerDevice) {
            openControlStream()
        } else {
            registered = true
            onRegistered()
        }
        val outcome = readLoop(receiveCipher)
        if (stopped) Outcome.STOPPED else outcome
    } catch (e: IOException) {
        log("session interrupted: ${e.javaClass.simpleName}")
        if (stopped) Outcome.STOPPED else Outcome.CLOSED
    } catch (e: NoiseError) {
        log("session interrupted: ${e.message}")
        if (stopped) Outcome.STOPPED else Outcome.CLOSED
    } finally {
        ended = true
        socket.close()
        streams.values.forEach { it.fail("session over") }
        streams.clear()
    }

    override fun open(
        service: ServiceType, verb: String, path: String, headers: List<Pair<String, String>>, body: ByteArray,
    ): MuseStream = open(service, verb, path, headers, body, endBody = true)

    /** With [endBody] false, sending stays open: see [sendBody]. */
    fun open(
        service: ServiceType, verb: String, path: String, headers: List<Pair<String, String>>, body: ByteArray,
        endBody: Boolean, maxBufferedBytes: Int = MuseStream.DEFAULT_BUFFER_BYTES,
    ): MuseStream {
        val inParts = body.size > MAX_INLINE_REQUEST_BYTES
        require(endBody || !inParts) { "initial body too large for an open stream" }
        try {
            // Stream identifiers go out in the order they are assigned.
            val stream = synchronized(sendLock) {
                if (!isOpen) throw IOException("Muse session not connected")
                val created = MuseStream(nextStreamId++, maxBufferedBytes) { abandon(it, service) }
                streams[created.id] = created
                val inline = if (inParts) NO_DATA else body
                val complete = endBody && !inParts
                sendFrame(ServiceFrame.Request(created.id, verb, path, headers, inline, complete), service)
                created
            }
            if (inParts) {
                for (offset in body.indices step REQUEST_CHUNK_BYTES) {
                    val end = minOf(offset + REQUEST_CHUNK_BYTES, body.size)
                    val chunk = body.copyOfRange(offset, end)
                    sendFrame(ServiceFrame.BodyChunk(stream.id, chunk, endBody = end == body.size), service)
                }
            }
            // The session may have ended during the send.
            if (ended) stream.fail("session over")
            return stream
        } catch (e: NoiseError) {
            throw IOException("cannot send", e)
        }
    }

    /** Sends a chunk of an open stream, unless [admissible] rejects it at the last moment. */
    @Throws(IOException::class)
    fun sendBody(
        stream: MuseStream, service: ServiceType, data: ByteArray, endBody: Boolean,
        admissible: () -> Boolean = { true },
    ): Boolean = synchronized(sendLock) {
        if (!isOpen) throw IOException("Muse session not connected")
        if (!admissible()) return false
        try {
            sendFrame(ServiceFrame.BodyChunk(stream.id, data, endBody), service)
        } catch (e: NoiseError) {
            throw IOException("cannot send", e)
        }
        true
    }

    private fun abandon(stream: MuseStream, service: ServiceType) {
        if (streams.remove(stream.id) == null || stream.ended) return
        try {
            sendFrame(ServiceFrame.Reset(stream.id, RESET_CANCELLED, "request ended"), service)
        } catch (e: IOException) {
            log("request cancellation not sent")
        } catch (e: NoiseError) {
            log("request cancellation not sent")
        }
    }

    override fun stop() {
        stopped = true
        socket.close()
    }

    private fun handshake(): CipherState {
        val initiator = NoiseXXInitiator()
        transmit(initiator.writeMessage1())
        val message2 = socket.receive(HANDSHAKE_TIMEOUT_MS) ?: throw IOException("closed during the handshake")
        initiator.readMessage2(message2)
        // The token already authenticated the device when opening; message 3 is empty.
        transmit(initiator.writeMessage3())
        val (send, receive) = initiator.split()
        sendCipher = send
        log("Noise session established")
        return receive
    }

    private fun openControlStream() {
        streamId = 1
        sendFrame(ServiceFrame.Request(streamId, "POST", CONTROL_PATH))
        sendMessage(device.registerMessage(registerId, commands.specs()))
        log("link.register sent for ${device.nodeId}")
    }

    private fun transmit(data: ByteArray) {
        if (!socket.send(data)) throw IOException("cannot send")
    }

    /** Encrypting advances the nonce: the lock covers encryption and sending. */
    private fun sendFrame(frame: ServiceFrame, service: ServiceType = ServiceType.GADGET) = synchronized(sendLock) {
        try {
            for (part in NoiseFraming.encode(Envelope.encodeRequest(service, frame))) {
                transmit(sendCipher.encryptWithAd(NO_DATA, part))
            }
        } catch (e: Exception) {
            // Delivery uncertain: this cipher must not be used again.
            socket.close()
            throw e
        }
    }

    /**
     * A message without answer on the control stream, once the device is registered. False if it was not sent.
     */
    fun report(message: JSONObject): Boolean {
        if (!isOpen || !registerDevice) return false
        return try {
            sendMessage(message)
            true
        } catch (e: IOException) {
            false
        } catch (e: NoiseError) {
            false
        }
    }

    private fun sendMessage(message: JSONObject) =
        sendFrame(ServiceFrame.BodyChunk(streamId, ControlMessages.encode(message)))

    private fun readLoop(receiveCipher: CipherState): Outcome {
        val decoder = NoiseFrameDecoder()
        while (true) {
            val raw = socket.receive() ?: run {
                log("control connection closed")
                return Outcome.CLOSED
            }
            val whole = decoder.decode(receiveCipher.decryptWithAd(NO_DATA, raw)) ?: continue
            val frame = Envelope.decodeResponse(whole)
            if (frame.streamId != streamId) {
                streams[frame.streamId]?.receive(frame)
                continue
            }
            val (data, ended) = when (frame) {
                is ServiceFrame.Reset -> {
                    log("control stream reset (code ${frame.code})")
                    return Outcome.CLOSED
                }
                is ServiceFrame.Response -> {
                    if (frame.status !in 200..299) {
                        log("$CONTROL_PATH refused: HTTP ${frame.status}")
                        return if (frame.status == 403) Outcome.FORBIDDEN else Outcome.CLOSED
                    }
                    frame.body to frame.endBody
                }
                is ServiceFrame.BodyChunk -> frame.data to frame.endBody
                is ServiceFrame.Request -> throw NoiseError("unexpected request from the server")
            }
            for (message in messages.feed(data)) handle(message)?.let { return it }
            if (ended) {
                log("control stream ended by the VM")
                return Outcome.CLOSED
            }
        }
    }

    private fun handle(message: JSONObject): Outcome? {
        if (message.opt("id") == registerId && !message.has("method")) {
            if (isSet(message.opt("error")) || message.opt("ok") == false) {
                log("link.register refused")
                return Outcome.FORBIDDEN
            }
            if (!registered) {
                registered = true
                log("registered with Muse")
                onRegistered()
            }
            return null
        }
        when (message.opt("event")) {
            "link.unpaired", "node.unpaired" -> {
                log("Muse removed this device")
                return Outcome.UNPAIRED
            }
        }
        if (message.opt("method") == "link.invoke") invoke(message)
        return null
    }

    private fun isSet(value: Any?) = value != null && value != JSONObject.NULL && value != false && value != ""

    private fun invoke(message: JSONObject) {
        val id = message.opt("id")
        if (!isSet(id)) return
        val command = message.opt("command") as? String
        val params = message.optJSONObject("params") ?: JSONObject()
        fun reply(result: JSONObject) {
            sendMessage(result.put("method", "link.result").put("id", id))
        }
        if (!invokes.tryAcquire()) return reply(JSONObject().put("ok", false).put("error", "device busy"))
        try {
            commandExecutor.execute {
                try {
                    val shown = command.orEmpty().take(40).map { if (it.isISOControl()) '?' else it }.joinToString("")
                    val result = commands.run(command.orEmpty(), params)
                    log("command $shown: ${if (result.optBoolean("ok")) "ok" else "refused"}")
                    reply(result)
                } catch (e: Exception) {
                    log("answer not sent: ${e.javaClass.simpleName}")
                } finally {
                    invokes.release()
                }
            }
        } catch (e: RejectedExecutionException) {
            invokes.release()
        }
    }
}
