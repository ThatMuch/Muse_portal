package com.portalmuse.core

/**
 * Silences the one reply to a request the device has already carried out (the volume): the Muse tends to
 * answer that it cannot. The reply is over after a pause; what comes next is played again.
 */
class ReplyMute(
    private val startWithinMillis: Long = 6_000,
    private val pauseMillis: Long = 1_500,
    private val atMostMillis: Long = 15_000,
) {
    private var since: Long? = null
    private var lastVoice: Long? = null

    @Synchronized
    fun start(now: Long) {
        since = now
        lastVoice = null
    }

    /** [voice]: this sound is the Muse speaking, not background noise. True if it must not be played. */
    @Synchronized
    fun drops(voice: Boolean, now: Long): Boolean {
        val started = since ?: return false
        val heard = lastVoice
        val over = now - started >= atMostMillis ||
            if (heard == null) now - started >= startWithinMillis else now - heard >= pauseMillis
        if (over) {
            since = null
            return false
        }
        if (voice) lastVoice = now
        return true
    }
}
