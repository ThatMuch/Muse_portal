package com.portalmuse.gadget

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.portalmuse.core.TimerInfo
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "PortalMuse"
private const val MAX_TIMERS = 8
private const val RING_LIMIT_MS = 2 * 60_000L
private const val RING_EVERY_MS = 1_500L

/**
 * The timers. They survive an app restart, ring even outside the kiosk, and stop on their own after two
 * minutes.
 */
object TimerCenter {
    class Timer(val id: Int, val label: String?, val endsAt: Long)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var context: Context
    private var timers = emptyList<Timer>()
    private var ringingSince = 0L
    private var tone: ToneGenerator? = null

    /** Finished timers that are still ringing. */
    @Volatile var ringing = emptyList<Timer>()
        private set

    /** Called on the main thread at every change. */
    @Volatile var onChange: (() -> Unit)? = null

    private val prefs get() = context.getSharedPreferences("timers", Context.MODE_PRIVATE)

    @Synchronized
    fun init(application: Context) {
        context = application.applicationContext
        timers = try {
            val list = JSONArray(prefs.getString("list", null) ?: "[]")
            (0 until list.length()).map { list.getJSONObject(it) }
                .map { Timer(it.getInt("id"), it.optString("label").ifEmpty { null }, it.getLong("ends")) }
        } catch (e: JSONException) {
            emptyList()
        }
        main.post(tick)
    }

    @Synchronized
    fun running(): List<Timer> = timers.sortedBy { it.endsAt }

    fun describe(timer: Timer) =
        TimerInfo(timer.id, timer.label, maxOf(0, (timer.endsAt - System.currentTimeMillis() + 999) / 1000))

    @Synchronized
    fun start(durationMillis: Long, label: String?): Timer? {
        if (timers.size >= MAX_TIMERS) return null
        val id = prefs.getInt("next", 1)
        prefs.edit().putInt("next", id + 1).apply()
        val timer = Timer(id, label, System.currentTimeMillis() + durationMillis)
        store(timers + timer)
        Log.i(TAG, "timer $id started for ${durationMillis / 1000} s")
        main.removeCallbacks(tick)
        main.post(tick)
        changed()
        return timer
    }

    /** [id] null: all of them, including those ringing. */
    @Synchronized
    fun cancel(id: Int?): Int {
        val kept = timers.filter { id != null && it.id != id }
        val removed = timers.size - kept.size + ringing.count { id == null || it.id == id }
        store(kept)
        ringing = ringing.filter { id != null && it.id != id }
        if (ringing.isEmpty()) silence()
        changed()
        return removed
    }

    @Synchronized
    fun stopRinging() {
        ringing = emptyList()
        silence()
        changed()
    }

    private fun store(next: List<Timer>) {
        timers = next
        val list = JSONArray(next.map { JSONObject().put("id", it.id).putOpt("label", it.label).put("ends", it.endsAt) })
        prefs.edit().putString("list", list.toString()).apply()
    }

    private val tick = object : Runnable {
        override fun run() {
            synchronized(TimerCenter) {
                val now = System.currentTimeMillis()
                val (over, left) = timers.partition { it.endsAt <= now }
                if (over.isNotEmpty()) {
                    store(left)
                    // A timer that ended long ago (device off) no longer rings.
                    val fresh = over.filter { now - it.endsAt < RING_LIMIT_MS }
                    if (fresh.isNotEmpty()) {
                        ringing = ringing + fresh
                        ringingSince = now
                        Log.i(TAG, "timer done")
                    }
                    changed()
                }
                if (ringing.isNotEmpty()) {
                    if (now - ringingSince >= RING_LIMIT_MS) stopRinging() else beep()
                }
                main.removeCallbacks(this)
                if (timers.isNotEmpty() || ringing.isNotEmpty()) {
                    main.postDelayed(this, if (ringing.isEmpty()) 500 else RING_EVERY_MS)
                }
            }
        }
    }

    private fun beep() {
        val generator = tone ?: try {
            // The alarm channel: audible even when media volume is at zero.
            ToneGenerator(AudioManager.STREAM_ALARM, 90).also { tone = it }
        } catch (e: RuntimeException) {
            return
        }
        generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900)
    }

    private fun silence() {
        tone?.release()
        tone = null
    }

    private fun changed() {
        main.post { onChange?.invoke() }
    }
}
