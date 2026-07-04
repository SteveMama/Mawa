package com.mawa.face

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.mawa.face.render.EyeView
import com.mawa.face.vision.FaceTracker
import com.mawa.face.vision.GazeMapper
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var eyeView: EyeView
    private var tracker: FaceTracker? = null

    // 5 taps within 2 s toggles the debug/calibration overlay
    private var tapCount = 0
    private var firstTapAt = 0L

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startTracking()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wall-appliance behaviors
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        enterImmersiveMode()

        eyeView = EyeView(this)
        eyeView.setOnClickListener { onScreenTap() }
        setContentView(eyeView)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startTracking()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startTracking() {
        tracker = FaceTracker(
            context = this,
            lifecycleOwner = this,
            onFace = { cx, cy, prox ->
                val (gx, gy) = GazeMapper.map(cx, cy)
                eyeView.engine.onFace(gx, gy, prox)
                eyeView.debugText = String.format(
                    Locale.US,
                    "raw %.2f,%.2f  gaze %.2f,%.2f  prox %.3f",
                    cx, cy, gx, gy, prox,
                )
            },
            onLost = { eyeView.engine.onFaceLost() },
        ).also { it.start() }
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
}
