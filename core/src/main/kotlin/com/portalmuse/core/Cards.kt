package com.portalmuse.core

import org.json.JSONArray
import org.json.JSONObject

private const val MAX_CARDS = 3

/**
 * A card of the home screen: a reminder, the next appointment, a note sent from the phone. It also shows
 * between the photos of the idle screen. [expiresMinutes]: how long it stays true.
 */
data class Card(
    val title: String, val text: String?, val icon: String?,
    val important: Boolean = false, val expiresMinutes: Int? = null,
)

interface CardHost {
    /** Replaces the cards; an empty list removes them. */
    fun setCards(cards: List<Card>)
}

class CardCommands(private val host: CardHost) : CommandSet {
    override fun specs(): Map<String, JSONObject> = linkedMapOf(
        SET to Specs.of(
            "Show up to $MAX_CARDS cards on the device's home screen, in place of the weather forecast, and " +
                "between the photos of its idle screen: next appointment, traffic, reminder, shopping note. Keep " +
                "them current on your own initiative whenever something deserves the person's attention. When the " +
                "person asks, from their phone, computer " +
                "or this device, to leave a note, reminder or information on the Portal, use this. The list " +
                "replaces the previous cards (send the ones to keep again); an empty list removes them. The " +
                "person can dismiss a card or send it to their phone.",
            JSONObject().put(
                "cards",
                Specs.field(
                    "array",
                    "Objects with title (up to 60 characters), text (up to 140), icon (one emoji), important " +
                        "(boolean: shown first) and expires_in_minutes (removed afterwards; set it for anything dated).",
                ),
            ),
            JSONObject(),
        ),
    )

    override fun run(command: String, params: JSONObject): JSONObject? {
        if (command != SET) return null
        val list = params.opt("cards") as? JSONArray ?: return Specs.error("cards must be an array")
        if (list.length() > MAX_CARDS) return Specs.error("at most $MAX_CARDS cards")
        val cards = (0 until list.length()).map { index ->
            val item = list.optJSONObject(index)
            val title = item?.let { Specs.text(it, "title", 60) } ?: return Specs.error("card ${index + 1} needs a title")
            Card(
                title, Specs.text(item, "text", 140), Specs.text(item, "icon", 8), item.opt("important") == true,
                (item.opt("expires_in_minutes") as? Number)?.toInt()?.coerceIn(5, 7 * 24 * 60),
            )
        }
        host.setCards(cards)
        return Specs.ok(JSONObject().put("cards", cards.size))
    }

    private companion object {
        const val SET = "home.set_cards"
    }
}
