// Kotlin port of muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0). Sources:
// linux/src/musegadget/muse_turn.py, service.py (send_and_wait, _ReplyReader)
package com.portalmuse.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

private const val APP_ID = "musegadget"
private const val REPLY_QUIET_MS = 3_000L
private const val EMPTY_REPLY_GRACE_MS = 1_000L
private const val POLL_MS = 250L
private const val MAX_EVENT_BYTES = 256 * 1024
private const val MAX_TEXT_CHARS = 64 * 1024
private const val MAX_MESSAGES = 32
private const val MAX_IGNORED = 256
private const val MAX_SMALL_RESPONSE_BYTES = 1024 * 1024
private val SAFE_PATH = Regex("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*")
private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
private const val MAX_SPEECH_BYTES = 8 * 1024 * 1024
private const val SPEECH_TIMEOUT_MS = 180_000L

object Wav {
    /** Muse's limit for a voice note. */
    const val MAX_BYTES = 2 * 1024 * 1024

    /** 16 bit mono little-endian PCM in a WAV container. */
    fun encode(pcm: ByteArray, sampleRate: Int): ByteArray = ByteBuffer.allocate(44 + pcm.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(pcm.size).put(pcm)
        .array()
}

class ChatProgress(
    val text: String, val activity: String?,
    /** The messages already complete: they can be read without waiting for the end of the request. */
    val finished: List<String> = emptyList(),
)

enum class ChatFailure {
    /** The message was not sent. */
    NOT_SENT,
    /** Sent or not: unknown. */
    UNCERTAIN,
    /** Muse refused it. */
    REFUSED,
    /** Sent, but no reply was read. */
    NO_REPLY,
}

sealed class ChatResult {
    /** [partial]: the reply was still in progress when waiting ended. */
    class Answer(
        val text: String, val partial: Boolean,
        /** The messages of the reply, in order: what [MuseChat.speech] can read. */
        val messageIds: List<String> = emptyList(),
    ) : ChatResult()
    class Failed(val failure: ChatFailure, val status: Int? = null) : ChatResult()
}

/** A past message of the conversation. */
data class PastMessage(val fromUser: Boolean, val text: String)

sealed class History {
    class Messages(val messages: List<PastMessage>) : History()
    /** [status]: the HTTP status of a refusal, `null` if the answer did not arrive or cannot be read. */
    class Unavailable(val status: Int?) : History()
}

/** The user's main conversation, the same as on the phone. */
class MuseChat(
    private val streams: MuseStreams,
    private val deviceId: String,
    private val replyTimeoutMillis: Long = 180_000,
    private val requestTimeoutMillis: Long = 60_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Diagnostics without content: event names, durations. */
    private val log: (String) -> Unit = {},
    /** Reply written in full and nothing more for this long: stop waiting for the end of the task. */
    private val stalledReplyMillis: Long = 20_000,
    /** A separate conversation; `null` for the main one, the phone's. */
    private val sideChat: String? = null,
) {
    /** [spoken] asks for a reply that [speech] can read aloud. */
    fun sendText(
        message: String, onProgress: (ChatProgress) -> Unit = {}, cancelled: () -> Boolean = { false },
        spoken: Boolean = false,
    ): ChatResult = ask(JSONObject().put("message", message), spoken, onProgress, cancelled)

    fun sendVoice(
        wav: ByteArray, onProgress: (ChatProgress) -> Unit = {}, cancelled: () -> Boolean = { false },
        spoken: Boolean = false,
    ): ChatResult {
        require(wav.isNotEmpty() && wav.size <= Wav.MAX_BYTES) { "voice note empty or over 2 MiB" }
        val voice = JSONObject()
            .put("type", "file")
            .put("mime_type", "audio/wav")
            .put("filename", "voice_note.wav")
            .put("data_base64", java.util.Base64.getEncoder().encodeToString(wav))
        return ask(JSONObject().put("message", "").put("items", JSONArray().put(voice)), spoken, onProgress, cancelled)
    }

    /** A JPEG photo, with [message] to say what to do with it. */
    fun sendImage(
        jpeg: ByteArray, message: String, onProgress: (ChatProgress) -> Unit = {},
        cancelled: () -> Boolean = { false }, spoken: Boolean = false,
    ): ChatResult {
        require(jpeg.isNotEmpty() && jpeg.size <= MAX_IMAGE_BYTES) { "picture empty or over 2 MiB" }
        val items = JSONArray()
        // Muse only reads the items of a request that has some: the text goes first.
        if (message.isNotBlank()) items.put(JSONObject().put("type", "text").put("text", message))
        items.put(
            JSONObject().put("type", "image").put("mime_type", "image/jpeg").put("filename", "photo.jpg")
                .put("data_base64", java.util.Base64.getEncoder().encodeToString(jpeg)),
        )
        return ask(JSONObject().put("message", message).put("items", items), spoken, onProgress, cancelled)
    }

    /**
     * Muse's voice for a message of a reply requested as spoken: MP3, handed to [onAudio] as it arrives.
     * Returns the HTTP status, or `null` if the sound did not arrive whole.
     */
    fun speech(messageId: String, onAudio: (ByteArray) -> Unit, cancelled: () -> Boolean = { false }): Int? {
        val id = java.net.URLEncoder.encode(messageId, "UTF-8").replace("+", "%20")
        return try {
            // Some VMs only know the old path.
            speechFrom("/api/voice/tts-stream?message_id=$id", onAudio, cancelled)
                .let { if (it == 404) speechFrom("/voice/tts-stream?message_id=$id", onAudio, cancelled) else it }
        } catch (e: IOException) {
            null
        }
    }

    private fun speechFrom(path: String, onAudio: (ByteArray) -> Unit, cancelled: () -> Boolean): Int? {
        val stream = streams.open(ServiceType.DAEMON, "GET", path, headers("Accept" to "audio/mpeg"), ByteArray(0))
        try {
            val deadline = clock() + SPEECH_TIMEOUT_MS
            val status = stream.awaitStatus(requestTimeoutMillis)
            if (status !in 200..299) return status
            var total = 0
            while (!cancelled() && clock() < deadline) {
                val chunk = stream.read(POLL_MS) ?: return status
                total += chunk.size
                if (total > MAX_SPEECH_BYTES) break
                if (chunk.isNotEmpty()) onAudio(chunk)
            }
            return null
        } finally {
            stream.close()
        }
    }

    /** The name the user gave their Muse, or `null`. */
    fun museName(): String? = try {
        val (status, body) = request(ServiceType.GADGET, "GET", "/api/gadget/me", ByteArray(0))
        if (status !in 200..299) null
        else (JSONObject(body).optJSONObject("result")?.opt("muse_name") as? String)
            ?.let(::stripControls)?.trim()?.takeIf { it.isNotEmpty() }?.take(40)
    } catch (e: IOException) {
        null
    } catch (e: JSONException) {
        null
    }

    /** The latest messages, oldest first. Not all VMs offer this access. */
    fun history(limit: Int = 12): History {
        require(limit in 1..50)
        return try {
            val (status, body) = request(ServiceType.DAEMON, "GET", "/chat/history?limit=$limit", ByteArray(0))
            if (status !in 200..299) return History.Unavailable(status)
            val events = JSONObject(body).optJSONObject("result")?.optJSONArray("chat_events")
                ?: return History.Unavailable(null)
            val rows = (0 until events.length()).mapNotNull { events.optJSONObject(it) }
            // In sequence number order when the VM provides them.
            val ordered = if (rows.all { it.opt("seq") is Number }) rows.sortedBy { it.getLong("seq") } else rows
            // Field names only, to understand an unexpected answer.
            log("history: ${rows.size} entry(ies), fields " +
                "${rows.firstOrNull()?.keys()?.asSequence()?.take(16)?.joinToString()}")
            History.Messages(ordered.mapNotNull(::past))
        } catch (e: IOException) {
            History.Unavailable(null)
        } catch (e: JSONException) {
            History.Unavailable(null)
        }
    }

    private fun past(row: JSONObject): PastMessage? {
        val role = row.opt("role")
        val name = row.opt("event_name") ?: row.opt("event")
        val fromUser = role == "user" || name == "message.user"
        if (!fromUser && role != "assistant" && name != "message.assistant") return null
        val text = listOf("display_text", "content", "text")
            .firstNotNullOfOrNull { (row.opt(it) as? String)?.takeIf { value -> value.isNotBlank() } }
            ?: return null
        return PastMessage(fromUser, stripControls(text).trim().take(4_000))
    }

    /**
     * Puts a file in Muse's files (`POST /fs/upload`, like the SDK). Returns the HTTP status, or `null` without
     * an answer. [path] is relative to the root of those files.
     */
    fun upload(path: String, data: ByteArray, mimeType: String): Int? {
        require(SAFE_PATH.matches(path) && ".." !in path) { "path refused" }
        require(data.size <= MAX_IMAGE_BYTES) { "file over 2 MiB" }
        val boundary = "portal-" + UUID.randomUUID().toString()
        val body = ByteArrayOutputStream()
        fun text(value: String) = body.write(value.toByteArray())
        for ((name, value) in listOf("path" to path, "overwrite" to "true", "create_parent" to "true")) {
            text("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
        }
        text(
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${path.substringAfterLast('/')}\"\r\n" +
                "Content-Type: $mimeType\r\n\r\n",
        )
        body.write(data)
        text("\r\n--$boundary--\r\n")
        return try {
            request(
                ServiceType.DAEMON, "POST", "/fs/upload", body.toByteArray(),
                "multipart/form-data; boundary=$boundary",
            ).first
        } catch (e: IOException) {
            null
        }
    }

    private fun headers(vararg extra: Pair<String, String>) =
        listOf(*extra, "x-request-id" to UUID.randomUUID().toString(), "x-app-id" to APP_ID)

    private fun request(
        service: ServiceType, verb: String, path: String, body: ByteArray, contentType: String = "application/json",
    ): Pair<Int, String> {
        val type = if (body.isEmpty()) emptyList() else listOf("Content-Type" to contentType)
        val stream = streams.open(service, verb, path, headers(*type.toTypedArray()), body)
        try {
            val deadline = clock() + requestTimeoutMillis
            val status = stream.awaitStatus(requestTimeoutMillis)
            val response = ByteArrayOutputStream()
            while (true) {
                val chunk = stream.read(POLL_MS) ?: break
                response.write(chunk)
                if (response.size() > MAX_SMALL_RESPONSE_BYTES) throw IOException("answer too large")
                if (clock() > deadline) throw IOException("answer too slow")
            }
            return status to response.toString("UTF-8")
        } finally {
            stream.close()
        }
    }

    private fun ask(
        body: JSONObject, spoken: Boolean, onProgress: (ChatProgress) -> Unit, cancelled: () -> Boolean,
    ): ChatResult {
        // The subscription opens before sending, so as to miss nothing of the reply. A side chat that does not
        // exist yet answers 404: the message creates it, and we subscribe afterwards.
        var subscription: MuseStream? = null
        try {
            var fresh = false
            val first = try {
                subscribe()
            } catch (e: IOException) {
                return ChatResult.Failed(ChatFailure.NOT_SENT)
            }
            subscription = first.first
            if (first.second == 404 && sideChat != null) {
                fresh = true
                first.first.close()
                subscription = null
            } else if (first.second !in 200..299) {
                return ChatResult.Failed(ChatFailure.NOT_SENT, first.second)
            }
            body.put("output_modality", if (spoken) "voice" else "text").put("device_id", deviceId)
            sideChat?.let { body.put("session_id", it) }
            val (status, response) = try {
                request(ServiceType.DAEMON, "POST", "/chat/stream", body.toString().toByteArray())
            } catch (e: IOException) {
                return ChatResult.Failed(ChatFailure.UNCERTAIN)
            }
            if (status !in 200..299) return ChatResult.Failed(ChatFailure.REFUSED, status)
            val turn = MuseTurn(clock)
            if (!turn.acknowledge(response)) return ChatResult.Failed(ChatFailure.NO_REPLY)
            val open = subscription ?: try {
                subscribe().let { (stream, answer) ->
                    subscription = stream
                    stream.takeIf { answer in 200..299 }
                }
            } catch (e: IOException) {
                null
            } ?: return ChatResult.Failed(ChatFailure.NO_REPLY)
            return follow(open, turn, fresh, onProgress, cancelled)
        } finally {
            subscription?.close()
        }
    }

    /** The subscription to the conversation and its HTTP status. */
    private fun subscribe(): Pair<MuseStream, Int> {
        val body = JSONObject().apply { sideChat?.let { put("session_id", it) } }
        val stream = streams.open(
            ServiceType.DAEMON, "POST", "/chat/subscribe",
            headers("Content-Type" to "application/json", "Accept" to "application/x-ndjson"), body.toString().toByteArray(),
        )
        return try {
            stream to stream.awaitStatus(requestTimeoutMillis)
        } catch (e: IOException) {
            stream.close()
            throw e
        }
    }

    private fun follow(
        subscription: MuseStream, turn: MuseTurn, fresh: Boolean,
        onProgress: (ChatProgress) -> Unit, cancelled: () -> Boolean,
    ): ChatResult {
        // In a conversation that was just born, everything that arrives answers our message.
        val router = ReplyRouter(turn, fresh)
        val line = ByteArrayOutputStream()
        val deadline = clock() + replyTimeoutMillis
        var shown = Triple("", null as String?, emptyList<String>())
        var open = true
        val started = clock()
        val seen = LinkedHashMap<String, Int>()
        fun summary(reason: String) = log(
            "summary: $reason after ${(clock() - started) / 1000} s; ${turn.state()}; received: " +
                seen.entries.joinToString { "${it.key} x${it.value}" },
        )
        while (true) {
            val now = clock()
            val text = turn.text()
            // Muse can stay "busy" long after its reply, which is there nonetheless.
            val stalled = text.isNotEmpty() && turn.stalled(now, stalledReplyMillis)
            if (turn.settled(now) || stalled || (text.isNotEmpty() && turn.complete(now))) {
                summary(if (stalled) "reply written, task still open" else "reply finished")
                return ChatResult.Answer(text, false, turn.messageIds())
            }
            if (!open || now >= deadline || cancelled()) {
                summary(if (!open) "stream closed" else if (cancelled()) "abandon" else "deadline exceeded")
                return if (text.isEmpty()) ChatResult.Failed(ChatFailure.NO_REPLY)
                else ChatResult.Answer(text, true, turn.messageIds())
            }
            try {
                val chunk = subscription.read(POLL_MS)
                if (chunk == null) open = false
                for (byte in chunk ?: ByteArray(0)) {
                    if (byte != '\n'.code.toByte()) {
                        line.write(byte.toInt())
                        if (line.size() > MAX_EVENT_BYTES) throw IOException("event too large")
                        continue
                    }
                    parse(line.toString("UTF-8"))?.let { event ->
                        var name = (event.opt("event") as? String)?.take(48) ?: "?"
                        // The kind of the voice events, never their content.
                        val kind = event.optJSONObject("payload")?.let { payload ->
                            (payload.optJSONObject("event")?.opt("type") ?: payload.opt("type")) as? String
                        }
                        if (kind != null && name.startsWith("hatch.voice")) name += "/" + kind.take(48)
                        if (seen.size < 40 || name in seen) seen[name] = (seen[name] ?: 0) + 1
                        router.route(event)
                    }
                    line.reset()
                }
                val update = Triple(turn.text(), turn.activity, turn.finishedIds())
                if (update != shown) {
                    shown = update
                    onProgress(ChatProgress(update.first, update.second, update.third))
                }
            } catch (e: IOException) {
                open = false
            } catch (e: IllegalStateException) {
                open = false // the reply exceeds the limits
            }
        }
    }

    private fun parse(line: String): JSONObject? = try {
        if (line.isBlank()) null else JSONObject(line).takeIf { it.opt("type") == "event" }
    } catch (e: JSONException) {
        null
    } catch (e: StackOverflowError) {
        null
    }
}

private fun stripControls(text: String): String =
    text.filterNot { it.code <= 8 || it.code in 11..31 || it.code in 127..159 }

private fun JSONObject.text(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotEmpty() }

private fun parentOf(payload: JSONObject) = payload.text("reply_to_message_id") ?: payload.text("parent_message_id")

private fun messageIdOf(event: JSONObject, payload: JSONObject) =
    payload.text("message_id") ?: event.text("message_id") ?: payload.text("id")

/**
 * Passes [turn] only what follows our own message: after its `message.user`, or what names it as parent.
 * Repeated `seq` values are dropped.
 */
internal class ReplyRouter(private val turn: MuseTurn, private var afterOurs: Boolean = false) {
    private var lastSeq = 0L
    private var lastRowSeq = 0L

    fun route(event: JSONObject) {
        val payload = event.optJSONObject("payload") ?: return
        val name = event.opt("event")
        val seq = (event.opt("seq") as? Number)?.toLong()
        if (seq != null) {
            // Saved messages and live events are numbered separately.
            if (name == "message.user" || name == "message.assistant") {
                if (seq <= lastRowSeq) return
                lastRowSeq = seq
            } else {
                if (seq <= lastSeq) return
                lastSeq = seq
            }
        }
        val chat = payload.text("session_id") ?: event.text("session_id")
        if (chat != null && turn.sessionId != null && chat != turn.sessionId) return
        val messageId = messageIdOf(event, payload)
        if (name == "message.user") {
            afterOurs = messageId in turn.userIds
            return
        }
        val parent = parentOf(payload)
        if (afterOurs || parent in turn.userIds || turn.knows(messageId) || turn.knows(parent)) turn.feed(event)
    }
}

/** Muse's work on a request, linked to the acknowledged user message. */
internal class MuseTurn(private val clock: () -> Long) {
    private class Reply(var text: String = "", var done: Boolean = false, var bound: Boolean = false)

    var sessionId: String? = null
        private set
    var userIds: Set<String> = emptySet()
        private set
    var activity: String? = null
        private set
    private var busy = false
    private var taskFinished = false
    private var lastActivity = clock()
    private var lastReplyAt = clock()
    private val replies = LinkedHashMap<String, Reply>()
    private val ignored = LinkedHashSet<String>()

    fun knows(id: String?) = id != null && id in replies

    /** What holds back the end of the request, with nothing of the content. */
    fun state() = "${replies.size} message(s), ${replies.values.count { it.done }} closed, " +
        (if (busy) "Muse busy, " else "") + if (taskFinished) "task finished" else "task not reported finished"

    /** The complete messages that have text. */
    fun finishedIds(): List<String> = replies.filterValues { it.done && it.text.isNotBlank() }.keys.toList()

    /** All messages are closed and none has moved for [quietMillis]. */
    fun stalled(now: Long, quietMillis: Long): Boolean =
        replies.isNotEmpty() && replies.values.all { it.done } && now - lastReplyAt >= quietMillis

    /** The messages of the reply that have text. */
    fun messageIds(): List<String> = replies.filterValues { it.text.isNotBlank() }.keys.toList()

    fun text(): String = replies.values.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

    /** False if the acknowledgement does not name our message. */
    fun acknowledge(response: String): Boolean {
        val result = try {
            val whole = JSONObject(response)
            whole.optJSONObject("result") ?: whole
        } catch (e: JSONException) {
            return false
        }
        userIds = listOfNotNull(result.text("message_id"), result.text("reply_to_message_id")).toSet()
        sessionId = result.text("session_id")
        lastActivity = clock()
        return userIds.isNotEmpty()
    }

    fun complete(now: Long): Boolean =
        !busy && replies.values.all { it.done } && (taskFinished || now - lastActivity >= REPLY_QUIET_MS)

    fun settled(now: Long): Boolean = taskFinished && !busy && replies.values.all { it.done } &&
        now - lastActivity >= (if (replies.isEmpty()) REPLY_QUIET_MS else EMPTY_REPLY_GRACE_MS)

    fun feed(event: JSONObject) {
        val payload = event.optJSONObject("payload") ?: return
        when (val name = event.opt("event") as? String ?: return) {
            "agent.status", "task.status" -> status(name, payload)
            "delta.message_start", "delta.text_append", "delta.message_done", "message.assistant" ->
                message(name, event, payload)
        }
    }

    private fun status(name: String, payload: JSONObject) {
        val code = payload.opt("activity_code") as? String
        val status = payload.opt("status") as? String
        val parent = parentOf(payload)
        val parentLinked = parent != null && (parent in userIds || replies[parent]?.bound == true)
        // A shared conversation also carries the statuses of other requests.
        if (parent != null && !parentLinked) return
        val linked = parentLinked || replies[payload.text("message_id")]?.bound == true
        val terminal = status in TERMINAL
        if (name == "task.status" && status != null) taskFinished = terminal
        if (code != null) busy = code !in setOf("", "online", "idle") && code !in TERMINAL
        else if (status != null) busy = status != "" && status != "idle" && !terminal
        if (name == "task.status" && taskFinished) busy = false
        lastActivity = clock()
        if (name == "agent.status" && linked && !taskFinished) {
            activity = payload.text("activity_text")?.let(::stripControls)?.take(200)
        }
    }

    private fun message(name: String, event: JSONObject, payload: JSONObject) {
        val id = messageIdOf(event, payload) ?: return
        if (payload.opt("role") == "user") return
        val parent = parentOf(payload)
        val known = replies[id]
        if (parent != null && parent !in userIds && parent !in replies) {
            if (known == null) ignore(id)
            return
        }
        if (known == null) {
            if (id in ignored) return
            check(replies.size < MAX_MESSAGES) { "too many messages for one request" }
        }
        val reply = known ?: Reply().also { replies[id] = it }
        if (parent != null) reply.bound = parent in userIds || replies[parent]?.bound == true
        lastActivity = clock()
        lastReplyAt = lastActivity
        if (name == "delta.text_append" && !reply.done) {
            payload.text("text")?.let { reply.text += stripControls(it) }
        }
        if (name == "delta.message_done" || name == "message.assistant") {
            finalText(payload, id)?.let { reply.text = stripControls(it) }
            if (name == "delta.message_done" || payload.opt("display_text_ready") != false) reply.done = true
        }
        check(reply.text.length <= MAX_TEXT_CHARS) { "reply too long" }
    }

    private fun finalText(payload: JSONObject, id: String): String? {
        (payload.opt("display_text") as? String)?.let { return it }
        val transcript = payload.opt("transcript")
        if (transcript is String) return transcript
        return transcriptText(transcript as? JSONObject, id) ?: payload.opt("content") as? String
    }

    /** Only the assistant's text in the structured transcript. */
    private fun transcriptText(transcript: JSONObject?, id: String): String? {
        val messages = transcript?.optJSONArray("messages") ?: return null
        val ours = (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }
            .lastOrNull { it.opt("role") == "assistant" && it.opt("id") == id } ?: return null
        val content = ours.optJSONArray("content") ?: return null
        return (0 until content.length()).mapNotNull { content.optJSONObject(it) }
            .filter { it.opt("type") == "text" }
            .mapNotNull { it.opt("text") as? String }
            .joinToString("\n")
            .takeIf { it.isNotEmpty() }
    }

    private fun ignore(id: String) {
        ignored.add(id)
        if (ignored.size > MAX_IGNORED) ignored.remove(ignored.first())
    }

    private companion object {
        val TERMINAL = setOf("completed", "failed", "errored", "cancelled", "canceled")
    }
}
