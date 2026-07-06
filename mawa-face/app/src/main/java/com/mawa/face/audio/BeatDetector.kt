package com.mawa.face.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import android.Manifest
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Tiny on-device beat detector. It reads mono microphone samples, reduces each
 * buffer to RMS energy, and immediately discards the samples. Only beat strength
 * reaches the animation engine; no audio is stored or transmitted.
 */
class BeatDetector(
    private val context: Context,
    private val onBeat: (Float) -> Unit,
    private val onStatus: (String) -> Unit = {},
) {
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var musicGate: MusicGate? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onStatus("beat: microphone permission needed")
            return
        }

        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) {
            onStatus("beat: microphone unavailable")
            return
        }

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBytes, BUFFER_SAMPLES * 2),
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            onStatus("beat: microphone init failed")
            return
        }

        recorder = audioRecord
        running = true
        try {
            audioRecord.startRecording()
        } catch (error: IllegalStateException) {
            running = false
            recorder = null
            audioRecord.release()
            onStatus("beat: microphone start failed")
            return
        }
        onStatus("beat: listening on-device")
        worker = Thread({ detectLoop(audioRecord) }, "mawa-beat").also { it.start() }
    }

    fun stop() {
        running = false
        try {
            recorder?.stop()
        } catch (_: IllegalStateException) {
            // Recorder may already have stopped after an audio-route change.
        }
        worker?.interrupt()
        worker = null
        recorder?.release()
        recorder = null
        musicGate?.close()
        musicGate = null
    }

    private fun detectLoop(audioRecord: AudioRecord) {
        val samples = ShortArray(BUFFER_SAMPLES)
        val gate = MusicGate(context).also { musicGate = it }
        val rhythmOnlyMode = !gate.enabled
        if (rhythmOnlyMode) {
            onStatus("beat: ${gate.unavailableReason() ?: "classifier off"} — rhythm-only mode")
        }
        var averageEnergy = 0.010f
        var averageRise = 0.006f
        var lastRms = 0f
        var lastBeatAt = 0L
        var lastCandidateAt = 0L
        var lastStatusAt = 0L
        var rhythmicHits = 0
        var stableTempoHits = 0
        var rhythmConfidence = 0f
        var musicActiveUntil = 0L
        var lastIntervalMs = 0L

        while (running) {
            val count = try {
                audioRecord.read(samples, 0, samples.size)
            } catch (_: IllegalStateException) {
                break
            }
            if (count <= 0) continue

            var sumSquares = 0.0
            for (index in 0 until count) {
                val normalized = samples[index] / 32768.0
                sumSquares += normalized * normalized
            }
            val rms = sqrt(sumSquares / count).toFloat()
            val rise = (rms - lastRms).coerceAtLeast(0f)
            val threshold = max(
                MIN_BEAT_ENERGY,
                averageEnergy * if (rhythmOnlyMode) ENERGY_MULTIPLIER_FALLBACK else ENERGY_MULTIPLIER,
            )
            val riseThreshold = max(
                MIN_RISE_ENERGY,
                averageRise * if (rhythmOnlyMode) RISE_MULTIPLIER_FALLBACK else RISE_MULTIPLIER,
            )
            val now = SystemClock.elapsedRealtime()
            val gateConfidence = gate.feed(samples, count, now)
            if (lastCandidateAt > 0L && now - lastCandidateAt > RHYTHM_MEMORY_MS) {
                rhythmicHits = 0
                stableTempoHits = 0
                rhythmConfidence = 0f
                lastIntervalMs = 0L
                if (now > musicActiveUntil) musicActiveUntil = 0L
            }
            val strongTransient = rms > threshold * STRONG_BEAT_MULTIPLIER
            if ((strongTransient || (rms > threshold && rise > riseThreshold)) &&
                now - lastBeatAt >= REFRACTORY_MS
            ) {
                val interval = if (lastCandidateAt > 0L) now - lastCandidateAt else Long.MAX_VALUE
                val rhythmic = interval in RHYTHM_INTERVAL_MIN_MS..RHYTHM_INTERVAL_MAX_MS
                val tempoStable = rhythmic &&
                    (lastIntervalMs == 0L ||
                        interval in (lastIntervalMs * TEMPO_STABILITY_MIN).toLong()..
                            (lastIntervalMs * TEMPO_STABILITY_MAX).toLong())
                rhythmicHits = if (rhythmic) rhythmicHits + 1 else 1
                stableTempoHits = when {
                    tempoStable -> stableTempoHits + 1
                    rhythmic -> 1
                    else -> 0
                }
                rhythmConfidence = if (rhythmic) {
                    (rhythmConfidence + RHYTHM_CONFIDENCE_STEP).coerceAtMost(1f)
                } else {
                    (rhythmConfidence * OFF_RHYTHM_PENALTY).coerceAtLeast(0f)
                }
                if (rhythmic) lastIntervalMs = interval
                lastCandidateAt = now

                val gateAllows = !gate.enabled || gate.allowsBeat(now)
                val gateStrong = !gate.enabled || gate.stronglyAllowsBeat(now)
                val requiredRhythmicHits =
                    if (rhythmOnlyMode) MIN_RHYTHMIC_HITS_FALLBACK else MIN_RHYTHMIC_HITS
                val requiredStableTempoHits =
                    if (rhythmOnlyMode) MIN_STABLE_TEMPO_HITS_FALLBACK else MIN_STABLE_TEMPO_HITS
                val requiredConfidence =
                    if (rhythmOnlyMode) MUSIC_ARM_CONFIDENCE_FALLBACK else MUSIC_ARM_CONFIDENCE
                val musicArmed = rhythmicHits >= requiredRhythmicHits &&
                    stableTempoHits >= requiredStableTempoHits &&
                    rhythmConfidence >= requiredConfidence &&
                    gateStrong
                if (musicArmed) musicActiveUntil = now + MUSIC_HOLD_MS

                if (musicArmed || (now < musicActiveUntil && gateAllows)) {
                    lastBeatAt = now
                    val strength =
                        ((((rms / threshold) - 1f) * 0.58f) + ((rise / riseThreshold) * 0.42f))
                            .coerceIn(0.18f, 1f) *
                            (0.58f + 0.27f * rhythmConfidence + 0.15f * gateConfidence)
                    onBeat(strength.coerceIn(0.18f, 1f))
                    onStatus(
                        "beat: groove ${(strength * 100f).toInt()}%  music ${(gateConfidence * 100f).toInt()}%  noise ${(gate.interference() * 100f).toInt()}%"
                    )
                } else if (rhythmOnlyMode &&
                    rhythmicHits >= SOFT_RHYTHMIC_HITS_FALLBACK &&
                    stableTempoHits >= SOFT_STABLE_TEMPO_HITS_FALLBACK &&
                    now - lastStatusAt > STATUS_INTERVAL_MS / 2
                ) {
                    lastBeatAt = now
                    lastStatusAt = now
                    val strength =
                        ((((rms / threshold) - 1f) * 0.46f) + ((rise / riseThreshold) * 0.30f))
                            .coerceIn(0.12f, 0.44f) * (0.60f + 0.40f * rhythmConfidence)
                    onBeat(strength.coerceIn(0.12f, 0.44f))
                    onStatus(
                        "beat: soft ${(strength * 100f).toInt()}%  rhythm-only"
                    )
                } else if (now - lastStatusAt > STATUS_INTERVAL_MS / 2) {
                    lastStatusAt = now
                    val gateStatus = if (gate.enabled) {
                        "music ${(gateConfidence * 100f).toInt()}%  noise ${(gate.interference() * 100f).toInt()}%"
                    } else {
                        "rhythm-only"
                    }
                    onStatus(
                        "beat: heard sound, waiting (${rhythmicHits}/${requiredRhythmicHits}, tempo ${stableTempoHits}/${requiredStableTempoHits})  $gateStatus"
                    )
                }
            } else if (now - lastStatusAt > STATUS_INTERVAL_MS) {
                lastStatusAt = now
                val gateStatus = if (gate.enabled) {
                    "music ${(gateConfidence * 100f).toInt()}%  noise ${(gate.interference() * 100f).toInt()}%"
                } else {
                    "rhythm-only"
                }
                onStatus(
                    "beat: listening ${(rms * 1000f).toInt()}/${(threshold * 1000f).toInt()}  $gateStatus"
                )
            }

            // Slow baseline follows room loudness without swallowing transients.
            averageEnergy = averageEnergy * 0.95f + rms * 0.05f
            averageRise = averageRise * 0.93f + rise * 0.07f
            lastRms = rms
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val BUFFER_SAMPLES = 384
        private const val MIN_BEAT_ENERGY = 0.020f
        private const val MIN_RISE_ENERGY = 0.0060f
        private const val ENERGY_MULTIPLIER = 1.55f
        private const val ENERGY_MULTIPLIER_FALLBACK = 1.38f
        private const val RISE_MULTIPLIER = 1.45f
        private const val RISE_MULTIPLIER_FALLBACK = 1.26f
        private const val STRONG_BEAT_MULTIPLIER = 1.58f
        private const val REFRACTORY_MS = 180L
        private const val STATUS_INTERVAL_MS = 1_200L
        private const val RHYTHM_INTERVAL_MIN_MS = 280L
        private const val RHYTHM_INTERVAL_MAX_MS = 950L
        private const val RHYTHM_MEMORY_MS = 1_800L
        private const val MIN_RHYTHMIC_HITS = 3
        private const val MIN_RHYTHMIC_HITS_FALLBACK = 2
        private const val MIN_STABLE_TEMPO_HITS = 2
        private const val MIN_STABLE_TEMPO_HITS_FALLBACK = 1
        private const val SOFT_RHYTHMIC_HITS_FALLBACK = 2
        private const val SOFT_STABLE_TEMPO_HITS_FALLBACK = 1
        private const val RHYTHM_CONFIDENCE_STEP = 0.34f
        private const val MUSIC_ARM_CONFIDENCE = 0.58f
        private const val MUSIC_ARM_CONFIDENCE_FALLBACK = 0.32f
        private const val OFF_RHYTHM_PENALTY = 0.55f
        private const val MUSIC_HOLD_MS = 2_600L
        private const val TEMPO_STABILITY_MIN = 0.78
        private const val TEMPO_STABILITY_MAX = 1.22
    }
}
