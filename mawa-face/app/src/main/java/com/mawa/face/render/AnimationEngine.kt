package com.mawa.face.render

import android.os.SystemClock
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Mood presets style HOW the eyes render; they are orthogonal to app state.
 * In Phase 1 moods are set locally (startle) or via the debug overlay;
 * in Phase 2+ the brain server drives them over WebSocket.
 */
enum class Mood(
    val opennessBase: Float,
    val lidAngle: Float,
    val squash: Float,
    val pupilScale: Float,
    val blinkRateMul: Float,
) {
    NEUTRAL(1.00f, 0f, 1.00f, 1.00f, 1.0f),
    HAPPY(1.00f, 0f, 0.80f, 1.05f, 1.0f),
    GRUMPY(0.85f, -10f, 1.00f, 0.95f, 0.6f),
    SLEEPY(0.55f, -4f, 1.00f, 0.90f, 0.5f),
    SUSPICIOUS(0.70f, 6f, 1.00f, 0.85f, 1.2f),
    EXCITED(1.00f, 2f, 0.95f, 1.30f, 1.4f),
}

/** One-shot scripted animations layered on top of the current state. */
enum class Gesture { LOCK_ON }

/** Per-eye animatable parameters. Everything is eased — nothing ever snaps. */
class EyeParams {
    var openness = 1f      // 0 closed .. 1 open
    var pupilX = 0f        // -1 .. 1 within the eye
    var pupilY = 0f
    var pupilScale = 1f    // dilation
    var lidAngle = 0f      // degrees; upper-lid tilt
    var squash = 1f        // vertical squash (happy crescent)
}

/**
 * Owns all eye motion: gaze smoothing, blinks, micro-saccades, idle wander,
 * sleep, startle, mood styling, and the slow burn-in drift.
 * Call [update] once per rendered frame; read [left]/[right]/[driftX]/[driftY].
 */
class AnimationEngine {

    val left = EyeParams()
    val right = EyeParams()

    var mood: Mood = Mood.NEUTRAL

    // --- gaze -------------------------------------------------------------
    private var gazeX = 0f
    private var gazeY = 0f
    private var targetX = 0f
    private var targetY = 0f
    var hasFace = false
        private set
    private var faceLastSeenAt = SystemClock.elapsedRealtime()
    private var lastProx = 0f
    private var startleUntil = 0L

    // --- blink ------------------------------------------------------------
    private var clock = 0.0                 // seconds since engine start
    private var nextBlinkAt = 1.5
    private var blinkStart = -1.0
    private val blinkDur = 0.14

    // --- saccades & wander -------------------------------------------------
    private var nextSaccadeAt = 0.0
    private var jitterX = 0f
    private var jitterY = 0f
    private var nextWanderAt = 0.0

    // --- sleep -------------------------------------------------------------
    var sleeping = false
        private set

    // --- gestures ----------------------------------------------------------
    private var gesture: Gesture? = null
    private var gestureStart = 0.0

    // --- burn-in drift -----------------------------------------------------
    var driftX = 0f
        private set
    var driftY = 0f
        private set

    /** Play a one-shot scripted animation on top of the current state. */
    fun play(g: Gesture) {
        gesture = g
        gestureStart = clock
    }

    /** Feed a mapped gaze target (-1..1) plus proximity (bbox area fraction). */
    fun onFace(gx: Float, gy: Float, prox: Float) {
        val now = SystemClock.elapsedRealtime()
        // Someone approached fast -> brief startled/suspicious reaction
        if (prox - lastProx > 0.08f && lastProx > 0f) {
            startleUntil = now + 1500
        }
        lastProx = prox
        targetX = gx
        targetY = gy
        hasFace = true
        faceLastSeenAt = now
        if (sleeping) sleeping = false   // wakes up when you come home
    }

    fun onFaceLost() {
        hasFace = false
        lastProx = 0f
    }

    fun update(dtSec: Float) {
        clock += dtSec
        val now = SystemClock.elapsedRealtime()

        // Sleep after 10 min without a face; onFace() wakes us.
        sleeping = now - faceLastSeenAt > SLEEP_AFTER_MS

        // Idle wander when nobody is around (and awake)
        if (!hasFace && !sleeping && clock >= nextWanderAt) {
            targetX = Random.nextFloat() * 1.6f - 0.8f
            targetY = Random.nextFloat() * 1.0f - 0.5f
            nextWanderAt = clock + 1.0 + Random.nextDouble() * 3.0
        }

        // Micro-saccades: tiny pupil jitter. Frozen pupils read as dead.
        if (clock >= nextSaccadeAt) {
            jitterX = (Random.nextFloat() - 0.5f) * 0.04f
            jitterY = (Random.nextFloat() - 0.5f) * 0.04f
            nextSaccadeAt = clock + 0.4 + Random.nextDouble() * 0.8
        }

        // Smooth gaze toward target (~120 ms lag: glides, never jitters)
        val k = min(1f, dtSec * 8f)
        gazeX += (targetX + jitterX - gazeX) * k
        gazeY += (targetY + jitterY - gazeY) * k

        // Blink scheduling
        if (blinkStart < 0 && clock >= nextBlinkAt && !sleeping) {
            blinkStart = clock
            val doubleBlink = Random.nextFloat() < 0.1f
            nextBlinkAt = if (doubleBlink) clock + blinkDur + 0.18
            else clock + (2.2 + Random.nextDouble() * 3.3) / mood.blinkRateMul
        }
        var blinkFactor = 1f
        if (blinkStart >= 0) {
            val p = ((clock - blinkStart) / blinkDur).toFloat()
            if (p >= 1f) blinkStart = -1.0
            // triangle: fully closed at the midpoint of the blink
            else blinkFactor = if (p < 0.5f) 1f - p * 2f else (p - 0.5f) * 2f
        }

        // Openness target: mood base x blink, or near-closed breathing in sleep
        var opennessTarget = if (sleeping) {
            0.06f + 0.03f * sin(clock * 2.0 * Math.PI / 4.5).toFloat()
        } else {
            mood.opennessBase * blinkFactor
        }

        // Startle overrides pupil size briefly
        val startled = now < startleUntil
        var pupilTarget = if (startled) 0.7f else mood.pupilScale * (1f + 0.25f * lastProx.coerceAtMost(0.4f))
        val lidTarget = if (startled) 8f else mood.lidAngle

        // One-shot gestures override the targets while they play
        if (gesture == Gesture.LOCK_ON) {
            val p = (clock - gestureStart).toFloat()
            when {
                p < 0.30f -> pupilTarget = 0.55f                       // narrow: focusing...
                p < 0.44f -> { opennessTarget = 0f }                   // snap blink
                p < 0.58f -> { opennessTarget = 1f; pupilTarget = 0.55f }
                p < 0.72f -> { opennessTarget = 0f }                   // second blink
                p < 1.80f -> pupilTarget = 1.45f                       // wide dilate: found you
                else -> gesture = null
            }
        }

        applyTo(left, opennessTarget, pupilTarget, lidTarget, dtSec)
        applyTo(right, opennessTarget, pupilTarget, -lidTarget, dtSec)  // mirrored tilt

        // Burn-in drift: whole face wanders +-12 px over a ~10 min cycle
        driftX = (12.0 * sin(clock * 2.0 * Math.PI / 600.0)).toFloat()
        driftY = (12.0 * sin(clock * 2.0 * Math.PI / 470.0 + 1.3)).toFloat()
    }

    private fun applyTo(eye: EyeParams, openness: Float, pupilScale: Float, lidAngle: Float, dt: Float) {
        val fast = min(1f, dt * 14f)   // blinks need to be quick
        val slow = min(1f, dt * 5f)    // styling changes ease in gently
        eye.openness += (openness - eye.openness) * fast
        eye.pupilX += (gazeX - eye.pupilX) * fast
        eye.pupilY += (gazeY - eye.pupilY) * fast
        eye.pupilScale += (pupilScale - eye.pupilScale) * slow
        eye.lidAngle += (lidAngle - eye.lidAngle) * slow
        eye.squash += (mood.squash - eye.squash) * slow
    }

    companion object {
        const val SLEEP_AFTER_MS = 10 * 60 * 1000L
    }
}
