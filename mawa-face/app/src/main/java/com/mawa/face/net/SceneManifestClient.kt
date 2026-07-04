package com.mawa.face.net

import android.util.Log
import com.mawa.face.scene.PanelSlot
import com.mawa.face.scene.ScenePanel
import com.mawa.face.weather.WeatherCondition
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class SceneSnapshot(
    val manifestId: String,
    val weather: WeatherCondition?,
    val panels: List<ScenePanel>,
    val pollAfterSeconds: Int,
)

/**
 * Polls the Vercel brain for a declarative scene. This client deliberately has
 * no authority over face tracking or sleep: if cloud access fails, the local
 * companion remains fully alive and MainActivity falls back to local weather.
 *
 * Location is rounded to ~1 km before transmission. Camera data never enters
 * this client, and manifests contain no executable code.
 */
class SceneManifestClient(
    private val baseUrl: String,
    private val deviceToken: String = "",
) {

    val enabled: Boolean get() = baseUrl.startsWith("https://") || baseUrl.startsWith("http://")

    fun fetch(
        latitude: Double,
        longitude: Double,
        appVersion: Int,
        onResult: (Result<SceneSnapshot>) -> Unit,
    ) {
        if (!enabled) {
            onResult(Result.failure(IllegalStateException("brain URL not configured")))
            return
        }

        Thread {
            var connection: HttpURLConnection? = null
            try {
                val lat = String.format(Locale.US, "%.2f", latitude)
                val lon = String.format(Locale.US, "%.2f", longitude)
                val url = URL(
                    "$baseUrl/api/manifest?lat=$lat&lon=$lon" +
                        "&device=oneplus-wall&version=$appVersion"
                )
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "Mawa-Android/$appVersion")
                if (deviceToken.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer $deviceToken")
                }

                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("manifest HTTP ${connection.responseCode}")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                onResult(Result.success(parse(JSONObject(body))))
            } catch (error: Exception) {
                Log.w(TAG, "manifest fetch failed", error)
                onResult(Result.failure(error))
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun parse(root: JSONObject): SceneSnapshot {
        require(root.getInt("schemaVersion") == SUPPORTED_SCHEMA) {
            "unsupported manifest schema"
        }
        val scene = root.getJSONObject("scene")
        val weather = scene.optJSONObject("weather")
            ?.optString("condition")
            ?.let(::weatherCondition)
        val rawPanels = scene.optJSONArray("panels")
        val panels = buildList {
            if (rawPanels != null) {
                for (index in 0 until minOf(rawPanels.length(), MAX_PANELS)) {
                    val panel = rawPanels.optJSONObject(index) ?: continue
                    add(
                        ScenePanel(
                            id = panel.optString("id", "panel-$index").take(64),
                            slot = panelSlot(panel.optString("slot")),
                            eyebrow = panel.optString("eyebrow").take(20),
                            title = panel.optString("title").take(32),
                            detail = panel.optString("detail").take(48),
                            accent = panel.optString("accent", "#8FA6C0").take(16),
                        )
                    )
                }
            }
        }
        return SceneSnapshot(
            manifestId = root.optString("manifestId", "unknown").take(80),
            weather = weather,
            panels = panels,
            pollAfterSeconds = root.optInt("pollAfterSeconds", 300).coerceIn(60, 1800),
        )
    }

    private fun weatherCondition(value: String): WeatherCondition = when (value.lowercase()) {
        "clear" -> WeatherCondition.CLEAR
        "clouds" -> WeatherCondition.CLOUDS
        "rain" -> WeatherCondition.RAIN
        "snow" -> WeatherCondition.SNOW
        "fog" -> WeatherCondition.FOG
        "thunder" -> WeatherCondition.THUNDER
        else -> WeatherCondition.CLOUDS
    }

    private fun panelSlot(value: String): PanelSlot = when (value) {
        "top-left" -> PanelSlot.TOP_LEFT
        "bottom-left" -> PanelSlot.BOTTOM_LEFT
        "bottom-right" -> PanelSlot.BOTTOM_RIGHT
        else -> PanelSlot.TOP_RIGHT
    }

    companion object {
        private const val TAG = "SceneManifestClient"
        private const val SUPPORTED_SCHEMA = 1
        private const val MAX_PANELS = 4
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 6_000
    }
}
