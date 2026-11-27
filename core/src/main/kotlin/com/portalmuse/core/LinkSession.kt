// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Sources : linux/src/musegadget/link_client.py, noise/transport.py
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

/** Messages binaires d'une WebSocket. */
interface FrameSocket {
    fun send(data: ByteArray): Boolean

    /** `null` : connexion fermée. [timeoutMillis] à 0 attend sans limite. */
    @Throws(IOException::class)
    fun receive(timeoutMillis: Long = 0): ByteArray?

    /** Ferme aussitôt et débloque [receive]. */
    fun close()
}

enum class Outcome {
    /** Connexion terminée : se reconnecter normalement. */
    CLOSED,
    /** Jeton de VM refusé : redemander la liste des VM. */
    AUTH_REJECTED,
    /** Authentifié mais pas autorisé pour l'instant. */
    FORBIDDEN,
    /** Muse a retiré cet appareil. */
    UNPAIRED,
    STOPPED,
}

interface ActiveSession {
    fun run(): Outcome
    fun stop()
}

/**
 * Une session de contrôle avec une VM Muse : handshake Noise XX, flux `POST /api/gadget/control`
 * sur le service Gadget, `link.register`, puis réponses aux `link.invoke`.
 * Les journaux ne portent jamais de paramètres, de résultats ni de jetons.
 */
class LinkSession(
    private val socket: FrameSocket,
    private val device: DeviceDescription,
    private val commands: DeviceCommands,
    private val commandExecutor: Executor,
    private val onRegistered: () -> Unit = {},
    private val log: (String) -> Unit = {},
    /**
     * Faux pour une connexion réservée à la voix : ni flux de contrôle ni `link.register`, pour ne
     * pas remplacer la session permanente du même appareil. [onRegistered] signale alors qu'elle
     * est prête.
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

    /** Vrai entre l'enregistrement et la fin de la session. */
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
        log("session interrompue : ${e.javaClass.simpleName}")
        if (stopped) Outcome.STOPPED else Outcome.CLOSED
    } catch (e: NoiseError) {
        log("session interrompue : ${e.message}")
        if (stopped) Outcome.STOPPED else Outcome.CLOSED
    } finally {
        ended = true
        socket.close()
        streams.values.forEach { it.fail("session terminée") }
        streams.clear()
    }

    override fun open(
        service: ServiceType, verb: String, path: String, headers: List<Pair<String, String>>, body: ByteArray,
    ): MuseStream = open(service, verb, path, headers, body, endBody = true)

    /** Avec [endBody] faux, l'envoi reste ouvert : voir [sendBody]. */
    fun open(
        service: ServiceType, verb: String, path: String, headers: List<Pair<String, String>>, body: ByteArray,
        endBody: Boolean, maxBufferedBytes: Int = MuseStream.DEFAULT_BUFFER_BYTES,
    ): MuseStream {
        val inParts = body.size > MAX_INLINE_REQUEST_BYTES
        require(endBody || !inParts) { "corps initial trop grand pour un flux ouvert" }
        try {
            // Les identifiants de flux partent dans l'ordre où ils sont attribués.
            val stream = synchronized(sendLock) {
                if (!isOpen) throw IOException("session Muse non connectée")
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
            // La session a pu finir pendant l'envoi.
            if (ended) stream.fail("session terminée")
            return stream
        } catch (e: NoiseError) {
            throw IOException("envoi impossible", e)
        }
    }

    /** Envoie un morceau d'un flux ouvert, sauf si [admissible] le refuse au dernier moment. */
    @Throws(IOException::class)
    fun sendBody(
        stream: MuseStream, service: ServiceType, data: ByteArray, endBody: Boolean,
        admissible: () -> Boolean = { true },
    ): Boolean = synchronized(sendLock) {
        if (!isOpen) throw IOException("session Muse non connectée")
        if (!admissible()) return false
        try {
            sendFrame(ServiceFrame.BodyChunk(stream.id, data, endBody), service)
        } catch (e: NoiseError) {
            throw IOException("envoi impossible", e)
        }
        true
    }

    private fun abandon(stream: MuseStream, service: ServiceType) {
        if (streams.remove(stream.id) == null || stream.ended) return
        try {
            sendFrame(ServiceFrame.Reset(stream.id, RESET_CANCELLED, "request ended"), service)
        } catch (e: IOException) {
            log("annulation de requête non envoyée")
        } catch (e: NoiseError) {
            log("annulation de requête non envoyée")
        }
    }

    override fun stop() {
        stopped = true
        socket.close()
    }

    private fun handshake(): CipherState {
        val initiator = NoiseXXInitiator()
        transmit(initiator.writeMessage1())
        val message2 = socket.receive(HANDSHAKE_TIMEOUT_MS) ?: throw IOException("fermée pendant le handshake")
        initiator.readMessage2(message2)
        // Le jeton a déjà authentifié l'appareil à l'ouverture ; le message 3 est vide.
        transmit(initiator.writeMessage3())
        val (send, receive) = initiator.split()
        sendCipher = send
        log("session Noise établie")
        return receive
    }

    private fun openControlStream() {
        streamId = 1
        sendFrame(ServiceFrame.Request(streamId, "POST", CONTROL_PATH))
        sendMessage(device.registerMessage(registerId, commands.specs()))
        log("link.register envoyé pour ${device.nodeId}")
    }

    private fun transmit(data: ByteArray) {
        if (!socket.send(data)) throw IOException("envoi impossible")
    }

    /** Chiffrer avance le nonce : le verrou couvre le chiffrement et l'envoi. */
    private fun sendFrame(frame: ServiceFrame, service: ServiceType = ServiceType.GADGET) = synchronized(sendLock) {
        try {
            for (part in NoiseFraming.encode(Envelope.encodeRequest(service, frame))) {
                transmit(sendCipher.encryptWithAd(NO_DATA, part))
            }
        } catch (e: Exception) {
            // Livraison incertaine : ce chiffrement ne doit plus servir.
            socket.close()
            throw e
        }
    }

    /**
     * Un message sans réponse sur le flux de contrôle, une fois l'appareil enregistré. Faux s'il
     * n'est pas parti.
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
                log("connexion de contrôle fermée")
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
                    log("flux de contrôle réinitialisé (code ${frame.code})")
                    return Outcome.CLOSED
                }
                is ServiceFrame.Response -> {
                    if (frame.status !in 200..299) {
                        log("$CONTROL_PATH refusé : HTTP ${frame.status}")
                        return if (frame.status == 403) Outcome.FORBIDDEN else Outcome.CLOSED
                    }
                    frame.body to frame.endBody
                }
                is ServiceFrame.BodyChunk -> frame.data to frame.endBody
                is ServiceFrame.Request -> throw NoiseError("requête inattendue venant du serveur")
            }
            for (message in messages.feed(data)) handle(message)?.let { return it }
            if (ended) {
                log("flux de contrôle terminé par la VM")
                return Outcome.CLOSED
            }
        }
    }

    private fun handle(message: JSONObject): Outcome? {
        if (message.opt("id") == registerId && !message.has("method")) {
            if (isSet(message.opt("error")) || message.opt("ok") == false) {
                log("link.register refusé")
                return Outcome.FORBIDDEN
            }
            if (!registered) {
                registered = true
                log("enregistré auprès de Muse")
                onRegistered()
            }
            return null
        }
        when (message.opt("event")) {
            "link.unpaired", "node.unpaired" -> {
                log("Muse a retiré cet appareil")
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
                    log("commande $shown : ${if (result.optBoolean("ok")) "ok" else "refusée"}")
                    reply(result)
                } catch (e: Exception) {
                    log("réponse non envoyée : ${e.javaClass.simpleName}")
                } finally {
                    invokes.release()
                }
            }
        } catch (e: RejectedExecutionException) {
            invokes.release()
        }
    }
}
