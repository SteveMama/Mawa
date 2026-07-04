# Mawa — Technical Design Document

Status: draft v1 · 2026-07-04
Companion docs: [TODO.md](TODO.md) (task checklist)

---

## 1. Overview

Mawa is a wall-mounted ambient companion built from an old OnePlus phone
mounted in **landscape**. The phone displays two animated eyes that track the
user's face via the front camera, speaks through the phone speaker, listens
via the phone mic after a wake word, and is driven by a small "brain" server
that holds the LLM, calendar/email integrations, music control, and
personality state.

### Goals

- Eyes that convincingly track a person in the room, with lifelike idle
  behavior (blinks, saccades, wandering, moods, sleep).
- Voice interaction: wake word → question → spoken answer.
- Proactive but restrained assistant: morning brief, meeting heads-ups,
  important-email mentions — hard-capped chattiness.
- Music via Spotify Connect.
- Run 24/7 unattended as a wall appliance.

### Non-goals (v1)

- Face *recognition* (identifying who is in the room) — detection only.
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
                │ WebSocket (JSON, LAN)
┌───────────────▼──────────────────────────────────────────────────┐
│  Brain server "mawa-brain" (Python / FastAPI, runs on Mac→Pi/VPS)│
│                                                                  │
│  WS gateway ── Personality engine ── LLMProvider (pluggable)     │
│       │              │                  ├─ Groq (default, free)  │
│  Scheduler ──── Mood state machine     ├─ Anthropic (optional)  │
│  (APScheduler)       │                  └─ Ollama (local, opt.)  │
│                      │                                           │
│  Integrations: Google Calendar · Gmail · Spotify Connect         │
│  Memory: markdown/JSON files on disk                             │
└──────────────────────────────────────────────────────────────────┘
```

### Design principles

1. **Dumb face, smart brain.** The phone renders and senses; it makes no
   decisions beyond local animation. All intelligence, secrets, and API
   tokens live on the server.
2. **Vision never leaves the device.** ML Kit runs on-phone; only derived
   events ("face appeared at x,y", "face lost") cross the network.
3. **Offline-degradable.** If the brain is unreachable, the eyes keep
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
| Networking | OkHttp WebSocket | Reconnect with exponential backoff |

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
      BrainClient.kt         // WebSocket, protocol codec, reconnect
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
- `FaceService` is a **foreground service** (camera + mic type) so Android
  never kills the pipeline while the activity is up.
- `BootReceiver` on `BOOT_COMPLETED` relaunches the activity.
- `Thread.setDefaultUncaughtExceptionHandler`: log crash to disk, schedule
  restart via `AlarmManager` (+2s), then die. The wall never shows a
  launcher.
- Battery/thermal telemetry (`BatteryManager.EXTRA_TEMPERATURE`) sampled
  every 5 min and reported to brain (see §8).

### 3.7 Audio

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

## 4. Brain server — `mawa-brain` (Python)

### 4.1 Stack

FastAPI + `uvicorn` (WebSocket endpoint + tiny status page), APScheduler
(cron-style proactive triggers), `httpx` (LLM + integrations), file-based
memory. Runs on the Mac during development; target host Raspberry Pi or a
small VPS. Managed by `launchd`/`systemd`.

### 4.2 Module layout

```
mawa-brain/
  main.py            # FastAPI app, WS endpoint /face
  config.py          # env-driven settings (see §10)
  gateway.py         # protocol codec, connection registry, send helpers
  brain.py           # conversation + proactive utterance generation
  llm/
    provider.py      # LLMProvider interface
    groq.py          # default: OpenAI-compatible chat completions
    anthropic_p.py   # optional
    ollama.py        # optional, fully local
  mood.py            # mood state machine (see 4.5)
  scheduler.py       # cron triggers -> brain
  budget.py          # chattiness budget
  integrations/
    google_cal.py    # Calendar read-only
    gmail.py         # Gmail read-only
    spotify.py       # Spotify Connect
  memory/
    store.py         # markdown notes the LLM can read/write
```

### 4.3 LLM layer (pluggable, Groq default)

```python
class LLMProvider(Protocol):
    async def complete(self, system: str, messages: list[dict],
                       max_tokens: int = 300) -> str: ...
```

**Default — Groq** (free tier, OpenAI-compatible):

```python
# llm/groq.py
import httpx

class GroqProvider:
    BASE = "https://api.groq.com/openai/v1"

    def __init__(self, api_key: str, model: str = "llama-3.3-70b-versatile"):
        self.key, self.model = api_key, model

    async def complete(self, system, messages, max_tokens=300):
        async with httpx.AsyncClient(timeout=30) as http:
            r = await http.post(
                f"{self.BASE}/chat/completions",
                headers={"Authorization": f"Bearer {self.key}"},
                json={
                    "model": self.model,
                    "max_tokens": max_tokens,
                    "messages": [{"role": "system", "content": system}, *messages],
                },
            )
            r.raise_for_status()
            return r.json()["choices"][0]["message"]["content"]
```

Rate-limit handling: on 429, retry once after `retry-after`; if still
limited, fall back to a canned response ("Give me a second, I'm thinking
too hard") so the device never hangs. Groq free-tier limits are generous
for a single device (tens of RPM), but the budget module also keeps
call volume low by design.

**Optional — Anthropic** (if personality quality warrants paying): official
`anthropic` SDK, model `claude-opus-4-8` (or `claude-haiku-4-5` at
$1/$5 per MTok for the frequent ambient calls — at Mawa's volume either is
a few dollars a month). Keep the persona system prompt byte-stable and mark
it with `cache_control: {"type": "ephemeral"}` so repeated calls hit the
prompt cache.

**Optional — Ollama** (zero cost, fully private): same OpenAI-compatible
shape at `http://localhost:11434/v1`; a small model (llama3.2 8B) is enough
for one-liners if the brain host has the RAM.

Provider selection: `LLM_PROVIDER=groq|anthropic|ollama` in config. No code
changes to switch.

### 4.4 Personality engine

One stable **persona system prompt** (~600 tokens) defining: name (Mawa),
voice (dry, warm, a little theatrical; 1–2 sentences max per utterance;
never explains itself; never says "as an AI"), and hard rules (no fake
facts about calendar/email — only what's in the provided context; ≤2
sentences; no emoji since output is spoken).

Two call shapes:

1. **Conversational:** user transcript + last ~10 turns + current mood +
   time of day → reply. Target < 2s wall clock (Groq typically well under).
2. **Proactive:** scheduler fires with structured context (e.g. today's
   events JSON) → one utterance. The prompt states the *occasion*
   ("morning brief", "meeting in 10min") and the mood.

Conversation history: in-memory ring buffer (20 turns), reset nightly.
Long-term memory: the brain can append "things to remember" to
`memory/notes.md`; the file is included in the system context (capped at
2KB, oldest lines pruned).

### 4.5 Mood state machine

Mood is a single enum + intensity, persisted across restarts.

| Mood | Eye styling (sent to phone) | Entered by |
|---|---|---|
| `neutral` | defaults | baseline decay from any mood after ~30 min |
| `happy` | squash 0.85, faster wander | meeting cancelled; user came home (face after >4h absence) |
| `grumpy` | lidAngle −10°, slow blinks | 7am meeting appears on tomorrow's calendar; >5 meetings today |
| `sleepy` | openness 0.55, blink 2× slower | after 22:30; before 07:00 |
| `suspicious` | openness 0.7, pupils small | Δproximity spike (someone rushed the phone) — phone-local trigger |
| `excited` | pupilScale 1.3, quick saccades | music started; Friday after 17:00 |

Transitions are events → `mood.set(m, intensity, ttl)`; TTL decay returns
to neutral. The mood is included in every LLM prompt so the *words* match
the *face*.

### 4.6 Scheduler & chattiness budget

APScheduler jobs (all in local timezone):

| Job | When | Action |
|---|---|---|
| Morning brief | first `face_appeared` after 07:00 | summarize today's calendar (1 call), speak |
| Meeting heads-up | event start − 10 min | speak title + time, no LLM needed for the template case |
| Email check | every 30 min, 08:00–20:00 | unread from allowlisted senders → mention (LLM summarize subject) |
| Nightly reset | 03:00 | clear conversation ring, prune memory, rotate logs |
| Mood decay tick | every 5 min | TTL decay |

**Budget:** max 4 *unprompted* utterances/day (meeting heads-ups exempt),
none during quiet hours (22:30–07:00), min 20 min between any two
unprompted utterances. Answers to the user are never budget-limited.

---

## 5. WebSocket protocol

Single WS connection, JSON text frames, phone connects to
`ws://<brain-host>:8300/face?token=<shared-secret>`.

### Brain → phone

| `type` | Payload | Effect |
|---|---|---|
| `say` | `{text, mood?}` | queue TTS; optional one-shot mood styling |
| `mood` | `{mood, intensity}` | set eye styling preset |
| `gesture` | `{name}` | one-shot: `blink`, `double_blink`, `eye_roll`, `glance_left/right`, `wink` |
| `gaze` | `{mode: "track"\|"wander"\|"point", x?, y?}` | override gaze source |
| `sleep` / `wake` | `{}` | force sleep state / wake up |
| `listen` | `{}` | open mic without wake word (follow-up questions) |
| `config` | `{key: value}` | tune calibration remotely |

### Phone → brain

| `type` | Payload | When |
|---|---|---|
| `hello` | `{device, version, battery, screen: {w,h}}` | on connect |
| `face` | `{event: "appeared"\|"lost", prox?}` | tracking transitions (NOT continuous positions) |
| `transcript` | `{text}` | after wake word + STT |
| `speaking_done` | `{}` | TTS queue drained |
| `telemetry` | `{batteryTemp, batteryPct, uptime}` | every 5 min |
| `error` | `{where, message}` | recoverable faults |

Notes: continuous gaze positions stay on-phone (bandwidth + privacy);
the brain only needs presence transitions. Reconnect: exponential backoff
1s→60s with jitter; phone remains fully functional offline (§2 principle 3).
Auth: static shared token in config on both ends; LAN-only in v1 — if the
brain moves to a VPS, switch to `wss://` behind Caddy/TLS.

---

## 6. External integrations

### Google Calendar + Gmail

- GCP project, OAuth consent (internal/test mode is fine for one user).
- Scopes: `calendar.readonly`, `gmail.readonly` — read-only on principle.
- One-time browser auth on the brain host; refresh token stored server-side
  (`token.json`, chmod 600). The phone never sees Google credentials.
- Calendar: `events.list(timeMin=now, timeMax=+24h, singleEvents, orderBy=startTime)`.
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

- **Camera:** frames never leave the phone; ML Kit is on-device; only
  appeared/lost events cross the LAN. No recording, no storage.
- **Mic:** audio processed on-device; opens only after wake word or a
  brain `listen` command; only final text transcripts are transmitted.
- **Secrets:** all API keys/OAuth tokens on the brain host only, loaded
  from `.env` (never committed). Phone holds just the WS token.
- **Transport:** LAN WebSocket in v1; `wss://` + TLS if ever remote.
- **Visitor courtesy:** SLEEPING state = camera hardware off; a physical
  camera-cover sticker is a fine analog fallback.

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
| Brain unreachable | Eyes fully functional offline; voice answers "my brain is offline"; reconnect w/ backoff |
| LLM 429/5xx | one retry, then canned personality response |
| STT no-match | "Sorry, say that again?" + `listen` reopened once |
| Google/Spotify token expired | brain logs + suppresses that feature; status page shows red; never crash-loops the phone |
| App crash | crash handler → auto-restart in ~2s (§3.6) |
| Phone reboot (update/power) | BOOT_COMPLETED relaunch |
| Brain host reboot | systemd/launchd restart; phone reconnects |

## 10. Configuration

`mawa-brain/.env`:

```
LLM_PROVIDER=groq            # groq | anthropic | ollama
GROQ_API_KEY=...
GROQ_MODEL=llama-3.3-70b-versatile
ANTHROPIC_API_KEY=           # optional
ANTHROPIC_MODEL=claude-opus-4-8
WS_TOKEN=<random 32 chars>
TIMEZONE=<local tz>
QUIET_HOURS=22:30-07:00
UNPROMPTED_BUDGET_PER_DAY=4
EMAIL_SENDER_ALLOWLIST=a@x.com,b@y.com
SPOTIFY_CLIENT_ID=... / SPOTIFY_DEVICE_NAME=Echo
GOOGLE_* (client id/secret; token.json created by auth flow)
```

Phone: brain host/port + WS token + calibration constants in a settings
screen (5-tap debug overlay).

## 11. Milestones & acceptance criteria

| Milestone | Done when |
|---|---|
| M1 Face (TODO Phase 1) | Phone on wall; eyes find and follow a person across the room; blinks/saccades/wander; sleeps when room empty; survives reboot and crash unattended for 48h |
| M2 Voice (Phase 2) | "Mawa" + question → spoken answer < 3s end-to-end on LAN; eyes choreograph listen/think/speak; offline degradation verified |
| M3 Brain (Phase 3) | Morning brief on first sighting; meeting heads-ups; ≤4 unprompted utterances/day enforced; moods visibly react to calendar events |
| M4 Music (Phase 4) | "Play X" starts playback on the Echo via Spotify Connect; thermal policy verified over a summer week |

## 12. Open questions

1. Exact Android version of the OnePlus (sets min SDK) — Phase 0.
2. Wake word final name — "Mawa" assumed; needs a Picovoice training pass.
3. Brain's long-term host: Mac (always on?) vs Raspberry Pi vs VPS.
4. Groq model choice: `llama-3.3-70b-versatile` (better wit) vs
   8B-class (faster, higher free-tier headroom) — decide by taste in M2.
5. Smart plug for battery care — worth $15?
