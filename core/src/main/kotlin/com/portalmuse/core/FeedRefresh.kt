package com.portalmuse.core

/**
 * When to ask for the "Feed" again without a touch on "Refresh": when it is opened, if empty or old. Each
 * request is a message to Muse, hence the delay between two attempts.
 */
object FeedRefresh {
    const val STALE_MILLIS = 3 * 3_600_000L
    const val RETRY_MILLIS = 30 * 60_000L

    /** [savedAt]: when the shown feed arrived, [askedAt]: last request; `null` if there is none. */
    fun isDue(savedAt: Long?, askedAt: Long?, now: Long): Boolean {
        if (askedAt != null && now - askedAt in 0 until RETRY_MILLIS) return false
        return savedAt == null || now - savedAt !in 0 until STALE_MILLIS
    }
}
