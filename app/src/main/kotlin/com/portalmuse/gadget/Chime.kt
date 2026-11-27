package com.portalmuse.gadget

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/** Des bips courts : « j'ai entendu » et « regardez l'écran ». */
object Chime {
    /** Bref : on peut déjà parler pendant qu'il retentit. */
    fun heard() = play(ToneGenerator.TONE_PROP_BEEP, 100)

    fun alert() = play(ToneGenerator.TONE_PROP_BEEP2, 400)

    private fun play(tone: Int, millis: Int) {
        val generator = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 70)
        } catch (e: RuntimeException) {
            return
        }
        generator.startTone(tone, millis)
        Handler(Looper.getMainLooper()).postDelayed(generator::release, millis + 200L)
    }
}
