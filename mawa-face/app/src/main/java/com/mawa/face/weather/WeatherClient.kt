package com.mawa.face.weather

import android.util.Log
import org.json.JSONObject
import java.net.URL

enum class WeatherCondition { CLEAR, CLOUDS, RAIN, SNOW, FOG, THUNDER }

/**
 * Current conditions from Open-Meteo — free, keyless, no account. Given a
 * lat/long it returns a WMO weather code we map to a small condition enum
 * the renderer animates. Runs off the main thread; failures are silent
 * (Mawa just keeps its last sky).
 */
object WeatherClient {
    private const val TAG = "WeatherClient"

    fun fetch(lat: Double, lon: Double, onResult: (WeatherCondition, Boolean) -> Unit) {
        Thread {
            try {
                val url =
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,weather_code,is_day"
                val json = URL(url).readText()
                val cur = JSONObject(json).getJSONObject("current")
                val code = cur.getInt("weather_code")
                val isDay = cur.optInt("is_day", 1) == 1
                onResult(codeToCondition(code), isDay)
            } catch (e: Exception) {
                Log.w(TAG, "weather fetch failed", e)
            }
        }.start()
    }

    // WMO weather interpretation codes
    private fun codeToCondition(code: Int): WeatherCondition = when (code) {
        0, 1 -> WeatherCondition.CLEAR
        2, 3 -> WeatherCondition.CLOUDS
        45, 48 -> WeatherCondition.FOG
        in 51..67 -> WeatherCondition.RAIN
        in 71..77 -> WeatherCondition.SNOW
        in 80..82 -> WeatherCondition.RAIN
        in 85..86 -> WeatherCondition.SNOW
        in 95..99 -> WeatherCondition.THUNDER
        else -> WeatherCondition.CLOUDS
    }
}
