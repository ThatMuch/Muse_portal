// Kotlin port of muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0). Source:
// linux/src/musegadget/voice_call.py
package com.portalmuse.core

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.concurrent.thread

private const val SAMPLE_RATE = 24_000
private const val FRAME_BYTES = 960 * 2
private const val BYTES_PER_SECOND = SAMPLE_RATE * 2
private const val MAX_AUDIO_BYTES = BYTES_PER_SECOND * 4
private const val MAX_PLAYBACK_BYTES = BYTES_PER_SECOND * 5
private const val MAX_HELD_TEXT_CHARS = 64 * 1024
private const val MAX_HELD_ITEMS = 4096
private const val MAX_LINE_BYTES = 1024 * 1024
private const val MAX_STREAM_BYTES = 4 * 1024 * 1024
private const val MAX_TRACKED_TURNS = 64
private const val MAX_SETTLED_TURNS = 1024
private const val SETTLE_MS = 200L
private const val DRAIN_MS = 5_200L
private const val TICK_MS = 50L
private const val AUDIBLE_RMS = 300.0

/** The call's microphone and speaker: 16 bit mono PCM at 24 kHz. */
interface CallAudio {
    /** The next microphone chunk (at most 1920 bytes); `null` once capture has stopped. */
    fun read(): ByteArray?

    /** May block while the speaker accepts the sound. */
    fun play(pcm: ByteArray)

    /** Drops the sound not heard yet. */
    fun flush()

    fun stopCapture()
}

sealed class CallEvent {
    object Connected : CallEvent()
    data class Speaking(val speaking: Boolean) : CallEvent()
    /** What Muse says: an addition, or the whole text when [final]. */
    data class Words(val text: String, val final: Boolean) : CallEvent()
    /** What Muse heard for a speech turn: an addition, or the whole text when [final]. */
    data class Heard(val item: String, val text: String, val final: Boolean) : CallEvent()
    /** The caller asked aloud for another volume. */
    data class Volume(val request: VolumeRequest) : CallEvent()
    /** Muse judged that this speech turn was not addressed to it: it will not answer. */
    data class Ignored(val item: String) : CallEvent()
}

/** [NOTHING_ASKED]: no request heard after a call opened by the wake word. */
enum class CallEnd { REMOTE_END, REMOTE_STOP, HUNG_UP, IDLE, TOO_LONG, NOTHING_ASKED, GOODBYE }

enum class CallFailure { REFUSED, NOT_READY, PROTOCOL, CONNECTION }

sealed class CallResult {
    data class Ended(val end: CallEnd) : CallResult()
    data class Failed(val failure: CallFailure, val status: Int?) : CallResult()
}

private class CallProblem(val failure: CallFailure, message: String, val status: Int? = null) : Exception(message)

private const val MAX_VOLUME_ITEMS = 200

/**
 * A real-time voice call, on a connection reserved for voice. Half duplex: the microphone is muted while Muse
 * speaks; [interrupt] cuts it off. A call is used only once.
 */
class VoiceCall(
    private val link: LinkSession,
    private val deviceId: String,
    private val audio: CallAudio,
    private val idleTimeoutMillis: Long = 45_000,
    private val overallTimeoutMillis: Long = 300_000,
    private val readyTimeoutMillis: Long = 35_000,
    private val cleanupTimeoutMillis: Long = 10_000,
    /** A turn never closed by the server does not hold the call beyond this silence. */
    private val stalledTurnTimeoutMillis: Long = 90_000,
    /**
     * For a call opened by the wake word: close it if Muse has recognized no request within this time. A false
     * alarm in front of the television must not leave the microphone open.
     */
    private val firstTurnTimeoutMillis: Long? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onEvent: (CallEvent) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private sealed class Held {
        class Audio(val pcm: ByteArrayOutputStream) : Held()
        class Words(val event: CallEvent.Words, val providers: List<String>) : Held()
    }

    private class Chunk(val pcm: ByteArray, val audible: Boolean)

    /** Protects the shared state. No network send happens under this lock. */
    private val lock = Object()
    private val callId = UUID.randomUUID().toString()
    private val gate = ReplyGate()
    private val playback = ArrayDeque<Chunk>()
    private val waiting = ArrayList<Held>()
    private val activeTurns = LinkedHashMap<String, String>()
    private val settledTurns = HashMap<String, String>()
    private var playbackBytes = 0
    private var audibleQueued = 0
    private var playbackEnd = 0L
    private var playingUntil = 0L
    private var flushes = 0
    private var receivedAudio = false
    private var held = false
    private var micEpoch = 0
    private var activity = 0L
    private var speaking = false
    private var threadId: String? = null
    private var admissionSent = false
    private var advisoryErrors = 0
    private var readyAt = 0L
    /** Start of the ongoing speech, as long as the server has neither closed nor dropped it. */
    private var speakingSince: Long? = null
    private var turnHeard = false
    private val volumeItems = HashSet<String>()
    /** Diagnostic counters: types and quantities, never content. */
    private val receivedTypes = LinkedHashMap<String, Int>()
    private var sentFrames = 0
    private var audibleFrames = 0
    private var mutedFrames = 0
    private var museAudioBytes = 0L
    private var workers = emptyList<Thread>()
    private lateinit var stream: MuseStream

    @Volatile private var ready = false
    @Volatile private var ended = false
    @Volatile private var hungUp = false
    @Volatile private var workerFailure: CallProblem? = null

    /** Hangs up. */
    fun hangUp() {
        hungUp = true
    }

    /** Cuts Muse off; the rest of that reply's sound will be ignored. */
    fun interrupt() {
        if (!ready || ended) return
        val response = synchronized(lock) {
            val open = gate.openResponse
            gate.interrupt()
            open
        }
        applyFlush()
        if (response != null) {
            try {
                send(JSONObject().put("type", "response.cancel").put("response_id", response))
            } catch (e: IOException) {
                log("reply cancellation not sent")
            }
        }
    }

    fun run(): CallResult {
        val started = clock()
        val startedAt = OffsetDateTime.now(ZoneOffset.UTC)
        activity = started
        return try {
            CallResult.Ended(converse(started))
        } catch (e: CallProblem) {
            log("call interrupted: ${e.message}")
            CallResult.Failed(e.failure, e.status)
        } catch (e: IOException) {
            log("call interrupted: ${e.javaClass.simpleName}")
            CallResult.Failed(CallFailure.CONNECTION, null)
        } catch (e: IllegalStateException) {
            log("call interrupted: limit exceeded")
            CallResult.Failed(CallFailure.PROTOCOL, null)
        } catch (e: NoiseError) {
            CallResult.Failed(CallFailure.CONNECTION, null)
        } finally {
            ended = true
            audio.stopCapture()
            flush()
            synchronized(lock) { lock.notifyAll() }
            workers.forEach { it.join(1_000) }
            log(summary())
            if (admissionSent) cleanUp(startedAt)
        }
    }

    private fun converse(started: Long): CallEnd {
        stream = link.open(
            ServiceType.DAEMON, "POST", "/voice/realtime?call_id=$callId",
            listOf("Content-Type" to "application/x-ndjson; charset=utf-8"), ByteArray(0),
            endBody = false, maxBufferedBytes = MAX_STREAM_BYTES,
        )
        // Even an uncertain send may have opened the call: it will have to be closed.
        admissionSent = true
        send(
            JSONObject().put("type", "hatch.voice.call.start").put("call_id", callId)
                .put("device", "mcu-wearable").put("device_id", deviceId)
                .put("call_media_type", "audio").put("proactive_greeting_enabled", false),
        )
        // This device can hang up at Muse's request, for example after a goodbye.
        send(JSONObject().put("type", "hatch.voice.capability.offer").put("end_call_stop", 1))
        val status = awaitStatus(started) ?: return CallEnd.HUNG_UP
        if (status !in 200..299) throw CallProblem(CallFailure.REFUSED, "refused: HTTP $status", status)
        workers = listOf(thread(name = "call-speaker") { playLoop() })
        val line = ByteArrayOutputStream()
        while (true) {
            due(started)?.let { return it }
            val chunk = stream.read(TICK_MS)
            if (chunk == null) {
                if (!ready) throw CallProblem(CallFailure.CONNECTION, "ended before the call started")
                drain()
                return CallEnd.REMOTE_END
            }
            for (byte in chunk) {
                if (byte != '\n'.code.toByte()) {
                    line.write(byte.toInt())
                    if (line.size() > MAX_LINE_BYTES) throw CallProblem(CallFailure.PROTOCOL, "line too long")
                    continue
                }
                val text = line.toString("UTF-8")
                line.reset()
                if (text.isBlank()) continue
                val event = try {
                    JSONObject(text)
                } catch (e: JSONException) {
                    throw CallProblem(CallFailure.PROTOCOL, "unreadable event")
                }
                handle(event)?.let { return it }
            }
            reportSpeaking()
        }
    }

    /** `null` if the user hangs up before the answer. */
    private fun awaitStatus(started: Long): Int? {
        while (true) {
            if (due(started) == CallEnd.HUNG_UP) return null
            try {
                return stream.awaitStatus(TICK_MS)
            } catch (e: SocketTimeoutException) {
                continue
            }
        }
    }

    /** The end imposed by the user or the timeouts, if any. */
    private fun due(started: Long): CallEnd? {
        workerFailure?.let { throw it }
        val now = clock()
        if (hungUp) return CallEnd.HUNG_UP
        if (now - started >= overallTimeoutMillis) return CallEnd.TOO_LONG
        if (!ready && now - started >= minOf(readyTimeoutMillis, overallTimeoutMillis)) {
            throw CallProblem(CallFailure.NOT_READY, "the call did not start in time")
        }
        val limit = firstTurnTimeoutMillis
        if (limit != null && ready && synchronized(lock) { !turnHeard && now - readyAt >= limit }) {
            return CallEnd.NOTHING_ASKED
        }
        // Only exchanges count: unlike the Linux client, noise picked up by the microphone (television,
        // conversations) does not extend the call.
        val idle = synchronized(lock) {
            val quiet = now - activity
            val talking = speakingSince?.let { now - it < idleTimeoutMillis } == true
            !talking && quiet >= idleTimeoutMillis && (activeTurns.isEmpty() || quiet >= stalledTurnTimeoutMillis)
        }
        return if (ready && idle) CallEnd.IDLE else null
    }

    private fun handle(event: JSONObject): CallEnd? {
        val kind = event.opt("type") as? String ?: throw CallProblem(CallFailure.PROTOCOL, "event without a type")
        count(kind)
        when (kind) {
            "error" -> {
                serverError(event)
                return null
            }
            "hatch.voice.session" -> confirm(event)
            "hatch.voice.switch_input_hold" -> {
                val value = event.opt("held") as? Boolean ?: throw CallProblem(CallFailure.PROTOCOL, "invalid wait")
                synchronized(lock) {
                    if (held != value) micEpoch++
                    held = value
                }
            }
            "input_audio_buffer.speech_started" -> synchronized(lock) { speakingSince = clock() }
            "input_audio_buffer.speech_stopped" -> synchronized(lock) { speakingSince = null }
            "conversation.item.added" -> {
                val item = event.optJSONObject("item")
                val control = item?.opt("call_id") as? String
                if (item?.opt("type") == "session_control" && !control.isNullOrEmpty() && control.length <= 128 &&
                    item.optString("name").trim().equals("stop", ignoreCase = true)
                ) {
                    if (!ready) throw CallProblem(CallFailure.PROTOCOL, "stop received before the start")
                    drain()
                    return CallEnd.REMOTE_STOP
                }
            }
            "hatch.voice.turn_started", "hatch.voice.turn_settled" -> if (!trackTurn(event)) return null
            // The end of a reply is not the end of the call.
            "response.created", "response.done", "response.completed" -> touch()
            "response.output_audio_transcript.delta" ->
                (event.opt("delta") as? String)?.takeIf { it.isNotEmpty() }?.let { words(CallEvent.Words(it, false)) }
            "response.output_audio_transcript.done" ->
                (event.opt("transcript") as? String)?.let { words(CallEvent.Words(it, true)) }
            "conversation.item.input_audio_transcription.delta" -> heard(event, "delta", final = false)
            "conversation.item.input_audio_transcription.completed" -> heard(event, "transcript", final = true)
            "hatch.voice.input_suppressed" -> {
                synchronized(lock) { speakingSince = null }
                log("speech dropped by Muse (${describeFields(event)})")
                identifier(event.opt("item_id"))?.let { onEvent(CallEvent.Ignored(it)) }
            }
        }
        val release = synchronized(lock) {
            val wasHolding = gate.holding
            gate.event(event)
            wasHolding
        }
        applyFlush()
        if (release) releaseHeld()
        audioFrom(event)?.let { received(event, it) }
        return null
    }

    private fun heard(event: JSONObject, field: String, final: Boolean) {
        val item = identifier(event.opt("item_id")) ?: return
        val text = (event.opt(field) as? String)?.takeIf { it.isNotEmpty() && it.length <= MAX_HELD_TEXT_CHARS } ?: return
        onEvent(CallEvent.Heard(item, text, final))
        if (final && volumeItems.size < MAX_VOLUME_ITEMS && volumeItems.add(item)) {
            if (VolumeRequest.parse(text) == null && VolumeRequest.mentions(text)) {
                log("loudness mentioned, no volume request recognized")
            }
            VolumeRequest.parse(text)?.let {
                log("volume asked aloud")
                onEvent(CallEvent.Volume(it))
                // The device has done it: the reply, often "I cannot", is not played.
                interrupt()
            }
        }
    }

    /** Field names, and the value of reason codes only. Never free text. */
    private fun describeFields(event: JSONObject): String {
        val token = Regex("[A-Za-z0-9_.-]{1,40}")
        return event.keys().asSequence().filter { it != "type" && it != "event_id" && it != "item_id" }
            .filter(token::matches)
            .map { key ->
                val value = event.opt(key)
                val shown = key in setOf("reason", "code", "classification", "category", "source") &&
                    value is String && token.matches(value)
                if (shown) "$key=$value" else key
            }
            .joinToString(", ").ifEmpty { "no reason" }
    }

    private fun count(kind: String) = synchronized(lock) {
        val name = kind.take(60).map { if (it.isLetterOrDigit() || it in "._") it else '?' }.joinToString("")
        if (name in receivedTypes || receivedTypes.size < 40) receivedTypes[name] = (receivedTypes[name] ?: 0) + 1
    }

    private fun summary(): String = synchronized(lock) {
        val received = receivedTypes.entries.joinToString(", ") { "${it.key} x${it.value}" }.ifEmpty { "rien" }
        "summary: microphone $sentFrames chunks sent ($audibleFrames with sound), $mutedFrames dropped; " +
            "Muse sound ${museAudioBytes * 10 / BYTES_PER_SECOND / 10.0} s; received: $received"
    }

    private fun touch() = synchronized(lock) { activity = clock() }

    private fun confirm(event: JSONObject) {
        if (ready || event.opt("call_id") != callId) throw CallProblem(CallFailure.PROTOCOL, "inconsistent call identity")
        if ((event.opt("session_id") as? String).isNullOrBlank()) {
            throw CallProblem(CallFailure.PROTOCOL, "session missing")
        }
        threadId = (event.opt("thread_id") as? String)?.takeIf { it.isNotBlank() }
        touch()
        synchronized(lock) { readyAt = clock() }
        ready = true
        workers = workers + thread(name = "call-microphone") { sendLoop() }
        onEvent(CallEvent.Connected)
    }

    private fun serverError(event: JSONObject) {
        val error = event.optJSONObject("error") ?: throw CallProblem(CallFailure.PROTOCOL, "unreadable error")
        if (!ready || error.opt("code") == "session_dead") {
            throw CallProblem(CallFailure.CONNECTION, "voice session failed")
        }
        // The failure of a turn does not end the call. The server's message is never logged.
        if (advisoryErrors++ == 0) log("a voice operation failed; the call goes on")
    }

    /** Ongoing turns hold the idle timeout. False: event to ignore. */
    private fun trackTurn(event: JSONObject): Boolean = synchronized(lock) {
        if (event.opt("type") == "hatch.voice.turn_started") {
            val message = identifier(event.opt("submission_message_id"))
            val item = identifier(event.opt("item_id"))
            if (message == null || item == null) throw CallProblem(CallFailure.PROTOCOL, "invalid turn")
            val previous = activeTurns[message] ?: settledTurns[message]
            if (previous != null) {
                if (previous != item) throw CallProblem(CallFailure.PROTOCOL, "contradictory turn")
                return true
            }
            check(activeTurns.size < MAX_TRACKED_TURNS) { "too many turns in progress" }
            activeTurns[message] = item
            turnHeard = true
            activity = clock()
            return true
        }
        val message = identifier(event.opt("response_message_id")) ?: return false
        val item = activeTurns[message] ?: return false
        // A late reply has its own identifier but names the same caller turn.
        val completed = ArrayList<String>()
        for ((key, value) in activeTurns) {
            if (value == item) completed.add(key)
            if (key == message) break
        }
        check(settledTurns.size + completed.size <= MAX_SETTLED_TURNS) { "too many finished turns" }
        for (key in completed) settledTurns[key] = activeTurns.remove(key)!!
        activity = clock()
        true
    }

    private fun words(event: CallEvent.Words) {
        val verdict = synchronized(lock) {
            val verdict = gate.words()
            if (verdict == ReplyGate.Verdict.HOLD) {
                val size = waiting.filterIsInstance<Held.Words>().sumOf { it.event.text.length }
                check(waiting.size < MAX_HELD_ITEMS && size + event.text.length <= MAX_HELD_TEXT_CHARS) { "pending text" }
                waiting.add(Held.Words(event, gate.wordsIds))
            }
            verdict
        }
        if (verdict == ReplyGate.Verdict.PLAY) onEvent(event)
    }

    private fun received(event: JSONObject, pcm: ByteArray) {
        // Keepalive silences may precede the start: neither sound nor an extended timeout.
        val silent = pcm.all { it == 0.toByte() }
        val keepalive = synchronized(lock) { silent && !receivedAudio && waiting.none { it is Held.Audio } }
        if (keepalive) {
            if (ready) synchronized(lock) { gate.audio(event) }
            applyFlush()
            return
        }
        if (!ready) throw CallProblem(CallFailure.PROTOCOL, "sound received before the call started")
        val verdict = synchronized(lock) { gate.audio(event) }
        applyFlush()
        synchronized(lock) {
            if (verdict == ReplyGate.Verdict.PLAY) {
                queue(pcm)
            } else if (verdict == ReplyGate.Verdict.HOLD) {
                val size = waiting.filterIsInstance<Held.Audio>().sumOf { it.pcm.size() }
                check(size + pcm.size <= MAX_PLAYBACK_BYTES) { "pending sound" }
                val last = waiting.lastOrNull() as? Held.Audio
                    ?: Held.Audio(ByteArrayOutputStream()).also { waiting.add(it) }
                last.pcm.write(pcm)
            }
        }
        reportSpeaking()
    }

    private fun releaseHeld() {
        val words = ArrayList<CallEvent.Words>()
        synchronized(lock) {
            if (gate.holding) return
            if (gate.confirmed) {
                for (item in waiting) {
                    if (item is Held.Audio) queue(item.pcm.toByteArray())
                    else if (item is Held.Words) words.add(item.event)
                }
            }
            waiting.clear()
        }
        words.forEach(onEvent)
    }

    /** Under the lock. */
    private fun queue(pcm: ByteArray) {
        receivedAudio = true
        if (rms(pcm) >= AUDIBLE_RMS) museAudioBytes += pcm.size
        check(playbackBytes + pcm.size <= MAX_PLAYBACK_BYTES) { "speaker lagging" }
        for (offset in pcm.indices step FRAME_BYTES) {
            val part = pcm.copyOfRange(offset, minOf(offset + FRAME_BYTES, pcm.size))
            val audible = rms(part) >= AUDIBLE_RMS
            playback.addLast(Chunk(part, audible))
            if (audible) {
                audibleQueued++
                micEpoch++
                activity = clock()
            }
        }
        playbackBytes += pcm.size
        lock.notifyAll()
    }

    private fun applyFlush() {
        val everything = synchronized(lock) {
            val flush = gate.takeFlush()
            if (!flush.playing && flush.waiting) {
                if (gate.holding) {
                    waiting.removeAll { item ->
                        item is Held.Audio || item is Held.Words && item.providers.isNotEmpty() &&
                            item.providers.all(gate::isStale)
                    }
                } else {
                    waiting.clear()
                }
            }
            flush.playing
        }
        if (everything) flush()
    }

    private fun flush() {
        synchronized(lock) {
            waiting.clear()
            playback.clear()
            playbackBytes = 0
            audibleQueued = 0
            receivedAudio = false
            playbackEnd = 0
            playingUntil = 0
            flushes++
        }
        audio.flush()
        reportSpeaking()
    }

    private fun playLoop() {
        while (!ended) {
            val (chunk, generation) = synchronized(lock) {
                if (playback.isEmpty()) lock.wait(TICK_MS)
                playback.removeFirstOrNull() to flushes
            }
            if (chunk == null) continue
            try {
                audio.play(chunk.pcm)
            } catch (e: RuntimeException) {
                workerFailure = CallProblem(CallFailure.CONNECTION, "speaker failed")
                return
            }
            synchronized(lock) {
                if (generation != flushes) return@synchronized
                playbackBytes -= chunk.pcm.size
                playbackEnd = maxOf(clock(), playbackEnd) + chunk.pcm.size * 1000L / BYTES_PER_SECOND
                if (chunk.audible) {
                    audibleQueued--
                    playingUntil = playbackEnd + SETTLE_MS
                    activity = clock()
                }
            }
        }
    }

    private fun museSpeaking() = synchronized(lock) { audibleQueued > 0 || clock() < playingUntil }

    private fun reportSpeaking() {
        val now = museSpeaking()
        val changed = synchronized(lock) { (now != speaking).also { speaking = now } }
        if (changed) onEvent(CallEvent.Speaking(now))
    }

    private fun micGated() = ended || synchronized(lock) { held } || museSpeaking()

    private fun sendLoop() {
        try {
            while (!ended) {
                val pcm = audio.read() ?: return
                if (pcm.isEmpty() || pcm.size > FRAME_BYTES || pcm.size % 2 != 0) {
                    throw CallProblem(CallFailure.PROTOCOL, "invalid microphone chunk")
                }
                // Sound captured while muted is dropped, never replayed later.
                if (micGated()) {
                    synchronized(lock) { mutedFrames++ }
                    continue
                }
                val audible = rms(pcm) >= AUDIBLE_RMS
                val epoch = synchronized(lock) { micEpoch }
                val sent = send(
                    JSONObject().put("type", "input_audio_buffer.append")
                        .put("audio", java.util.Base64.getEncoder().encodeToString(pcm))
                        .put("send_timestamp_ms", System.currentTimeMillis()),
                ) { !micGated() && synchronized(lock) { epoch == micEpoch } }
                synchronized(lock) {
                    if (!sent) {
                        mutedFrames++
                    } else {
                        sentFrames++
                        if (audible) audibleFrames++
                    }
                }
            }
        } catch (e: CallProblem) {
            workerFailure = e
        } catch (e: IOException) {
            if (!ended) workerFailure = CallProblem(CallFailure.CONNECTION, "cannot send sound")
        } catch (e: RuntimeException) {
            if (!ended) workerFailure = CallProblem(CallFailure.CONNECTION, "microphone failed")
        }
    }

    private fun send(event: JSONObject, admissible: () -> Boolean = { true }): Boolean =
        link.sendBody(stream, ServiceType.DAEMON, (event.toString() + "\n").toByteArray(), false, admissible)

    /** Lets the last sentence finish, without a stuck speaker delaying the hang-up. */
    private fun drain() {
        val deadline = clock() + DRAIN_MS
        while (!hungUp && clock() < deadline &&
            // Bytes are only counted once the chunk is handed to the speaker.
            synchronized(lock) { playbackBytes > 0 || clock() < playbackEnd }
        ) {
            Thread.sleep(TICK_MS)
        }
    }

    /** Explicitly closes the call on the server side, then closes the stream. */
    private fun cleanUp(startedAt: OffsetDateTime) {
        try {
            val format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val endedAt = maxOf(now, startedAt.plusNanos(1_000_000))
            val body = JSONObject().put("call_id", callId)
                .put("call_started_at", startedAt.format(format))
                .put("call_ended_at", endedAt.format(format))
            threadId?.let { body.put("thread_id", it) }
            if (!endCall(body)) log("end of call not confirmed by the server")
            if (::stream.isInitialized) {
                link.sendBody(stream, ServiceType.DAEMON, ByteArray(0), true)
                stream.close()
            }
        } catch (e: IOException) {
            log("end of call not confirmed: ${e.javaClass.simpleName}")
        } catch (e: NoiseError) {
            log("end of call not confirmed")
        }
    }

    private fun endCall(body: JSONObject): Boolean {
        val request = link.open(
            ServiceType.DAEMON, "POST", "/voice/end-call",
            listOf("Content-Type" to "application/json"), body.toString().toByteArray(),
        )
        try {
            val deadline = clock() + cleanupTimeoutMillis
            val status = request.awaitStatus(cleanupTimeoutMillis)
            val response = ByteArrayOutputStream()
            while (clock() < deadline && response.size() < MAX_LINE_BYTES) {
                response.write(request.read(TICK_MS) ?: break)
            }
            val json = JSONObject(response.toString("UTF-8"))
            return status in 200..299 && json.opt("ok") == true && json.optJSONObject("result")?.opt("ok") == true
        } catch (e: JSONException) {
            return false
        } finally {
            request.close()
        }
    }

    private fun audioFrom(event: JSONObject): ByteArray? {
        fun invalid(): Nothing = throw CallProblem(CallFailure.PROTOCOL, "invalid sound received")
        val encoded: Any? = when (event.opt("type")) {
            "continuous_audio_video.delta" -> {
                val delta = event.opt("audio_delta")
                if (delta == null || delta == JSONObject.NULL) return null
                if (delta !is JSONObject) invalid()
                val format = delta.opt("format")
                if (format != null && format != JSONObject.NULL) {
                    if (format !is JSONObject || format.optString("type") !in setOf("", "audio/pcm") ||
                        format.optInt("rate") != SAMPLE_RATE
                    ) invalid()
                }
                if (delta.optInt("num_channels", 1) != 1) invalid()
                delta.opt("audio_frames")
            }
            "response.output_audio.delta", "response.audio.delta" -> event.opt("delta")
            else -> return null
        }
        if (encoded !is String || encoded.length > (MAX_AUDIO_BYTES + 2) / 3 * 4) invalid()
        val pcm = try {
            java.util.Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            invalid()
        }
        if (pcm.size % 2 != 0 || pcm.size > MAX_AUDIO_BYTES) invalid()
        return pcm.takeIf { it.isNotEmpty() }
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
