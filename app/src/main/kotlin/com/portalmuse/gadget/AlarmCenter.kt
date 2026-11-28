package com.portalmuse.gadget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.portalmuse.core.Alarm
import com.portalmuse.core.Stations
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.ZonedDateTime

private const val TAG = "PortalMuse"
private const val MAX_ALARMS = 10
private const val EXTRA_ID = "alarm"
private const val SNOOZE_MS = 9 * 60_000L
private const val RING_LIMIT_MS = 10 * 60_000L
private const val RADIO_DEADLINE_MS = 12_000L
private const val BEEP_EVERY_MS = 1_500L
/** Kept apart from the alarm identifiers. */
private const val SNOOZE_ID = 100_000

/**
 * The wake-up alarms, handed to Android so that they ring after a reboot too. They play their radio on the
 * alarm volume, and beep if it does not start. All ringing happens on the main thread.
 */
object AlarmCenter {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var context: Context
    private val radio = StreamPlayer(AudioAttributes.USAGE_ALARM)
    private var tone: ToneGenerator? = null

    /** The alarm that is ringing, if any. */
    @Volatile var ringing: Alarm? = null
        private set

    /** Called on the main thread at every change. */
    @Volatile var onChange: (() -> Unit)? = null

    private val prefs get() = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)

    @Synchronized
    fun init(application: Context) {
        context = application.applicationContext
        alarms().forEach(::schedule)
    }

    @Synchronized
    fun alarms(): List<Alarm> = try {
        val list = JSONArray(prefs.getString("list", null) ?: "[]")
        (0 until list.length()).map { list.getJSONObject(it) }.map { item ->
            val days = item.getJSONArray("days")
            Alarm(
                item.getInt("id"), item.getInt("hour"), item.getInt("minute"),
                (0 until days.length()).map { DayOfWeek.of(days.getInt(it)) }.toSet(),
                item.optString("label").ifEmpty { null }, item.optString("station").ifEmpty { null },
            )
        }.sortedBy { it.next(ZonedDateTime.now()) }
    } catch (e: JSONException) {
        emptyList()
    }

    @Synchronized
    fun add(alarm: Alarm): Alarm? {
        val current = alarms()
        if (current.size >= MAX_ALARMS) return null
        val id = prefs.getInt("next", 1)
        prefs.edit().putInt("next", id + 1).apply()
        val saved = alarm.copy(id = id)
        store(current + saved)
        schedule(saved)
        Log.i(TAG, "alarm $id set")
        changed()
        return saved
    }

    /** [id] null: all of them. */
    @Synchronized
    fun cancel(id: Int?): Int {
        val (gone, kept) = alarms().partition { id == null || it.id == id }
        store(kept)
        val manager = context.getSystemService(AlarmManager::class.java)
        gone.forEach {
            manager.cancel(intent(it.id))
            manager.cancel(intent(SNOOZE_ID + it.id))
        }
        if (id == null || ringing?.id == id) main.post { stopRinging() }
        changed()
        return gone.size
    }

    private fun store(alarms: List<Alarm>) {
        val list = JSONArray(
            alarms.map {
                JSONObject().put("id", it.id).put("hour", it.hour).put("minute", it.minute)
                    .put("days", JSONArray(it.days.map(DayOfWeek::getValue))).putOpt("label", it.label)
                    .putOpt("station", it.station)
            },
        )
        prefs.edit().putString("list", list.toString()).apply()
    }

    private fun intent(requestCode: Int, alarm: Alarm? = null): PendingIntent {
        val target = Intent(context, AlarmReceiver::class.java).putExtra(EXTRA_ID, requestCode)
        // A snooze carries what it needs: a one-time alarm is already gone from the list.
        alarm?.let { target.putExtra("hour", it.hour).putExtra("minute", it.minute).putExtra("label", it.label).putExtra("station", it.station) }
        return PendingIntent.getBroadcast(context, requestCode, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun schedule(alarm: Alarm) {
        at(alarm.next(ZonedDateTime.now()).toInstant().toEpochMilli(), intent(alarm.id))
    }

    private fun at(millis: Long, action: PendingIntent) {
        val show = PendingIntent.getActivity(context, 0, Intent(context, KioskActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(millis, show), action)
    }

    /** Called by Android at the time of an alarm. */
    fun fired(received: Intent) {
        val code = received.getIntExtra(EXTRA_ID, -1)
        val alarm = if (code >= SNOOZE_ID) {
            Alarm(
                code - SNOOZE_ID, received.getIntExtra("hour", 0), received.getIntExtra("minute", 0), emptySet(),
                received.getStringExtra("label"), received.getStringExtra("station"),
            )
        } else {
            synchronized(this) {
                alarms().firstOrNull { it.id == code }?.also {
                    if (it.days.isEmpty()) store(alarms().filterNot { other -> other.id == code }) else schedule(it)
                }
            }
        } ?: return
        Log.i(TAG, "alarm ${alarm.id} ringing")
        main.post { ring(alarm) }
    }

    private val limit = Runnable { stopRinging() }

    private val beep = object : Runnable {
        override fun run() {
            val generator = tone ?: try {
                ToneGenerator(AudioManager.STREAM_ALARM, 100).also { tone = it }
            } catch (e: RuntimeException) {
                return
            }
            generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900)
            main.postDelayed(this, BEEP_EVERY_MS)
        }
    }

    private val radioDeadline = Runnable { if (ringing != null && !radio.playing) beepInstead() }

    private fun ring(alarm: Alarm) {
        quiet()
        ringing = alarm
        RadioCenter.hold(true)
        val station = Stations.byId(alarm.station)
        if (station == null) {
            beep.run()
        } else {
            radio.start(station) { if (ringing != null) beepInstead() }
            main.postDelayed(radioDeadline, RADIO_DEADLINE_MS)
        }
        main.postDelayed(limit, RING_LIMIT_MS)
        KioskReturn.show(context)
        changed()
    }

    /** The radio did not start: an alarm must be heard anyway. */
    private fun beepInstead() {
        radio.stop()
        main.removeCallbacks(beep)
        beep.run()
    }

    fun stopRinging(): Boolean {
        val was = ringing != null
        quiet()
        ringing = null
        if (was) RadioCenter.hold(false)
        changed()
        return was
    }

    fun snooze() {
        val alarm = ringing ?: return
        at(System.currentTimeMillis() + SNOOZE_MS, intent(SNOOZE_ID + alarm.id, alarm))
        stopRinging()
    }

    private fun quiet() {
        main.removeCallbacks(limit)
        main.removeCallbacks(beep)
        main.removeCallbacks(radioDeadline)
        radio.stop()
        tone?.release()
        tone = null
    }

    private fun changed() {
        main.post { onChange?.invoke() }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AlarmCenter.fired(intent)
}
