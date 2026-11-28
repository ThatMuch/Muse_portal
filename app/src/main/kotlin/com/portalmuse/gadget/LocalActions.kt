package com.portalmuse.gadget

import android.content.Context
import com.portalmuse.core.LocalRequest

/** Carries out what was asked aloud during a call; a beep says it is done. */
object LocalActions {
    /** [onScreen]: the home screen must be looked at again. */
    fun apply(context: Context, request: LocalRequest, onScreen: (dimNow: Boolean) -> Unit) {
        val settings = Settings(context)
        when (request) {
            is LocalRequest.Volume -> Loudness.apply(context, request.request)
            is LocalRequest.Night -> {
                settings.changeNight { it.force(request.on, Clock.minuteOfDay(), System.currentTimeMillis()) }
                onScreen(request.on)
            }
            is LocalRequest.PlayRadio -> RadioCenter.play(context, request.station ?: RadioCenter.last(context))
            LocalRequest.StopRadio -> RadioCenter.stop()
            LocalRequest.StopTimer -> {
                // The ringing ones if any; otherwise all those running.
                if (TimerCenter.ringing.isNotEmpty()) TimerCenter.stopRinging() else TimerCenter.cancel(null)
            }
            LocalRequest.StopAlarm -> AlarmCenter.stopRinging()
        }
        Chime.heard()
    }
}
