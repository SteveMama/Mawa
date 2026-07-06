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
import com.mawa.face.audio.BeatDetector
import com.mawa.face.net.SceneManifestClient
import com.mawa.face.render.EyeView
import com.mawa.face.render.Gesture
import com.mawa.face.render.Mood
import com.mawa.face.sensing.LightSensor
import com.mawa.face.update.Updater
import com.mawa.face.util.LocationHelper
import com.mawa.face.util.TimeOfDay
import com.mawa.face.vision.FaceRecognizer
import com.mawa.face.vision.FaceTracker
import com.mawa.face.vision.GazeMapper
import com.mawa.face.weather.WeatherClient
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var eyeView: EyeView
    private lateinit var prefs: SharedPreferences
    private lateinit var speech: Speech
    private var beatDetector: BeatDetector? = null
    private var tracker: FaceTracker? = null
    private var lightSensor: LightSensor? = null
    private var recognizer: FaceRecognizer? = null
    private val manifestClient = SceneManifestClient(
        BuildConfig.BRAIN_BASE_URL,
        BuildConfig.DEVICE_TOKEN,
    )
    private val handler = Handler(Looper.getMainLooper())

    // Face recognition (dormant until a model is bundled)
    private var enrolledEmbedding: FloatArray? = null
    private var lastEmbedding: FloatArray? = null
    private var recognizedIsMe = false
    private var recognitionScore: Float? = null

    // Last raw face observation, used by long-press calibration
    private var lastRawX = 0.5f
    private var lastRawY = 0.5f
    private var lastFaceAtMs = 0L

    // Overlay status + new-person greeting
    private var camStatus = "starting..."
    private var brainStatus = "brain: starting..."
    private var beatStatus = "beat: starting..."
    private var sceneRequestRunning = false
    private var faceLine = ""
    private var prevFaceCount = 0
    private var newPersonMutedUntil = 0L

    // Ambient sensing
    private var latestLux = 100f
    private var awaySinceMs = SystemClock.elapsedRealtime()
    private var greetedThisVisit = false
    private var identityLockEnabled = false
    private var identityAcquireUntilMs = 0L
    private var lastRecognizedMeAtMs = 0L

    // Blink-back edge detection
    private var prevEyeOpen = 1f
    private var lastBlinkBackAt = 0L

    // 5 taps within 2 s toggles the calibration overlay
    private var tapCount = 0
    private var firstTapAt = 0L
    private var lastTapAt = 0L
    private var scenePollMs = SCENE_CHECK_MS

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) startTracking()
            if (result[Manifest.permission.RECORD_AUDIO] == true) {
                startBeatDetection()
            } else if (result.containsKey(Manifest.permission.RECORD_AUDIO)) {
                beatStatus = "beat: disabled (microphone permission denied)"
            }
            refreshScene()
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
            // Darkness is a sleep cue only after 10 PM. Re-evaluate on the
            // clock tick so crossing 22:00 works without a new sensor event.
            eyeView.engine.ambientDark = TimeOfDay.isNight() && latestLux < DARK_LUX
            if (TimeOfDay.isNight()) {
                if (eyeView.engine.mood == Mood.NEUTRAL) eyeView.engine.mood = Mood.SLEEPY
            } else if (eyeView.engine.mood == Mood.SLEEPY) {
                eyeView.engine.mood = Mood.NEUTRAL
            }
            handler.postDelayed(this, 60_000)
        }
    }

    private val sceneTick = object : Runnable {
        override fun run() {
            refreshScene()
            handler.postDelayed(this, scenePollMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("mawa", MODE_PRIVATE)
        GazeMapper.load(prefs)
        speech = Speech(this)
        recognizer = FaceRecognizer(this)
        enrolledEmbedding = loadEmbedding()
        identityLockEnabled = prefs.getBoolean("identity_lock_enabled", enrolledEmbedding != null)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        enterImmersiveMode()

        eyeView = EyeView(this)
        eyeView.engine.identityLockEnabled = identityLockEnabled
        eyeView.setOnClickListener { onScreenTap() }
        eyeView.setOnLongClickListener { onCalibrateLongPress() }
        setContentView(eyeView)

        lightSensor = LightSensor(this) { lux ->
            latestLux = lux
            // During the day, darkness never forces sleep.
            eyeView.engine.ambientDark = TimeOfDay.isNight() && lux < DARK_LUX
        }.also { it.start() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startTracking()
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startBeatDetection()
        }
        permissions.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.RECORD_AUDIO,
            )
        )

        handler.post(updateCheck)
        handler.post(ambientTick)
        handler.post(sceneTick)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        lightSensor?.stop()
        beatDetector?.stop()
        speech.shutdown()
        super.onDestroy()
    }

    private fun refreshScene() {
        if (sceneRequestRunning) return
        sceneRequestRunning = true
        val (lat, lon) = LocationHelper.lastKnown(this)
        manifestClient.fetch(lat, lon, BuildConfig.VERSION_CODE) { result ->
            runOnUiThread {
                sceneRequestRunning = false
                result.onSuccess { snapshot ->
                    snapshot.weather?.let { eyeView.weather = it }
                    eyeView.scenePanels = snapshot.panels
                    eyeView.cloudAnimation = snapshot.animation
                    eyeView.engine.cloudMood = snapshot.mood
                    scenePollMs = (snapshot.pollAfterSeconds * 1000L).coerceIn(60_000L, 10 * 60_000L)
                    brainStatus = "brain: online  ${snapshot.manifestId}"
                }.onFailure { error ->
                    eyeView.cloudAnimation = null
                    eyeView.engine.cloudMood = null
                    scenePollMs = SCENE_CHECK_MS
                    brainStatus = "brain: offline (${error.message ?: "unavailable"})"
                    refreshLocalWeather(lat, lon)
                }
                refreshOverlay()
            }
        }
    }

    private fun refreshLocalWeather(lat: Double, lon: Double) {
        WeatherClient.fetch(lat, lon) { condition, _ ->
            runOnUiThread { eyeView.weather = condition }
        }
    }

    private fun startBeatDetection() {
        if (beatDetector != null) return
        beatDetector = BeatDetector(
            context = this,
            onBeat = { strength -> eyeView.engine.onBeat(strength) },
            onStatus = { status ->
                runOnUiThread {
                    beatStatus = status
                    refreshOverlay()
                }
            },
        ).also { it.start() }
    }

    private fun startTracking() {
        if (tracker != null) return
        tracker = FaceTracker(
            context = this,
            lifecycleOwner = this,
            onFace = { cx, cy, prox, count, eyeOpen ->
                val now = SystemClock.elapsedRealtime()
                lastRawX = cx
                lastRawY = cy
                lastFaceAtMs = now
                eyeView.rawFace = Pair(cx, cy)
                val (gx, gy) = GazeMapper.map(cx, cy)
                if (count > prevFaceCount && count > 0 && identityLockActive()) {
                    identityAcquireUntilMs = now + IDENTITY_ACQUIRE_MS
                }
                val following = shouldFollowCurrentFace(now)
                if (following) {
                    eyeView.engine.onFace(gx, gy, prox)
                    greetOnArrival()
                } else {
                    eyeView.engine.onIgnoredFace(prox)
                }
                greetNewPerson(count)
                blinkBack(eyeOpen)
                faceLine = String.format(
                    Locale.US,
                    "raw %.2f,%.2f  gaze %.2f,%.2f  prox %.3f  lux %.0f  faces %d  v%d%s  %s",
                    cx, cy, gx, gy, prox, latestLux, count, BuildConfig.VERSION_CODE, recognitionSummary(),
                    lockSummary(following, now),
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
            onFaceCrop = { bmp ->
                val emb = recognizer?.embed(bmp) ?: return@FaceTracker
                lastEmbedding = emb
                val enrolled = enrolledEmbedding
                recognitionScore = enrolled?.let { FaceRecognizer.cosine(emb, it) }
                recognizedIsMe = recognitionScore?.let { it > FaceRecognizer.THRESHOLD } == true
                if (recognizedIsMe) lastRecognizedMeAtMs = SystemClock.elapsedRealtime()
            },
        ).also {
            it.recognitionEnabled = recognizer?.enabled == true
            it.start()
        }
    }

    // --- enrolled-embedding persistence (comma-separated floats) ----------
    private fun loadEmbedding(): FloatArray? =
        prefs.getString("face_embedding", null)
            ?.split(",")?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()
            ?.takeIf { it.isNotEmpty() }

    private fun saveEmbedding(e: FloatArray) {
        prefs.edit().putString("face_embedding", e.joinToString(",")).apply()
        enrolledEmbedding = e
    }

    private fun refreshOverlay() {
        eyeView.debugText = camStatus + "\n" + brainStatus + "\n" + beatStatus + "\n" + faceLine
    }

    private fun recognitionSummary(): String {
        if (recognizer?.enabled != true) return "  rec:model-off"
        if (enrolledEmbedding == null) return "  rec:not-enrolled"
        val score = recognitionScore ?: return "  rec:waiting"
        val identity = if (recognizedIsMe) "ME" else "OTHER"
        return String.format(
            Locale.US,
            "  rec:%.3f/%s (cut %.2f)",
            score,
            identity,
            FaceRecognizer.THRESHOLD,
        )
    }

    /**
     * Spoken greeting when you return after being away a while (once per visit).
     * If recognition is active and enrolled, only greets you by name when it's
     * actually you; otherwise falls back to greeting anyone (recognition off).
     */
    private fun greetOnArrival() {
        if (greetedThisVisit || eyeView.engine.ambientDark) return
        val recognitionActive = identityLockActive()
        if (recognitionActive && !recognizedIsMe) return  // a face, but not you — wait
        val now = SystemClock.elapsedRealtime()
        greetedThisVisit = true
        if (now - awaySinceMs > GREET_GAP_MS) {
            handler.postDelayed({ speech.say(TimeOfDay.greeting()) }, 500)
        }
    }

    /** A second face joins -> "Hey! New person!" (60 s cooldown). */
    private fun greetNewPerson(count: Int) {
        if (identityLockActive() && !recognizedIsMe) {
            prevFaceCount = count
            return
        }
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
        // Long-press does double duty: calibrate gaze AND enroll your face
        // (when a recognition model is present), so "you" is learned from the
        // same spot you calibrated from.
        lastEmbedding?.let {
            saveEmbedding(it)
            recognizedIsMe = true
            lastRecognizedMeAtMs = SystemClock.elapsedRealtime()
            identityLockEnabled = true
            prefs.edit().putBoolean("identity_lock_enabled", true).apply()
            eyeView.engine.identityLockEnabled = true
        }
        eyeView.engine.play(Gesture.LOCK_ON)
        handler.postDelayed({ speech.say("Found you, Pranav.") }, 700)
        return true
    }

    private fun onScreenTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTapAt <= DOUBLE_TAP_MS) {
            lastTapAt = 0L
            tapCount = 0
            toggleIdentityLock()
            return
        }
        lastTapAt = now
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

    private fun identityLockActive(): Boolean =
        identityLockEnabled && recognizer?.enabled == true && enrolledEmbedding != null

    private fun shouldFollowCurrentFace(now: Long): Boolean {
        if (!identityLockActive()) return true
        if (recognizedIsMe) {
            lastRecognizedMeAtMs = now
            return true
        }
        if (now - lastRecognizedMeAtMs <= IDENTITY_HOLD_MS) return true
        return now <= identityAcquireUntilMs
    }

    private fun lockSummary(following: Boolean, now: Long): String = when {
        !identityLockEnabled -> "lock:relaxed"
        !identityLockActive() -> "lock:needs-enroll"
        recognizedIsMe -> "lock:me"
        now <= identityAcquireUntilMs -> "lock:checking"
        following -> "lock:holding"
        else -> "lock:ignoring"
    }

    private fun toggleIdentityLock() {
        if (enrolledEmbedding == null || recognizer?.enabled != true) {
            speech.say("Enroll your face first.")
            return
        }
        identityLockEnabled = !identityLockEnabled
        eyeView.engine.identityLockEnabled = identityLockEnabled
        prefs.edit().putBoolean("identity_lock_enabled", identityLockEnabled).apply()
        if (identityLockEnabled) {
            identityAcquireUntilMs = SystemClock.elapsedRealtime() + IDENTITY_ACQUIRE_MS
            speech.say("Locked on you.")
        } else {
            speech.say("Relaxing now.")
        }
        refreshOverlay()
    }

    companion object {
        private const val UPDATE_CHECK_MS = 15 * 60 * 1000L
        private const val SCENE_CHECK_MS = 5 * 60 * 1000L
        private const val GREET_GAP_MS = 30 * 60 * 1000L
        private const val DARK_LUX = 6f
        private const val COVERED_LUMA = 12f
        private const val DOUBLE_TAP_MS = 320L
        private const val IDENTITY_ACQUIRE_MS = 2_500L
        private const val IDENTITY_HOLD_MS = 4_000L
    }
}
