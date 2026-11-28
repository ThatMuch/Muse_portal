package com.portalmuse.gadget

import android.content.Context
import com.portalmuse.core.Card
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The home screen cards, in private storage. Expired ones disappear on reading. */
object CardStore {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("cards", Context.MODE_PRIVATE)

    @Synchronized
    fun set(context: Context, cards: List<Card>) {
        val now = System.currentTimeMillis()
        store(context, cards.map { it to it.expiresMinutes?.let { minutes -> now + minutes * 60_000L } })
    }

    /** The cards still valid, important ones first. */
    @Synchronized
    fun load(context: Context): List<Card> = read(context).map { it.first }.sortedByDescending { it.important }

    /** Removes the card at this place of [load]. */
    @Synchronized
    fun remove(context: Context, position: Int) {
        val gone = load(context).getOrNull(position) ?: return
        store(context, read(context).filterNot { it.first == gone })
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun store(context: Context, cards: List<Pair<Card, Long?>>) {
        val list = JSONArray(
            cards.map { (card, until) ->
                JSONObject().put("title", card.title).putOpt("text", card.text).putOpt("icon", card.icon)
                    .put("important", card.important).putOpt("until", until)
            },
        )
        prefs(context).edit().putString("list", list.toString()).apply()
    }

    private fun read(context: Context): List<Pair<Card, Long?>> = try {
        val list = JSONArray(prefs(context).getString("list", null) ?: "[]")
        val now = System.currentTimeMillis()
        (0 until list.length()).map { list.getJSONObject(it) }.mapNotNull { item ->
            val until = if (item.has("until")) item.getLong("until") else null
            fun text(key: String) = item.optString(key).ifEmpty { null }
            if (until != null && until <= now) null
            else Card(item.getString("title"), text("text"), text("icon"), item.optBoolean("important")) to until
        }
    } catch (e: JSONException) {
        emptyList()
    }
}
