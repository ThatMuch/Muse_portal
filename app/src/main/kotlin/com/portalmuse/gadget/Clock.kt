package com.portalmuse.gadget

import java.util.Calendar

/** L'heure locale du Portal. */
object Clock {
    fun minuteOfDay(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
}
