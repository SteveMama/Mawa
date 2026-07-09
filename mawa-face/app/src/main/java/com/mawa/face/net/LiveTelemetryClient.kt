package com.mawa.face.net

import android.util.Log
import com.mawa.face.render.Mood
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class LiveTelemetryClient(
    private val baseUrl: String,
    private val deviceToken: String = "",
) {

    data class ThoughtSnapshot(
        val eyebrow: String,
        val title: String,
        val detail: String,
        val accent: String = "#8FA6C0",
    )

    data class FeelingSnapshot(
        val mood: Mood,
        val summary: String,
        val attention: String,
        val sleeping: Boolean,
        val covered: Boolean,
        val ambientDark: Boolean,
        val energy: Float,
        val expressiveness: Float,
    )

    data class PresenceSnapshot(
        val faceCount: Int,
        val recognized: String,
        val personLabel: String? = null,
        val proximity: Float,
        val identityLock: Boolean,
        val following: Boolean,
    )

    data class MusicSnapshot(
        val active: Boolean,
        val groove: Float,
        val tasteProfile: String? = null,
        val stance: String = "quiet",
        val enjoyment: Float = 0f,
        val affinity: Float = 0.5f,
        val preferredIntensity: Float = 0.5f,
        val steadiness: Float = 0.5f,
        val lateNightBias: Float = 0.5f,
        val sessionCount: Int = 0,
        val beatStatus: String = "",
    )

    data class StatusSnapshot(
        val camera: String,
        val brain: String,
        val beat: String,
        val scene: String,
        val face: String,
    )

    data class TelemetrySnapshot(
        val deviceId: String,
        val appVersion: String,
        val manifestId: String? = null,
        val capturedAt: String,
        val thought: ThoughtSnapshot? = null,
        val feeling: FeelingSnapshot,
        val presence: PresenceSnapshot,
        val music: MusicSnapshot,
        val status: StatusSnapshot,
    )

    val enabled: Boolean get() = baseUrl.startsWith("https://") || baseUrl.startsWith("http://")

    fun publish(snapshot: TelemetrySnapshot) {
        if (!enabled) return

        Thread {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("$baseUrl/api/device/telemetry")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "Mawa-Android/${snapshot.appVersion}")
                if (deviceToken.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer $deviceToken")
                }

                connection.outputStream.bufferedWriter().use { writer ->
                    writer.write(jsonOf(snapshot).toString())
                }

                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("telemetry HTTP ${connection.responseCode}")
                }
                connection.inputStream.close()
            } catch (error: Exception) {
                Log.w(TAG, "telemetry publish failed", error)
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun jsonOf(snapshot: TelemetrySnapshot): JSONObject = JSONObject().apply {
        put("deviceId", snapshot.deviceId)
        put("appVersion", snapshot.appVersion)
        put("manifestId", snapshot.manifestId ?: JSONObject.NULL)
        put("capturedAt", snapshot.capturedAt)
        put("thought", snapshot.thought?.let { thought ->
            JSONObject().apply {
                put("eyebrow", thought.eyebrow)
                put("title", thought.title)
                put("detail", thought.detail)
                put("accent", thought.accent)
            }
        } ?: JSONObject.NULL)
        put("feeling", JSONObject().apply {
            put("mood", snapshot.feeling.mood.name.lowercase())
            put("summary", snapshot.feeling.summary)
            put("attention", snapshot.feeling.attention)
            put("sleeping", snapshot.feeling.sleeping)
            put("covered", snapshot.feeling.covered)
            put("ambientDark", snapshot.feeling.ambientDark)
            put("energy", snapshot.feeling.energy.coerceIn(0f, 1f).toDouble())
            put("expressiveness", snapshot.feeling.expressiveness.coerceIn(0f, 1f).toDouble())
        })
        put("presence", JSONObject().apply {
            put("faceCount", snapshot.presence.faceCount.coerceIn(0, 8))
            put("recognized", snapshot.presence.recognized)
            put("personLabel", snapshot.presence.personLabel ?: JSONObject.NULL)
            put("proximity", snapshot.presence.proximity.coerceIn(0f, 1f).toDouble())
            put("identityLock", snapshot.presence.identityLock)
            put("following", snapshot.presence.following)
        })
        put("music", JSONObject().apply {
            put("active", snapshot.music.active)
            put("groove", snapshot.music.groove.coerceIn(0f, 1f).toDouble())
            put("tasteProfile", snapshot.music.tasteProfile ?: JSONObject.NULL)
            put("stance", snapshot.music.stance)
            put("enjoyment", snapshot.music.enjoyment.coerceIn(0f, 1f).toDouble())
            put("affinity", snapshot.music.affinity.coerceIn(0f, 1f).toDouble())
            put("preferredIntensity", snapshot.music.preferredIntensity.coerceIn(0f, 1f).toDouble())
            put("steadiness", snapshot.music.steadiness.coerceIn(0f, 1f).toDouble())
            put("lateNightBias", snapshot.music.lateNightBias.coerceIn(0f, 1f).toDouble())
            put("sessionCount", snapshot.music.sessionCount.coerceAtLeast(0))
            put("beatStatus", snapshot.music.beatStatus)
        })
        put("status", JSONObject().apply {
            put("camera", snapshot.status.camera)
            put("brain", snapshot.status.brain)
            put("beat", snapshot.status.beat)
            put("scene", snapshot.status.scene)
            put("face", snapshot.status.face)
        })
    }

    companion object {
        private const val TAG = "LiveTelemetryClient"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 5_000
    }
}
