package com.portalmuse.core

/**
 * A volume request spoken during a call and understood on the device: a Muse does not always answer it
 * with `audio.volume`. Only a whole, short sentence counts, so that a television does not set the volume.
 */
sealed class VolumeRequest {
    /** Louder (positive) or quieter (negative), in points of percent. */
    data class Change(val points: Int) : VolumeRequest()
    data class Level(val percent: Int) : VolumeRequest()

    companion object {
        private const val STEP = 15
        private const val MAX_CHARS = 60
        private val WORDS = mapOf(
            "zéro" to 0, "dix" to 10, "vingt" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50,
            "soixante" to 60, "soixante dix" to 70, "quatre vingt" to 80, "quatre vingts" to 80,
            "quatre vingt dix" to 90, "cent" to 100,
        )
        private val NUMBER = "(\\d{1,3}|" + WORDS.keys.sortedByDescending { it.length }.joinToString("|") + ")"
        private val POLITE = Regex("\\b(hey muse|s['’ ]il te pla[iî]t|s['’ ]il vous pla[iî]t|stp|svp|please)\\b")
        private const val ASK = "(?:(?:est ce que )?(?:tu peux |peux tu |tu pourrais |pourrais tu )|can you |could you )?"
        private const val SOUND = "(?:le |ton )?(?:son|volume)"
        private const val SOME = "(?:un peu |encore )?"
        private const val AGAIN = "(?: un peu| encore)?"
        private const val EN_SOUND = "(?:the |your )?(?:sound|volume)"
        private const val EN_SOME = "(?:a bit |a little )?"

        private fun whole(vararg forms: String) = Regex(ASK + "(?:" + forms.joinToString("|") + ")")

        private val LEVEL = whole(
            "(?:mets|met|mettre|règle[rz]?|monte[rz]?|augmente[rz]?|baisse[rz]?|passe[rz]?) (?:moi )?$SOUND " +
                "(?:à|a|au) $NUMBER(?: ?%| pour ?cents?)?",
            "(?:set|put|turn) $EN_SOUND (?:to|at) $NUMBER(?: ?%| percent)?",
        )
        private val UP = whole(
            "(?:monte[rz]?|augmente[rz]?|hausse[rz]?) $SOME$SOUND$AGAIN",
            "(?:parle[rz]? )?${SOME}plus fort",
            "(?:turn )?$EN_SOUND up", "turn up $EN_SOUND", "turn it up", "(?:speak )?${EN_SOME}louder",
        )
        private val DOWN = whole(
            "(?:baisse[rz]?|diminue[rz]?|réduis|réduire) $SOME$SOUND$AGAIN",
            "(?:parle[rz]? )?${SOME}moins fort",
            "(?:turn )?$EN_SOUND down", "turn down $EN_SOUND", "turn it down",
            "(?:speak )?$EN_SOME(?:quieter|softer)",
        )

        fun parse(phrase: String, museName: String? = null): VolumeRequest? {
            if (phrase.length > 2 * MAX_CHARS) return null
            var text = phrase.lowercase().replace('-', ' ').replace(POLITE, " ")
            if (museName != null) text = text.replace(Regex("\\b${Regex.escape(museName.lowercase())}\\b"), " ")
            text = text.replace(Regex("[.,!?…]+"), " ").replace(Regex("\\s+"), " ").trim()
            if (text.length > MAX_CHARS) return null
            LEVEL.matchEntire(text)?.let { match ->
                val said = match.groupValues.drop(1).first { it.isNotEmpty() }
                return (said.toIntOrNull() ?: WORDS[said])?.takeIf { it in 0..100 }?.let(::Level)
            }
            if (UP.matches(text)) return Change(STEP)
            if (DOWN.matches(text)) return Change(-STEP)
            return null
        }
    }
}
