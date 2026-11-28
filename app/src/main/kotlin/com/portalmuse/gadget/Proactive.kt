package com.portalmuse.gadget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.portalmuse.core.ChatResult
import com.portalmuse.core.ProactiveSchedule
import com.portalmuse.core.tr
import java.io.IOException
import java.time.ZonedDateTime
import kotlin.concurrent.thread

private const val TAG = "PortalMuse"
private const val RETRY_MS = 30 * 60_000L

/**
 * A few times a day, the Portal asks its Muse for what deserves the screen, without anyone asking. The request
 * goes into a conversation of its own, so that the main one stays clean.
 */
object Proactive {
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var askedAt = 0L

    private val REQUEST get() = tr(
        "Automatic update of my Portal screen. On your own initiative: put with home.set_cards what deserves my " +
            "attention today (important appointments coming up, unusual traffic, reminders, a useful word), with " +
            "important set for what matters and expires_in_minutes for anything dated; an empty list if there is " +
            "nothing. Update my feed with feed.set without opening it. Use display.notify only for something " +
            "urgent. Answer just “done”.",
        "Mise à jour automatique de l'écran de mon Portal. De toi-même : mets avec home.set_cards ce qui mérite " +
            "mon attention aujourd'hui (rendez-vous importants à venir, trafic inhabituel, rappels, mot utile), " +
            "important à true pour l'essentiel et expires_in_minutes pour tout ce qui est daté ; une liste vide " +
            "s'il n'y a rien. Mets mon fil à jour avec feed.set sans l'ouvrir. display.notify seulement pour une " +
            "urgence. Réponds juste « fait ».",
    )

    /** Main thread. [now] true: asked from the menu, whatever the time. False if nothing was sent. */
    fun check(context: Context, now: Boolean = false): Boolean {
        val settings = Settings(context)
        if (running || !settings.screenAllowed) return false
        if (!now) {
            if (!settings.proactive || System.currentTimeMillis() - askedAt < RETRY_MS) return false
            if (settings.night.isNight(Clock.minuteOfDay(), System.currentTimeMillis())) return false
            if (!ProactiveSchedule.isDue(settings.proactiveDoneAt, ZonedDateTime.now())) return false
        }
        val chat = MuseSession.chat(settings.ambientSession()) ?: return false
        running = true
        askedAt = System.currentTimeMillis()
        Log.i(TAG, "screen update asked")
        thread(name = "proactive") {
            var done = false
            try {
                var result = chat.sendText(REQUEST)
                // A server without side chats: the main conversation, once.
                if (result is ChatResult.Failed && result.status != null) {
                    result = MuseSession.chat()?.sendText(REQUEST) ?: result
                }
                done = result is ChatResult.Answer
                Log.i(TAG, "screen update: ${if (done) "done" else "failed"}")
            } catch (e: IOException) {
                Log.w(TAG, "screen update failed (${e.javaClass.simpleName})")
            } catch (e: RuntimeException) {
                Log.w(TAG, "screen update failed (${e.javaClass.simpleName})")
            } finally {
                main.post {
                    running = false
                    if (done) Settings(context).proactiveDoneAt = System.currentTimeMillis()
                }
            }
        }
        return true
    }
}
