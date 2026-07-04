package com.mawa.face.vision

import android.content.SharedPreferences

/**
 * Maps a normalized face center (0..1 in the rotated camera frame) to a gaze
 * target (-1..1) for the pupils.
 *
 * Landscape mounting means two corrections beyond the raw coordinates:
 *  - mirrorX: the front camera is mirrored relative to the world; without
 *    this the eyes look away from you instead of at you.
 *  - offsetX/offsetY: in landscape the camera sits at one short edge of the
 *    phone, not between the eyes, so the raw center is skewed.
 *
 * Calibration is one-touch and on-device: stand where you normally are and
 * long-press the screen — [calibrateTo] solves the offsets so the current
 * face position maps to dead-center gaze, and persists them.
 */
object GazeMapper {
    var mirrorX = true
    var gainX = 2.2f
    var gainY = 2.0f
    var offsetX = 0f
    var offsetY = 0f

    fun map(cx: Float, cy: Float): Pair<Float, Float> {
        return Pair(
            (norm(cx, mirrorX) * gainX + offsetX).coerceIn(-1f, 1f),
            (norm(cy, false) * gainY + offsetY).coerceIn(-1f, 1f),
        )
    }

    /** Make the current raw face position map to gaze (0, 0), and persist. */
    fun calibrateTo(rawX: Float, rawY: Float, prefs: SharedPreferences) {
        offsetX = -norm(rawX, mirrorX) * gainX
        offsetY = -norm(rawY, false) * gainY
        prefs.edit()
            .putFloat(KEY_OFFSET_X, offsetX)
            .putFloat(KEY_OFFSET_Y, offsetY)
            .apply()
    }

    fun load(prefs: SharedPreferences) {
        offsetX = prefs.getFloat(KEY_OFFSET_X, 0f)
        offsetY = prefs.getFloat(KEY_OFFSET_Y, 0f)
    }

    private fun norm(v: Float, mirror: Boolean): Float {
        val n = (v - 0.5f) * 2f
        return if (mirror) -n else n
    }

    private const val KEY_OFFSET_X = "gaze_offset_x"
    private const val KEY_OFFSET_Y = "gaze_offset_y"
}
