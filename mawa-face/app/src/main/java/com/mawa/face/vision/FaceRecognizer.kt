package com.mawa.face.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * On-device face recognition (identity), separate from ML Kit detection
 * (presence). Loads a MobileFaceNet-style TFLite embedding model from assets;
 * if the model is absent or fails to load, [enabled] stays false and the app
 * behaves exactly as before — recognition simply never activates. Nothing
 * here ever leaves the phone.
 *
 * To turn it on: drop a compatible model at
 *   app/src/main/assets/mobilefacenet.tflite
 * (112x112x3 input, L2-normalizable float embedding output). Then tune
 * THRESHOLD on-device.
 */
class FaceRecognizer(context: Context) {

    private var interpreter: Interpreter? = null
    private var embeddingSize = 192
    private val inputSize = 112

    var enabled = false
        private set

    init {
        try {
            val afd = context.assets.openFd(MODEL)
            FileInputStream(afd.fileDescriptor).use { fis ->
                val model = fis.channel.map(
                    FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength
                )
                val itp = Interpreter(model)
                embeddingSize = itp.getOutputTensor(0).shape().last()
                interpreter = itp
                enabled = true
                Log.i(TAG, "face model loaded, embedding size=$embeddingSize")
            }
        } catch (e: Exception) {
            Log.w(TAG, "no face model in assets; recognition disabled")
            enabled = false
        }
    }

    /** Returns an L2-normalized embedding for a face crop, or null if disabled. */
    fun embed(face: Bitmap): FloatArray? {
        val itp = interpreter ?: return null
        return try {
            val scaled = Bitmap.createScaledBitmap(face, inputSize, inputSize, true)
            val buf = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
                .order(ByteOrder.nativeOrder())
            val px = IntArray(inputSize * inputSize)
            scaled.getPixels(px, 0, inputSize, 0, 0, inputSize, inputSize)
            for (p in px) {
                buf.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128f)
                buf.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128f)
                buf.putFloat(((p and 0xFF) - 127.5f) / 128f)
            }
            val out = Array(1) { FloatArray(embeddingSize) }
            itp.run(buf, out)
            l2normalize(out[0])
        } catch (e: Exception) {
            Log.w(TAG, "embed failed", e)
            null
        }
    }

    private fun l2normalize(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        val n = sqrt(s)
        if (n > 0f) for (i in v.indices) v[i] /= n
        return v
    }

    companion object {
        private const val TAG = "FaceRecognizer"
        private const val MODEL = "mobilefacenet.tflite"

        // Cosine similarity of two L2-normalized embeddings; 1 = identical.
        // Tune on-device: same person typically > 0.6, strangers well below.
        const val THRESHOLD = 0.62f

        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return -1f
            var d = 0f
            for (i in a.indices) d += a[i] * b[i]
            return d
        }
    }
}
