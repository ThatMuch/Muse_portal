package com.portalmuse.core

private const val WINDOW_MS = 10 * 60_000L
private const val MAX_IN_WINDOW = 3

/** Rouvrir l'écran après un plantage, sans tourner en boucle si la panne se répète. */
object RestartGuard {
    /**
     * [history] : les heures des réouvertures précédentes. Renvoie la liste à garder, avec [now],
     * ou `null` s'il faut s'abstenir.
     */
    fun allow(history: List<Long>, now: Long): List<Long>? {
        val recent = history.filter { it in (now - WINDOW_MS)..now }
        return if (recent.size >= MAX_IN_WINDOW) null else recent + now
    }
}
