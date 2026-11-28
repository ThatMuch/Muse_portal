// Kotlin port of muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0). Source:
// linux/src/musegadget/video_call.py
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

private const val FRAME_BYTES = 960 * 2 // 20 ms at 48 kHz
private const val SETTLE_MS = 200L
private const val ECHO_TAIL_MS = 300L
/** Muse's sound carries a faint noise between its replies: only louder sound counts. */
private const val AUDIBLE_RMS = 300.0
/** An isolated noise does not extend the call: it takes [SUSTAINED] loud packets within [SUSTAINED_MS]. */
private const val SUSTAINED = 5
private const val SUSTAINED_MS = 500L
private const val FAREWELL_MS = 2_500L
/** After a "goodbye": the time left to Muse to start, then finish, its reply. */
private const val GOODBYE_WAIT_MS = 6_000L
private const val GOODBYE_SETTLE_MS = 1_500L
/** The transcription arrives a few seconds after the speech. */
private const val OWN_SPEECH_MS = 12_000L
private const val SAME_REQUEST_MS = 5_000L
private const val HEARD = "conversation.item.input_audio_transcription.completed"
private const val MAX_EVENT_BYTES = 256 * 1024
private const val MAX_QUEUED_PACKETS = 250 // 5 s

/** The microphone, speaker and Opus codec of a video call. Sound is 48 kHz mono PCM. */
interface VideoAudio {
    /** 20 ms of microphone (1920 bytes); `null` once capture has stopped. */
    fun read(): ByteArray?

    /** Zero, one or several Opus packets. */
    fun encode(pcm: ByteArray): List<ByteArray>

    /** The sound of a received packet; may be empty. */
    fun decode(opus: ByteArray): ByteArray

    /** May block while the speaker accepts the sound. */
    fun play(pcm: ByteArray)

    fun stopCapture()
}

/**
 * A video call: signalling goes through the Muse session, media goes directly to the endpoint the VM gives.
 * Microphone muted while Muse speaks; only its voice extends the call. No picture leaves the Portal. A call is
 * used only once.
 */
class VideoCall(
    private val link: MuseStreams,
    private val deviceId: String,
    private val audio: VideoAudio,
    /** Muse's pictures, from the receiving thread. */
    private val onVideo: (ByteArray) -> Unit,
    private val onEvent: (CallEvent) -> Unit = {},
    private val idleTimeoutMillis: Long = 20_000,
    /** After "Hey Muse": the call closes if Muse has said nothing after this time. */
    private val firstTurnTimeoutMillis: Long? = null,
    private val overallTimeoutMillis: Long = 300_000,
    private val readyTimeoutMillis: Long = 35_000,
    private val cleanupTimeoutMillis: Long = 10_000,
    private val mediaTimeoutMillis: Long = 10_000,
    private val permitted: (InetAddress) -> Boolean = WebImages::isPublic,
    private val log: (String) -> Unit = {},
    /** The name given to Muse, which a "goodbye" may contain. */
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
    private var volumeAsked: Pair<VolumeRequest, Long>? = null
    @Volatile private var volumeDone = false
    /** Last time someone spoke in front of this device. */
    @Volatile private var spokeAt = 0L
    private var events: MuseStream? = null
    private val received = LinkedBlockingDeque<ByteArray>(MAX_QUEUED_PACKETS)

    private fun clock() = System.nanoTime() / 1_000_000

    fun hangUp() {
        hungUp = true
    }

    /** A last order about pictures: call it when the screen can start showing again. */
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
            log("video call interrupted: ${e.message}")
            CallResult.Failed(e.failure, e.status)
        } catch (e: IOException) {
            log("video call interrupted: ${e.message}")
            if (hungUp) CallResult.Ended(CallEnd.HUNG_UP) else CallResult.Failed(CallFailure.CONNECTION, null)
        } catch (e: IllegalArgumentException) {
            log("video call: unexpected answer (${e.message})")
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
                    "video summary: ${it.stats.transport}, ${it.stats.videoFrames} pictures, " +
                        "${it.stats.audioPackets} sound packets, ${it.stats.rejected} rejected",
                )
                log(
                    "video microphone summary: $micFrames chunks ($micAudible with sound, $micMuted forced " +
                        "silence), peak level ${micPeak.toInt()}, $sentPackets packets sent; Muse audible in " +
                        "$museAudible packets",
                )
                it.close()
            }
            if (admitted) endCall(startedAt)
        }
    }

    /** The endpoint, the join request, then the media connection. */
    private fun start(onAdmission: () -> Unit): MediaSession {
        val endpoint = call("GET", "/voice/calls/media-endpoint", null, readyTimeoutMillis, "media endpoint")
        val host = endpoint.opt("host") as? String
        val port = endpoint.opt("port") as? Int
        val type = ENDPOINT_TYPES[endpoint.opt("vip_type") as? String]
        if (host.isNullOrEmpty() || port == null || port !in 1..65535 || type == null) {
            throw Problem(CallFailure.PROTOCOL, "invalid media endpoint")
        }
        val offer = Join.build(host, port, type)
        val body = JSONObject()
            .put("payload_b64", Base64.getEncoder().encodeToString(offer.payload))
            .put("call_id", callId).put("call_media_type", "video").put("device", "mcu-wearable")
            .put("device_id", deviceId).put("proactive_greeting_enabled", false)
            .put("enable_media_to_cosmos", endpoint.optBoolean("enable_media_to_cosmos"))
        // Even an uncertain request may have opened the call: it will have to be closed.
        onAdmission()
        val joined = call("POST", "/voice/calls/join", body, readyTimeoutMillis, "joining the call")
        val payload = joined.opt("payload_b64") as? String
            ?: throw Problem(CallFailure.PROTOCOL, "no media granted")
        val answer = Join.parseResponse(Base64.getDecoder().decode(payload))
        val session = MediaSession(
            offer.secrets, answer, host, port,
            object : MediaListener {
                override fun audio(opus: ByteArray) {
                    // Bounds latency: after a hiccup, the oldest sound is forgotten.
                    while (!received.offerLast(opus)) received.pollFirst()
                }

                override fun video(unit: ByteArray) {
                    if (!ended) onVideo(unit)
                }
            },
            permitted,
        )
        media = session
        log("video call: media connected over ${session.connect(readyTimeoutMillis)}")
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
                // While Muse speaks, the microphone mostly picks up its echo: silence is sent.
                val gated = clock() < playingUntil + ECHO_TAIL_MS
                // Levels only, never the sound.
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
            if (!ended) log("video call: microphone failed (${e.javaClass.simpleName})")
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
                            // The microphone is muted before the sound comes out.
                            playingUntil = clock() + pcm.size / 96 + SETTLE_MS
                            loud.addLast(clock())
                            while (loud.isNotEmpty() && clock() - loud.first() > SUSTAINED_MS) loud.removeFirst()
                            if (loud.size >= SUSTAINED) activity = clock()
                        }
                        // After a volume request the call is ending: the reply to it is not started.
                        if (!volumeDone) audio.play(pcm)
                    }
                }
                val now = clock() < playingUntil
                if (now != speaking) {
                    speaking = now
                    onEvent(CallEvent.Speaking(now))
                }
            }
        } catch (e: RuntimeException) {
            if (!ended) log("video call: speaker failed (${e.javaClass.simpleName})")
            hungUp = true
        }
    }

    private fun watch(session: MediaSession): CallEnd {
        val started = clock()
        while (true) {
            val now = clock()
            if (hungUp) return CallEnd.HUNG_UP
            if (volumeDone) return CallEnd.VOLUME
            if (session.lost || now - session.lastMediaAt > mediaTimeoutMillis) {
                // Muse hanging up cuts its media; without any packet at all, it is the connection.
                if (session.stats.audioPackets + session.stats.videoFrames > 0) return CallEnd.REMOTE_END
                throw Problem(CallFailure.CONNECTION, "no media received")
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
     * Video has no event channel of its own. The conversation is followed during the call, for Muse's hang-up
     * request and to know what goes through it (names only).
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
                        // An oversized line (a snapshot) is dropped.
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
                    // What is said to Muse is also added to the conversation.
                    if (name == "message.user") readMessage(event, shapes)
                }
            }
        } catch (e: IOException) {
            if (!ended) log("video call events unavailable")
        } finally {
            if (seen.isNotEmpty()) {
                log("events during the video: " + seen.entries.joinToString { "${it.key} x${it.value}" })
            }
        }
    }

    /**
     * A relayed voice event: a stop order from Muse, or a "goodbye" from the user. [ours]: it carries this
     * call's identifier. Otherwise it only counts if someone has just spoken here, another call possibly
     * happening on the phone.
     */
    private fun read(event: JSONObject, ours: Boolean, shapes: MutableSet<String>) {
        // The structure only, to understand these events: a few different samples.
        val shape = VoiceEvents.shape(event)
        if (shapes.size < 14 && shapes.add(shape)) log("shape of a voice event (call recognized: $ours): $shape")
        val here = ours || spokeAt != 0L && clock() - spokeAt < OWN_SPEECH_MS
        for (item in VoiceEvents.objects(event)) {
            if (here && item.opt("type") == HEARD) (item.opt("transcript") as? String)?.let(::volume)
            val stop = item.opt("type") == "session_control" &&
                (item.opt("name") as? String)?.trim().equals("stop", ignoreCase = true)
            val goodbye = item.opt("type") == HEARD &&
                (item.opt("transcript") as? String)?.let { Farewell.matches(it, museName) } == true
            if (!stop && !goodbye) continue
            if (!here) {
                log("end requested, but not by this device")
            } else if (stop) {
                stopAsked = clock()
            } else if (goodbyeAt == 0L) {
                log("“goodbye” heard")
                goodbyeAt = clock()
            }
        }
    }

    private fun readMessage(event: JSONObject, shapes: MutableSet<String>) {
        val shape = VoiceEvents.shape(event)
        if (shapes.size < 14 && shapes.add(shape)) log("shape of a message during the video: $shape")
        val payload = event.optJSONObject("payload") ?: return
        val said = listOf("display_text", "transcript", "content", "text")
            .firstNotNullOfOrNull { (payload.opt(it) as? String)?.takeIf { value -> value.isNotBlank() } } ?: return
        val here = spokeAt != 0L && clock() - spokeAt < OWN_SPEECH_MS
        if (here) volume(said)
        if (here && goodbyeAt == 0L && Farewell.matches(said, museName)) {
            log("“goodbye” heard")
            goodbyeAt = clock()
        }
    }

    /** The same sentence arrives both as a transcription and as a message: it counts once. */
    private fun volume(said: String) {
        val request = VolumeRequest.parse(said, museName)
        if (request == null) {
            if (VolumeRequest.mentions(said)) log("loudness mentioned, no volume request recognized")
            return
        }
        val last = volumeAsked
        if (last != null && last.first == request && clock() - last.second < SAME_REQUEST_MS) return
        volumeAsked = request to clock()
        log("volume asked aloud")
        onEvent(CallEvent.Volume(request))
        volumeDone = true
    }

    /** A small request to the VM, which answers `{"ok":true,"result":{…}}`. */
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
            if (status !in 200..299) throw Problem(CallFailure.REFUSED, "$what refused: HTTP $status", status)
            val json = try {
                JSONObject(response.toString("UTF-8"))
            } catch (e: JSONException) {
                throw Problem(CallFailure.PROTOCOL, "$what: unreadable answer")
            }
            val result = json.optJSONObject("result")
            if (json.opt("ok") != true || result == null) {
                throw Problem(CallFailure.REFUSED, "$what refused", status)
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
            if (call("POST", "/voice/end-call", body, cleanupTimeoutMillis, "end of call").opt("ok") != true) {
                log("end of call not confirmed by the server")
            }
        } catch (e: Problem) {
            log("end of call not confirmed: ${e.message}")
        } catch (e: IOException) {
            log("end of call not confirmed: ${e.javaClass.simpleName}")
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
