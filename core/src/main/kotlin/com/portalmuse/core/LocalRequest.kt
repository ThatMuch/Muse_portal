package com.portalmuse.core

/** What the device does itself when it hears it during a call, before hanging up. */
sealed class LocalRequest {
    data class Volume(val request: VolumeRequest) : LocalRequest()
    data class Night(val on: Boolean) : LocalRequest()
    /** [station] `null`: the last one listened to. */
    data class PlayRadio(val station: Station?) : LocalRequest()
    data object StopRadio : LocalRequest()
    data object StopTimer : LocalRequest()
    data object StopAlarm : LocalRequest()

    companion object {
        private const val STOP = "(?:arrête[rz]?|stoppe[rz]?|stop|coupe[rz]?|éteins|éteindre|annule[rz]?)"
        private const val PLAY = "(?:mets|met|mettre|lance[rz]?|allume[rz]?|joue[rz]?|écoute[rz]?)(?: moi)?"
        private val STATIONS = Stations.ALL.joinToString("|") { it.spoken }
        private val MENTION = Regex("(?<![\\p{L}])(radio|réveil|alarme?|minuteurs?|timers?|écran|nuit|screen|night)(?![\\p{L}])")

        private val STOP_TIMER = SpokenRequest.ending(
            "$STOP (?:le |les |mon |mes )?(?:minuteurs?|timers?|minuterie)",
            "(?:stop|cancel) (?:the |my )?timers?",
        )
        private val STOP_ALARM = SpokenRequest.ending(
            "$STOP (?:le |mon )?réveil", "$STOP (?:l['’ ]|mon )alarme", "(?:stop|turn off) (?:the |my )?alarm",
        )
        private val STOP_RADIO = SpokenRequest.ending(
            "$STOP (?:la |ma )?radio", "(?:stop|turn off) (?:the )?radio",
        )
        private val PLAY_RADIO = SpokenRequest.ending(
            "$PLAY (?:la |une )?(?:radio )?($STATIONS)", "$PLAY (?:la |une )?radio",
            "play (?:the )?(?:radio )?($STATIONS)", "(?:play|turn on) (?:the )?radio",
        )
        private val NIGHT_ON = SpokenRequest.ending(
            "(?:$PLAY |active[rz]? |passe[rz]? en )(?:le )?mode nuit", "$STOP l['’ ]écran", "mode nuit",
            "turn off the screen", "(?:turn on )?night mode(?: on)?",
        )
        private val NIGHT_OFF = SpokenRequest.ending(
            "(?:désactive[rz]?|quitte[rz]?|enlève|arrête[rz]?) (?:le )?mode nuit", "(?:rallume[rz]?|allume[rz]?) l['’ ]écran",
            "turn on the screen", "(?:turn off night mode|night mode off)",
        )

        /** True if the sentence is about something the device handles: said in the logs when nothing matched. */
        fun mentions(phrase: String) =
            VolumeRequest.mentions(phrase) || MENTION.containsMatchIn(phrase.lowercase())

        fun parse(phrase: String, museName: String? = null): LocalRequest? {
            VolumeRequest.parse(phrase, museName)?.let { return Volume(it) }
            val text = SpokenRequest.normalize(phrase, museName) ?: return null
            fun said(request: Regex) = SpokenRequest.find(text, request)
            // "Turn off night mode" and "arrête le mode nuit" before the forms that switch it on.
            if (said(NIGHT_OFF) != null) return Night(false)
            if (said(STOP_TIMER) != null) return StopTimer
            if (said(STOP_ALARM) != null) return StopAlarm
            if (said(STOP_RADIO) != null) return StopRadio
            said(PLAY_RADIO)?.let { match ->
                val name = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
                return PlayRadio(name?.let { heard -> Stations.ALL.first { Regex(it.spoken).matches(heard) } })
            }
            if (said(NIGHT_ON) != null) return Night(true)
            return null
        }
    }
}
