package com.mawa.face.vision

/**
 * Maps a normalized face center (0..1 in the rotated camera frame) to a gaze
 * target (-1..1) for the pupils.
 *
 * Landscape mounting means two corrections beyond the raw coordinates:
 *  - mirrorX: the front camera is mirrored relative to the world; without
 *    this the eyes look away from you instead of at you.
 *  - offsetX/offsetY: in landscape the camera sits at one short edge of the
 *    phone, not between the eyes, so the raw center is skewed. Tune these
 *    once the phone is on the wall by standing dead-center and adjusting
 *    until the eyes look straight at you (debug overlay: 5-tap the screen).
 */
object GazeMapper {
    var mirrorX = true
    var gainX = 2.2f
    var gainY = 2.0f
    var offsetX = 0f
    var offsetY = 0f

    fun map(cx: Float, cy: Float): Pair<Float, Float> {
        var x = (cx - 0.5f) * 2f
        if (mirrorX) x = -x
        val y = (cy - 0.5f) * 2f
        return Pair(
            (x * gainX + offsetX).coerceIn(-1f, 1f),
            (y * gainY + offsetY).coerceIn(-1f, 1f),
        )
    }
}
