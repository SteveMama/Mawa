package com.mawa.face

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.mawa.face.audio.Speech
import com.mawa.face.render.EyeView
import com.mawa.face.render.Gesture
import com.mawa.face.render.Mood
import com.mawa.face.sensing.LightSensor
import com.mawa.face.update.Updater
import com.mawa.face.util.LocationHelper
import com.mawa.face.util.TimeOfDay
import com.mawa.face.vision.FaceTracker
import com.mawa.face.vision.GazeMapper
import com.mawa.face.weather.WeatherClient
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var eyeView: EyeView
    private lateinit var prefs: SharedPreferences
    private lateinit var speech: Speech
    private var tracker: FaceTracker? = null
    private var lightSensor: LightSensor? = null
    private val handler = Handler(Looper.getMainLooper())

    // Last raw face observation, used by long-press calibration
    private var lastRawX = 0.5f
    private var lastRawY = 0.5f
    private var lastFaceAtMs = 0L

    // Overlay status + new-person greeting
    private var camStatus = "starting..."
    private var faceLine = ""
    private var prevFaceCount = 0
    private var newPersonMutedUntil = 0L

    // Ambient sensing
    private var latestLux = 100f
    private var awaySinceMs = SystemClock.elapsedRealtime()
    private var greetedThisVisit = false

    // Blink-back edge detection
    private var prevEyeOpen = 1f
    private var lastBlinkBackAt = 0L

    // 5 taps within 2 s toggles the calibration overlay
    private var tapCount = 0
    private var firstTapAt = 0L

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) startTracking()
            refreshWeather()
        }

    private val updateCheck = object : Runnable {
        override fun run() {
            Updater.checkAsync(this@MainActivity) { status ->
                runOnUiThread { camStatus = status; refreshOverlay() }
            }
            handler.postDelayed(this, UPDATE_CHECK_MS)
        }
    }

    private val ambientTick = object : Runnable {
        override fun run() {
            // Time-of-day: warm the screen at golden hour, drowsy mood at night
            eyeView.warmth = TimeOfDay.warmth()
            if (TimeOfDay.isNight()) {
                if (eyeView.engine.mood == Mood.NEUTRAL) eyeView.engine.mood = Mood.SLEEPY
            } else if (eyeView.engine.mood == Mood.SLEEPY) {
                eyeView.engine.mood = Mood.NEUTRAL
            }
            handler.postDelayed(this, 60_000)
        }
    }

    private val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather()
            handler.postDelayed(this, 15 * 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("mawa", MODE_PRIVATE)
        GazeMapper.load(prefs)
        speech = Speech(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        enterImmersiveMode()

        eyeView = EyeView(this)
        eyeView.setOnClickListener { onScreenTap() }
        eyeView.setOnLongClickListener { onCalibrateLongPress() }
        setContentView(eyeView)

        lightSensor = LightSensor(this) { lux ->
            latestLux = lux
            // Lights off -> sleep (with ZZZ). Sustained by the sensor itself.
            eyeView.engine.ambientDark = lux < DARK_LUX
        }.also { it.start() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startTracking()
        }
        permissions.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_COARSE_LOCATION)
        )

        handler.post(updateCheck)
        handler.post(ambientTick)
        handler.post(weatherTick)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        lightSensor?.stop()
        speech.shutdown()
        super.onDestroy()
    }

    private fun refreshWeather() {
        val (lat, lon) = LocationHelper.lastKnown(this)
        WeatherClient.fetch(lat, lon) { condition, _ ->
            runOnUiThread { eyeView.weather = condition }
        }
    }

    private fun startTracking() {
        if (tracker != null) return
        tracker = FaceTracker(
            context = this,
            lifecycleOwner = this,
            onFace = { cx, cy, prox, count, eyeOpen ->
                lastRawX = cx
                lastRawY = cy
                lastFaceAtMs = SystemClock.elapsedRealtime()
                eyeView.rawFace = Pair(cx, cy)
                val (gx, gy) = GazeMapper.map(cx, cy)
                eyeView.engine.onFace(gx, gy, prox)
                greetOnArrival()
                greetNewPerson(count)
                blinkBack(eyeOpen)
                faceLine = String.format(
                    Locale.US,
                    "raw %.2f,%.2f  gaze %.2f,%.2f  prox %.3f  lux %.0f  faces %d  v%d",
                    cx, cy, gx, gy, prox, latestLux, count, BuildConfig.VERSION_CODE,
                )
                refreshOverlay()
            },
            onLost = {
                eyeView.rawFace = null
                eyeView.engine.onFaceLost()
                prevFaceCount = 0
                if (greetedThisVisit) awaySinceMs = SystemClock.elapsedRealtime()
                greetedThisVisit = false
                faceLine = "no face in view"
                refreshOverlay()
            },
            onStatus = { status ->
                camStatus = status
                runOnUiThread { refreshOverlay() }
            },
            onLuma = { luma ->
                // Lens covered = dark frame while the room is still lit -> polite DND
                eyeView.engine.covered = luma < COVERED_LUMA && latestLux > DARK_LUX
            },
        ).also { it.start() }
    }

    private fun refreshOverlay() {
        eyeView.debugText = camStatus + "\n" + faceLine
    }

    /** Spoken greeting when you return after being away a while (once per visit). */
    private fun greetOnArrival() {
        if (greetedThisVisit || eyeView.engine.ambientDark) return
        val now = SystemClock.elapsedRealtime()
        if (now - awaySinceMs > GREET_GAP_MS) {
            greetedThisVisit = true
            handler.postDelayed({ speech.say(TimeOfDay.greeting()) }, 500)
        } else {
            greetedThisVisit = true  // seen continuously; don't greet, but mark visit
        }
    }

    /** A second face joins -> "Hey! New person!" (60 s cooldown). */
    private fun greetNewPerson(count: Int) {
        val now = SystemClock.elapsedRealtime()
        if (count > prevFaceCount && count >= 2 && now > newPersonMutedUntil) {
            newPersonMutedUntil = now + 60_000
            speech.say("Hey! New person!")
        }
        prevFaceCount = count
    }

    /** You blink -> Mawa blinks back (sometimes). */
    private fun blinkBack(eyeOpen: Float) {
        val now = SystemClock.elapsedRealtime()
        if (prevEyeOpen > 0.65f && eyeOpen < 0.25f &&
            now - lastBlinkBackAt > 1500 && Math.random() < 0.6
        ) {
            lastBlinkBackAt = now
            eyeView.engine.play(Gesture.BLINK)
        }
        prevEyeOpen = eyeOpen
    }

    /**
     * One-touch calibration: stand where you normally are and long-press.
     * The current face position becomes "dead center."
     */
    private fun onCalibrateLongPress(): Boolean {
        val faceFresh = SystemClock.elapsedRealtime() - lastFaceAtMs < 2000
        if (!faceFresh) return false
        GazeMapper.calibrateTo(lastRawX, lastRawY, prefs)
        eyeView.engine.play(Gesture.LOCK_ON)
        handler.postDelayed({ speech.say("Found you, Pranav.") }, 700)
        return true
    }

    private fun onScreenTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - firstTapAt > 2000) {
            firstTapAt = now
            tapCount = 0
        }
        if (++tapCount >= 5) {
            eyeView.debug = !eyeView.debug
            tapCount = 0
        }
    }

    private fun enterImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    companion object {
        private const val UPDATE_CHECK_MS = 15 * 60 * 1000L
        private const val GREET_GAP_MS = 30 * 60 * 1000L
        private const val DARK_LUX = 6f
        private const val COVERED_LUMA = 12f
    }
}
