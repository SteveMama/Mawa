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
    }

    private fun detectLoop(audioRecord: AudioRecord) {
        val samples = ShortArray(BUFFER_SAMPLES)
        var averageEnergy = 0.010f
        var averageRise = 0.006f
        var lastRms = 0f
        var lastBeatAt = 0L
        var lastCandidateAt = 0L
        var lastStatusAt = 0L
        var rhythmicHits = 0
        var musicConfidence = 0f
        var musicActiveUntil = 0L

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
            val threshold = max(MIN_BEAT_ENERGY, averageEnergy * ENERGY_MULTIPLIER)
            val riseThreshold = max(MIN_RISE_ENERGY, averageRise * RISE_MULTIPLIER)
            val now = SystemClock.elapsedRealtime()
            if (lastCandidateAt > 0L && now - lastCandidateAt > RHYTHM_MEMORY_MS) {
                rhythmicHits = 0
                musicConfidence = 0f
                if (now > musicActiveUntil) musicActiveUntil = 0L
            }
            val strongTransient = rms > threshold * STRONG_BEAT_MULTIPLIER
            if ((strongTransient || (rms > threshold && rise > riseThreshold)) &&
                now - lastBeatAt >= REFRACTORY_MS
            ) {
                val interval = if (lastCandidateAt > 0L) now - lastCandidateAt else Long.MAX_VALUE
                val rhythmic = interval in RHYTHM_INTERVAL_MIN_MS..RHYTHM_INTERVAL_MAX_MS
                rhythmicHits = if (rhythmic) rhythmicHits + 1 else 1
                musicConfidence = if (rhythmic) {
                    (musicConfidence + RHYTHM_CONFIDENCE_STEP).coerceAtMost(1f)
                } else {
                    (musicConfidence * OFF_RHYTHM_PENALTY).coerceAtLeast(0f)
                }
                lastCandidateAt = now

                val musicArmed = rhythmicHits >= MIN_RHYTHMIC_HITS &&
                    musicConfidence >= MUSIC_ARM_CONFIDENCE
                if (musicArmed) musicActiveUntil = now + MUSIC_HOLD_MS

                if (musicArmed || now < musicActiveUntil) {
                    lastBeatAt = now
                    val strength =
                        ((((rms / threshold) - 1f) * 0.58f) + ((rise / riseThreshold) * 0.42f))
                            .coerceIn(0.18f, 1f) * (0.70f + 0.30f * musicConfidence)
                    onBeat(strength.coerceIn(0.18f, 1f))
                    onStatus(
                        "beat: groove ${(strength * 100f).toInt()}%  conf ${(musicConfidence * 100f).toInt()}%"
                    )
                } else if (now - lastStatusAt > STATUS_INTERVAL_MS / 2) {
                    lastStatusAt = now
                    onStatus(
                        "beat: heard sound, waiting for rhythm (${rhythmicHits}/${MIN_RHYTHMIC_HITS})"
                    )
                }
            } else if (now - lastStatusAt > STATUS_INTERVAL_MS) {
                lastStatusAt = now
                onStatus(
                    "beat: listening ${(rms * 1000f).toInt()}/${(threshold * 1000f).toInt()}  conf ${(musicConfidence * 100f).toInt()}%"
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
        private const val RISE_MULTIPLIER = 1.45f
        private const val STRONG_BEAT_MULTIPLIER = 1.75f
        private const val REFRACTORY_MS = 220L
        private const val STATUS_INTERVAL_MS = 1_200L
        private const val RHYTHM_INTERVAL_MIN_MS = 280L
        private const val RHYTHM_INTERVAL_MAX_MS = 950L
        private const val RHYTHM_MEMORY_MS = 1_800L
        private const val MIN_RHYTHMIC_HITS = 3
        private const val RHYTHM_CONFIDENCE_STEP = 0.38f
        private const val MUSIC_ARM_CONFIDENCE = 0.72f
        private const val OFF_RHYTHM_PENALTY = 0.35f
        private const val MUSIC_HOLD_MS = 1_600L
    }
}
