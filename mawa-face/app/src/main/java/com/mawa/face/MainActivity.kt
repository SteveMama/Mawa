package com.mawa.face

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.mawa.face.audio.MusicTasteProfile
import com.mawa.face.audio.Speech
import com.mawa.face.audio.BeatDetector
import com.mawa.face.net.LiveTelemetryClient
import com.mawa.face.net.SceneManifestClient
import com.mawa.face.render.EyeView
import com.mawa.face.render.Gesture
import com.mawa.face.render.Mood
import com.mawa.face.sensing.LightSensor
import com.mawa.face.update.Updater
import com.mawa.face.util.LocationHelper
import com.mawa.face.util.TimeOfDay
import com.mawa.face.vision.FaceGallery
import com.mawa.face.vision.FaceRecognizer
import com.mawa.face.vision.FaceTracker
import com.mawa.face.vision.GazeMapper
import com.mawa.face.weather.WeatherClient
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class MainActivity : ComponentActivity() {

    private lateinit var eyeView: EyeView
    private lateinit var prefs: SharedPreferences
    private lateinit var faceGallery: FaceGallery
    private lateinit var musicTaste: MusicTasteProfile
    private lateinit var speech: Speech
    private var beatDetector: BeatDetector? = null
    private var tracker: FaceTracker? = null
    private var lightSensor: LightSensor? = null
    private var recognizer: FaceRecognizer? = null
    private val manifestClient = SceneManifestClient(
        BuildConfig.BRAIN_BASE_URL,
        BuildConfig.DEVICE_TOKEN,
    )
    private val telemetryClient = LiveTelemetryClient(
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
    private var latestFaceCount = 0
    private var latestProx = 0f
    private var latestObservedPersonId: String? = null
    private var latestObservedPersonLabel: String? = null
    private var latestObservedPersonSimilarity: Float? = null
    private var latestTasteSnapshot = MusicTasteProfile.Snapshot()
    private var currentManifestId: String? = null
    private var currentThoughtEyebrow: String? = null
    private var currentThoughtTitle: String? = null
    private var currentThoughtDetail: String? = null
    private var currentThoughtAccent: String? = null
    private var lastCompanionLineKey: String? = null
    private var lastCompanionSpeechAtMs = 0L

    // Blink-back edge detection
    private var prevEyeOpen = 1f
    private var lastBlinkBackAt = 0L

    // 5 taps within 2 s toggles the calibration overlay
    private var tapCount = 0
    private var firstTapAt = 0L
    private var lastTapAt = 0L
    private var scenePollMs = SCENE_CHECK_MS
    private var micPermissionRequestedAtLeastOnce = false

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) startTracking()
            refreshScene()
            refreshOverlay()
        }

    private val micPermission =
        registerForActivityResult(RequestPermission()) { granted ->
            micPermissionRequestedAtLeastOnce = true
            if (granted) {
                beatStatus = "beat: microphone granted"
                startBeatDetection()
            } else {
                beatStatus =
                    if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                        "beat: microphone denied — tap to retry"
                    } else if (micPermissionRequestedAtLeastOnce) {
                        "beat: microphone blocked — enable in app settings"
                    } else {
                        "beat: microphone permission needed"
                    }
            }
            refreshOverlay()
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

    private val telemetryTick = object : Runnable {
        override fun run() {
            publishTelemetry()
            handler.postDelayed(this, TELEMETRY_PUSH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("mawa", MODE_PRIVATE)
        faceGallery = FaceGallery(prefs)
        musicTaste = MusicTasteProfile(prefs)
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
        permissions.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
        requestMicrophonePermissionIfNeeded(force = false)

        handler.post(updateCheck)
        handler.post(ambientTick)
        handler.post(sceneTick)
        handler.post(telemetryTick)
    }

    override fun onResume() {
        super.onResume()
        requestMicrophonePermissionIfNeeded(force = false)
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
        manifestClient.fetch(lat, lon, BuildConfig.VERSION_CODE, currentPresenceSnapshot()) { result ->
            runOnUiThread {
                sceneRequestRunning = false
                result.onSuccess { snapshot ->
                    snapshot.weather?.let { eyeView.weather = it }
                    eyeView.scenePanels = snapshot.panels
                    eyeView.cloudAnimation = snapshot.animation
                    eyeView.engine.cloudMood = snapshot.mood
                    currentManifestId = snapshot.manifestId
                    snapshot.panels.firstOrNull { it.id == "mawa-thought" }?.let { panel ->
                        currentThoughtEyebrow = panel.eyebrow
                        currentThoughtTitle = panel.title
                        currentThoughtDetail = panel.detail
                        currentThoughtAccent = panel.accent
                    } ?: run {
                        currentThoughtEyebrow = null
                        currentThoughtTitle = null
                        currentThoughtDetail = null
                        currentThoughtAccent = null
                    }
                    maybeSpeakCompanionLine(snapshot.companionLine, snapshot.companionLineKey, snapshot.companionSpeechStyle)
                    scenePollMs = (snapshot.pollAfterSeconds * 1000L).coerceIn(60_000L, 10 * 60_000L)
                    brainStatus = "brain: online  ${snapshot.manifestId}"
                }.onFailure { error ->
                    eyeView.cloudAnimation = null
                    eyeView.engine.cloudMood = null
                    currentManifestId = null
                    currentThoughtEyebrow = null
                    currentThoughtTitle = null
                    currentThoughtDetail = null
                    currentThoughtAccent = null
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

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestMicrophonePermissionIfNeeded(force: Boolean) {
        if (hasMicrophonePermission()) {
            startBeatDetection()
            return
        }
        beatDetector?.stop()
        beatDetector = null
        beatStatus =
            if (micPermissionRequestedAtLeastOnce &&
                !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            ) {
                "beat: microphone blocked — enable in app settings"
            } else {
                "beat: microphone permission needed"
            }
        refreshOverlay()

        if (micPermissionRequestedAtLeastOnce &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        ) {
            if (force) openAppSettings()
            return
        }

        if (force || !micPermissionRequestedAtLeastOnce) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
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
                latestFaceCount = count
                latestProx = prox
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
                latestFaceCount = 0
                latestProx = 0f
                latestObservedPersonId = null
                latestObservedPersonLabel = null
                latestObservedPersonSimilarity = null
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
                val now = SystemClock.elapsedRealtime()
                if (recognizedIsMe) lastRecognizedMeAtMs = now
                val galleryMatch = faceGallery.observe(emb, now)
                latestObservedPersonId = galleryMatch.identity.id
                latestObservedPersonLabel = galleryMatch.identity.label
                latestObservedPersonSimilarity = galleryMatch.similarity
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

    private fun currentPresenceSnapshot(): SceneManifestClient.PresenceSnapshot {
        val now = SystemClock.elapsedRealtime()
        val recognized = when {
            latestFaceCount <= 0 -> "none"
            recognizedIsMe -> "me"
            identityLockActive() -> "other"
            else -> "unknown"
        }
        val following = latestFaceCount > 0 && shouldFollowCurrentFace(now)
        val groove = beatDetector?.let {
            eyeView.engine.musicLevel().coerceIn(0f, 1f)
        } ?: 0f
        latestTasteSnapshot = musicTaste.observe(groove, now)
        return SceneManifestClient.PresenceSnapshot(
            faceCount = latestFaceCount,
            recognized = recognized,
            personLabel = latestObservedPersonLabel,
            proximity = latestProx,
            covered = eyeView.engine.covered,
            ambientDark = eyeView.engine.ambientDark,
            musicActive = groove >= 0.18f,
            groove = groove,
            identityLock = identityLockActive(),
            following = following,
            musicTasteProfile = latestTasteSnapshot.profileLabel,
            musicEnjoyment = latestTasteSnapshot.enjoyment,
            musicAffinity = latestTasteSnapshot.affinity,
            musicSteadiness = latestTasteSnapshot.steadiness,
        )
    }

    private fun publishTelemetry() {
        if (!telemetryClient.enabled) return
        val presence = currentPresenceSnapshot()
        val currentMood = eyeView.engine.currentMood()
        val groove = eyeView.engine.musicLevel().coerceIn(0f, 1f)
        val attention = when {
            eyeView.engine.covered -> "covered"
            eyeView.engine.isSleeping() -> "sleeping"
            presence.identityLock && recognizedIsMe && presence.following -> "locked-on-you"
            presence.identityLock && latestFaceCount > 0 && !presence.following -> "guarded"
            latestFaceCount > 0 && presence.following -> "engaged"
            latestFaceCount > 0 -> "checking"
            else -> "wandering"
        }

        telemetryClient.publish(
            LiveTelemetryClient.TelemetrySnapshot(
                deviceId = "oneplus-wall",
                appVersion = BuildConfig.VERSION_CODE.toString(),
                manifestId = currentManifestId,
                capturedAt = isoTimestamp(),
                thought = if (!currentThoughtTitle.isNullOrBlank()) {
                    LiveTelemetryClient.ThoughtSnapshot(
                        eyebrow = currentThoughtEyebrow ?: "MAWA",
                        title = currentThoughtTitle ?: "Quiet orbit",
                        detail = currentThoughtDetail ?: "",
                        accent = currentThoughtAccent ?: "#8FA6C0",
                    )
                } else {
                    null
                },
                feeling = LiveTelemetryClient.FeelingSnapshot(
                    mood = currentMood,
                    summary = feelingSummary(currentMood, attention, groove, latestTasteSnapshot),
                    attention = attention,
                    sleeping = eyeView.engine.isSleeping(),
                    covered = eyeView.engine.covered,
                    ambientDark = eyeView.engine.ambientDark,
                    energy = eyeView.engine.visualEnergy().coerceIn(0f, 1f),
                    expressiveness = eyeView.engine.expressivenessLevel().coerceIn(0f, 1f),
                ),
                presence = LiveTelemetryClient.PresenceSnapshot(
                    faceCount = presence.faceCount,
                    recognized = presence.recognized,
                    personLabel = presence.personLabel,
                    proximity = presence.proximity,
                    identityLock = presence.identityLock,
                    following = presence.following,
                ),
                music = LiveTelemetryClient.MusicSnapshot(
                    active = presence.musicActive,
                    groove = groove,
                    tasteProfile = latestTasteSnapshot.profileLabel,
                    stance = latestTasteSnapshot.stance,
                    enjoyment = latestTasteSnapshot.enjoyment,
                    affinity = latestTasteSnapshot.affinity,
                    preferredIntensity = latestTasteSnapshot.preferredIntensity,
                    steadiness = latestTasteSnapshot.steadiness,
                    lateNightBias = latestTasteSnapshot.lateNightBias,
                    sessionCount = latestTasteSnapshot.sessionCount,
                    beatStatus = beatStatus,
                ),
                status = LiveTelemetryClient.StatusSnapshot(
                    camera = camStatus,
                    brain = brainStatus,
                    beat = beatStatus,
                    face = faceLine,
                ),
            )
        )
    }

    private fun feelingSummary(
        mood: Mood,
        attention: String,
        groove: Float,
        taste: MusicTasteProfile.Snapshot,
    ): String = when {
        attention == "covered" -> "Eyes closed politely."
        attention == "sleeping" -> "Dim and drifting."
        groove >= 0.22f && taste.enjoyment >= 0.76f -> "Caught in the groove."
        groove >= 0.22f && taste.enjoyment >= 0.58f -> "Leaning into the music."
        attention == "locked-on-you" -> "Locked on you and steady."
        attention == "guarded" -> "Present, but keeping some distance."
        mood == Mood.SUSPICIOUS -> "Something about the room feels off."
        mood == Mood.EXCITED -> "The room feels charged."
        mood == Mood.SLEEPY -> "Holding the room softly."
        else -> "Quietly keeping the room."
    }

    private fun isoTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())

    private fun maybeSpeakCompanionLine(
        line: String?,
        key: String?,
        style: String?,
    ) {
        val text = line?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val lineKey = key?.trim()?.takeIf { it.isNotEmpty() } ?: text.lowercase(Locale.US)
        val now = SystemClock.elapsedRealtime()
        if (lineKey == lastCompanionLineKey && now - lastCompanionSpeechAtMs < 10 * 60_000L) return
        if (now - lastCompanionSpeechAtMs < COMPANION_SPEECH_COOLDOWN_MS) return
        if (eyeView.engine.covered || eyeView.engine.isSleeping()) return
        lastCompanionLineKey = lineKey
        lastCompanionSpeechAtMs = now
        speech.say(text, style ?: "measured")
    }

    private fun recognitionSummary(): String {
        val gallery = latestObservedPersonId?.let { id ->
            val label = latestObservedPersonLabel ?: id
            val sim = latestObservedPersonSimilarity?.let { String.format(Locale.US, "%.3f", it) } ?: "--"
            "  seen:$label@$sim"
        } ?: ""
        if (recognizer?.enabled != true) return "  rec:model-off$gallery"
        if (enrolledEmbedding == null) return "  rec:not-enrolled$gallery"
        val score = recognitionScore ?: return "  rec:waiting$gallery"
        val identity = if (recognizedIsMe) "ME" else "OTHER"
        return String.format(
            Locale.US,
            "  rec:%.3f/%s (cut %.2f)%s",
            score,
            identity,
            FaceRecognizer.THRESHOLD,
            gallery,
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
        if (!hasMicrophonePermission()) {
            requestMicrophonePermissionIfNeeded(force = true)
            return
        }
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
        private const val TELEMETRY_PUSH_MS = 12_000L
        private const val COMPANION_SPEECH_COOLDOWN_MS = 80_000L
        private const val GREET_GAP_MS = 30 * 60 * 1000L
        private const val DARK_LUX = 6f
        private const val COVERED_LUMA = 12f
        private const val DOUBLE_TAP_MS = 320L
        private const val IDENTITY_ACQUIRE_MS = 2_500L
        private const val IDENTITY_HOLD_MS = 4_000L
    }
}
