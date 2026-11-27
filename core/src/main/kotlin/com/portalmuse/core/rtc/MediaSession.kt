// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Source : linux/src/musegadget/rtc/media.py
package com.portalmuse.core.rtc

import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val OPUS_PAYLOAD = 111
private const val H264_PAYLOAD = 127
private const val CLIENT_AUDIO_SSRC = 100
private const val CLIENT_VIDEO_SSRC = 101
private const val SERVER_AUDIO_SSRC = 200
private const val SERVER_VIDEO_SSRC = 201
private const val CHECK_INTERVAL_MS = 200L
private const val CONSENT_INTERVAL_MS = 4_000L
private const val CONSENT_TIMEOUT_MS = 30_000L
private const val RESPONSE_WINDOW_MS = 5_000L
/** 20 ms à 48 kHz. */
private const val OPUS_FRAME_SAMPLES = 960

/** Ce que le média livre, depuis les fils de réception. */
interface MediaListener {
    /** Un paquet Opus de Muse, dans l'ordre. */
    fun audio(opus: ByteArray)

    /** Une image H.264 complète au format « Annexe B ». */
    fun video(unit: ByteArray)
}

class MediaStats {
    @Volatile var transport = ""
    @Volatile var audioPackets = 0
    @Volatile var videoFrames = 0
    @Volatile var rejected = 0
}

/**
 * Le média d'un appel : vérifications ICE-lite en UDP et en TCP (RFC 4571) à la fois, le premier
 * chemin qui répond est gardé ; puis SRTP et RTP.
 */
class MediaSession(
    private val offer: JoinSecrets,
    private val answer: JoinAnswer,
    private val host: String,
    private val port: Int,
    private val listener: MediaListener,
    /** Les adresses vers lesquelles on accepte d'envoyer. */
    private val permitted: (InetAddress) -> Boolean,
    private val udp: Boolean = true,
    private val tcp: Boolean = true,
) {
    private class Path(val kind: String, val send: (ByteArray) -> Unit, val close: () -> Unit)
    private class Check(val path: Path, val sentAt: Long, val transaction: ByteArray)

    val stats = MediaStats()
    private val random = SecureRandom()
    private val srtp = Srtp(offer.srtpKey)
    private val username = "${answer.serverUfrag}:${offer.clientUfrag}"
    private val tie = random.nextLong()
    private val paths = CopyOnWriteArrayList<Path>()
    private val pending = LinkedHashMap<String, Check>()
    private val connected = CountDownLatch(1)
    private val video = H264Depacketizer()
    private val lock = Any()
    @Volatile private var selected: Path? = null
    @Volatile private var closed = false
    private var sequence = random.nextInt(1 shl 16)
    private var timestamp = random.nextInt().toLong() and 0xFFFFFFFFL
    private var audioSequence: Int? = null
    private var lastKeyframeRequest = 0L
    private var lastConsent = 0L

    /** Quand le dernier paquet de Muse est arrivé. */
    @Volatile var lastMediaAt = 0L
        private set

    @Volatile var lost = false
        private set

    private fun now() = System.nanoTime() / 1_000_000

    /** Bloque jusqu'à un chemin ouvert ; lève [IOException] sinon. Renvoie « udp » ou « tcp ». */
    fun connect(timeoutMillis: Long): String {
        val named = listOf(answer.ipv4, answer.ipv6).filter { it.isNotEmpty() } + host
        val addresses = named.flatMap { name ->
            try {
                InetAddress.getAllByName(name).toList()
            } catch (e: IOException) {
                emptyList()
            }
        }.distinct().filter(permitted).take(8)
        if (addresses.isEmpty()) throw IOException("aucune adresse utilisable pour le média")
        for (address in addresses) {
            val target = InetSocketAddress(address, port)
            if (udp) openUdp(target)
            if (tcp) thread(name = "media-tcp", isDaemon = true) { openTcp(target) }
        }
        val deadline = now() + timeoutMillis
        while (!closed && selected == null && now() < deadline) {
            paths.forEach { check(it, nominate = false) }
            connected.await(CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
        val winner = selected
        if (winner == null) {
            close()
            throw IOException("aucune connexion au média")
        }
        for (path in paths) if (path !== winner) path.close()
        paths.retainAll(listOf(winner))
        stats.transport = winner.kind
        lastMediaAt = now()
        synchronized(lock) { lastConsent = now() }
        // Les vérifications en course ne faisaient que tester ; on désigne le gagnant.
        check(winner, nominate = true)
        thread(name = "media-consent", isDaemon = true) { keepConsent() }
        requestKeyframe()
        return winner.kind
    }

    private fun openUdp(target: InetSocketAddress) {
        try {
            val socket = DatagramSocket()
            socket.receiveBufferSize = 1 shl 20
            socket.connect(target)
            val path = Path(
                "udp",
                { data -> try { socket.send(DatagramPacket(data, data.size)) } catch (e: IOException) { Unit } },
                { socket.close() },
            )
            paths.add(path)
            thread(name = "media-udp", isDaemon = true) {
                val buffer = ByteArray(65_536)
                while (!socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: IOException) {
                        // Un port injoignable remonte ici ; les autres chemins continuent.
                        if (socket.isClosed || selected != null) break else continue
                    }
                    receive(path, buffer.copyOf(packet.length))
                }
            }
        } catch (e: IOException) {
            return
        }
    }

    private fun openTcp(target: InetSocketAddress) {
        val socket = Socket()
        try {
            socket.connect(target, 5_000)
            socket.tcpNoDelay = true
            val output = socket.getOutputStream()
            val path = Path(
                "tcp",
                { data ->
                    val framed = ByteBuffer.allocate(2 + data.size).putShort(data.size.toShort()).put(data).array()
                    synchronized(output) { try { output.write(framed) } catch (e: IOException) { Unit } }
                },
                { runCatching { socket.close() } },
            )
            if (closed || selected != null) return socket.close()
            paths.add(path)
            val input = DataInputStream(socket.getInputStream().buffered())
            try {
                while (!closed) {
                    val packet = ByteArray(input.readUnsignedShort())
                    input.readFully(packet)
                    receive(path, packet)
                }
            } catch (e: IOException) {
                if (path === selected && !closed) lost = true
            }
        } catch (e: IOException) {
            runCatching { socket.close() }
        }
    }

    private fun check(path: Path, nominate: Boolean) {
        val priority = ((if (path.kind == "udp") 110 else 90) shl 24) or (0xFFFF shl 8) or 255
        val request = Stun.bindingRequest(username, offer.serverPwd, priority, tie, nominate)
        synchronized(lock) {
            pending[request.transaction.joinToString(",")] = Check(path, now(), request.transaction)
            if (pending.size > 64) pending.remove(pending.keys.first())
        }
        path.send(request.packet)
    }

    private fun keepConsent() {
        while (!closed) {
            try {
                Thread.sleep(CONSENT_INTERVAL_MS)
            } catch (e: InterruptedException) {
                return
            }
            val path = selected ?: return
            if (closed) return
            if (synchronized(lock) { now() - lastConsent } > CONSENT_TIMEOUT_MS) {
                lost = true
                return
            }
            check(path, nominate = true)
        }
    }

    private fun receive(path: Path, data: ByteArray) {
        if (Stun.isStun(data)) {
            val key = data.copyOfRange(8, 20).joinToString(",")
            synchronized(lock) {
                val sent = pending[key] ?: return
                if (sent.path !== path || now() - sent.sentAt > RESPONSE_WINDOW_MS ||
                    !Stun.isSuccess(data, sent.transaction, offer.serverPwd)
                ) return
                pending.remove(key)
                if (path === selected) lastConsent = now()
                if (selected == null && !closed) {
                    selected = path
                    connected.countDown()
                }
            }
            return
        }
        if (path !== selected || data.size < 12 || Rtp.isRtcp(data)) return
        // Seulement les deux flux du serveur : cela écarte aussi nos paquets renvoyés, chiffrés
        // avec la même clé.
        val ssrc = ByteBuffer.wrap(data).getInt(8)
        if (ssrc != SERVER_AUDIO_SSRC && ssrc != SERVER_VIDEO_SSRC) return
        val packet = try {
            Rtp.parse(srtp.unprotectRtp(data))
        } catch (e: SrtpError) {
            stats.rejected++
            return
        } catch (e: IllegalArgumentException) {
            stats.rejected++
            return
        }
        lastMediaAt = now()
        when (packet.payloadType) {
            OPUS_PAYLOAD -> {
                // En UDP un paquet peut arriver après un plus récent : on le laisse tomber.
                val previous = audioSequence
                if (previous != null && (packet.sequence - previous) and 0xFFFF >= 0x8000) return
                audioSequence = packet.sequence
                stats.audioPackets++
                listener.audio(packet.payload)
            }
            H264_PAYLOAD -> {
                val unit = synchronized(video) { video.push(packet) }
                if (video.needsKeyframe) requestKeyframe()
                if (unit != null) {
                    stats.videoFrames++
                    listener.video(unit)
                }
            }
        }
    }

    fun requestKeyframe() {
        if (closed) return
        synchronized(lock) {
            if (now() - lastKeyframeRequest < 1_000) return
            lastKeyframeRequest = now()
        }
        selected?.send?.invoke(srtp.protectRtcp(Rtp.pictureLoss(CLIENT_VIDEO_SSRC, SERVER_VIDEO_SSRC)))
    }

    /** Un paquet Opus de 20 ms. */
    @Synchronized
    fun sendAudio(opus: ByteArray) {
        val path = selected ?: return
        if (closed) return
        val packet = Rtp.build(OPUS_PAYLOAD, sequence and 0xFFFF, timestamp, CLIENT_AUDIO_SSRC, opus)
        sequence = (sequence + 1) and 0xFFFF
        timestamp = (timestamp + OPUS_FRAME_SAMPLES) and 0xFFFFFFFFL
        path.send(srtp.protectRtp(packet))
    }

    fun close() {
        closed = true
        paths.forEach { it.close() }
        paths.clear()
        connected.countDown()
    }
}
