package com.portalmuse.core

/**
 * A volume request spoken during a call and understood on the device: a Muse does not always answer it
 * with `audio.volume`. See [SpokenRequest] for what counts.
 */
sealed class VolumeRequest {
    /** Louder (positive) or quieter (negative), in points of percent. */
    data class Change(val points: Int) : VolumeRequest()
    data class Level(val percent: Int) : VolumeRequest()

    companion object {
        private const val STEP = 15
        private val WORDS = mapOf(
            "zéro" to 0, "dix" to 10, "vingt" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50,
            "soixante" to 60, "soixante dix" to 70, "quatre vingt" to 80, "quatre vingts" to 80,
            "quatre vingt dix" to 90, "cent" to 100,
        )
        private val NUMBER = "(\\d{1,3}|" + WORDS.keys.sortedByDescending { it.length }.joinToString("|") + ")"
        private val MENTION = Regex("(?<![\\p{L}])(son|volume|fort|sound|louder|quieter)(?![\\p{L}])")

        private const val SOUND = "(?:le |ton )?(?:son|volume)(?: de ta voix)?"
        private const val SOME = "(?:(?:encore )?(?:un (?:petit )?peu )|encore )?"
        private const val AGAIN = "(?: un (?:petit )?peu| encore)?"
        private const val PUT = "(?:mets|met|mettre)"
        private const val EN_SOUND = "(?:the |your )?(?:sound|volume)"
        private const val EN_SOME = "(?:a bit |a little )?"

        private fun ending(vararg forms: String) = SpokenRequest.ending(*forms)

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
            val text = SpokenRequest.normalize(phrase, museName) ?: return null
            SpokenRequest.find(text, LEVEL)?.let { match ->
                val said = match.groupValues.drop(1).first { it.isNotEmpty() }
                return (said.toIntOrNull() ?: WORDS[said])?.takeIf { it in 0..100 }?.let(::Level)
            }
            if (SpokenRequest.find(text, UP) != null) return Change(STEP)
            if (SpokenRequest.find(text, DOWN) != null) return Change(-STEP)
            return null
        }
    }
}
