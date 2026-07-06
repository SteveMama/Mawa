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
        var lastStatusAt = 0L

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
            val strongTransient = rms > threshold * STRONG_BEAT_MULTIPLIER
            if ((strongTransient || (rms > threshold && rise > riseThreshold)) &&
                now - lastBeatAt >= REFRACTORY_MS
            ) {
                lastBeatAt = now
                val strength = (((rms / threshold) - 1f) * 0.75f + (rise / riseThreshold) * 0.55f)
                    .coerceIn(0.30f, 1f)
                onBeat(strength)
                onStatus("beat: pulse ${(strength * 100f).toInt()}%")
            } else if (now - lastStatusAt > STATUS_INTERVAL_MS) {
                lastStatusAt = now
                onStatus(
                    "beat: listening ${(rms * 1000f).toInt()}/${(threshold * 1000f).toInt()}"
                )
            }

            // Slow baseline follows room loudness without swallowing transients.
            averageEnergy = averageEnergy * 0.93f + rms * 0.07f
            averageRise = averageRise * 0.90f + rise * 0.10f
            lastRms = rms
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val BUFFER_SAMPLES = 384
        private const val MIN_BEAT_ENERGY = 0.015f
        private const val MIN_RISE_ENERGY = 0.0045f
        private const val ENERGY_MULTIPLIER = 1.28f
        private const val RISE_MULTIPLIER = 1.20f
        private const val STRONG_BEAT_MULTIPLIER = 1.42f
        private const val REFRACTORY_MS = 150L
        private const val STATUS_INTERVAL_MS = 1_200L
    }
}
