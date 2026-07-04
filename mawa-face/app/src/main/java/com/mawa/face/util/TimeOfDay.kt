package com.mawa.face.util

import java.util.Calendar

/** Clock-derived greetings, night detection, and golden-hour warmth. */
object TimeOfDay {

    fun hour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    fun greeting(h: Int = hour()): String = when (h) {
        in 5..11 -> "Good morning, Pranav."
        in 12..16 -> "Good afternoon, Pranav."
        in 17..21 -> "Good evening, Pranav."
        else -> "You're up late, Pranav."
    }

    fun isNight(h: Int = hour()): Boolean = h >= 22 || h < 6

    /** 0 = neutral black, 1 = full golden-hour warmth (dawn/dusk). */
    fun warmth(h: Int = hour()): Float = when (h) {
        6, 7, 19 -> 0.6f
        8, 17, 18 -> 1f
        20 -> 0.5f
        else -> 0f
    }
}
