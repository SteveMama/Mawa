package com.mawa.face.net

import android.util.Log
import com.mawa.face.render.CloudAnimation
import com.mawa.face.render.CloudCompanionIntent
import com.mawa.face.render.CloudCompanionStance
import com.mawa.face.render.CloudGazeMode
import com.mawa.face.render.CloudPalette
import com.mawa.face.render.Mood
import com.mawa.face.scene.PanelSlot
import com.mawa.face.scene.ScenePanel
import com.mawa.face.weather.WeatherCondition
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class SceneSnapshot(
    val manifestId: String,
    val mood: Mood?,
    val animation: CloudAnimation?,
    val weather: WeatherCondition?,
    val companionLine: String?,
    val companionLineKey: String?,
    val companionSpeechStyle: String?,
    val companionStance: CloudCompanionStance,
    val companionIntent: CloudCompanionIntent,
    val companionAttention: String,
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

    data class PresenceSnapshot(
        val faceCount: Int = 0,
        val recognized: String = "none",
        val personLabel: String? = null,
        val proximity: Float = 0f,
        val covered: Boolean = false,
        val ambientDark: Boolean = false,
        val musicActive: Boolean = false,
        val groove: Float = 0f,
        val identityLock: Boolean = false,
        val following: Boolean = false,
        val musicTasteProfile: String? = null,
        val musicEnjoyment: Float = 0f,
        val musicAffinity: Float = 0f,
        val musicSteadiness: Float = 0f,
    )

    val enabled: Boolean get() = baseUrl.startsWith("https://") || baseUrl.startsWith("http://")

    fun fetch(
        latitude: Double,
        longitude: Double,
        appVersion: Int,
        presence: PresenceSnapshot = PresenceSnapshot(),
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
                val prox = String.format(Locale.US, "%.3f", presence.proximity.coerceIn(0f, 1f))
                val groove = String.format(Locale.US, "%.3f", presence.groove.coerceIn(0f, 1f))
                val personLabel = presence.personLabel?.take(40)?.let {
                    URLEncoder.encode(it, "UTF-8")
                } ?: ""
                val tasteProfile = presence.musicTasteProfile?.take(64)?.let {
                    URLEncoder.encode(it, "UTF-8")
                } ?: ""
                val url = URL(
                    "$baseUrl/api/manifest?lat=$lat&lon=$lon" +
                        "&device=oneplus-wall&version=$appVersion" +
                        "&faces=${presence.faceCount.coerceIn(0, 8)}" +
                        "&recognized=${presence.recognized.take(16)}" +
                        "&person=$personLabel" +
                        "&prox=$prox" +
                        "&covered=${if (presence.covered) 1 else 0}" +
                        "&dark=${if (presence.ambientDark) 1 else 0}" +
                        "&music=${if (presence.musicActive) 1 else 0}" +
                        "&groove=$groove" +
                        "&lock=${if (presence.identityLock) 1 else 0}" +
                        "&follow=${if (presence.following) 1 else 0}" +
                        "&taste=$tasteProfile" +
                        "&enjoy=${String.format(Locale.US, "%.3f", presence.musicEnjoyment.coerceIn(0f, 1f))}" +
                        "&affinity=${String.format(Locale.US, "%.3f", presence.musicAffinity.coerceIn(0f, 1f))}" +
                        "&steady=${String.format(Locale.US, "%.3f", presence.musicSteadiness.coerceIn(0f, 1f))}"
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
        val mood = scene.optString("mood", "neutral").let(::moodOf)
        val animation = scene.optJSONObject("animation")?.let(::animationOf)
        val weather = scene.optJSONObject("weather")
            ?.optString("condition")
            ?.let(::weatherCondition)
        val speech = scene.optJSONObject("companion")
            ?.optJSONObject("speech")
        val companion = scene.optJSONObject("companion")
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
            mood = mood,
            animation = animation,
            weather = weather,
            companionLine = speech?.optString("text")?.takeIf { !it.isNullOrBlank() }?.take(96),
            companionLineKey = speech?.optString("key")?.takeIf { !it.isNullOrBlank() }?.take(64),
            companionSpeechStyle = speech?.optString("style")?.takeIf { !it.isNullOrBlank() }?.take(20),
            companionStance = stanceOf(companion?.optString("stance", "watchful") ?: "watchful"),
            companionIntent = intentOf(companion?.optString("intent", "observe") ?: "observe"),
            companionAttention = companion?.optString("attention", "wandering")?.take(28) ?: "wandering",
            panels = panels,
            pollAfterSeconds = root.optInt("pollAfterSeconds", 300).coerceIn(60, 1800),
        )
    }

    private fun animationOf(value: JSONObject): CloudAnimation = CloudAnimation(
        palette = paletteOf(value.optString("palette", "cool")),
        gazeMode = gazeModeOf(value.optString("gazeMode", "curious")),
        energy = clamp(value.optDouble("energy", 0.0), 0.0, 1.0),
        expressiveness = clamp(value.optDouble("expressiveness", 0.0), 0.0, 1.0),
        aura = clamp(value.optDouble("aura", 0.0), 0.0, 1.0),
        bars = clamp(value.optDouble("bars", 0.0), 0.0, 1.0),
        glyphs = clamp(value.optDouble("glyphs", 0.0), 0.0, 1.0),
        sway = clamp(value.optDouble("sway", 0.0), 0.0, 1.0),
        bounce = clamp(value.optDouble("bounce", 0.0), 0.0, 1.0),
        blinkRate = clamp(value.optDouble("blinkRate", 1.0), 0.6, 1.8),
        openness = clamp(value.optDouble("openness", 1.0), 0.55, 1.15),
        pupilScale = clamp(value.optDouble("pupilScale", 1.0), 0.8, 1.45),
        squint = clamp(value.optDouble("squint", 0.0), 0.0, 1.0),
    )

    private fun weatherCondition(value: String): WeatherCondition = when (value.lowercase()) {
        "clear" -> WeatherCondition.CLEAR
        "clouds" -> WeatherCondition.CLOUDS
        "rain" -> WeatherCondition.RAIN
        "snow" -> WeatherCondition.SNOW
        "fog" -> WeatherCondition.FOG
        "thunder" -> WeatherCondition.THUNDER
        else -> WeatherCondition.CLOUDS
    }

    private fun paletteOf(value: String): CloudPalette = when (value.lowercase()) {
        "warm" -> CloudPalette.WARM
        "violet" -> CloudPalette.VIOLET
        "teal" -> CloudPalette.TEAL
        "dusk" -> CloudPalette.DUSK
        else -> CloudPalette.COOL
    }

    private fun gazeModeOf(value: String): CloudGazeMode = when (value.lowercase()) {
        "steady" -> CloudGazeMode.STEADY
        "dart" -> CloudGazeMode.DART
        "locked" -> CloudGazeMode.LOCKED
        "dreamy" -> CloudGazeMode.DREAMY
        else -> CloudGazeMode.CURIOUS
    }

    private fun stanceOf(value: String): CloudCompanionStance = when (value.lowercase()) {
        "dry" -> CloudCompanionStance.DRY
        "warm" -> CloudCompanionStance.WARM
        "playful" -> CloudCompanionStance.PLAYFUL
        "protective" -> CloudCompanionStance.PROTECTIVE
        "amused" -> CloudCompanionStance.AMUSED
        "tender" -> CloudCompanionStance.TENDER
        "braced" -> CloudCompanionStance.BRACED
        else -> CloudCompanionStance.WATCHFUL
    }

    private fun intentOf(value: String): CloudCompanionIntent = when (value.lowercase()) {
        "welcome" -> CloudCompanionIntent.WELCOME
        "guard" -> CloudCompanionIntent.GUARD
        "tease" -> CloudCompanionIntent.TEASE
        "comfort" -> CloudCompanionIntent.COMFORT
        "admire_music" -> CloudCompanionIntent.ADMIRE_MUSIC
        "study" -> CloudCompanionIntent.STUDY
        "rest" -> CloudCompanionIntent.REST
        else -> CloudCompanionIntent.OBSERVE
    }

    private fun panelSlot(value: String): PanelSlot = when (value) {
        "top-left" -> PanelSlot.TOP_LEFT
        "bottom-left" -> PanelSlot.BOTTOM_LEFT
        "bottom-right" -> PanelSlot.BOTTOM_RIGHT
        else -> PanelSlot.TOP_RIGHT
    }

    private fun moodOf(value: String): Mood? = when (value.lowercase()) {
        "happy" -> Mood.HAPPY
        "grumpy" -> Mood.GRUMPY
        "sleepy" -> Mood.SLEEPY
        "suspicious" -> Mood.SUSPICIOUS
        "excited" -> Mood.EXCITED
        else -> Mood.NEUTRAL
    }

    private fun clamp(value: Double, min: Double, max: Double): Float =
        value.coerceIn(min, max).toFloat()

    companion object {
        private const val TAG = "SceneManifestClient"
        private const val SUPPORTED_SCHEMA = 1
        private const val MAX_PANELS = 4
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 6_000
    }
}
