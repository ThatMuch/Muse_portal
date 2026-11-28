package com.portalmuse.gadget

import java.util.Calendar

/** The Portal's local time. */
object Clock {
    fun minuteOfDay(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
}
