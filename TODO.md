# Mawa — wall companion on an old OnePlus

A Sphere-style creature for the wall: the phone screen is its face, the front
camera is how it sees, the speaker is its voice, and a small cloud brain is its
mind (LLM + calendar + music).

Architecture in one line: **dumb face on the phone, smart brain in the cloud**.
The phone renders eyes and handles camera/mic/speaker on-device; the brain
(Vercel dashboard + connectors) composes a "scene manifest" the phone polls.

Build/deploy loop: **no Android Studio, no local SDK**. GitHub Actions builds a
signed APK on every push to `main` and attaches it to the rolling `latest`
release; the app self-updates over the air (checks every 15 min). See
[TECHNICAL_DESIGN.md](TECHNICAL_DESIGN.md).

---

## ✅ Done so far (as of 2026-07-04)

**Phase 0 — Hardware & setup**
- [x] OnePlus charged and running (Android 13 / OxygenOS)
- [x] Wall-mounted in landscape (camera in the top-right corner)
- [x] Sideload path proven (install the APK from the Files app, not the
      browser banner — OxygenOS quirk)
- [ ] OxygenOS battery → **Unrestricted** for Mawa (still to do — stops the OS
      killing it overnight)
- [ ] Disable lock screen / other-app notification sounds

**Phase 1 — The face (eyes that track you)** — COMPLETE
- [x] Kotlin app, plain Canvas renderer, fullscreen immersive, screen-on
- [x] `sensorLandscape` so it works mounted in either direction
- [x] Pure black OLED background
- [x] Two capsule eyes; animation engine, all eased (blinks + double-blinks,
      micro-saccades, idle wander, slow burn-in drift)
- [x] CameraX + ML Kit face detection, front camera, ~5 fps, fully on-device
- [x] Gaze mapping: mirror correction + smoothing + landscape offset
- [x] **One-touch calibration**: long-press → learns your spot, saved on device
      (red-dot calibration overlay via 5-tap)
- [x] Multiple faces: tracks nearest; **"Hey! New person!"** on a 2nd face
- [x] Sleep after 10 min alone; proximity startle reaction
- [x] Auto-start on boot + auto-restart on crash (never shows the launcher)

**Beyond the original plan — shipped this round**
- [x] **Self-update pipeline**: CI builds a persistently-signed APK; in-app
      updater downloads + installs new builds over the air
- [x] **Ambient awareness pack** (all on-device):
  - [x] Light sensor → after 10 PM only, sleeps when the lights go off, with a
        floating **ZZZ** (daytime darkness no longer forces sleep)
  - [x] **Weather** via Open-Meteo (free, keyless) → rain / snow / fog /
        thunder-flash particle animations over the eyes
  - [x] Time-of-day spoken greeting on arrival; drowsy mood at night;
        golden-hour warm tint on the black
  - [x] **Blink-back** (you blink → it blinks back)
  - [x] **Camera-cover "do not disturb"** (hand over the lens → eyes close)
- [x] **Face recognition plumbing** + CI model fetch (see pending item below)

---

## ⏳ Pending

### Face recognition — last mile (recognize Pranav specifically)
- [x] `FaceRecognizer` embedding pipeline + enroll-on-long-press + cosine match
- [x] CI fetches a MobileFaceNet model into assets (size-guarded, graceful)
- [x] Five-tap overlay shows model/enrollment state, live cosine score,
      ME/OTHER decision, and current threshold
- [ ] **Verify on-device**: confirm the model loaded (build log said "ACTIVE"),
      long-press to enroll, check it greets only you
- [ ] **Tune `FaceRecognizer.THRESHOLD`** against your face/lighting (start 0.62)
- [ ] Refine "new person" to mean "a face that isn't you" once recognition is solid

### Phase 2 — Voice (make it talk back)
- [ ] Wake word on the phone: Picovoice Porcupine (free, on-device) — confirm
      the name is "Mawa"
- [ ] Listening expression on wake (widen + look straight at you)
- [ ] Speech-to-text (Android `SpeechRecognizer` first; Whisper later)
- [ ] Send transcript to the brain, speak the reply (Android TTS already wired)
- [ ] Eye choreography: listening → thinking → speaking
- [ ] Mic privacy: audio leaves the phone only after the wake word

### The brain — Vercel dashboard + connectors (decided: cloud)
- [x] Next.js app deployed at `mawa-brain.vercel.app`: dashboard +
      schema-versioned `/api/manifest` + health endpoint
- [x] Connector registry (each connector contributes state, panels, and scene cues)
- [x] **Weather as connector #1** (no OAuth — proves the whole loop)
- [x] Phone polls the manifest every 5 min and renders bounded, auto-positioned
      panels around the eyes; existing local weather is the offline fallback
- [x] Privacy boundary: only rounded (~1 km) coordinates and app metadata reach
      the manifest endpoint; camera frames remain on-device
- [x] Paired-device bearer token stored in Vercel + GitHub Actions secrets;
      private connector panels are withheld from public dashboard previews
- [ ] LLM personality via pluggable provider — **Groq free tier by default**
      (swap to Anthropic / local Ollama by config)
- [ ] Encrypt OAuth tokens at rest; read-only scopes only

### Phase 3 — Useful connectors
- [x] Dual Google Calendar feed connector: separate Personal and Work slots,
      recurring-event expansion, next-event panels, private-device-only output
- [ ] Add the Personal and Work private ICS URLs to Vercel to activate the feeds
- [ ] Calendar morning brief and meeting heads-up 10 min prior
- [ ] Gmail (read-only): important-email mentions (sender allowlist)
- [ ] Chattiness budget: ≤ ~4 unprompted utterances/day, quiet hours
- [ ] Mood state machine perturbed by real events:
  - [x] Sleepy at night (done on-device)
  - [x] Suspicious/startle when approached fast (done on-device)
  - [x] Random idle glances (saccades/wander — done on-device)
  - [ ] Grumpy at a 7am meeting on tomorrow's calendar
  - [ ] Pleased when a meeting gets cancelled
- [ ] Memory: notes file the brain reads/writes to remember things you told it

### Phase 4 — Music & polish
- [ ] Spotify Connect: "play some jazz" → plays on the Echo / any linked device
- [x] Music reactivity: on-device RMS beat detection → pupil pulse + whole-face
      bounce; raw microphone samples are discarded and never uploaded
- [ ] Eyes react to Spotify "now playing" (track/album art via the API)
- [ ] Heat check: log battery temperature; drop camera fps / duty-cycle if hot
- [ ] Battery care: hold charge ~40–80% (smart plug the brain toggles?)
- [ ] Status page (uptime, temp, last utterance)

### Bonus ideas (cheap, high-delight — not yet built)
- [ ] Follow you to the doorway and hold a beat when you leave
- [ ] Notification glances (eye-dart to a corner when your phone buzzes)
- [ ] Scenes/modes from the dashboard (Focus / Party / Morning / Away)
- [ ] Remote poke: type from the dashboard → Mawa says it
- [ ] Home Assistant / smart-light hooks (react to home state)

---

## Decisions made

- Landscape mount; camera-offset handled by one-touch calibration
- Face **detection** on-device (ML Kit); face **recognition** on-device (TFLite
  MobileFaceNet) — no frames ever leave the phone
- Build/deploy via GitHub Actions + OTA self-update (no Android Studio)
- **Brain lives in the cloud (Vercel)** — cloud-stored OAuth tokens, read-only
  scopes, encrypted at rest (chosen over a home box for zero local infra)
- Connector/manifest architecture: connect apps in a dashboard, phone renders
  auto-arranged panels
- LLM: Groq free tier by default behind a pluggable provider
- Spotify Connect instead of direct Alexa control

## Open questions

- [ ] Wake word name — is it "Mawa"? (needs a Picovoice training pass)
- [ ] Does the corner-camera calibration hold and track well on the wall?
      (verify after the next self-update)
- [ ] Which Groq model — `llama-3.3-70b-versatile` (wittier) vs an 8B (faster)?
- [ ] Recognition threshold value after on-device tuning
