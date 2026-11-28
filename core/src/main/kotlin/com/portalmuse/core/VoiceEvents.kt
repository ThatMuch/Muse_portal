package com.portalmuse.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val MAX_DEPTH = 8
private const val MAX_NESTED_CHARS = 64 * 1024
private const val MAX_OBJECTS = 200

/** A farewell sentence: short, ending with a phrase that closes the call. */
object Farewell {
    private const val MAX_CHARS = 40
    private val ending = Regex(
        "(au ?revoir|[àa] bient[ôo]t|[àa] plus( tard)?|bonne nuit|bye([ -]bye)?|good ?bye|ciao|" +
            "raccroche[rz]?|(termine|arr[êe]te|coupe) l.appel|good ?night|see (you|ya)( later| soon| tomorrow)?|" +
            "talk (to you )?(later|soon)|hang up|(end|stop) the call)( muse)?[\\s.!…]*$",
    )

    fun matches(phrase: String, museName: String? = null): Boolean {
        var text = phrase.lowercase().trim()
        if (museName != null) text = text.replace(Regex("[\\s,]*\\b${Regex.escape(museName.lowercase())}\\b"), "").trim()
        return text.length <= MAX_CHARS && ending.containsMatchIn(text)
    }
}

/**
 * The voice events as the conversation relays them: their shape is not documented. They are walked through,
 * including JSON stored in a string.
 */
object VoiceEvents {
    /** All the objects contained in [event], itself included. */
    fun objects(event: JSONObject): List<JSONObject> {
        val found = ArrayList<JSONObject>()
        walk(event, 0, "") { value, _ -> if (value is JSONObject && found.size < MAX_OBJECTS) found.add(value) }
        return found
    }

    /**
     * The structure of an event, without any content: the paths, the kind of each value, and only the protocol
     * identifiers stored under "type".
     */
    fun shape(event: JSONObject): String {
        val parts = LinkedHashSet<String>()
        walk(event, 0, "") { value, path ->
            if (value is JSONObject || value is JSONArray || path.isEmpty()) return@walk
            val key = path.substringAfterLast('.')
            val label = if (key in TYPE_KEYS && value is String && IDENTIFIER.matches(value)) {
                "=$value"
            } else {
                ":" + (value.javaClass.simpleName.lowercase())
            }
            if (parts.size < 40) parts.add(path + label)
        }
        return parts.joinToString(" ")
    }

    private val TYPE_KEYS = setOf("type", "event", "event_type", "kind", "method")
    private val IDENTIFIER = Regex("[a-z0-9_.\\-]{1,64}")
    /** A field name must also look like an identifier: some are data. */
    private val FIELD = Regex("[A-Za-z_]{1,32}")

    private fun walk(value: Any?, depth: Int, path: String, visit: (Any, String) -> Unit) {
        if (value == null || value == JSONObject.NULL || depth > MAX_DEPTH) return
        if (value is String && value.length in 2..MAX_NESTED_CHARS && value.startsWith("{")) {
            val nested = try {
                JSONObject(value)
            } catch (e: JSONException) {
                null
            }
            if (nested != null) return walk(nested, depth + 1, "$path{}", visit)
        }
        visit(value, path)
        when (value) {
            is JSONObject -> for (key in value.keys()) {
                val name = if (FIELD.matches(key)) key else "?"
                walk(value.opt(key), depth + 1, if (path.isEmpty()) name else "$path.$name", visit)
            }
            is JSONArray -> for (i in 0 until minOf(value.length(), 20)) walk(value.opt(i), depth + 1, "$path[]", visit)
        }
    }
}
