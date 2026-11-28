package com.portalmuse.core

private const val WINDOW_MS = 10 * 60_000L
private const val MAX_IN_WINDOW = 3

/** Reopening the screen after a crash, without looping if the failure repeats. */
object RestartGuard {
    /**
     * [history]: the times of the previous reopenings. Returns the list to keep, with [now], or `null` if we
     * must refrain.
     */
    fun allow(history: List<Long>, now: Long): List<Long>? {
        val recent = history.filter { it in (now - WINDOW_MS)..now }
        return if (recent.size >= MAX_IN_WINDOW) null else recent + now
    }
}
