package com.mawa.face.vision

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

/**
 * Front-camera face detection, fully on-device (ML Kit bundled model).
 * Frames are throttled to ~5 fps to keep the old phone cool.
 *
 * The analysis rotation is refreshed every frame from the display rotation so
 * the phone works wall-mounted in either landscape direction (ML Kit can't
 * detect upside-down faces — a stale rotation silently kills detection).
 *
 * Emits: normalized face center + proximity + face count + average eye-open
 * probability (for blink-back), plus per-frame luminance (for camera-cover
 * "do not disturb"). No frame ever leaves this class.
 */
class FaceTracker(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFace: (cx: Float, cy: Float, prox: Float, faceCount: Int, eyeOpen: Float) -> Unit,
    private val onLost: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onLuma: (Float) -> Unit,
) {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // eye-open probs
            .setMinFaceSize(0.05f)  // a face across the room is small in-frame
            .build()
    )
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var analysis: ImageAnalysis? = null
    private var lastProcessedAt = 0L
    private var lastSeenAt = 0L
    private var lostReported = true
    private var framesProcessed = 0

    fun start() {
        onStatus("starting front camera...")
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                this.analysis = analysis
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis,
                )
                onStatus("front camera bound, waiting for frames...")
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                onStatus("CAMERA ERROR: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return wm.defaultDisplay.rotation
    }

    /** Mean luminance (0..255) from a subsample of the Y plane. */
    private fun lumaOf(image: android.media.Image): Float {
        val y = image.planes[0].buffer
        var sum = 0L
        var count = 0
        var i = 0
        val cap = y.capacity()
        val step = 1024
        while (i < cap) {
            sum += (y.get(i).toInt() and 0xFF)
            count++
            i += step
        }
        return if (count > 0) sum.toFloat() / count else 0f
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProcessedAt < FRAME_INTERVAL_MS) {
            proxy.close()
            return
        }
        lastProcessedAt = now

        analysis?.targetRotation = displayRotation()

        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }

        onLuma(lumaOf(mediaImage))

        val rotation = proxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)
        val frameW = if (rotation % 180 == 0) proxy.width else proxy.height
        val frameH = if (rotation % 180 == 0) proxy.height else proxy.width

        detector.process(input)
            .addOnSuccessListener { faces ->
                framesProcessed++
                if (framesProcessed % 25 == 0 || framesProcessed == 1) {
                    onStatus("front cam OK  rot=$rotation  frames=$framesProcessed  faces=${faces.size}")
                }
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face != null) {
                    lastSeenAt = now
                    lostReported = false
                    val b = face.boundingBox
                    val cx = b.exactCenterX() / frameW
                    val cy = b.exactCenterY() / frameH
                    val prox = (b.width().toFloat() * b.height().toFloat()) /
                        (frameW.toFloat() * frameH.toFloat())
                    val le = face.leftEyeOpenProbability ?: 1f
                    val re = face.rightEyeOpenProbability ?: 1f
                    onFace(cx, cy, prox, faces.size, (le + re) / 2f)
                } else if (!lostReported && now - lastSeenAt > LOST_AFTER_MS) {
                    lostReported = true
                    onLost()
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "detection failed", e)
                onStatus("DETECTOR ERROR: ${e.message}")
            }
            .addOnCompleteListener { proxy.close() }
    }

    companion object {
        private const val TAG = "FaceTracker"
        private const val FRAME_INTERVAL_MS = 180L   // ~5 fps
        private const val LOST_AFTER_MS = 2000L      // hold gaze 2 s before wandering
    }
}
