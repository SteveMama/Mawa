package com.mawa.face.render

import android.os.SystemClock
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Mood presets style HOW the eyes render; they are orthogonal to app state.
 * In Phase 1 moods are set locally (startle) or via the debug overlay;
 * in Phase 2+ the cloud brain can drive them through scene manifests.
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
enum class Gesture { LOCK_ON, BLINK }

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
 * Owns all eye motion. The gaze core models a real eye: it FIXATES (holds
 * dead still) and then SACCADES (a fast ballistic jump with a tiny overshoot),
 * rather than continuously servoing toward a target — continuous gliding is
 * what makes fake eyes read as a tracking camera. Layered on top: vergence
 * (pupils converge on near objects), asymmetric blinks (fast close, slow
 * reopen, eyes never perfectly in unison), independent per-eye tremor, a slow
 * breathing oscillation, and gaze aversion (it can't hold eye contact forever).
 *
 * Call [update] once per rendered frame; read [left]/[right]/[driftX]/[driftY].
 */
class AnimationEngine {

    val left = EyeParams()
    val right = EyeParams()

    var mood: Mood = Mood.NEUTRAL
    var cloudMood: Mood? = null
    var cloudAnimation: CloudAnimation? = null
    var cloudStance: CloudCompanionStance = CloudCompanionStance.WATCHFUL
    var cloudIntent: CloudCompanionIntent = CloudCompanionIntent.OBSERVE
    var cloudAttention: String = "wandering"
    var identityLockEnabled = false

    // --- gaze: spring-driven position + discrete saccade target -----------
    private var gazeX = 0f          // rendered gaze (spring position)
    private var gazeY = 0f
    private var gazeVX = 0f         // spring velocity
    private var gazeVY = 0f
    private var aimX = 0f           // current saccade target (held between shifts)
    private var aimY = 0f
    private var faceX = 0f          // last mapped face position (raw target source)
    private var faceY = 0f
    var hasFace = false
        private set
    private var faceLastSeenAt = SystemClock.elapsedRealtime()
    private var lastProx = 0f
    private var startleUntil = 0L
    private var vergence = 0f       // eased inward convergence for near faces

    // --- fixation / aversion scheduling -----------------------------------
    private var nextShiftAt = 0.0   // next idle fixation change (clock secs)
    private var avertUntil = 0.0    // gaze-aversion is active until this clock
    private var nextAvertAt = 4.0   // when to next break eye contact

    // --- room audio -------------------------------------------------------
    @Volatile private var pendingBeat = 0f
    private var beatPulse = 0f
    private var grooveLevel = 0f
    private var intruderUntil = 0L
    private var guardUntil = 0L

    // --- blink ------------------------------------------------------------
    private var clock = 0.0                 // seconds since engine start
    private var nextBlinkAt = 1.5
    private var blinkStart = -1.0
    private var blinkFloor = 0f             // 0 = full close; >0 = half blink
    private var blinkRightDelay = 0.014     // right lid trails the left slightly
    private var blinkRightSkip = false      // occasional single-eye blink
    private var openBase = 1f               // pre-blink openness, eased for mood

    // --- tremor -----------------------------------------------------------
    private var tremorClock = 0f

    // --- sleep -------------------------------------------------------------
    var sleeping = false
        private set

    // --- gestures ----------------------------------------------------------
    private var gesture: Gesture? = null
    private var gestureStart = 0.0

    // --- ambient inputs (driven from MainActivity) -------------------------
    var ambientDark = false   // light sensor says the lights are off -> sleep
    var covered = false       // camera lens covered -> polite eyes-closed (no ZZZ)

    // --- burn-in drift -----------------------------------------------------
    var driftX = 0f
        private set
    var driftY = 0f
        private set
    private var focusFrameUntil = 0L

    /** Play a one-shot scripted animation on top of the current state. */
    fun play(g: Gesture) {
        when (g) {
            Gesture.BLINK -> triggerBlink()
            Gesture.LOCK_ON -> {
                gesture = g
                gestureStart = clock
                focusFrameUntil = SystemClock.elapsedRealtime() + FOCUS_FRAME_MS
            }
        }
    }

    private fun triggerBlink(floor: Float = 0f) {
        if (blinkStart >= 0) return
        blinkStart = clock
        blinkFloor = floor
        blinkRightDelay = 0.008 + Random.nextDouble() * 0.020
        blinkRightSkip = Random.nextFloat() < 0.06f   // rare single-eye flick
    }

    /** Feed a mapped gaze target (-1..1) plus proximity (bbox area fraction). */
    fun onFace(gx: Float, gy: Float, prox: Float) {
        val now = SystemClock.elapsedRealtime()
        // Fresh acquisition: hold eye contact for a beat before ever looking away.
        if (!hasFace) {
            aimX = gx
            aimY = gy
            nextAvertAt = clock + 2.5 + Random.nextDouble() * 2.5
            avertUntil = 0.0
            if (identityLockEnabled) {
                focusFrameUntil = now + FOCUS_FRAME_MS
            }
        }
        // Someone approached fast -> brief startled/suspicious reaction
        if (prox - lastProx > 0.08f && lastProx > 0f) {
            startleUntil = now + 1500
            triggerBlink()
        }
        lastProx = prox
        faceX = gx
        faceY = gy
        hasFace = true
        faceLastSeenAt = now
        if (sleeping) sleeping = false   // wakes up when you come home
    }

    fun onFaceLost() {
        hasFace = false
        lastProx = 0f
    }

    fun onIgnoredFace(prox: Float) {
        hasFace = false
        lastProx = prox
        val now = SystemClock.elapsedRealtime()
        if (prox >= 0.16f) {
            intruderUntil = now + 900L
        } else {
            guardUntil = now + 1_100L
        }
    }

    /** Feed a normalized transient from the on-device beat detector. */
    fun onBeat(strength: Float) {
        pendingBeat = maxOf(pendingBeat, strength.coerceIn(0f, 1f))
    }

    fun musicLevel(): Float = grooveLevel

    fun beatLevel(): Float = beatPulse

    fun currentMood(): Mood = activeMood(SystemClock.elapsedRealtime())

    fun isSleeping(): Boolean = sleeping

    fun visualEnergy(): Float = maxOf(grooveLevel, activeCloudAnimation()?.energy ?: 0f)

    fun expressivenessLevel(): Float =
        maxOf(grooveLevel * 0.72f, activeCloudAnimation()?.expressiveness ?: 0f)

    fun auraLevel(): Float = maxOf(grooveLevel * 0.85f, activeCloudAnimation()?.aura ?: 0f)

    fun barLevel(): Float = maxOf(grooveLevel, activeCloudAnimation()?.bars ?: 0f)

    fun glyphLevel(): Float = maxOf(grooveLevel, activeCloudAnimation()?.glyphs ?: 0f)

    fun palette(): CloudPalette = activeCloudAnimation()?.palette ?: CloudPalette.COOL

    fun stance(): CloudCompanionStance = cloudStance

    fun intent(): CloudCompanionIntent = cloudIntent

    fun attention(): String = cloudAttention

    fun protectiveLevel(): Float = when (cloudStance) {
        CloudCompanionStance.PROTECTIVE, CloudCompanionStance.BRACED -> 1f
        CloudCompanionStance.WATCHFUL -> 0.45f
        else -> 0f
    }

    fun warmthLevel(): Float = when (cloudStance) {
        CloudCompanionStance.WARM, CloudCompanionStance.TENDER -> 1f
        else -> 0f
    }

    fun playfulLevel(): Float = when (cloudStance) {
        CloudCompanionStance.PLAYFUL, CloudCompanionStance.AMUSED -> 1f
        else -> 0f
    }

    fun studyLevel(): Float = when (cloudIntent) {
        CloudCompanionIntent.STUDY, CloudCompanionIntent.GUARD -> 1f
        else -> 0f
    }

    fun shouldShowFocusFrame(): Boolean =
        (
            identityLockEnabled ||
                cloudStance == CloudCompanionStance.PROTECTIVE ||
                cloudIntent == CloudCompanionIntent.STUDY ||
                cloudIntent == CloudCompanionIntent.GUARD
            ) && !sleeping && SystemClock.elapsedRealtime() < focusFrameUntil

    fun guardedRecently(): Boolean = SystemClock.elapsedRealtime() < guardUntil

    fun update(dtSec: Float) {
        clock += dtSec
        val now = SystemClock.elapsedRealtime()
        val cloud = activeCloudAnimation()
        beatPulse = maxOf(beatPulse, pendingBeat)
        pendingBeat = 0f
        beatPulse = (beatPulse - dtSec * 4.6f).coerceAtLeast(0f)
        grooveLevel = maxOf(grooveLevel, beatPulse * 0.92f)
        grooveLevel = (grooveLevel - dtSec * 0.68f).coerceAtLeast(0f)
        val visualEnergy = maxOf(grooveLevel, cloud?.energy ?: 0f)
        val expressiveness = expressivenessLevel()
        val protective = protectiveLevel()
        val playful = playfulLevel()
        val warmth = warmthLevel()
        val study = studyLevel()

        // Sleep when the room goes dark, or after 10 min without a face.
        sleeping = ambientDark || (now - faceLastSeenAt > SLEEP_AFTER_MS)
        val styleMood = activeMood(now)

        chooseAim(cloud)
        integrateGaze(dtSec)

        // Vergence: pupils rotate inward when something is close.
        val vergeTarget = (lastProx.coerceIn(0f, 0.35f) / 0.35f) * MAX_VERGENCE
        vergence += (vergeTarget - vergence) * min(1f, dtSec * 4f)

        scheduleBlink(styleMood, cloud, visualEnergy)

        // Openness (pre-blink): mood base, cloud shaping, energy shimmer,
        // sleep breathing, plus a constant slow breath so idle never reads dead.
        var opennessTarget = if (sleeping) {
            0.06f + 0.03f * sin(clock * 2.0 * Math.PI / 4.5).toFloat()
        } else {
            styleMood.opennessBase * blinkStyleScale(styleMood)
        }
        opennessTarget += warmth * 0.08f + playful * 0.06f - protective * 0.08f - study * 0.05f
        opennessTarget = (opennessTarget * (cloud?.openness ?: 1f) - (cloud?.squint ?: 0f) * 0.22f)
            .coerceIn(0f, 1.1f)
        opennessTarget = (opennessTarget + visualEnergy * 0.09f * (0.5f + 0.5f *
            sin(clock * 2.0 * Math.PI * 2.6).toFloat())).coerceIn(0f, 1.1f)
        opennessTarget = (opennessTarget + expressiveness * 0.05f * sin(clock * 2.0 * Math.PI * 0.55).toFloat())
            .coerceIn(0f, 1.1f)
        if (!sleeping) {
            val breath = sin(clock * 2.0 * Math.PI * BREATH_HZ).toFloat()
            opennessTarget = (opennessTarget * (1f + 0.028f * breath)).coerceIn(0f, 1.1f)
        }
        // Ease the pre-blink base so mood changes glide; the blink itself is crisp.
        openBase += (opennessTarget - openBase) * min(1f, dtSec * 6f)

        // Pupil size: startle overrides; otherwise mood x proximity/beat/energy.
        val startled = now < startleUntil
        var pupilTarget = if (startled) 0.7f else styleMood.pupilScale *
            (1f + 0.25f * lastProx.coerceAtMost(0.4f) + 0.42f * beatPulse + 0.18f * visualEnergy) *
            (cloud?.pupilScale ?: 1f)
        pupilTarget = (pupilTarget + playful * 0.10f - warmth * 0.04f - study * 0.07f).coerceIn(0.72f, 1.62f)
        var lidTarget = if (startled) 8f else styleMood.lidAngle + (cloud?.squint ?: 0f) * 8f
        lidTarget += protective * 5.0f + study * 3.3f - warmth * 2.4f - playful * 1.8f

        // Blink shape (per eye) and one-shot LOCK_ON scripting.
        var leftOpen = openBase * blinkFactor(clock, blinkFloor)
        var rightOpen = openBase *
            if (blinkRightSkip) 1f else blinkFactor(clock - blinkRightDelay, blinkFloor)

        if (gesture == Gesture.LOCK_ON) {
            val p = (clock - gestureStart).toFloat()
            when {
                p < 0.30f -> pupilTarget = 0.55f                    // narrow: focusing...
                p < 0.44f -> { leftOpen = 0f; rightOpen = 0f }      // snap blink
                p < 0.58f -> { leftOpen = openBase; rightOpen = openBase; pupilTarget = 0.55f }
                p < 0.72f -> { leftOpen = 0f; rightOpen = 0f }      // second blink
                p < 1.80f -> pupilTarget = 1.45f                    // wide dilate: found you
                else -> gesture = null
            }
        }

        // Hand over the lens -> close politely. Distinct from sleep: no ZZZ.
        if (covered && !sleeping) { leftOpen = 0f; rightOpen = 0f }

        val expressionWave = sin(clock * 2.0 * Math.PI * 0.37).toFloat() * expressiveness
        val expressionLift = sin(clock * 2.0 * Math.PI * 0.71 + 1.1).toFloat() * expressiveness
        leftOpen = (leftOpen + expressionWave * (0.065f + playful * 0.030f) + beatPulse * 0.026f + warmth * 0.035f)
            .coerceIn(0f, 1.12f)
        rightOpen = (rightOpen - expressionWave * (0.056f + playful * 0.022f) + beatPulse * 0.020f + warmth * 0.028f)
            .coerceIn(0f, 1.12f)
        val leftLidTarget = lidTarget + expressionWave * (7.2f + playful * 2.8f) + expressionLift * 3.4f
        val rightLidTarget = -lidTarget + expressionWave * (5.2f + playful * 2.1f) - expressionLift * 3.0f

        // Independent ocular tremor keeps fixations alive without reading as jitter.
        tremorClock += dtSec
        val tremorAmp = if (sleeping) 0f else TREMOR_AMP *
            (0.55f + 0.35f * visualEnergy + 0.45f * expressiveness + playful * 0.25f - study * 0.18f)
        val tLX = sin(tremorClock * 41f).toFloat() * tremorAmp + (Random.nextFloat() - 0.5f) * tremorAmp * 0.5f
        val tRX = sin(tremorClock * 37f + 1.7f).toFloat() * tremorAmp + (Random.nextFloat() - 0.5f) * tremorAmp * 0.5f
        val tLY = sin(tremorClock * 29f + 0.6f).toFloat() * tremorAmp
        val tRY = sin(tremorClock * 33f + 2.3f).toFloat() * tremorAmp

        applyTo(left, leftOpen, gazeX + vergence + tLX, gazeY + tLY, pupilTarget, leftLidTarget, dtSec, styleMood, expressiveness, -1f)
        applyTo(right, rightOpen, gazeX - vergence + tRX, gazeY + tRY, pupilTarget, rightLidTarget, dtSec, styleMood, expressiveness, 1f)

        // Burn-in drift: whole face wanders +-12 px over a ~10 min cycle
        val slowDriftX = (12.0 * sin(clock * 2.0 * Math.PI / 600.0)).toFloat()
        val slowDriftY = (12.0 * sin(clock * 2.0 * Math.PI / 470.0 + 1.3)).toFloat()
        val swayLevel = maxOf(grooveLevel, cloud?.sway ?: 0f) + warmth * 0.08f + playful * 0.12f
        val bounceLevel = maxOf(grooveLevel, cloud?.bounce ?: 0f) + playful * 0.10f
        val grooveSway = sin(clock * 2.0 * Math.PI * (1.2 + expressiveness * 1.4)).toFloat() * 12f * swayLevel
        val grooveBounce = (0.4f + 0.6f * sin(clock * 2.0 * Math.PI * 3.2).toFloat()) *
            22f * bounceLevel
        val theatricalLean = sin(clock * 2.0 * Math.PI * 0.23 + 0.8).toFloat() * 9f * (expressiveness + warmth * 0.22f)
        // A gentle breathing bob so the whole face rises and falls when at rest.
        val breathBob = if (sleeping) 0f else sin(clock * 2.0 * Math.PI * BREATH_HZ).toFloat() * 3.5f
        driftX = slowDriftX + grooveSway + theatricalLean
        driftY = slowDriftY - 12f * beatPulse - grooveBounce + breathBob
    }

    /**
     * Decide where the eyes are aimed. On a face: hold contact, but break away
     * periodically (aversion) and only re-fixate with a corrective saccade once
     * the face has drifted past a deadband. Idle: wander per the cloud gaze mode.
     */
    private fun chooseAim(cloud: CloudAnimation?) {
        if (sleeping) return
        if (hasFace) {
            when {
                clock < avertUntil -> Unit  // currently looking away; leave aim be
                clock >= nextAvertAt -> {
                    // Break eye contact briefly, then it will re-fixate.
                    avertUntil = clock + 0.35 + Random.nextDouble() * 0.55
                    nextAvertAt = clock + 3.0 + Random.nextDouble() * 4.5
                    aimX = (faceX + (Random.nextFloat() - 0.5f) * 0.9f).coerceIn(-1f, 1f)
                    aimY = (faceY - 0.18f - Random.nextFloat() * 0.22f).coerceIn(-1f, 1f)
                    if (Random.nextFloat() < 0.35f) triggerBlink()
                }
                hypot((faceX - aimX).toDouble(), (faceY - aimY).toDouble()) > FIXATE_DEADBAND -> {
                    // A real corrective saccade back onto the face.
                    aimX = faceX
                    aimY = faceY
                }
            }
            return
        }

        // Idle wander: pick a fresh fixation on a cadence set by the gaze mode.
        if (clock >= nextShiftAt) {
            when {
                cloudStance == CloudCompanionStance.TENDER || cloudIntent == CloudCompanionIntent.REST -> {
                    aimX = Random.nextFloat() * 0.56f - 0.28f
                    aimY = -0.10f + Random.nextFloat() * 0.20f
                    nextShiftAt = clock + 3.2 + Random.nextDouble() * 3.4
                }
                cloudStance == CloudCompanionStance.PLAYFUL || cloudStance == CloudCompanionStance.AMUSED -> {
                    aimX = Random.nextFloat() * 1.85f - 0.92f
                    aimY = Random.nextFloat() * 1.05f - 0.52f
                    nextShiftAt = clock + 0.55 + Random.nextDouble() * 1.15
                }
                cloudStance == CloudCompanionStance.PROTECTIVE || cloudIntent == CloudCompanionIntent.GUARD || cloudIntent == CloudCompanionIntent.STUDY -> {
                    aimX = Random.nextFloat() * 0.28f - 0.14f
                    aimY = Random.nextFloat() * 0.14f - 0.07f
                    nextShiftAt = clock + 1.35 + Random.nextDouble() * 1.5
                }
                else -> when (cloud?.gazeMode ?: CloudGazeMode.CURIOUS) {
                CloudGazeMode.STEADY -> {
                    aimX = Random.nextFloat() * 0.44f - 0.22f
                    aimY = Random.nextFloat() * 0.26f - 0.13f
                    nextShiftAt = clock + 2.4 + Random.nextDouble() * 2.8
                }
                CloudGazeMode.DART -> {
                    aimX = Random.nextFloat() * 1.9f - 0.95f
                    aimY = Random.nextFloat() * 1.1f - 0.55f
                    nextShiftAt = clock + 0.45 + Random.nextDouble() * 1.0
                }
                CloudGazeMode.LOCKED -> {
                    aimX = Random.nextFloat() * 0.34f - 0.17f
                    aimY = Random.nextFloat() * 0.18f - 0.09f
                    nextShiftAt = clock + 1.2 + Random.nextDouble() * 1.4
                }
                CloudGazeMode.DREAMY -> {
                    aimX = Random.nextFloat() * 0.78f - 0.39f
                    aimY = -0.12f + Random.nextFloat() * 0.26f
                    nextShiftAt = clock + 2.6 + Random.nextDouble() * 3.2
                }
                CloudGazeMode.CURIOUS -> {
                    aimX = Random.nextFloat() * 1.6f - 0.8f
                    aimY = Random.nextFloat() * 1.0f - 0.5f
                    nextShiftAt = clock + 1.0 + Random.nextDouble() * 3.0
                }
                }
            }
            // We usually blink through a big gaze shift, like a real eye.
            if (Random.nextFloat() < 0.30f) triggerBlink()
        }
    }

    /**
     * Underdamped spring toward [aimX]/[aimY]: fast ballistic move with a small
     * overshoot and settle, then it holds. Sub-stepped for stability at low fps.
     */
    private fun integrateGaze(dtSec: Float) {
        var remaining = dtSec
        while (remaining > 0f) {
            val h = min(SPRING_STEP, remaining)
            val ax = GAZE_STIFFNESS * (aimX - gazeX) - GAZE_DAMPING * gazeVX
            val ay = GAZE_STIFFNESS * (aimY - gazeY) - GAZE_DAMPING * gazeVY
            gazeVX += ax * h
            gazeVY += ay * h
            gazeX += gazeVX * h
            gazeY += gazeVY * h
            remaining -= h
        }
    }

    private fun scheduleBlink(styleMood: Mood, cloud: CloudAnimation?, visualEnergy: Float) {
        if (blinkStart < 0 && clock >= nextBlinkAt && !sleeping) {
            val doubleBlink = Random.nextFloat() < 0.1f
            val halfBlink = !doubleBlink && Random.nextFloat() < 0.12f
            triggerBlink(if (halfBlink) 0.42f else 0f)
            nextBlinkAt = if (doubleBlink) clock + BLINK_TOTAL + 0.18
            else clock + (2.0 + Random.nextDouble() * 2.8) /
                (styleMood.blinkRateMul * (cloud?.blinkRate ?: 1f) * (1f + visualEnergy * 1.1f))
        }
        if (blinkStart >= 0 && clock - blinkStart >= BLINK_TOTAL + blinkRightDelay) {
            blinkStart = -1.0
        }
    }

    /** Asymmetric lid curve: fast close, slow reopen; [floor] allows half blinks. */
    private fun blinkFactor(t: Double, floor: Float): Float {
        if (blinkStart < 0) return 1f
        val e = t - blinkStart
        return when {
            e < 0.0 -> 1f
            e < BLINK_CLOSE -> 1f - (1f - floor) * (e / BLINK_CLOSE).toFloat()
            e < BLINK_TOTAL -> floor + (1f - floor) * ((e - BLINK_CLOSE) / BLINK_OPEN).toFloat()
            else -> 1f
        }
    }

    /** Sleepy/grumpy moods sit at a slightly lower resting openness. */
    private fun blinkStyleScale(mood: Mood): Float = when (mood) {
        Mood.EXCITED -> 1.02f
        else -> 1f
    }

    private fun activeCloudAnimation(): CloudAnimation? =
        cloudAnimation?.takeIf { !ambientDark && !covered && it.isActive() }

    private fun activeMood(now: Long): Mood = when {
        sleeping -> mood
        now < intruderUntil -> Mood.SUSPICIOUS
        now < guardUntil -> Mood.NEUTRAL
        cloudMood != null && !ambientDark && !covered -> cloudMood!!
        grooveLevel > 0.30f && !covered -> Mood.EXCITED
        else -> mood
    }

    private fun applyTo(
        eye: EyeParams,
        openness: Float,
        pupilX: Float,
        pupilY: Float,
        pupilScale: Float,
        lidAngle: Float,
        dt: Float,
        activeMood: Mood,
        expressiveness: Float,
        eyeBias: Float,
    ) {
        // Openness is already shaped by the crisp blink curve — snap it so blinks
        // stay sharp. Pupil position is already spring-smoothed. Styling eases.
        val slow = min(1f, dt * 5f)
        eye.openness = openness
        eye.pupilX = pupilX.coerceIn(-1.2f, 1.2f)
        eye.pupilY = pupilY.coerceIn(-1.2f, 1.2f)
        eye.pupilScale += (pupilScale - eye.pupilScale) * slow
        eye.lidAngle += (lidAngle - eye.lidAngle) * slow
        val squashTarget = (activeMood.squash - expressiveness * 0.09f * eyeBias).coerceIn(0.76f, 1.06f)
        eye.squash += (squashTarget - eye.squash) * slow
    }

    companion object {
        const val SLEEP_AFTER_MS = 10 * 60 * 1000L

        // Gaze spring: ~4% overshoot, settles in ~0.18 s (snappy, eye-like).
        private const val GAZE_STIFFNESS = 900f
        private const val GAZE_DAMPING = 44f
        private const val SPRING_STEP = 0.008f
        private const val FIXATE_DEADBAND = 0.06   // face must drift this far to re-saccade
        private const val MAX_VERGENCE = 0.22f
        private const val TREMOR_AMP = 0.006f
        private const val BREATH_HZ = 0.2
        private const val FOCUS_FRAME_MS = 1_800L

        // Asymmetric blink: ~45 ms to close, ~170 ms to reopen.
        private const val BLINK_CLOSE = 0.045
        private const val BLINK_OPEN = 0.170
        private const val BLINK_TOTAL = BLINK_CLOSE + BLINK_OPEN
    }
}
