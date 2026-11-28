package com.portalmuse.core

import org.json.JSONArray
import org.json.JSONObject

/** [spoken]: how people call the station aloud. */
data class Station(val id: String, val name: String, val url: String, val spoken: String)

/** A fixed list of public streams: the Muse cannot make the device play any other address. */
object Stations {
    private fun radioFrance(id: String, name: String, spoken: String) =
        Station(id, name, "https://icecast.radiofrance.fr/$id-midfi.mp3", spoken)

    private fun somaFm(id: String, name: String, spoken: String) =
        Station(id, name, "https://ice1.somafm.com/$id-128-mp3", spoken)

    val ALL = listOf(
        radioFrance("fip", "FIP", "fip|flip"),
        radioFrance("franceinter", "France Inter", "france inter"),
        radioFrance("franceinfo", "France Info", "france info"),
        radioFrance("franceculture", "France Culture", "france culture"),
        radioFrance("francemusique", "France Musique", "france musique"),
        radioFrance("mouv", "Mouv'", "mouv"),
        somaFm("groovesalad", "Groove Salad (SomaFM)", "groove salad"),
        somaFm("dronezone", "Drone Zone (SomaFM)", "drone zone"),
    )

    fun byId(id: String?) = ALL.firstOrNull { it.id == id }
}

interface RadioHost {
    fun playRadio(station: Station)

    /** False if no radio was on. */
    fun stopRadio(): Boolean

    /** The station that is on, if any. */
    fun radio(): Station?
}

class RadioCommands(private val host: RadioHost) : CommandSet {
    override fun specs(): Map<String, JSONObject> = linkedMapOf(
        PLAY to Specs.of(
            "Play a radio station on the device's speaker until stopped. During a call it starts when the call ends.",
            JSONObject().put("station", Specs.field("string", "One of: " + Stations.ALL.joinToString { it.id } + ".")),
            JSONObject(),
        ),
        STOP to Specs.of("Stop the radio.", JSONObject(), JSONObject()),
    )

    override fun run(command: String, params: JSONObject): JSONObject? = when (command) {
        PLAY -> {
            val station = Stations.byId(params.opt("station") as? String)
            if (station == null) {
                Specs.error("unknown station; use one of " + Stations.ALL.joinToString { it.id })
            } else {
                host.playRadio(station)
                Specs.ok(JSONObject().put("playing", station.name))
            }
        }
        STOP -> Specs.ok(JSONObject().put("stopped", host.stopRadio()))
        else -> null
    }

    private companion object {
        const val PLAY = "radio.play"
        const val STOP = "radio.stop"
    }
}
