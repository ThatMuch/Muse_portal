package com.portalmuse.core

/**
 * Recognizing, in what was heard during a call, a request the device carries out itself. The request must end
 * a short sentence and only follow a few filler words, such as the wake word however it was transcribed, so
 * that ordinary talk triggers nothing.
 */
internal object SpokenRequest {
    private const val MAX_CHARS = 70
    private const val MAX_BEFORE = 3
    private val ASIDE = Regex(
        "(?<![\\p{L}])(s['’ ]il te pla[iî]t|s['’ ]il vous pla[iî]t|stp|svp|please|merci|thank you|thanks|" +
            "sur le portal|on the portal)(?![\\p{L}])",
    )
    /** The sounds a wake word starts with, whatever the transcription makes of the rest. */
    private val CALLING = setOf("hey", "hé", "eh", "ey", "hay")
    private val FILLERS = CALLING + setOf(
        "et", "ok", "okay", "alors", "euh", "bon", "oui", "dis", "moi", "muse", "mouse", "mius", "meuse", "amuse",
    )
    const val ASK = "(?:(?:est ce que )?(?:tu peux |peux tu |tu pourrais |pourrais tu )|can you |could you )?"

    fun ending(vararg forms: String) = Regex("(?<![\\p{L}])" + ASK + "(?:" + forms.joinToString("|") + ")$")

    /** Lower case, without punctuation, polite words or the Muse's name; `null` if too long to be a request. */
    fun normalize(phrase: String, museName: String?): String? {
        if (phrase.length > 2 * MAX_CHARS) return null
        var text = phrase.lowercase().replace('-', ' ').replace(Regex("[.,;:!?…]+"), " ").replace(ASIDE, " ")
        if (museName != null) text = text.replace(Regex("\\b${Regex.escape(museName.lowercase())}\\b"), " ")
        text = text.replace(Regex("\\s+"), " ").trim()
        return text.takeIf { it.length <= MAX_CHARS }
    }

    /** The request ending [text], if what precedes it is only a few words calling the Muse. */
    fun find(text: String, request: Regex): MatchResult? = request.find(text)?.takeIf { match ->
        val before = text.substring(0, match.range.first).split(' ').filter { it.isNotEmpty() }
        before.size <= MAX_BEFORE && (before.firstOrNull() in CALLING || before.all { it in FILLERS })
    }
}
