# Mawa — Technical Design Document

Status: implemented v2 · 2026-07-04
Companion docs: [TODO.md](TODO.md) (task checklist)

---

## 1. Overview

Mawa is a wall-mounted ambient companion built from an old OnePlus phone
mounted in **landscape**. The phone displays two animated eyes that track the
user's face via the front camera, speaks through the phone speaker, listens
via the phone mic after a wake word, and is driven by a small "brain" server
that composes declarative scenes, hosts integrations, and will hold the LLM,
music control, and personality state.

### Goals

- Eyes that convincingly track a person in the room, with lifelike idle
  behavior (blinks, saccades, wandering, moods, sleep).
- Voice interaction: wake word → question → spoken answer.
- Proactive but restrained assistant: morning brief, meeting heads-ups,
  important-email mentions — hard-capped chattiness.
- Music via Spotify Connect.
- Run 24/7 unattended as a wall appliance.

### Non-goals (v1)

- Multi-room / multi-device.
- Direct Alexa device control (no good public API; Spotify Connect covers
  the music use case).
- Camera frames ever leaving the phone.

---

## 2. System architecture

```
┌─────────────────────────────  WALL  ─────────────────────────────┐
│  OnePlus (landscape, Android app "mawa-face", Kotlin)            │
│                                                                  │
│  CameraX ──► ML Kit face detect (on-device, ~5fps)               │
│                    │ face position                               │
│                    ▼                                             │
│  Eye renderer (Canvas, 60fps) ◄── Animation engine ◄── AppState  │
│                                                                  │
│  Porcupine wake word ──► mic capture ──► STT                     │
│  Android TTS ◄── speech queue                                    │
└───────────────┬──────────────────────────────────────────────────┘
                │ HTTPS poll (versioned JSON manifest, every ~5 min)
┌───────────────▼──────────────────────────────────────────────────┐
│  Brain "mawa-brain" (Next.js / TypeScript on Vercel)             │
│                                                                  │
│  Manifest composer ◄── connector registry                       │
│       │                 ├─ Weather (live)                        │
│  Dashboard              ├─ Calendar / Gmail (planned)            │
│       │                 └─ Spotify (planned)                     │
│  Personality engine ── LLMProvider (Groq default, planned)       │
└──────────────────────────────────────────────────────────────────┘
```

### Design principles

1. **Resilient face, smart brain.** The phone owns vision and lifelike local
   behavior. The brain composes optional information scenes. All secrets and
   OAuth tokens live in Vercel.
2. **Vision never leaves the device.** ML Kit runs on-phone; only derived
   events ("face appeared at x,y", "face lost") cross the network.
3. **Offline-degradable.** If Vercel is unreachable, the eyes keep
   working — tracking, blinking, sleeping. Only voice/proactive features
   degrade.
4. **Provider-pluggable LLM.** The brain talks to one `LLMProvider`
   interface. Default: Groq free tier (OpenAI-compatible API). Swappable to
   Anthropic or local Ollama via config only.

---

## 3. Phone app — `mawa-face` (Android / Kotlin)

### 3.1 Stack

| Concern | Choice | Notes |
|---|---|---|
| Language | Kotlin | Native; no framework between us and CameraX/ML Kit |
| Min SDK | TBD — check phone's Android version (Phase 0) | CameraX needs API 21+, ML Kit 19+; any OnePlus from ~2016 on is fine |
| Rendering | Custom `View` + `Canvas`, driven by `Choreographer` at 60fps | Simple 2D shapes; no need for OpenGL |
| Camera | CameraX `ImageAnalysis` | Front camera, `STRATEGY_KEEP_ONLY_LATEST` |
| Face detection | ML Kit Face Detection (bundled model) | `PERFORMANCE_MODE_FAST`, no landmarks/contours needed |
| Wake word | Picovoice Porcupine (free tier) | On-device, low CPU |
| STT | Android `SpeechRecognizer` | Free; upgrade path: stream audio to brain → Whisper |
| TTS | Android `TextToSpeech` | Free; upgrade path: cloud TTS via brain |
| Networking | `HttpURLConnection` + JSON | Poll v1 scene manifest; local fallback |

### 3.2 Module layout

```
mawa-face/
  app/src/main/java/com/mawa/face/
    MainActivity.kt          // fullscreen host, immersive mode
    FaceService.kt           // foreground service; owns camera, ws, audio
    render/
      EyeView.kt             // Canvas view, 60fps draw loop
      EyeModel.kt            // per-eye params (see 3.3)
      AnimationEngine.kt     // springs, blink scheduler, saccades, drift
    vision/
      FaceTracker.kt         // CameraX + ML Kit pipeline
      GazeMapper.kt          // face bbox -> gaze target (see 3.4)
    audio/
      WakeWord.kt            // Porcupine
      Speech.kt              // SpeechRecognizer + TTS wrappers
    net/
      SceneManifestClient.kt // HTTPS manifest polling + bounded parser
    scene/
      ScenePanel.kt          // Declarative panel model and screen slot
    state/
      AppStateMachine.kt     // see 3.5
    boot/
      BootReceiver.kt        // BOOT_COMPLETED -> start FaceService
```

### 3.3 Eye renderer

**Coordinate system.** Screen in landscape, origin center. Each eye is
positioned symmetric about center, roughly 0.28 × screenWidth apart.

**EyeModel — per-eye animatable parameters:**

| Param | Range | Meaning |
|---|---|---|
| `openness` | 0..1 | 0 = closed lid, 1 = fully open (blink, sleep, squint) |
| `pupilX`, `pupilY` | −1..1 | Normalized pupil offset within the eye |
| `pupilScale` | 0.6..1.4 | Dilation (proximity/interest) |
| `lidAngle` | −15..15° | Upper-lid tilt (grumpy vs surprised) |
| `squash` | 0.7..1.1 | Vertical eye squash (happy crescent) |

All parameters move through a critically-damped spring (or `lerp` with
per-param rate) — **nothing ever snaps**. Target values come from
(a) the gaze mapper, (b) the current mood preset, (c) one-shot gestures
(blink, glance, eye-roll) layered on top.

**Blink scheduler.** Next blink at `now + uniform(2.2s, 5.5s)`; blink is a
~140ms close-open of `openness`; 10% chance of a double blink. Suppressed
during SPEAKING gestures that already animate lids.

**Micro-saccades.** Every 0.4–1.2s, add ±0.02 jitter to pupil target.
Frozen pupils are the #1 tell that a face is fake.

**Idle wander.** With no face: pick a random gaze point, ease to it, dwell
1–4s, repeat. Occasionally (every few minutes) a scripted gesture: glance
at "nothing", slow blink, brief squint.

**Burn-in mitigation.** Background is pure black (`#000000`, OLED off). The
whole face composition drifts inside a ±12px window over a ~10 minute cycle.
No static bright pixels anywhere.

### 3.4 Face tracking pipeline

```
CameraX ImageAnalysis (front camera, 640×480, KEEP_ONLY_LATEST)
  → throttle to ≤5 fps (skip frames by timestamp)
  → ML Kit FaceDetector (FAST mode, minFaceSize 0.1)
  → pick largest bounding box (nearest person)
  → GazeMapper → gaze target (gx, gy) in −1..1
  → AnimationEngine (smoothing)
```

**GazeMapper math.** Let `(cx, cy)` = face bbox center normalized to the
analysis frame (0..1 each axis).

1. **Rotation:** the analysis frame arrives in sensor orientation; map
   through `imageInfo.rotationDegrees` into landscape-display space first.
2. **Mirroring:** front camera preview space is mirrored relative to the
   world; flip the horizontal axis so that when the user moves to *their*
   left, the eyes look to *the phone's* right (i.e., at them).
3. **Landscape camera offset:** in landscape the camera sits at one short
   edge, not above the eyes. Apply calibration constants:
   `gx = clamp((cx − 0.5) * KX + OFFSET_X, −1, 1)` (same for y).
   `OFFSET_X/Y` and gain `KX/KY` are tuned once by standing at known spots;
   store in shared prefs, expose via a hidden 5-tap debug overlay.
4. **Smoothing:** `gaze += (target − gaze) * 0.15` per frame at 60fps
   (≈120ms lag — glides, never jitters). Proximity signal
   `prox = bboxArea / frameArea` drives `pupilScale` and, if `Δprox` spikes,
   the "startled/suspicious" gesture.

**Face lost:** hold last gaze 2s → wander. **Multiple faces:** track
largest; every ~30s optionally glance at a secondary face for 1s (crowd
awareness — pure charm, zero cost).

### 3.5 App state machine

```
BOOT ──► CONNECTING ──► IDLE_WANDER ◄──────────┐
                            │ face detected     │ face lost 2 min
                            ▼                   │
                        TRACKING ───────────────┘
                            │ wake word
                            ▼
                        LISTENING ──► THINKING ──► SPEAKING ──► TRACKING
                            
SLEEPING: entered from any state on (no face 10 min) OR quiet hours
          camera OFF, eyes closed, slow "breathing" lid motion
          exit on: motion? no — on schedule (morning) or brain "wake" cmd
          cheap wake probe: run camera 1 frame every 60s while sleeping
```

Mood (from brain) is orthogonal to state — it styles *how* the current
state renders (lid angle, blink rate, pupil size, wander speed).

### 3.6 Appliance behaviors (kiosk)

- `MainActivity`: immersive sticky fullscreen, `FLAG_KEEP_SCREEN_ON`,
  `setShowWhenLocked(true)` / `setTurnScreenOn(true)`.
- `MainActivity` currently owns camera and microphone lifecycles. OxygenOS
  battery mode must be Unrestricted for reliable overnight operation; a
  foreground service remains a future hardening step.
- `BootReceiver` on `BOOT_COMPLETED` relaunches the activity.
- `Thread.setDefaultUncaughtExceptionHandler`: log crash to disk, schedule
  restart via `AlarmManager` (+2s), then die. The wall never shows a
  launcher.
- Battery/thermal telemetry (`BatteryManager.EXTRA_TEMPERATURE`) is planned
  but not yet implemented (see §8).

### 3.7 Audio

- **Beat reactivity (implemented):** an 8 kHz mono `AudioRecord` stream is
  reduced to RMS energy. Transients drive pupil dilation and whole-face bounce;
  samples are discarded immediately and never stored or uploaded.
- **Wake word:** Porcupine runs continuously except in SLEEPING/SPEAKING.
  Custom keyword "Mawa" trained via Picovoice Console (free tier allows
  custom keywords for personal use).
- **STT:** on wake → `SpeechRecognizer` with 6s silence timeout; partial
  results ignored; final transcript sent to brain.
- **TTS:** `say` commands queue; eyes enter SPEAKING while queue is
  non-empty; subtle lid/pupil pulse per utterance.
- **Audio focus:** request transient focus while speaking so we duck any
  other audio; abandon after.
- Mic audio is processed on-device; only the final *text transcript* is
  sent to the brain.

---

## 4. Cloud brain — `mawa-brain` (Next.js on Vercel)

### 4.1 Stack and deployment

Next.js 16 App Router + strict TypeScript, deployed serverlessly to Vercel at
`https://mawa-brain.vercel.app`. The dashboard is static; API routes compose
fresh manifests. Connector fetches are independently failure-contained so one
bad provider cannot take down the scene.

### 4.2 Module layout

```
mawa-brain/
  app/
    page.tsx                 # connector/status dashboard
    api/health/route.ts      # liveness
    api/manifest/route.ts    # phone scene endpoint
  lib/
    manifest.ts              # shared v1 schema
    compose-manifest.ts      # fan-out + bounded composition
    connectors/
      registry.ts            # active + planned connectors
      weather.ts             # Open-Meteo connector
      google-calendar.ts     # Personal + Work private ICS feeds
```

Each connector returns status, zero or more short panels, and optional scene
cues such as weather or mood. The composer runs connectors concurrently,
flattens their output, and sets explicit generation/expiry times.

### 4.3 Future LLM and state

The conversational endpoint will depend on an `LLMProvider` TypeScript
interface. Groq is the default; Anthropic remains an optional provider. Local
Ollama is not a Vercel production provider, but can remain a development
adapter. Rate-limit handling is one bounded retry followed by a short canned
response so the phone never waits indefinitely.

Vercel functions have no durable local filesystem. Conversation memory,
chattiness budgets, encrypted OAuth refresh tokens, and scheduled connector
state must use managed durable storage (KV/Postgres) rather than files.

The personality remains dry, warm, slightly theatrical, and brief: one or two
spoken sentences, no invented calendar/email facts, and no emoji in TTS text.

### 4.4 Mood and proactive behavior

Mood is carried in each manifest and eventually in voice replies. Phone-local
signals such as proximity startle and night sleep take precedence when they
are safety- or latency-sensitive. Calendar-driven mood and proactive speech
are cloud decisions subject to quiet hours and a four-per-day chattiness
budget.

---

## 5. HTTP scene manifest protocol

The phone polls `GET /api/manifest` over HTTPS approximately every five
minutes. Query parameters are `lat`, `lon`, `device`, and `version`; Android
rounds coordinates to two decimals (~1 km) before transmission.

The v1 response contains:

- `schemaVersion`, `manifestId`, `generatedAt`, `expiresAt`, and
  `pollAfterSeconds`;
- `scene.mode`, `scene.mood`, optional normalized weather, and up to four
  declarative panels;
- connector health states for the dashboard and diagnostics;
- explicit privacy assertions.

Android rejects unknown schema versions, caps panel count and string lengths,
and treats the payload strictly as data. A failed request never changes local
face tracking and triggers the existing direct Open-Meteo fallback. Future
transcript and telemetry writes will use separate authenticated POST endpoints;
they do not belong in the read-only scene manifest.

---

## 6. External integrations

### Google Calendar + Gmail

- **Calendar v1 (implemented):** separate private Google ICS URLs for Personal
  and Work. The connector expands RRULE/EXDATE/overrides for the next 24 hours
  and emits one next-event panel per account. No write access exists.
- Calendar event details are returned only when the paired-device bearer token
  matches; public dashboard previews show status without titles.
- **Calendar OAuth v2 / Gmail (planned):** Google OAuth consent and read-only
  `calendar.readonly` / `gmail.readonly` scopes with encrypted durable token
  storage. This replaces ICS only if interactive account management is needed.
- Gmail: `messages.list(q="is:unread newer_than:1d from:(<allowlist>)")`,
  metadata-only fetch (From/Subject) unless summarization is requested.

### Spotify Connect

- Spotify Developer app, Authorization Code + PKCE, scopes
  `user-read-playback-state user-modify-playback-state`.
- Voice flow: "Mawa, play some jazz" → LLM extracts intent
  `{action: play, query: "jazz"}` → `GET /v1/search` →
  `PUT /v1/me/player/play` with `device_id` of the Echo/any Connect device.
- Device picking: prefer configured `SPOTIFY_DEVICE_NAME`, else currently
  active device. Note: target device must be awake/known to Spotify; if
  none found, Mawa says so instead of failing silently.

### Dropped: direct Alexa control

Amazon offers no supported public API for commanding an Echo. Alternatives
rejected: AVS (turns the phone *into* an Alexa — heavy, redundant) and
unofficial cookie-based APIs (fragile). Spotify Connect reaches the same
speaker legitimately.

---

## 7. Security & privacy

- **Camera:** frames never leave the phone; ML Kit and MobileFaceNet are
  on-device. No recording and no image storage.
- **Mic:** ambient access performs local beat-energy analysis only; raw samples
  are immediately discarded. Future voice recognition sends final transcript
  text only.
- **Location:** manifest requests use coordinates rounded to two decimals
  (~1 km); weather does not require precise location.
- **Secrets:** API keys and OAuth tokens are Vercel environment variables and
  managed encrypted records, never Android resources or committed files.
- **Transport:** HTTPS only. The public weather manifest is read-only; private
  connector data and future POST endpoints require a device bearer token.
- **Visitor courtesy:** covering the camera closes the eyes; a physical
  camera-cover sticker remains the reliable hardware privacy control. Camera
  duty-cycling during sleep is still pending.

## 8. Performance & thermals

Budgets: face detection ≤5fps (ML Kit FAST on 640×480 is ~10–30ms/frame on
old hardware — mostly idle); render 60fps flat shapes (trivial); Porcupine
~few % CPU.

Thermal policy (phone-side, from battery temp): >38°C → camera to 2fps;
>41°C → camera duty-cycle (5s on / 25s off) + warn brain; >44°C → SLEEPING
+ alert. Battery care: old cells hate 100% float — if a smart plug is
available, brain toggles charging to hold 40–80%; otherwise accept it, the
battery is already a write-off.

## 9. Failure modes & recovery

| Failure | Behavior |
|---|---|
| Brain unreachable | Eyes remain functional; direct weather fallback; next scheduled poll retries |
| LLM 429/5xx | one retry, then canned personality response |
| STT no-match | "Sorry, say that again?" + `listen` reopened once |
| Google/Spotify token expired | brain logs + suppresses that feature; status page shows red; never crash-loops the phone |
| App crash | crash handler → auto-restart in ~2s (§3.6) |
| Phone reboot (update/power) | BOOT_COMPLETED relaunch |
| Brain host reboot | systemd/launchd restart; phone reconnects |

## 10. Configuration

`mawa-brain/.env.local` for local development, mirrored by encrypted Vercel
environment variables in production:

```
GROQ_API_KEY=...
GROQ_MODEL=llama-3.3-70b-versatile
MAWA_DEVICE_TOKEN=<random 32+ chars>
TOKEN_ENCRYPTION_KEY=<32-byte encryption key>
MAWA_TIME_ZONE=America/New_York
PERSONAL_CALENDAR_ICS_URL=<private Google ICS URL>
WORK_CALENDAR_ICS_URL=<private Google ICS URL>
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
SPOTIFY_CLIENT_ID=...
```

Phone: `BuildConfig.BRAIN_BASE_URL` defaults to the production Vercel alias and
can be overridden by `MAWA_BRAIN_URL` during build. Calibration remains in
device `SharedPreferences` and the five-tap overlay exposes diagnostics.

## 11. Milestones & acceptance criteria

| Milestone | Done when |
|---|---|
| M1 Face (TODO Phase 1) | Phone on wall; eyes find and follow a person across the room; blinks/saccades/wander; sleeps when room empty; survives reboot and crash unattended for 48h |
| M2 Voice (Phase 2) | "Mawa" + question → spoken answer; eyes choreograph listen/think/speak; offline degradation verified |
| M3 Brain (Phase 3) | Morning brief on first sighting; meeting heads-ups; ≤4 unprompted utterances/day enforced; moods visibly react to calendar events |
| M4 Music (Phase 4) | "Play X" starts playback on the Echo via Spotify Connect; thermal policy verified over a summer week |

## 12. Open questions

1. Wake word final name — "Mawa" assumed; needs a Picovoice training pass.
2. Groq model choice: `llama-3.3-70b-versatile` (better wit) vs
   8B-class (faster, higher free-tier headroom) — decide by taste in M2.
3. Recognition cosine threshold after real wall tests.
4. Smart plug for battery care — worth $15?
