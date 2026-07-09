package com.mawa.face.net

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class RoomMomentClient(
    private val baseUrl: String,
    private val deviceToken: String = "",
) {
    data class MomentSnapshot(
        val deviceId: String,
        val capturedAt: String,
        val labels: List<String>,
        val changeScore: Float,
        val luma: Float,
        val faceCount: Int,
        val recognized: String,
        val personLabel: String? = null,
        val musicActive: Boolean = false,
        val groove: Float = 0f,
        val imageBase64: String,
    )

    val enabled: Boolean get() = baseUrl.startsWith("https://") || baseUrl.startsWith("http://")

    fun publish(
        deviceId: String,
        bitmap: Bitmap,
        labels: List<String>,
        changeScore: Float,
        luma: Float,
        faceCount: Int,
        recognized: String,
        personLabel: String?,
        musicActive: Boolean,
        groove: Float,
    ) {
        if (!enabled) {
            bitmap.recycle()
            return
        }

        val encoded = encode(bitmap)
        bitmap.recycle()
        if (encoded == null) return

        val snapshot = MomentSnapshot(
            deviceId = deviceId,
            capturedAt = isoTimestamp(),
            labels = labels,
            changeScore = changeScore.coerceIn(0f, 1f),
            luma = luma.coerceIn(0f, 255f),
            faceCount = faceCount.coerceIn(0, 8),
            recognized = recognized,
            personLabel = personLabel?.take(40),
            musicActive = musicActive,
            groove = groove.coerceIn(0f, 1f),
            imageBase64 = encoded,
        )

        Thread {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("$baseUrl/api/device/moment")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("Accept", "application/json")
                if (deviceToken.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer $deviceToken")
                }
                connection.outputStream.bufferedWriter().use { writer ->
                    writer.write(jsonOf(snapshot).toString())
                }

                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("moment HTTP ${connection.responseCode}")
                }
                connection.inputStream.close()
            } catch (error: Exception) {
                Log.w(TAG, "moment publish failed", error)
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun encode(bitmap: Bitmap): String? {
        return try {
            val scaled = scale(bitmap)
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            if (scaled !== bitmap) scaled.recycle()
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (error: Exception) {
            Log.w(TAG, "moment encode failed", error)
            null
        }
    }

    private fun scale(bitmap: Bitmap): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= MAX_UPLOAD_EDGE) return bitmap
        val ratio = MAX_UPLOAD_EDGE.toFloat() / maxSide.toFloat()
        val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    private fun jsonOf(snapshot: MomentSnapshot): JSONObject = JSONObject().apply {
        put("deviceId", snapshot.deviceId)
        put("capturedAt", snapshot.capturedAt)
        put("labels", JSONArray(snapshot.labels))
        put("changeScore", snapshot.changeScore.toDouble())
        put("luma", snapshot.luma.toDouble())
        put("faceCount", snapshot.faceCount)
        put("recognized", snapshot.recognized)
        put("personLabel", snapshot.personLabel ?: JSONObject.NULL)
        put("musicActive", snapshot.musicActive)
        put("groove", snapshot.groove.toDouble())
        put("imageBase64", snapshot.imageBase64)
    }

    private fun isoTimestamp(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }

    companion object {
        private const val TAG = "RoomMomentClient"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 12_000
        private const val MAX_UPLOAD_EDGE = 480
        private const val JPEG_QUALITY = 68
    }
}
