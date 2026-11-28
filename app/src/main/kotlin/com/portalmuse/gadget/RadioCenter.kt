package com.portalmuse.gadget

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.portalmuse.core.Station
import com.portalmuse.core.Stations
import java.io.IOException

private const val TAG = "PortalMuse"

/** One public stream being played. All calls on the main thread. */
class StreamPlayer(private val usage: Int) {
    private var player: MediaPlayer? = null

    /** True once sound is coming out. */
    var playing = false
        private set

    fun start(station: Station, onFailed: () -> Unit) {
        stop()
        val created = MediaPlayer()
        player = created
        try {
            created.setAudioAttributes(AudioAttributes.Builder().setUsage(usage).build())
            created.setDataSource(station.url)
            created.setOnPreparedListener {
                it.start()
                playing = true
            }
            created.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "radio: playback failed ($what, $extra)")
                stop()
                onFailed()
                true
            }
            created.setOnCompletionListener {
                stop()
                onFailed()
            }
            created.prepareAsync()
        } catch (e: IOException) {
            Log.w(TAG, "radio: cannot open (${e.javaClass.simpleName})")
            stop()
            onFailed()
        }
    }

    fun stop() {
        playing = false
        player?.release()
        player = null
    }
}

/**
 * The radio. It keeps its station while held for a call or a voice note, and comes back afterwards. A lost
 * stream is tried again a few times.
 */
object RadioCenter {
    private const val RETRIES = 3
    private val main = Handler(Looper.getMainLooper())
    private val player = StreamPlayer(AudioAttributes.USAGE_MEDIA)
    private var held = false
    private var failures = 0

    /** The station that is on, if any. */
    @Volatile var station: Station? = null
        private set

    /** Called on the main thread at every change. */
    @Volatile var onChange: (() -> Unit)? = null

    fun play(context: Context, wanted: Station) {
        context.getSharedPreferences("radio", Context.MODE_PRIVATE).edit().putString("last", wanted.id).apply()
        station = wanted
        main.post {
            failures = 0
            start()
            onChange?.invoke()
        }
    }

    fun last(context: Context): Station =
        Stations.byId(context.getSharedPreferences("radio", Context.MODE_PRIVATE).getString("last", null))
            ?: Stations.ALL.first()

    /** False if the radio was off. */
    fun stop(): Boolean {
        val was = station != null
        station = null
        main.post {
            player.stop()
            onChange?.invoke()
        }
        return was
    }

    /** Silent while something else needs the speaker or the microphone. Main thread. */
    fun hold(wanted: Boolean) {
        if (wanted == held) return
        held = wanted
        if (wanted) player.stop() else start()
    }

    private fun start() {
        val wanted = station ?: return player.stop()
        if (held) return
        player.start(wanted) {
            if (++failures <= RETRIES) main.postDelayed({ if (station == wanted && !player.playing) start() }, 5_000)
            else {
                station = null
                onChange?.invoke()
            }
        }
    }
}
