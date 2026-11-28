package com.portalmuse.gadget

import android.content.Context
import android.media.AudioManager
import com.portalmuse.core.VolumeRequest
import kotlin.math.roundToInt

/** The media volume, in percent: Muse's voice during calls and everything the app plays. */
object Loudness {
    private fun manager(context: Context) = context.getSystemService(AudioManager::class.java)

    fun percent(context: Context): Int {
        val audio = manager(context)
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / maximum).roundToInt()
    }

    /** [show]: with Android's volume slider, as feedback. Returns the level reached. */
    fun set(context: Context, percent: Int, show: Boolean = false): Int {
        val audio = manager(context)
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val flags = if (show) AudioManager.FLAG_SHOW_UI else 0
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent * maximum / 100f).roundToInt(), flags)
        return percent(context)
    }

    fun apply(context: Context, request: VolumeRequest): Int {
        val target = when (request) {
            is VolumeRequest.Level -> request.percent
            is VolumeRequest.Change -> (percent(context) + request.points).coerceIn(0, 100)
        }
        return set(context, target, show = true)
    }
}
