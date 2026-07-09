package com.mawa.face.vision

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlin.math.abs

data class SceneMoment(
    val capturedAtMs: Long,
    val bitmap: Bitmap,
    val labels: List<String>,
    val changeScore: Float,
    val luma: Float,
    val faceCount: Int,
)

class SceneMomentAnalyzer(
    private val onMoment: (SceneMoment) -> Unit,
    private val onStatus: (String) -> Unit = {},
) {
    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
    private var lastSampleAt = 0L
    private var lastEmitAt = 0L
    private var analyzing = false
    private var lastSignature: FloatArray? = null
    private var lastLabels: List<String> = emptyList()

    fun consider(bitmap: Bitmap, luma: Float, faceCount: Int, capturedAtMs: Long = SystemClock.elapsedRealtime()) {
        if (analyzing) {
            bitmap.recycle()
            return
        }
        if (capturedAtMs - lastSampleAt < SAMPLE_INTERVAL_MS) {
            bitmap.recycle()
            return
        }
        if (luma < MIN_ANALYSIS_LUMA) {
            bitmap.recycle()
            return
        }
        lastSampleAt = capturedAtMs
        analyzing = true

        val signature = signatureOf(bitmap)
        val structuralChange = diff(signature, lastSignature)
        val forceInterpretation = lastEmitAt == 0L || capturedAtMs - lastEmitAt >= FORCE_EMIT_MS
        val shouldInspect = structuralChange >= INSPECT_CHANGE_THRESHOLD || forceInterpretation
        lastSignature = signature

        if (!shouldInspect) {
            analyzing = false
            bitmap.recycle()
            return
        }

        labeler.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { labels ->
                val topLabels = labels
                    .filter { it.confidence >= MIN_LABEL_CONFIDENCE }
                    .sortedByDescending { it.confidence }
                    .map { it.text.trim().lowercase() }
                    .distinct()
                    .take(MAX_LABELS)
                val labelChange = labelShift(topLabels, lastLabels)
                val shouldEmit =
                    lastEmitAt == 0L ||
                        forceInterpretation ||
                        structuralChange >= EMIT_CHANGE_THRESHOLD ||
                        labelChange >= LABEL_SHIFT_THRESHOLD
                if (shouldEmit) {
                    lastEmitAt = capturedAtMs
                    lastLabels = topLabels
                    onStatus(
                        "scene: ${topLabels.joinToString(", ").ifBlank { "change" }} " +
                            "(${String.format("%.2f", structuralChange)})"
                    )
                    onMoment(
                        SceneMoment(
                            capturedAtMs = capturedAtMs,
                            bitmap = bitmap,
                            labels = topLabels,
                            changeScore = structuralChange,
                            luma = luma,
                            faceCount = faceCount,
                        )
                    )
                } else {
                    bitmap.recycle()
                }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "scene labeling failed", error)
                bitmap.recycle()
            }
            .addOnCompleteListener {
                analyzing = false
            }
    }

    fun close() {
        labeler.close()
    }

    private fun signatureOf(bitmap: Bitmap): FloatArray {
        val sample = Bitmap.createScaledBitmap(bitmap, GRID_SIZE, GRID_SIZE, true)
        val values = FloatArray(GRID_SIZE * GRID_SIZE)
        var i = 0
        for (y in 0 until GRID_SIZE) {
            for (x in 0 until GRID_SIZE) {
                val pixel = sample.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                values[i++] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            }
        }
        sample.recycle()
        return values
    }

    private fun diff(current: FloatArray, previous: FloatArray?): Float {
        if (previous == null || previous.size != current.size) return 1f
        var total = 0f
        for (i in current.indices) {
            total += abs(current[i] - previous[i])
        }
        return (total / current.size).coerceIn(0f, 1f)
    }

    private fun labelShift(current: List<String>, previous: List<String>): Float {
        if (current.isEmpty() && previous.isEmpty()) return 0f
        if (current.isEmpty() || previous.isEmpty()) return 1f
        val overlap = current.count(previous::contains).toFloat()
        val union = (current + previous).distinct().size.toFloat().coerceAtLeast(1f)
        return (1f - overlap / union).coerceIn(0f, 1f)
    }

    companion object {
        private const val TAG = "SceneMomentAnalyzer"
        private const val GRID_SIZE = 12
        private const val SAMPLE_INTERVAL_MS = 8_000L
        private const val FORCE_EMIT_MS = 8 * 60_000L
        private const val INSPECT_CHANGE_THRESHOLD = 0.07f
        private const val EMIT_CHANGE_THRESHOLD = 0.12f
        private const val LABEL_SHIFT_THRESHOLD = 0.55f
        private const val MIN_ANALYSIS_LUMA = 10f
        private const val MIN_LABEL_CONFIDENCE = 0.55f
        private const val MAX_LABELS = 4
    }
}
