package com.mawa.face.audio

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.components.containers.AudioData.AudioDataFormat
import com.google.mediapipe.tasks.core.BaseOptions

/**
 * On-device music/sound gate. It classifies short room-audio clips with YAMNet
 * and only arms beat-driven visuals when the clip actually looks musical.
 *
 * This is intentionally conservative. False negatives are preferable to Mawa
 * grooving to speech, claps, or random room noise.
 */
class MusicGate(
    context: Context,
) {
    private var classifier: AudioClassifier? = null
    private var audioData: AudioData? = null
    private var bufferedSamples = 0
    private var lastAnalysisAt = 0L
    private var musicConfidence = 0f
    private var musicArmedUntil = 0L

    val enabled: Boolean
        get() = classifier != null && audioData != null

    init {
        try {
            context.assets.openFd(MODEL).close()
            val options = AudioClassifier.AudioClassifierOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL)
                        .build()
                )
                .setRunningMode(RunningMode.AUDIO_CLIPS)
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(SCORE_THRESHOLD)
                .build()
            classifier = AudioClassifier.createFromOptions(context, options)
            audioData = AudioData.create(
                AudioDataFormat.builder()
                    .setNumOfChannels(1)
                    .setSampleRate(SAMPLE_RATE)
                    .build(),
                MODEL_WINDOW_SAMPLES,
            )
            Log.i(TAG, "music gate ready")
        } catch (error: Exception) {
            Log.w(TAG, "music gate unavailable", error)
            classifier = null
            audioData = null
        }
    }

    fun feed(samples: ShortArray, count: Int, nowMs: Long): Float {
        val clip = audioData ?: return 0f
        if (count <= 0) return musicConfidence

        clip.load(samples, 0, count)
        bufferedSamples = (bufferedSamples + count).coerceAtMost(MODEL_WINDOW_SAMPLES)
        if (bufferedSamples < MODEL_WINDOW_SAMPLES) return musicConfidence
        if (nowMs - lastAnalysisAt < ANALYZE_INTERVAL_MS) return musicConfidence
        lastAnalysisAt = nowMs

        val categories = try {
            classifier?.classify(clip)
                ?.classificationResults()
                ?.firstOrNull()
                ?.classifications()
                ?.firstOrNull()
                ?.categories()
                .orEmpty()
        } catch (error: Exception) {
            Log.w(TAG, "music gate classify failed", error)
            return musicConfidence * 0.85f
        }

        var positive = 0f
        var negative = 0f
        for (category in categories) {
            val name = category.categoryName().orEmpty().lowercase()
            val score = category.score()
            when {
                MUSIC_KEYWORDS.any(name::contains) -> positive = maxOf(positive, score)
                NEGATIVE_KEYWORDS.any(name::contains) -> negative = maxOf(negative, score)
            }
        }

        val instantaneous = (positive - negative * NEGATIVE_WEIGHT).coerceIn(0f, 1f)
        musicConfidence = (musicConfidence * CONFIDENCE_DECAY + instantaneous * (1f - CONFIDENCE_DECAY))
            .coerceIn(0f, 1f)
        if (instantaneous >= ARM_THRESHOLD || musicConfidence >= ARM_THRESHOLD) {
            musicArmedUntil = nowMs + ARM_HOLD_MS
        }
        return musicConfidence
    }

    fun allowsBeat(nowMs: Long): Boolean = enabled &&
        (musicConfidence >= ARM_THRESHOLD || nowMs < musicArmedUntil)

    fun confidence(): Float = musicConfidence

    fun close() {
        try {
            classifier?.close()
        } catch (_: Exception) {
        }
        classifier = null
        audioData = null
    }

    companion object {
        private const val TAG = "MusicGate"
        private const val MODEL = "yamnet.tflite"
        private const val SAMPLE_RATE = 16_000
        private const val MODEL_WINDOW_SAMPLES = 15_600 // 0.975 s, per YAMNet sample usage
        private const val ANALYZE_INTERVAL_MS = 420L
        private const val ARM_HOLD_MS = 2_200L
        private const val ARM_THRESHOLD = 0.52f
        private const val CONFIDENCE_DECAY = 0.70f
        private const val SCORE_THRESHOLD = 0.10f
        private const val MAX_RESULTS = 8
        private const val NEGATIVE_WEIGHT = 0.85f

        private val MUSIC_KEYWORDS = listOf(
            "music", "singing", "choir", "chant", "drum", "snare", "bass drum",
            "hi-hat", "cymbal", "guitar", "piano", "organ", "synth", "orchestra",
            "violin", "cello", "harp", "flute", "trumpet", "saxophone", "ukulele",
            "electronic", "rock", "pop", "hip hop", "dance", "jingle", "theme"
        )

        private val NEGATIVE_KEYWORDS = listOf(
            "speech", "conversation", "narration", "whisper", "shout", "yell",
            "clap", "finger snapping", "laughter", "giggle", "crying", "cough",
            "sneeze", "door", "engine", "vehicle", "traffic", "typing", "tap",
            "thump", "boom", "noise"
        )
    }
}
