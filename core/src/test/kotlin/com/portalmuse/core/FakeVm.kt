package com.portalmuse.core

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Côté serveur du handshake Noise XX, pour les tests. */
class NoiseResponder {
    private val state = SymmetricState(ByteArray(0))
    private val ephemeral = X25519KeyPair.generate()
    private val static = X25519KeyPair.generate()

    fun message2(message1: ByteArray): ByteArray {
        val re = message1.copyOfRange(0, 32)
        state.mixHash(re)
        state.decryptAndHash(message1.copyOfRange(32, message1.size))
        state.mixHash(ephemeral.publicKey)
        state.mixKey(X25519.dh(ephemeral, re))
        val encryptedStatic = state.encryptAndHash(static.publicKey)
        state.mixKey(X25519.dh(static, re))
        return ephemeral.publicKey + encryptedStatic + state.encryptAndHash(ByteArray(0))
    }

    /** Renvoie (réception, envoi) du point de vue du serveur. */
    fun finish(message3: ByteArray): Pair<CipherState, CipherState> {
        val rs = state.decryptAndHash(message3.copyOfRange(0, 48))
        state.mixKey(X25519.dh(ephemeral, rs))
        state.decryptAndHash(message3.copyOfRange(48, message3.size))
        return state.split()
    }
}

/**
 * VM Muse simulée derrière un [FrameSocket] : vrai handshake, vraies trames. [onMessage] reçoit
 * chaque message de contrôle de l'appareil et peut répondre avec [send].
 */
class FakeVm(
    /** Requête HTTP complète autre que le flux de contrôle. */
    private val onRequest: FakeVm.(HttpCall) -> Unit = {},
    private val onMessage: FakeVm.(JSONObject) -> Unit = {},
) : FrameSocket {
    class HttpCall(
        val streamId: Long, val service: ServiceType, val verb: String, val path: String, val body: ByteArray, val parts: Int,
        val headers: Map<String, String> = emptyMap(),
    )

    private val uploads = HashMap<Long, Triple<Pair<ServiceType, ServiceFrame.Request>, java.io.ByteArrayOutputStream, IntArray>>()
    val calls = LinkedBlockingQueue<HttpCall>()
    val resets = LinkedBlockingQueue<Long>()
    /** Requêtes laissées ouvertes dans les deux sens (appel vocal), et leurs morceaux. */
    val duplexOpened = LinkedBlockingQueue<HttpCall>()
    val duplexData = LinkedBlockingQueue<ServiceFrame.BodyChunk>()
    private var duplex = 0L
    private val toDevice = LinkedBlockingQueue<ByteArray>()
    private val responder = NoiseResponder()
    private val decoder = NoiseFrameDecoder()
    private val messages = LengthPrefixedDecoder()
    private var receive: CipherState? = null
    private var transmit: CipherState? = null
    private var handshakeStep = 0
    @Volatile var closed = false
    val requests = mutableListOf<Pair<ServiceType, ServiceFrame.Request>>()
    val received = LinkedBlockingQueue<JSONObject>()
    var controlStream = 0L

    @Synchronized
    override fun send(data: ByteArray): Boolean {
        if (closed) return false
        when (handshakeStep) {
            0 -> { toDevice.add(responder.message2(data)); handshakeStep = 1 }
            1 -> { val keys = responder.finish(data); receive = keys.first; transmit = keys.second; handshakeStep = 2 }
            else -> {
                val plain = receive!!.decryptWithAd(ByteArray(0), data)
                val whole = decoder.decode(plain) ?: return true
                val (service, frame) = Envelope.decodeRequest(whole)
                when (frame) {
                    is ServiceFrame.Request -> {
                        if (frame.path == "/api/gadget/control") {
                            requests.add(service to frame)
                            controlStream = frame.streamId
                        } else if (frame.path.startsWith("/voice/realtime")) {
                            duplex = frame.streamId
                            duplexOpened.add(HttpCall(frame.streamId, service, frame.verb, frame.path, frame.body, 0))
                        } else if (frame.endBody) {
                            complete(service, frame, frame.body, 1)
                        } else {
                            uploads[frame.streamId] = Triple(service to frame, java.io.ByteArrayOutputStream(), intArrayOf(0))
                        }
                    }
                    is ServiceFrame.BodyChunk -> if (frame.streamId == controlStream) {
                        for (message in messages.feed(frame.data)) {
                            received.add(message)
                            onMessage(message)
                        }
                    } else if (frame.streamId == duplex) {
                        duplexData.add(frame)
                    } else {
                        val upload = uploads.getValue(frame.streamId)
                        upload.second.write(frame.data)
                        upload.third[0]++
                        if (frame.endBody) {
                            uploads.remove(frame.streamId)
                            complete(upload.first.first, upload.first.second, upload.second.toByteArray(), upload.third[0])
                        }
                    }
                    is ServiceFrame.Reset -> resets.add(frame.streamId)
                    else -> Unit
                }
            }
        }
        return true
    }

    private fun complete(service: ServiceType, request: ServiceFrame.Request, body: ByteArray, parts: Int) {
        val call = HttpCall(request.streamId, service, request.verb, request.path, body, parts, request.headers.toMap())
        calls.add(call)
        onRequest(call)
    }

    override fun receive(timeoutMillis: Long): ByteArray? {
        val data = if (timeoutMillis > 0) {
            toDevice.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: throw java.net.SocketTimeoutException()
        } else toDevice.take()
        return data.takeIf { it.isNotEmpty() }
    }

    override fun close() {
        closed = true
        toDevice.add(ByteArray(0))
    }

    @Synchronized
    fun sendFrame(frame: ServiceFrame) {
        for (part in NoiseFraming.encode(Envelope.encodeResponse(frame))) {
            toDevice.add(transmit!!.encryptWithAd(ByteArray(0), part))
        }
    }

    fun send(message: JSONObject) = sendFrame(ServiceFrame.BodyChunk(controlStream, prefixed(message)))

    fun prefixed(message: JSONObject): ByteArray {
        val data = message.toString().toByteArray()
        return ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).put(data).array()
    }
}
