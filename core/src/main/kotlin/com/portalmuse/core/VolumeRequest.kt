package com.portalmuse.core

/**
 * A volume request spoken during a call and understood on the device: a Muse does not always answer it
 * with `audio.volume`. The request must end a short sentence and only follow a few filler words, such as
 * the wake word however it was transcribed, so that ordinary talk does not set the volume.
 */
sealed class VolumeRequest {
    /** Louder (positive) or quieter (negative), in points of percent. */
    data class Change(val points: Int) : VolumeRequest()
    data class Level(val percent: Int) : VolumeRequest()

    companion object {
        private const val STEP = 15
        private const val MAX_CHARS = 70
        private const val MAX_BEFORE = 3
        private val WORDS = mapOf(
            "zéro" to 0, "dix" to 10, "vingt" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50,
            "soixante" to 60, "soixante dix" to 70, "quatre vingt" to 80, "quatre vingts" to 80,
            "quatre vingt dix" to 90, "cent" to 100,
        )
        private val NUMBER = "(\\d{1,3}|" + WORDS.keys.sortedByDescending { it.length }.joinToString("|") + ")"
        private val ASIDE = Regex(
            "(?<![\\p{L}])(s['’ ]il te pla[iî]t|s['’ ]il vous pla[iî]t|stp|svp|please|merci|thank you|thanks|" +
                "sur le portal|on the portal)(?![\\p{L}])",
        )
        /** The sounds a wake word starts with, whatever the transcription makes of the rest. */
        private val CALLING = setOf("hey", "hé", "eh", "ey", "hay")
        private val FILLERS = CALLING + setOf(
            "et", "ok", "okay", "alors", "euh", "bon", "oui", "dis", "moi", "muse", "mouse", "mius", "meuse", "amuse",
        )
        private val MENTION = Regex("(?<![\\p{L}])(son|volume|fort|sound|louder|quieter)(?![\\p{L}])")

        private const val ASK = "(?:(?:est ce que )?(?:tu peux |peux tu |tu pourrais |pourrais tu )|can you |could you )?"
        private const val SOUND = "(?:le |ton )?(?:son|volume)(?: de ta voix)?"
        private const val SOME = "(?:(?:encore )?(?:un (?:petit )?peu )|encore )?"
        private const val AGAIN = "(?: un (?:petit )?peu| encore)?"
        private const val PUT = "(?:mets|met|mettre)"
        private const val EN_SOUND = "(?:the |your )?(?:sound|volume)"
        private const val EN_SOME = "(?:a bit |a little )?"

        private fun ending(vararg forms: String) = Regex("(?<![\\p{L}])" + ASK + "(?:" + forms.joinToString("|") + ")$")

        private val LEVEL = ending(
            "(?:(?:$PUT|règle[rz]?|monte[rz]?|augmente[rz]?|baisse[rz]?|passe[rz]?) (?:moi )?)?$SOUND " +
                "(?:à|a|au|sur) $NUMBER(?: ?%| pour ?cents?)?",
            "(?:(?:set|put|turn) )?$EN_SOUND (?:to|at) $NUMBER(?: ?%| percent)?",
        )
        private val UP = ending(
            "(?:monte[rz]?|augmente[rz]?|hausse[rz]?) $SOME$SOUND$AGAIN",
            "(?:(?:parle[rz]?|$PUT)(?: $SOUND)? )?${SOME}plus fort",
            "(?:turn )?$EN_SOUND up", "turn up $EN_SOUND", "turn it up", "(?:speak )?${EN_SOME}louder",
        )
        private val DOWN = ending(
            "(?:baisse[rz]?|diminue[rz]?|réduis|réduire) $SOME$SOUND$AGAIN",
            "(?:(?:parle[rz]?|$PUT)(?: $SOUND)? )?${SOME}moins fort",
            "(?:turn )?$EN_SOUND down", "turn down $EN_SOUND", "turn it down",
            "(?:speak )?$EN_SOME(?:quieter|softer)",
        )

        /** True if the sentence talks about loudness: said in the logs when no request was recognized. */
        fun mentions(phrase: String) = MENTION.containsMatchIn(phrase.lowercase())

        fun parse(phrase: String, museName: String? = null): VolumeRequest? {
            if (phrase.length > 2 * MAX_CHARS) return null
            var text = phrase.lowercase().replace('-', ' ').replace(Regex("[.,;:!?…]+"), " ").replace(ASIDE, " ")
            if (museName != null) text = text.replace(Regex("\\b${Regex.escape(museName.lowercase())}\\b"), " ")
            text = text.replace(Regex("\\s+"), " ").trim()
            if (text.length > MAX_CHARS) return null
            LEVEL.find(text)?.takeIf { addressed(text, it) }?.let { match ->
                val said = match.groupValues.drop(1).first { it.isNotEmpty() }
                return (said.toIntOrNull() ?: WORDS[said])?.takeIf { it in 0..100 }?.let(::Level)
            }
            if (UP.find(text)?.let { addressed(text, it) } == true) return Change(STEP)
            if (DOWN.find(text)?.let { addressed(text, it) } == true) return Change(-STEP)
            return null
        }

        /** What precedes the request is only a few words calling the Muse. */
        private fun addressed(text: String, request: MatchResult): Boolean {
            val before = text.substring(0, request.range.first).split(' ').filter { it.isNotEmpty() }
            if (before.size > MAX_BEFORE) return false
            return before.firstOrNull() in CALLING || before.all { it in FILLERS }
        }
    }
}
