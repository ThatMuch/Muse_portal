// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Source : linux/src/musegadget/voice_gate.py
package com.portalmuse.core

import org.json.JSONObject

private const val MAX_IDS = 128

internal fun identifier(value: Any?): String? =
    (value as? String)?.takeIf { it.isNotEmpty() && '\u0000' !in it && it.toByteArray().size <= MAX_IDS }

/**
 * Écarte le son d'une réponse interrompue. Les identifiants de flux du fournisseur désignent le son
 * périmé ; la réponse au tour de parole confirmé de l'appelant est admise, avec ses suites.
 * Le texte transcrit suit le son de la même réponse.
 */
class ReplyGate {
    enum class Verdict { DROP, HOLD, PLAY }

    class Flush(val playing: Boolean, val waiting: Boolean)

    private enum class State { STALE, WAITING, PLAYING, CONTINUING }

    private val ids = LinkedHashMap<String, State>()
    private val items = HashSet<String>()
    private var fenced = false
    private var item: String? = null
    private var turnFresh = false
    private var flushPlaying = false
    private var flushWaiting = false
    private var wordsFenced = false

    var confirmed = false
        private set
    var blocked = false
        private set
    var openResponse: String? = null
        private set

    /** Flux entendus pour la première fois depuis le dernier `response.created`. */
    var wordsIds: MutableList<String> = ArrayList()
        private set

    val waiting get() = has(State.WAITING)
    private val playing get() = has(State.PLAYING, State.CONTINUING)
    val holding get() = fenced && item != null && !blocked && !confirmed

    fun interrupt(newItem: String? = null) {
        fenced = true
        wordsFenced = true
        for (key in ids.keys.toList()) ids[key] = State.STALE
        item = newItem
        confirmed = false
        blocked = false
        turnFresh = false
        openResponse = null
        flushPlaying = true
        flushWaiting = true
    }

    fun takeFlush(): Flush = Flush(flushPlaying, flushWaiting).also {
        flushPlaying = false
        flushWaiting = false
    }

    fun isStale(id: String) = ids[id] == State.STALE

    fun event(event: JSONObject) {
        when (val kind = event.opt("type")) {
            "input_audio_buffer.speech_started" -> {
                val heard = identifier(event.opt("item_id"))
                if (heard != null) {
                    // Un début de parole rejoué ne doit pas rechoisir un ancien tour.
                    if (heard in items) return
                    check(items.size < MAX_IDS) { "trop de tours de parole" }
                    items.add(heard)
                }
                interrupt(heard)
            }
            "hatch.voice.turn_started" -> {
                val started = identifier(event.opt("item_id"))
                if (item == null || started == null || identifier(event.opt("submission_message_id")) == null) return
                if (started != item) {
                    flushWaiting = flushWaiting || waiting
                    flushPlaying = flushPlaying || has(State.CONTINUING)
                    replace(State.STALE, State.WAITING, State.CONTINUING)
                    blocked = true
                    turnFresh = false
                    return
                }
                val hadReply = waiting || playing
                replace(State.PLAYING, State.WAITING, State.CONTINUING)
                confirmed = true
                blocked = false
                turnFresh = !hadReply
            }
            "hatch.voice.input_suppressed" ->
                if (item != null && identifier(event.opt("item_id")) == item) interrupt()
            "response.created", "response.done", "response.completed" -> {
                if (kind == "response.created") {
                    wordsFenced = false
                    wordsIds = ArrayList()
                }
                val id = identifier(event.optJSONObject("response")?.opt("id")) ?: return
                if (kind == "response.created") openResponse = id
                else if (id == openResponse) openResponse = null
            }
        }
    }

    fun words(): Verdict {
        if (wordsFenced) return Verdict.DROP
        if (wordsIds.isNotEmpty()) {
            val states = wordsIds.mapNotNull { ids[it] }.toSet()
            if (State.PLAYING in states || State.CONTINUING in states) return Verdict.PLAY
            return if (State.WAITING in states) Verdict.HOLD else Verdict.DROP
        }
        if (fenced && (item == null || blocked)) return Verdict.DROP
        return if (holding) Verdict.HOLD else Verdict.PLAY
    }

    fun audio(event: JSONObject): Verdict {
        val id = if (event.opt("type") == "continuous_audio_video.delta") identifier(event.opt("response_id")) else null
        if (id == null) return if (fenced) Verdict.DROP else Verdict.PLAY
        ids[id]?.let { return verdict(it) }
        check(ids.size < MAX_IDS) { "trop de réponses dans l'appel" }
        val state = when {
            !fenced -> State.PLAYING
            item == null || blocked -> State.STALE
            confirmed -> {
                flushPlaying = flushPlaying || playing
                replace(State.STALE, State.PLAYING, State.CONTINUING)
                val next = if (turnFresh) State.PLAYING else State.CONTINUING
                turnFresh = false
                next
            }
            else -> {
                flushWaiting = flushWaiting || waiting
                replace(State.STALE, State.WAITING)
                State.WAITING
            }
        }
        ids[id] = state
        wordsIds.add(id)
        return verdict(state)
    }

    private fun verdict(state: State) = when (state) {
        State.WAITING -> Verdict.HOLD
        State.STALE -> Verdict.DROP
        else -> Verdict.PLAY
    }

    private fun has(vararg states: State) = ids.values.any { it in states }

    private fun replace(target: State, vararg from: State) {
        for ((key, state) in ids.entries.toList()) if (state in from) ids[key] = target
    }
}
