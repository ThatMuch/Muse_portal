package com.portalmuse.core

import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException

private const val MAX_URL_CHARS = 2_048

/** A web page the person wants to continue on the big screen. */
object WebLinks {
    private val LOCAL = listOf(".local", ".localhost", ".lan", ".home", ".internal", ".home.arpa")
    private val NAME = Regex("[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z][A-Za-z0-9-]+")

    /** Only https pages on a public domain name: never an address of the home network. */
    fun allowed(address: String): Boolean {
        if (address.length > MAX_URL_CHARS) return false
        val uri = try {
            URI(address)
        } catch (e: URISyntaxException) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) && NAME.matches(host) && LOCAL.none(host::endsWith)
    }
}

interface HandoffHost {
    /** False if the kiosk is not on screen. */
    fun openUrl(url: String, title: String?): Boolean
}

class HandoffCommands(private val host: HandoffHost) : CommandSet {
    override fun specs(): Map<String, JSONObject> = linkedMapOf(
        OPEN to Specs.of(
            "Open a web page on the device's screen, where the person can scroll and tap. Use it when they want " +
                "to continue here what they have on their phone or computer: a recipe, an article, a map, a " +
                "video page. The person can send the link back to their phone.",
            JSONObject().put("url", Specs.field("string", "A public https address.")),
            JSONObject().put("title", Specs.field("string", "Short heading, in the person's language.")),
        ),
    )

    override fun run(command: String, params: JSONObject): JSONObject? {
        if (command != OPEN) return null
        val url = (params.opt("url") as? String)?.trim()
        if (url == null || !WebLinks.allowed(url)) return Specs.error("url must be a public https address")
        return if (host.openUrl(url, Specs.text(params, "title", 80))) {
            Specs.ok(JSONObject().put("shown", true))
        } else {
            Specs.unavailable()
        }
    }

    private companion object {
        const val OPEN = "display.open_url"
    }
}
