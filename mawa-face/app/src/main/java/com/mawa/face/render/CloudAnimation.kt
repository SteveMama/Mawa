package com.mawa.face.render

enum class CloudPalette { COOL, WARM, VIOLET, TEAL, DUSK }

enum class CloudGazeMode { STEADY, CURIOUS, DART, LOCKED, DREAMY }

/**
 * Stable remote direction pack from the cloud brain. New moods can be composed
 * by changing these normalized primitives without shipping a new APK, as long
 * as the requested behavior fits inside the renderer's existing capability set.
 */
data class CloudAnimation(
    val palette: CloudPalette = CloudPalette.COOL,
    val gazeMode: CloudGazeMode = CloudGazeMode.CURIOUS,
    val energy: Float = 0f,
    val expressiveness: Float = 0f,
    val aura: Float = 0f,
    val bars: Float = 0f,
    val glyphs: Float = 0f,
    val sway: Float = 0f,
    val bounce: Float = 0f,
    val blinkRate: Float = 1f,
    val openness: Float = 1f,
    val pupilScale: Float = 1f,
    val squint: Float = 0f,
) {
    fun isActive(): Boolean =
        energy > 0.02f || aura > 0.02f || bars > 0.02f || glyphs > 0.02f || expressiveness > 0.02f
}
