package com.portalmuse.core

import org.json.JSONArray
import org.json.JSONObject

private const val MAX_PICTURES = 8
private const val MAX_TITLE_CHARS = 120
private const val MAX_PAGE_CHARS = 200_000
private const val DEFAULT_DURATION_MS = 120_000L
private const val MIN_DURATION_MS = 5_000L
private const val MAX_DURATION_MS = 1_800_000L
private const val LOAD_DEADLINE_S = 45L
private const val COMMAND_TIMEOUT_MS = 90_000

sealed class PictureSource {
    /** Un fichier sur la VM de Muse. */
    data class File(val path: String) : PictureSource()
    /** Une image publique en https. */
    data class Web(val url: String) : PictureSource()
}

/** [image] est ce que l'écran a préparé, par exemple une image décodée. */
class LoadedPicture(val image: Any, val caption: String?)

/** L'écran de l'appareil, vu par les commandes. */
interface ScreenHost {
    /** Faux si le kiosque n'est pas à l'écran. */
    fun isAvailable(): Boolean

    /** Télécharge et prépare une image ; `null` si ce n'est pas possible. Bloquant. */
    fun load(source: PictureSource): Any?

    fun showPictures(pictures: List<LoadedPicture>, title: String?, durationMillis: Long): Boolean
    fun showPage(html: String, title: String?, durationMillis: Long): Boolean
    fun close()
}

/**
 * Ce que Muse peut faire de l'écran : montrer des images, montrer une page, et les retirer.
 * Les messages renvoyés à Muse sont en anglais, comme le reste du protocole.
 */
class DisplayCommands(private val screen: ScreenHost) : CommandSet {
    override fun specs(): Map<String, JSONObject> {
        val field = Specs::field
        val spec = Specs::of
        fun options() = JSONObject()
            .put("title", field("string", "Short heading, in the person's language."))
            .put("duration_ms", field("integer", "How long it stays, 5000 to 1800000. Default 120000. 0 means until closed."))
        return linkedMapOf(
            SHOW_IMAGES to spec(
                "Show up to 8 pictures full screen on this 10 inch landscape touchscreen (1280x800), as a " +
                    "slideshow the person can swipe. Use it whenever seeing helps, without waiting to be asked: " +
                    "a place, an animal, a person's work, a product, a recipe, a map or a chart you made. " +
                    "Give direct links to image files from sources that allow reuse, such as Wikimedia Commons, " +
                    "or files you saved on your VM. The result says how many were shown and which failed.",
                JSONObject().put(
                    "images",
                    field(
                        "array",
                        "1 to 8 objects, each with exactly one of url (https link to a JPEG, PNG, WebP or GIF " +
                            "file itself, not a web page) or path (image file on your VM), and an optional caption.",
                    ),
                ),
                options(),
            ).put("timeout_ms", COMMAND_TIMEOUT_MS),
            SHOW_PAGE to spec(
                "Show a page full screen: a list, table, recipe, timeline, comparison or chart that you write " +
                    "as self-contained HTML with inline CSS and inline SVG. No scripts, links or external " +
                    "resources; images only as data: URIs. The background is dark and the page is read from a " +
                    "distance: light text of 24px or more, within 1280x700, scrolling if longer.",
                JSONObject().put("html", field("string", "The page body, up to 200000 characters.")),
                options(),
            ),
            CLOSE to spec("Remove what you put on the screen and return to the home screen.", JSONObject(), JSONObject()),
        )
    }

    /** `null` si [command] n'est pas une commande d'écran. */
    override fun run(command: String, params: JSONObject): JSONObject? = when (command) {
        SHOW_IMAGES -> available { images(params) }
        SHOW_PAGE -> available { page(params) }
        CLOSE -> {
            screen.close()
            ok(JSONObject().put("closed", true))
        }
        else -> null
    }

    private fun available(action: () -> JSONObject): JSONObject =
        if (screen.isAvailable()) action() else error("the screen is showing something else right now")

    private fun images(params: JSONObject): JSONObject {
        val list = params.opt("images") as? JSONArray ?: return error("images must be an array")
        if (list.length() !in 1..MAX_PICTURES) return error("give 1 to $MAX_PICTURES images")
        val requests = Pictures.parse(list) ?: return error("each image needs exactly one of url or path")
        val loaded = Pictures.load(requests.map { it.first }, screen::load, LOAD_DEADLINE_S)
        val pictures = loaded.mapIndexedNotNull { index, image -> image?.let { LoadedPicture(it, requests[index].second) } }
        val failed = loaded.indices.filter { loaded[it] == null }.map { it + 1 }
        if (pictures.isEmpty()) {
            return error("no image could be loaded: check that each link is a public https image file")
        }
        if (!screen.showPictures(pictures, Specs.text(params, "title", MAX_TITLE_CHARS), duration(params))) {
            return error("the screen is showing something else right now")
        }
        return ok(JSONObject().put("shown", pictures.size).put("failed_items", JSONArray(failed)))
    }

    private fun page(params: JSONObject): JSONObject {
        val html = params.opt("html") as? String
        if (html.isNullOrBlank() || html.length > MAX_PAGE_CHARS) {
            return error("html must be 1 to $MAX_PAGE_CHARS characters")
        }
        return if (screen.showPage(SafePage.wrap(html), Specs.text(params, "title", MAX_TITLE_CHARS), duration(params))) {
            ok(JSONObject().put("shown", true))
        } else {
            error("the screen is showing something else right now")
        }
    }

    private fun duration(params: JSONObject): Long {
        val asked = (params.opt("duration_ms") as? Number)?.toLong() ?: return DEFAULT_DURATION_MS
        return if (asked == 0L) MAX_DURATION_MS else asked.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }

    private fun ok(payload: JSONObject) = Specs.ok(payload)
    private fun error(message: String) = Specs.error(message)

    private companion object {
        const val SHOW_IMAGES = "display.show_images"
        const val SHOW_PAGE = "display.show_page"
        const val CLOSE = "display.close"
    }
}

/** Une page écrite par Muse, enfermée : ni script, ni réseau, ni ressource extérieure. */
object SafePage {
    private const val HEAD = "<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\">" +
        "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; " +
        "style-src 'unsafe-inline'; font-src data:\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>html{background:#000;color:#eef2f6;font-family:sans-serif;font-size:24px}" +
        "body{margin:24px 48px;line-height:1.4}img,svg{max-width:100%;height:auto}" +
        "table{border-collapse:collapse;width:100%}th,td{border-bottom:1px solid #333;padding:8px 12px;text-align:left}" +
        "</style></head><body>"

    fun wrap(html: String) = HEAD + html
}
