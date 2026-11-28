package com.portalmuse.core

/**
 * Quand redemander le « Fil » sans qu'on touche « Actualiser » : à son ouverture, s'il est vide ou
 * ancien. Chaque demande est un message à Muse, d'où le délai entre deux essais.
 */
object FeedRefresh {
    const val STALE_MILLIS = 3 * 3_600_000L
    const val RETRY_MILLIS = 30 * 60_000L

    /** [savedAt] : réception du fil affiché, [askedAt] : dernière demande ; `null` s'il n'y en a pas. */
    fun isDue(savedAt: Long?, askedAt: Long?, now: Long): Boolean {
        if (askedAt != null && now - askedAt in 0 until RETRY_MILLIS) return false
        return savedAt == null || now - savedAt !in 0 until STALE_MILLIS
    }
}
