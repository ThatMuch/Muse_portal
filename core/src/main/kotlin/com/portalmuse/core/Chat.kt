// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Sources : linux/src/musegadget/muse_turn.py, service.py (send_and_wait, _ReplyReader)
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
    /** Limite de Muse pour une note vocale. */
    const val MAX_BYTES = 2 * 1024 * 1024

    /** PCM 16 bits mono petit-boutiste dans un conteneur WAV. */
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
    /** Les messages déjà complets : on peut les lire sans attendre la fin de la demande. */
    val finished: List<String> = emptyList(),
)

enum class ChatFailure {
    /** Le message n'est pas parti. */
    NOT_SENT,
    /** Parti ou non : on ne sait pas. */
    UNCERTAIN,
    /** Muse l'a refusé. */
    REFUSED,
    /** Envoyé, mais aucune réponse n'a été lue. */
    NO_REPLY,
}

sealed class ChatResult {
    /** [partial] : la réponse était encore en cours quand l'attente a pris fin. */
    class Answer(
        val text: String, val partial: Boolean,
        /** Les messages de la réponse, dans l'ordre : ce que [MuseChat.speech] sait lire. */
        val messageIds: List<String> = emptyList(),
    ) : ChatResult()
    class Failed(val failure: ChatFailure, val status: Int? = null) : ChatResult()
}

/** Un message passé de la conversation. */
data class PastMessage(val fromUser: Boolean, val text: String)

sealed class History {
    class Messages(val messages: List<PastMessage>) : History()
    /** [status] : le statut HTTP d'un refus, `null` si la réponse n'est pas arrivée ou est illisible. */
    class Unavailable(val status: Int?) : History()
}

/** La conversation principale de l'utilisateur, la même que sur le téléphone. */
class MuseChat(
    private val streams: MuseStreams,
    private val deviceId: String,
    private val replyTimeoutMillis: Long = 180_000,
    private val requestTimeoutMillis: Long = 60_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Des diagnostics sans contenu : noms d'événements, durées. */
    private val log: (String) -> Unit = {},
    /** Réponse écrite en entier et plus rien depuis ce temps : on n'attend plus la fin de la tâche. */
    private val stalledReplyMillis: Long = 20_000,
    /** Une conversation à part ; `null` pour la principale, celle du téléphone. */
    private val sideChat: String? = null,
) {
    /** [spoken] demande une réponse que [speech] pourra lire à voix haute. */
    fun sendText(
        message: String, onProgress: (ChatProgress) -> Unit = {}, cancelled: () -> Boolean = { false },
        spoken: Boolean = false,
    ): ChatResult = ask(JSONObject().put("message", message), spoken, onProgress, cancelled)

    fun sendVoice(
        wav: ByteArray, onProgress: (ChatProgress) -> Unit = {}, cancelled: () -> Boolean = { false },
        spoken: Boolean = false,
    ): ChatResult {
        require(wav.isNotEmpty() && wav.size <= Wav.MAX_BYTES) { "note vocale vide ou de plus de 2 Mio" }
        val voice = JSONObject()
            .put("type", "file")
            .put("mime_type", "audio/wav")
            .put("filename", "voice_note.wav")
            .put("data_base64", java.util.Base64.getEncoder().encodeToString(wav))
        return ask(JSONObject().put("message", "").put("items", JSONArray().put(voice)), spoken, onProgress, cancelled)
    }

    /** Une photo JPEG, avec [message] pour dire quoi en faire. */
    fun sendImage(
        jpeg: ByteArray, message: String, onProgress: (ChatProgress) -> Unit = {},
        cancelled: () -> Boolean = { false }, spoken: Boolean = false,
    ): ChatResult {
        require(jpeg.isNotEmpty() && jpeg.size <= MAX_IMAGE_BYTES) { "image vide ou de plus de 2 Mio" }
        val items = JSONArray()
        // Muse ne lit que les éléments d'une demande qui en a : le texte part d'abord.
        if (message.isNotBlank()) items.put(JSONObject().put("type", "text").put("text", message))
        items.put(
            JSONObject().put("type", "image").put("mime_type", "image/jpeg").put("filename", "photo.jpg")
                .put("data_base64", java.util.Base64.getEncoder().encodeToString(jpeg)),
        )
        return ask(JSONObject().put("message", message).put("items", items), spoken, onProgress, cancelled)
    }

    /**
     * La voix de Muse pour un message d'une réponse demandée à l'oral : du MP3, remis à [onAudio]
     * au fil de son arrivée. Renvoie le statut HTTP, ou `null` si le son n'est pas arrivé entier.
     */
    fun speech(messageId: String, onAudio: (ByteArray) -> Unit, cancelled: () -> Boolean = { false }): Int? {
        val id = java.net.URLEncoder.encode(messageId, "UTF-8").replace("+", "%20")
        return try {
            // Certaines VM ne connaissent que l'ancien chemin.
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

    /** Le nom que l'utilisateur a donné à son Muse, ou `null`. */
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

    /** Les derniers messages, du plus ancien au plus récent. Toutes les VM n'offrent pas cet accès. */
    fun history(limit: Int = 12): History {
        require(limit in 1..50)
        return try {
            val (status, body) = request(ServiceType.DAEMON, "GET", "/chat/history?limit=$limit", ByteArray(0))
            if (status !in 200..299) return History.Unavailable(status)
            val events = JSONObject(body).optJSONObject("result")?.optJSONArray("chat_events")
                ?: return History.Unavailable(null)
            val rows = (0 until events.length()).mapNotNull { events.optJSONObject(it) }
            // Dans l'ordre des numéros quand la VM en donne.
            val ordered = if (rows.all { it.opt("seq") is Number }) rows.sortedBy { it.getLong("seq") } else rows
            // Les noms des champs seulement, pour comprendre une réponse inattendue.
            log("historique : ${rows.size} entrée(s), champs ${rows.firstOrNull()?.keys()?.asSequence()?.take(16)?.joinToString()}")
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
     * Dépose un fichier dans les fichiers de Muse (`POST /fs/upload`, comme le SDK). Renvoie le
     * statut HTTP, ou `null` sans réponse. [path] est relatif à la racine de ces fichiers.
     */
    fun upload(path: String, data: ByteArray, mimeType: String): Int? {
        require(SAFE_PATH.matches(path) && ".." !in path) { "chemin refusé" }
        require(data.size <= MAX_IMAGE_BYTES) { "fichier de plus de 2 Mio" }
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
                if (response.size() > MAX_SMALL_RESPONSE_BYTES) throw IOException("réponse trop grande")
                if (clock() > deadline) throw IOException("réponse trop lente")
            }
            return status to response.toString("UTF-8")
        } finally {
            stream.close()
        }
    }

    private fun ask(
        body: JSONObject, spoken: Boolean, onProgress: (ChatProgress) -> Unit, cancelled: () -> Boolean,
    ): ChatResult {
        // L'abonnement s'ouvre avant l'envoi, pour ne rien manquer de la réponse. Une conversation
        // à part encore inconnue répond 404 : le message la crée, on s'abonne ensuite.
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

    /** L'abonnement à la conversation et son statut HTTP. */
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
        // Dans une conversation qui vient de naître, tout ce qui arrive répond à notre message.
        val router = ReplyRouter(turn, fresh)
        val line = ByteArrayOutputStream()
        val deadline = clock() + replyTimeoutMillis
        var shown = Triple("", null as String?, emptyList<String>())
        var open = true
        val started = clock()
        val seen = LinkedHashMap<String, Int>()
        fun summary(reason: String) = log(
            "bilan : $reason après ${(clock() - started) / 1000} s ; ${turn.state()} ; reçu : " +
                seen.entries.joinToString { "${it.key} x${it.value}" },
        )
        while (true) {
            val now = clock()
            val text = turn.text()
            // Muse peut rester « occupé » bien après sa réponse, qui n'en est pas moins là.
            val stalled = text.isNotEmpty() && turn.stalled(now, stalledReplyMillis)
            if (turn.settled(now) || stalled || (text.isNotEmpty() && turn.complete(now))) {
                summary(if (stalled) "réponse écrite, tâche encore ouverte" else "réponse terminée")
                return ChatResult.Answer(text, false, turn.messageIds())
            }
            if (!open || now >= deadline || cancelled()) {
                summary(if (!open) "flux fermé" else if (cancelled()) "abandon" else "délai dépassé")
                return if (text.isEmpty()) ChatResult.Failed(ChatFailure.NO_REPLY)
                else ChatResult.Answer(text, true, turn.messageIds())
            }
            try {
                val chunk = subscription.read(POLL_MS)
                if (chunk == null) open = false
                for (byte in chunk ?: ByteArray(0)) {
                    if (byte != '\n'.code.toByte()) {
                        line.write(byte.toInt())
                        if (line.size() > MAX_EVENT_BYTES) throw IOException("événement trop grand")
                        continue
                    }
                    parse(line.toString("UTF-8"))?.let { event ->
                        var name = (event.opt("event") as? String)?.take(48) ?: "?"
                        // Le genre des événements vocaux, jamais leur contenu.
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
                open = false // la réponse dépasse les limites
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
 * Ne transmet à [turn] que ce qui suit notre propre message : après son `message.user`, ou ce
 * qui le désigne comme parent. Les répétitions de `seq` sont écartées.
 */
internal class ReplyRouter(private val turn: MuseTurn, private var afterOurs: Boolean = false) {
    private var lastSeq = 0L
    private var lastRowSeq = 0L

    fun route(event: JSONObject) {
        val payload = event.optJSONObject("payload") ?: return
        val name = event.opt("event")
        val seq = (event.opt("seq") as? Number)?.toLong()
        if (seq != null) {
            // Les messages enregistrés et les événements en direct sont numérotés à part.
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

/** Le travail de Muse sur une demande, lié au message utilisateur accusé réception. */
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

    /** Ce qui retient la fin de la demande, sans rien du contenu. */
    fun state() = "${replies.size} message(s) dont ${replies.values.count { it.done }} clos, " +
        (if (busy) "Muse occupé, " else "") + if (taskFinished) "tâche finie" else "tâche non signalée finie"

    /** Les messages complets qui ont un texte. */
    fun finishedIds(): List<String> = replies.filterValues { it.done && it.text.isNotBlank() }.keys.toList()

    /** Tous les messages sont clos et aucun n'a bougé depuis [quietMillis]. */
    fun stalled(now: Long, quietMillis: Long): Boolean =
        replies.isNotEmpty() && replies.values.all { it.done } && now - lastReplyAt >= quietMillis

    /** Les messages de la réponse qui ont un texte. */
    fun messageIds(): List<String> = replies.filterValues { it.text.isNotBlank() }.keys.toList()

    fun text(): String = replies.values.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

    /** Faux si l'accusé de réception ne désigne pas notre message. */
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
        // Une conversation partagée porte aussi les statuts d'autres demandes.
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
            check(replies.size < MAX_MESSAGES) { "trop de messages pour une demande" }
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
        check(reply.text.length <= MAX_TEXT_CHARS) { "réponse trop longue" }
    }

    private fun finalText(payload: JSONObject, id: String): String? {
        (payload.opt("display_text") as? String)?.let { return it }
        val transcript = payload.opt("transcript")
        if (transcript is String) return transcript
        return transcriptText(transcript as? JSONObject, id) ?: payload.opt("content") as? String
    }

    /** Seulement le texte de l'assistant dans le transcript structuré. */
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
