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
import com.mawa.face.render.EyeView
import com.mawa.face.render.Mood
import com.mawa.face.update.Updater
import com.mawa.face.vision.FaceTracker
import com.mawa.face.vision.GazeMapper
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var eyeView: EyeView
    private lateinit var prefs: SharedPreferences
    private var tracker: FaceTracker? = null
    private val handler = Handler(Looper.getMainLooper())

    // Last raw face observation, used by long-press calibration
    private var lastRawX = 0.5f
    private var lastRawY = 0.5f
    private var lastFaceAtMs = 0L

    // 5 taps within 2 s toggles the debug/calibration overlay
    private var tapCount = 0
    private var firstTapAt = 0L

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startTracking()
        }

    private val updateCheck = object : Runnable {
        override fun run() {
            Updater.checkAsync(this@MainActivity) { status ->
                runOnUiThread { eyeView.debugText = status }
            }
            handler.postDelayed(this, UPDATE_CHECK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("mawa", MODE_PRIVATE)
        GazeMapper.load(prefs)

        // Wall-appliance behaviors
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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startTracking()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }

        handler.post(updateCheck)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startTracking() {
        tracker = FaceTracker(
            context = this,
            lifecycleOwner = this,
            onFace = { cx, cy, prox ->
                lastRawX = cx
                lastRawY = cy
                lastFaceAtMs = SystemClock.elapsedRealtime()
                val (gx, gy) = GazeMapper.map(cx, cy)
                eyeView.engine.onFace(gx, gy, prox)
                eyeView.debugText = String.format(
                    Locale.US,
                    "raw %.2f,%.2f  gaze %.2f,%.2f  prox %.3f  off %.2f,%.2f  v%d",
                    cx, cy, gx, gy, prox,
                    GazeMapper.offsetX, GazeMapper.offsetY,
                    BuildConfig.VERSION_CODE,
                )
            },
            onLost = { eyeView.engine.onFaceLost() },
        ).also { it.start() }
    }

    /**
     * One-touch calibration: stand where you normally are and long-press.
     * The current face position becomes "dead center" — the eyes look
     * straight at you from now on. Persisted across restarts.
     */
    private fun onCalibrateLongPress(): Boolean {
        val faceFresh = SystemClock.elapsedRealtime() - lastFaceAtMs < 2000
        if (!faceFresh) return false
        GazeMapper.calibrateTo(lastRawX, lastRawY, prefs)
        // A happy little acknowledgment
        eyeView.engine.mood = Mood.HAPPY
        handler.postDelayed({ eyeView.engine.mood = Mood.NEUTRAL }, 2500)
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
    }
}
