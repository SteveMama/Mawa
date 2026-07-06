package com.mawa.face.audio

import android.content.SharedPreferences
import com.mawa.face.util.TimeOfDay
import kotlin.math.abs

/**
 * Lightweight persistent taste model built from repeated music sessions. It
 * does not identify tracks; it remembers the kinds of grooves this wall tends
 * to enjoy: steadier vs chaotic, softer vs intense, and whether late-night
 * sessions are where it usually comes alive.
 */
class MusicTasteProfile(
    private val prefs: SharedPreferences,
) {

    data class Snapshot(
        val profileLabel: String = "curious pulse",
        val stance: String = "quiet",
        val enjoyment: Float = 0.18f,
        val affinity: Float = 0.50f,
        val preferredIntensity: Float = 0.44f,
        val steadiness: Float = 0.50f,
        val lateNightBias: Float = 0.50f,
        val sessionCount: Int = 0,
    )

    private var sessionActive = false
    private var sessionStartedAt = 0L
    private var lastActiveAt = 0L
    private var sessionSum = 0f
    private var sessionPeak = 0f
    private var sessionSamples = 0
    private var sessionSteadySteps = 0
    private var sessionLateNight = false
    private var lastGroove = 0f

    fun observe(groove: Float, nowMs: Long): Snapshot {
        val clampedGroove = groove.coerceIn(0f, 1f)
        val active = clampedGroove >= ACTIVE_GROOVE

        if (active) {
            if (!sessionActive) {
                sessionActive = true
                sessionStartedAt = nowMs
                sessionLateNight = TimeOfDay.isNight()
                sessionSum = 0f
                sessionPeak = 0f
                sessionSamples = 0
                sessionSteadySteps = 0
            }
            if (sessionSamples > 0 && abs(clampedGroove - lastGroove) <= STEADY_DELTA) {
                sessionSteadySteps += 1
            }
            sessionSum += clampedGroove
            sessionPeak = maxOf(sessionPeak, clampedGroove)
            sessionSamples += 1
            sessionLateNight = sessionLateNight || TimeOfDay.isNight()
            lastGroove = clampedGroove
            lastActiveAt = nowMs
        } else if (sessionActive && nowMs - lastActiveAt >= SESSION_END_GRACE_MS) {
            finalizeSession(nowMs)
        }

        return snapshot(clampedGroove, active)
    }

    private fun finalizeSession(nowMs: Long) {
        val duration = nowMs - sessionStartedAt
        if (duration >= MIN_SESSION_MS && sessionSamples >= 2) {
            val avg = sessionSum / sessionSamples.toFloat()
            val steadiness = sessionSteadySteps.toFloat() / maxOf(1f, (sessionSamples - 1).toFloat())
            val dynamicRange = (sessionPeak - avg).coerceIn(0f, 1f)
            val currentPreferred = loadFloat(KEY_PREFERRED_INTENSITY, 0.44f)
            val closeness = (1f - abs(avg - currentPreferred) * 1.6f).coerceIn(0f, 1f)
            val sessionEnjoyment = (
                0.22f +
                    steadiness * 0.36f +
                    closeness * 0.28f +
                    (1f - dynamicRange) * 0.10f +
                    if (sessionLateNight) loadFloat(KEY_LATE_NIGHT_BIAS, 0.5f) * 0.12f else 0f
                ).coerceIn(0f, 1f)

            prefs.edit()
                .putFloat(KEY_AFFINITY, blend(loadFloat(KEY_AFFINITY, 0.50f), sessionEnjoyment, 0.22f))
                .putFloat(KEY_PREFERRED_INTENSITY, blend(currentPreferred, avg, 0.16f))
                .putFloat(KEY_STEADINESS, blend(loadFloat(KEY_STEADINESS, 0.50f), steadiness, 0.20f))
                .putFloat(
                    KEY_LATE_NIGHT_BIAS,
                    blend(loadFloat(KEY_LATE_NIGHT_BIAS, 0.50f), if (sessionLateNight) 1f else 0f, 0.10f),
                )
                .putInt(KEY_SESSION_COUNT, prefs.getInt(KEY_SESSION_COUNT, 0) + 1)
                .apply()
        }

        sessionActive = false
        sessionStartedAt = 0L
        sessionSum = 0f
        sessionPeak = 0f
        sessionSamples = 0
        sessionSteadySteps = 0
        sessionLateNight = false
    }

    private fun snapshot(groove: Float, active: Boolean): Snapshot {
        val affinity = loadFloat(KEY_AFFINITY, 0.50f)
        val preferredIntensity = loadFloat(KEY_PREFERRED_INTENSITY, 0.44f)
        val learnedSteadiness = loadFloat(KEY_STEADINESS, 0.50f)
        val liveSteadiness =
            if (sessionActive && sessionSamples > 1) {
                sessionSteadySteps.toFloat() / maxOf(1f, (sessionSamples - 1).toFloat())
            } else {
                learnedSteadiness
            }
        val lateNightBias = loadFloat(KEY_LATE_NIGHT_BIAS, 0.50f)
        val enjoyment =
            if (!active) {
                (affinity * 0.40f).coerceIn(0f, 1f)
            } else {
                val closeness = (1f - abs(groove - preferredIntensity) * 1.6f).coerceIn(0f, 1f)
                val timeLift = if (TimeOfDay.isNight()) lateNightBias * 0.16f else (1f - lateNightBias) * 0.08f
                (0.12f + affinity * 0.34f + closeness * 0.28f + liveSteadiness * 0.20f + timeLift)
                    .coerceIn(0f, 1f)
            }

        return Snapshot(
            profileLabel = profileLabel(preferredIntensity, learnedSteadiness, lateNightBias),
            stance = stance(active, groove, enjoyment, learnedSteadiness),
            enjoyment = enjoyment,
            affinity = affinity,
            preferredIntensity = preferredIntensity,
            steadiness = liveSteadiness,
            lateNightBias = lateNightBias,
            sessionCount = prefs.getInt(KEY_SESSION_COUNT, 0),
        )
    }

    private fun stance(active: Boolean, groove: Float, enjoyment: Float, steadiness: Float): String {
        if (!active || groove < ACTIVE_GROOVE) return "quiet"
        return when {
            enjoyment >= 0.82f -> "fully locked in"
            enjoyment >= 0.64f && steadiness >= 0.58f -> "nodding along"
            groove >= 0.72f && enjoyment < 0.48f -> "holding it at arm's length"
            enjoyment >= 0.50f -> "listening carefully"
            else -> "not convinced yet"
        }
    }

    private fun profileLabel(
        preferredIntensity: Float,
        steadiness: Float,
        lateNightBias: Float,
    ): String = when {
        lateNightBias >= 0.64f && steadiness >= 0.62f && preferredIntensity <= 0.48f ->
            "late-night slow burn"
        preferredIntensity <= 0.38f && steadiness >= 0.60f ->
            "patient warm drift"
        preferredIntensity >= 0.70f && steadiness < 0.46f ->
            "restless bright pulse"
        preferredIntensity >= 0.58f && steadiness >= 0.54f ->
            "steady open-pocket groove"
        steadiness >= 0.66f ->
            "measured pocket"
        else ->
            "curious pulse"
    }

    private fun loadFloat(key: String, fallback: Float): Float =
        prefs.getFloat(key, fallback).coerceIn(0f, 1f)

    private fun blend(previous: Float, next: Float, weight: Float): Float =
        (previous * (1f - weight) + next * weight).coerceIn(0f, 1f)

    companion object {
        private const val KEY_AFFINITY = "music_taste_affinity"
        private const val KEY_PREFERRED_INTENSITY = "music_taste_preferred_intensity"
        private const val KEY_STEADINESS = "music_taste_steadiness"
        private const val KEY_LATE_NIGHT_BIAS = "music_taste_late_night_bias"
        private const val KEY_SESSION_COUNT = "music_taste_session_count"

        private const val ACTIVE_GROOVE = 0.16f
        private const val STEADY_DELTA = 0.16f
        private const val SESSION_END_GRACE_MS = 14_000L
        private const val MIN_SESSION_MS = 22_000L
    }
}
