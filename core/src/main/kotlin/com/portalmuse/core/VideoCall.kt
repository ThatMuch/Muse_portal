// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Source : linux/src/musegadget/video_call.py
package com.portalmuse.core

import com.portalmuse.core.rtc.ENDPOINT_TYPES
import com.portalmuse.core.rtc.Join
import com.portalmuse.core.rtc.MediaListener
import com.portalmuse.core.rtc.MediaSession
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val FRAME_BYTES = 960 * 2 // 20 ms à 48 kHz
private const val SETTLE_MS = 200L
private const val ECHO_TAIL_MS = 300L
/** Le son de Muse porte un léger bruit entre ses réponses : seul un son plus fort compte. */
private const val AUDIBLE_RMS = 300.0
/** Un bruit isolé ne prolonge pas l'appel : il faut [SUSTAINED] paquets forts en [SUSTAINED_MS]. */
private const val SUSTAINED = 5
private const val SUSTAINED_MS = 500L
private const val FAREWELL_MS = 2_500L
/** Après un « au revoir » : le temps laissé à Muse pour commencer, puis finir, sa réponse. */
private const val GOODBYE_WAIT_MS = 6_000L
private const val GOODBYE_SETTLE_MS = 1_500L
/** La transcription arrive quelques secondes après la parole. */
private const val OWN_SPEECH_MS = 12_000L
private const val HEARD = "conversation.item.input_audio_transcription.completed"
private const val MAX_EVENT_BYTES = 256 * 1024
private const val MAX_QUEUED_PACKETS = 250 // 5 s

/** Le micro, le haut-parleur et le codec Opus d'un appel vidéo. Le son est en PCM 48 kHz mono. */
interface VideoAudio {
    /** 20 ms de micro (1920 octets) ; `null` une fois la capture arrêtée. */
    fun read(): ByteArray?

    /** Zéro, un ou plusieurs paquets Opus. */
    fun encode(pcm: ByteArray): List<ByteArray>

    /** Le son d'un paquet reçu ; peut être vide. */
    fun decode(opus: ByteArray): ByteArray

    /** Peut bloquer le temps que le haut-parleur accepte le son. */
    fun play(pcm: ByteArray)

    fun stopCapture()
}

/**
 * Un appel vidéo : la signalisation passe par la session Muse, le média va directement au point
 * d'accès que donne la VM. Micro coupé pendant que Muse parle ; seule sa voix prolonge l'appel.
 * Aucune image ne part du Portal. Un appel ne sert qu'une fois.
 */
class VideoCall(
    private val link: MuseStreams,
    private val deviceId: String,
    private val audio: VideoAudio,
    /** Les images de Muse, depuis le fil de réception. */
    private val onVideo: (ByteArray) -> Unit,
    private val onEvent: (CallEvent) -> Unit = {},
    private val idleTimeoutMillis: Long = 20_000,
    /** Après « Hey Muse » : l'appel se ferme si Muse n'a rien dit au bout de ce temps. */
    private val firstTurnTimeoutMillis: Long? = null,
    private val overallTimeoutMillis: Long = 300_000,
    private val readyTimeoutMillis: Long = 35_000,
    private val cleanupTimeoutMillis: Long = 10_000,
    private val mediaTimeoutMillis: Long = 10_000,
    private val permitted: (InetAddress) -> Boolean = WebImages::isPublic,
    private val log: (String) -> Unit = {},
    /** Le nom donné à Muse, qu'un « au revoir » peut contenir. */
    private val museName: String? = null,
) {
    private val callId = UUID.randomUUID().toString()
    @Volatile private var hungUp = false
    @Volatile private var ended = false
    @Volatile private var playingUntil = 0L
    @Volatile private var activity = 0L
    @Volatile private var media: MediaSession? = null
    @Volatile private var stopAsked = 0L
    @Volatile private var goodbyeAt = 0L
    /** Dernière fois qu'on a parlé devant cet appareil. */
    @Volatile private var spokeAt = 0L
    private var events: MuseStream? = null
    private val received = LinkedBlockingDeque<ByteArray>(MAX_QUEUED_PACKETS)

    private fun clock() = System.nanoTime() / 1_000_000

    fun hangUp() {
        hungUp = true
    }

    /** Un dernier ordre d'images : à appeler quand l'écran peut recommencer à afficher. */
    fun requestKeyframe() {
        media?.requestKeyframe()
    }

    private class Problem(val failure: CallFailure, message: String, val status: Int? = null) : Exception(message)

    fun run(): CallResult {
        val startedAt = OffsetDateTime.now(ZoneOffset.UTC)
        var admitted = false
        val workers = mutableListOf<Thread>()
        return try {
            val session = start { admitted = true }
            if (hungUp) return CallResult.Ended(CallEnd.HUNG_UP)
            activity = clock()
            onEvent(CallEvent.Connected)
            workers += thread(name = "video-microphone") { sendLoop(session) }
            workers += thread(name = "video-speaker") { playLoop() }
            workers += thread(name = "video-events") { followEvents() }
            CallResult.Ended(watch(session))
        } catch (e: Problem) {
            log("appel vidéo interrompu : ${e.message}")
            CallResult.Failed(e.failure, e.status)
        } catch (e: IOException) {
            log("appel vidéo interrompu : ${e.message}")
            if (hungUp) CallResult.Ended(CallEnd.HUNG_UP) else CallResult.Failed(CallFailure.CONNECTION, null)
        } catch (e: IllegalArgumentException) {
            log("appel vidéo : réponse inattendue (${e.message})")
            CallResult.Failed(CallFailure.PROTOCOL, null)
        } catch (e: NoiseError) {
            CallResult.Failed(CallFailure.CONNECTION, null)
        } finally {
            ended = true
            audio.stopCapture()
            events?.close()
            workers.forEach { it.join(1_000) }
            media?.let {
                log(
                    "bilan vidéo : ${it.stats.transport}, ${it.stats.videoFrames} images, " +
                        "${it.stats.audioPackets} paquets de son, ${it.stats.rejected} rejetés",
                )
                log(
                    "bilan micro vidéo : $micFrames morceaux (dont $micAudible avec du son, $micMuted en " +
                        "silence forcé), niveau maximal ${micPeak.toInt()}, $sentPackets paquets envoyés ; " +
                        "Muse audible dans $museAudible paquets",
                )
                it.close()
            }
            if (admitted) endCall(startedAt)
        }
    }

    /** Le point d'accès, la demande d'entrée, puis la connexion du média. */
    private fun start(onAdmission: () -> Unit): MediaSession {
        val endpoint = call("GET", "/voice/calls/media-endpoint", null, readyTimeoutMillis, "point d'accès média")
        val host = endpoint.opt("host") as? String
        val port = endpoint.opt("port") as? Int
        val type = ENDPOINT_TYPES[endpoint.opt("vip_type") as? String]
        if (host.isNullOrEmpty() || port == null || port !in 1..65535 || type == null) {
            throw Problem(CallFailure.PROTOCOL, "point d'accès média invalide")
        }
        val offer = Join.build(host, port, type)
        val body = JSONObject()
            .put("payload_b64", Base64.getEncoder().encodeToString(offer.payload))
            .put("call_id", callId).put("call_media_type", "video").put("device", "mcu-wearable")
            .put("device_id", deviceId).put("proactive_greeting_enabled", false)
            .put("enable_media_to_cosmos", endpoint.optBoolean("enable_media_to_cosmos"))
        // Même une demande incertaine peut avoir ouvert l'appel : il faudra le clore.
        onAdmission()
        val joined = call("POST", "/voice/calls/join", body, readyTimeoutMillis, "entrée dans l'appel")
        val payload = joined.opt("payload_b64") as? String
            ?: throw Problem(CallFailure.PROTOCOL, "aucun média accordé")
        val answer = Join.parseResponse(Base64.getDecoder().decode(payload))
        val session = MediaSession(
            offer.secrets, answer, host, port,
            object : MediaListener {
                override fun audio(opus: ByteArray) {
                    // Borne la latence : après un à-coup, le plus ancien son est oublié.
                    while (!received.offerLast(opus)) received.pollFirst()
                }

                override fun video(unit: ByteArray) {
                    if (!ended) onVideo(unit)
                }
            },
            permitted,
        )
        media = session
        log("appel vidéo : média connecté en ${session.connect(readyTimeoutMillis)}")
        return session
    }

    private var micFrames = 0
    private var micAudible = 0
    private var micMuted = 0
    private var micPeak = 0.0
    private var sentPackets = 0
    @Volatile private var museAudible = 0

    private fun sendLoop(session: MediaSession) {
        val silence = ByteArray(FRAME_BYTES)
        try {
            while (!ended) {
                val pcm = audio.read() ?: return
                // Pendant que Muse parle, le micro capte surtout son écho : on envoie du silence.
                val gated = clock() < playingUntil + ECHO_TAIL_MS
                // Des niveaux seulement, jamais le son.
                val level = rms(pcm)
                micFrames++
                if (gated) micMuted++
                else if (level >= AUDIBLE_RMS) {
                    micAudible++
                    spokeAt = clock()
                }
                micPeak = maxOf(micPeak, level)
                for (packet in audio.encode(if (gated) silence else pcm)) {
                    session.sendAudio(packet)
                    sentPackets++
                }
            }
        } catch (e: RuntimeException) {
            if (!ended) log("appel vidéo : micro en échec (${e.javaClass.simpleName})")
            hungUp = true
        }
    }

    private fun playLoop() {
        var speaking = false
        val loud = ArrayDeque<Long>()
        try {
            while (!ended) {
                val opus = received.pollFirst(50, TimeUnit.MILLISECONDS)
                if (opus != null) {
                    val pcm = audio.decode(opus)
                    if (pcm.isNotEmpty()) {
                        if (rms(pcm) > AUDIBLE_RMS) {
                            museAudible++
                            // Le micro est coupé avant que le son ne sorte.
                            playingUntil = clock() + pcm.size / 96 + SETTLE_MS
                            loud.addLast(clock())
                            while (loud.isNotEmpty() && clock() - loud.first() > SUSTAINED_MS) loud.removeFirst()
                            if (loud.size >= SUSTAINED) activity = clock()
                        }
                        audio.play(pcm)
                    }
                }
                val now = clock() < playingUntil
                if (now != speaking) {
                    speaking = now
                    onEvent(CallEvent.Speaking(now))
                }
            }
        } catch (e: RuntimeException) {
            if (!ended) log("appel vidéo : haut-parleur en échec (${e.javaClass.simpleName})")
            hungUp = true
        }
    }

    private fun watch(session: MediaSession): CallEnd {
        val started = clock()
        while (true) {
            val now = clock()
            if (hungUp) return CallEnd.HUNG_UP
            if (session.lost || now - session.lastMediaAt > mediaTimeoutMillis) {
                // Muse qui raccroche coupe son média ; sans aucun paquet, c'est la connexion.
                if (session.stats.audioPackets + session.stats.videoFrames > 0) return CallEnd.REMOTE_END
                throw Problem(CallFailure.CONNECTION, "aucun média reçu")
            }
            if (stopAsked != 0L && now - stopAsked >= FAREWELL_MS && now >= playingUntil) return CallEnd.REMOTE_STOP
            val goodbye = goodbyeAt
            if (goodbye != 0L) {
                val answered = activity > goodbye && now >= playingUntil + GOODBYE_SETTLE_MS
                val silent = activity <= goodbye && now - goodbye >= GOODBYE_WAIT_MS
                if (answered || silent) return CallEnd.GOODBYE
            }
            if (now - started >= overallTimeoutMillis) return CallEnd.TOO_LONG
            val first = firstTurnTimeoutMillis
            if (first != null && museAudible == 0 && now - started >= first) return CallEnd.NOTHING_ASKED
            if (now >= playingUntil && now - activity >= idleTimeoutMillis) return CallEnd.IDLE
            Thread.sleep(100)
        }
    }

    /**
     * La vidéo n'a pas de canal d'événements propre. On suit la conversation le temps de l'appel,
     * pour la demande de raccrocher de Muse et pour savoir ce qui y passe (des noms seulement).
     */
    private fun followEvents() {
        val seen = LinkedHashMap<String, Int>()
        val shapes = LinkedHashSet<String>()
        try {
            val stream = link.open(
                ServiceType.DAEMON, "POST", "/chat/subscribe",
                listOf(
                    "Content-Type" to "application/json", "Accept" to "application/x-ndjson",
                    "x-request-id" to UUID.randomUUID().toString(), "x-app-id" to "musegadget",
                ),
                "{}".toByteArray(),
            )
            events = stream
            if (ended) return stream.close()
            if (stream.awaitStatus(cleanupTimeoutMillis) !in 200..299) return
            val line = ByteArrayOutputStream()
            while (!ended) {
                for (byte in stream.read(100) ?: break) {
                    if (byte != '\n'.code.toByte()) {
                        line.write(byte.toInt())
                        // Une ligne démesurée (un instantané) est abandonnée.
                        if (line.size() > MAX_EVENT_BYTES) line.reset()
                        continue
                    }
                    val text = line.toString("UTF-8")
                    line.reset()
                    val event = try {
                        JSONObject(text)
                    } catch (e: JSONException) {
                        continue
                    }
                    val name = (event.opt("event") as? String ?: "?").take(48)
                    if (seen.size < 40 || name in seen) seen[name] = (seen[name] ?: 0) + 1
                    if (name.startsWith("hatch.voice")) read(event, callId in text, shapes)
                    // Ce qui est dit à Muse est aussi versé dans la conversation.
                    if (name == "message.user") readMessage(event, shapes)
                }
            }
        } catch (e: IOException) {
            if (!ended) log("événements de l'appel vidéo indisponibles")
        } finally {
            if (seen.isNotEmpty()) {
                log("événements pendant la vidéo : " + seen.entries.joinToString { "${it.key} x${it.value}" })
            }
        }
    }

    /**
     * Un événement vocal relayé : un ordre d'arrêt de Muse, ou un « au revoir » de l'utilisateur.
     * [ours] : il porte l'identifiant de cet appel. Sinon il n'est retenu que si on vient de
     * parler ici, un autre appel pouvant avoir lieu sur le téléphone.
     */
    private fun read(event: JSONObject, ours: Boolean, shapes: MutableSet<String>) {
        // La structure seule, pour comprendre ces événements : quelques exemplaires différents.
        val shape = VoiceEvents.shape(event)
        if (shapes.size < 14 && shapes.add(shape)) log("forme d'un événement vocal (appel reconnu : $ours) : $shape")
        val here = ours || spokeAt != 0L && clock() - spokeAt < OWN_SPEECH_MS
        for (item in VoiceEvents.objects(event)) {
            val stop = item.opt("type") == "session_control" &&
                (item.opt("name") as? String)?.trim().equals("stop", ignoreCase = true)
            val goodbye = item.opt("type") == HEARD &&
                (item.opt("transcript") as? String)?.let { Farewell.matches(it, museName) } == true
            if (!stop && !goodbye) continue
            if (!here) {
                log("fin demandée, mais pas par cet appareil")
            } else if (stop) {
                stopAsked = clock()
            } else if (goodbyeAt == 0L) {
                log("« au revoir » entendu")
                goodbyeAt = clock()
            }
        }
    }

    private fun readMessage(event: JSONObject, shapes: MutableSet<String>) {
        val shape = VoiceEvents.shape(event)
        if (shapes.size < 14 && shapes.add(shape)) log("forme d'un message pendant la vidéo : $shape")
        val payload = event.optJSONObject("payload") ?: return
        val said = listOf("display_text", "transcript", "content", "text")
            .firstNotNullOfOrNull { (payload.opt(it) as? String)?.takeIf { value -> value.isNotBlank() } } ?: return
        val here = spokeAt != 0L && clock() - spokeAt < OWN_SPEECH_MS
        if (here && goodbyeAt == 0L && Farewell.matches(said, museName)) {
            log("« au revoir » entendu")
            goodbyeAt = clock()
        }
    }

    /** Une petite requête à la VM, qui répond `{"ok":true,"result":{…}}`. */
    private fun call(verb: String, path: String, body: JSONObject?, timeout: Long, what: String): JSONObject {
        val headers = if (body == null) emptyList() else listOf("Content-Type" to "application/json")
        val stream = link.open(
            ServiceType.DAEMON, verb, path, headers, body?.toString()?.toByteArray() ?: ByteArray(0),
        )
        try {
            val deadline = clock() + timeout
            val status = stream.awaitStatus(timeout)
            val response = ByteArrayOutputStream()
            while (clock() < deadline && response.size() < 256 * 1024) {
                response.write(stream.read(100) ?: break)
            }
            if (status !in 200..299) throw Problem(CallFailure.REFUSED, "$what refusé : HTTP $status", status)
            val json = try {
                JSONObject(response.toString("UTF-8"))
            } catch (e: JSONException) {
                throw Problem(CallFailure.PROTOCOL, "$what : réponse illisible")
            }
            val result = json.optJSONObject("result")
            if (json.opt("ok") != true || result == null) {
                throw Problem(CallFailure.REFUSED, "$what refusé", status)
            }
            return result
        } finally {
            stream.close()
        }
    }

    private fun endCall(startedAt: OffsetDateTime) {
        try {
            val format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")
            val endedAt = maxOf(OffsetDateTime.now(ZoneOffset.UTC), startedAt.plusNanos(1_000_000))
            val body = JSONObject().put("call_id", callId)
                .put("call_started_at", startedAt.format(format))
                .put("call_ended_at", endedAt.format(format))
            if (call("POST", "/voice/end-call", body, cleanupTimeoutMillis, "fin d'appel").opt("ok") != true) {
                log("fin d'appel non confirmée par le serveur")
            }
        } catch (e: Problem) {
            log("fin d'appel non confirmée : ${e.message}")
        } catch (e: IOException) {
            log("fin d'appel non confirmée : ${e.javaClass.simpleName}")
        }
    }

    private fun rms(pcm: ByteArray): Double {
        var sum = 0.0
        for (i in 0 until pcm.size - 1 step 2) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toDouble()
            sum += sample * sample
        }
        return if (pcm.size < 2) 0.0 else Math.sqrt(sum / (pcm.size / 2))
    }
}
