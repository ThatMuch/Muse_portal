package com.portalmuse.core

import org.json.JSONArray
import org.json.JSONObject

private const val MAX_ITEMS = 12
private const val DEADLINE_S = 150L

/** A suggestion of Muse's feed. Touching [actionLabel] sends [actionMessage] to Muse. */
data class FeedItem(
    val title: String,
    val text: String?,
    val icon: String?,
    val actionLabel: String,
    val actionMessage: String,
    /** A picture ready to save, in the form returned by [FeedHost.loadPhoto]. */
    val image: Any? = null,
)

interface FeedHost {
    fun loadPhoto(source: PictureSource): Any?
    /** Replaces the feed; [open] asks to show it. Returns whether it is shown. */
    fun setFeed(items: List<FeedItem>, open: Boolean): Boolean
}

/** Muse's "Feed" on the Portal. A gadget has no access to that feed: Muse is the one that puts it here. */
class FeedCommands(private val host: FeedHost) : CommandSet {
    override fun specs(): Map<String, JSONObject> = linkedMapOf(
        SET to Specs.of(
            "Put the person's feed (the suggestions you curate for them, as in your app's feed) on the " +
                "device, newest first. It replaces the previous one; an empty list clears it. Send it when they " +
                "ask, and again when your feed changes if they asked you to keep it up to date.",
            JSONObject().put(
                "items",
                Specs.field(
                    "array",
                    "Up to $MAX_ITEMS objects: title (required), text, icon (one emoji), image_url (https image " +
                        "file) or image_path, action_label, action_message (sent to you as the person if they tap).",
                ),
            ),
            JSONObject().put("open", Specs.field("boolean", "Show the feed on screen now. Default false.")),
        ).put("timeout_ms", 200_000),
    )

    override fun run(command: String, params: JSONObject): JSONObject? {
        if (command != SET) return null
        val list = params.opt("items") as? JSONArray ?: return Specs.error("items must be an array")
        if (list.length() > MAX_ITEMS) return Specs.error("at most $MAX_ITEMS items")
        val sources = ArrayList<PictureSource?>()
        val items = (0 until list.length()).map { index ->
            val item = list.optJSONObject(index) ?: return Specs.error("item ${index + 1} must be an object")
            val title = Specs.text(item, "title", 140) ?: return Specs.error("item ${index + 1} needs a title")
            val url = Specs.text(item, "image_url", 2_048)
            val path = Specs.text(item, "image_path", 2_048)
            sources.add(url?.let { PictureSource.Web(it) } ?: path?.let { PictureSource.File(it) })
            FeedItem(
                title, Specs.text(item, "text", 600), Specs.text(item, "icon", 8),
                Specs.text(item, "action_label", 24) ?: tr("Let's go", "C'est parti"),
                Specs.text(item, "action_message", 500) ?: tr("Let's go: $title", "C'est parti pour : $title"),
            )
        }
        val wanted = sources.filterNotNull()
        val loaded = if (wanted.isEmpty()) emptyList() else Pictures.load(wanted, host::loadPhoto, DEADLINE_S)
        var next = 0
        val complete = items.mapIndexed { index, item ->
            if (sources[index] == null) item else item.copy(image = loaded[next++])
        }
        val shown = host.setFeed(complete, params.opt("open") == true && complete.isNotEmpty())
        // A missing picture does not fail the feed: we say which one.
        val failed = complete.indices.filter { sources[it] != null && complete[it].image == null }.map { it + 1 }
        return Specs.ok(
            JSONObject().put("items", complete.size).put("shown", shown).put("failed_images", JSONArray(failed)),
        )
    }

    private companion object {
        const val SET = "feed.set"
    }
}
