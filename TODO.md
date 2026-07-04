# Mawa — wall companion on an old OnePlus

A Sphere-style creature for the wall: the phone screen is its face, the front
camera is how it sees, the speaker is its voice, and a small server is its
brain (Claude + calendar + email + music).

Architecture in one line: **dumb face on the phone, smart brain on a server**,
connected by a WebSocket. The phone renders eyes and handles mic/speaker; the
server holds all API keys, the LLM calls, and the personality.

---

## Phase 0 — Hardware & setup (do this first, no code)

- [ ] Dig out the OnePlus, charge it, factory reset it
- [ ] Check its Android version (Settings → About phone) — this decides the
      minimum SDK for the app
- [ ] Enable Developer options + USB debugging (tap Build number 7×)
- [ ] Verify `adb devices` sees the phone from the Mac (`brew install android-platform-tools` if needed)
- [ ] Set up ADB over Wi-Fi (`adb tcpip 5555`) so you can install updates without
      taking the phone off the wall
- [ ] Pick the wall spot: roughly eye height, facing where you usually sit/stand,
      within reach of a power outlet
- [ ] Mount solution: a cheap landscape wall bracket or 3M Command strips on a
      slim case (case, not bare phone — easier to remove)
- [ ] Cable management: cable exits sideways in landscape — run it along the
      wall edge or behind furniture
- [ ] Phone settings: screen timeout → never (or handled by app), disable
      lock screen, disable notifications/sounds from other apps, disable
      auto-update reboots

## Phase 1 — The face (weekend 1: eyes that track you)

The goal: walk into the room, the eyes find you and follow you. No cloud, no
audio, all on-device.

- [ ] Create the Android app (Kotlin + Jetpack Compose or plain Canvas view;
      min SDK = whatever the OnePlus runs)
- [ ] Fullscreen immersive mode, landscape-locked, `FLAG_KEEP_SCREEN_ON`
- [ ] Pure black background everywhere (OLED pixels off → no burn-in, no glow
      on the wall at night)
- [ ] Eye renderer on Canvas: two eyes (sclera + pupil + eyelid), sized wide
      apart for a landscape face
- [ ] Animation engine: everything eased/spring-based, never snapping
  - [ ] Randomized blinks (double-blinks occasionally)
  - [ ] Micro-saccades: tiny pupil jitters so the gaze never looks frozen
  - [ ] Idle wander: when no face is present, gaze drifts around the room
  - [ ] Slow positional drift of the whole face (a few px/min — burn-in insurance)
- [ ] CameraX + ML Kit face detection, front camera, throttled to ~5 fps
      (plenty for tracking; keeps the old phone cool)
- [ ] Map face bounding-box center → pupil offset
  - [ ] Camera offset correction: in landscape the camera sits at one edge of
        the face, so raw coordinates are skewed — calibrate a fixed offset
  - [ ] Mirror correction (front camera is mirrored)
  - [ ] Smoothing (lerp toward target) so the gaze glides instead of jittering
- [ ] Multiple faces: track the largest (nearest) one; optional fun — glance
      between people
- [ ] No face for 2 min → wander; no face for 10 min → eyes close, camera off
      (sleep = privacy + heat management)
- [ ] Proximity reaction: face bounding box grows fast → pupils dilate / lean back
- [ ] Auto-start on boot (BOOT_COMPLETED receiver) and auto-restart on crash —
      it's a wall appliance, it must never show the launcher

## Phase 2 — Voice (weekend 2: it talks)

- [ ] Brain server skeleton: small Node or Python service (runs on the Mac to
      start; Raspberry Pi or $5 VPS later), WebSocket endpoint
- [ ] Define the phone↔brain message protocol, e.g.
      `{say: "..."}`, `{mood: "sleepy"}`, `{gaze: "wander"}`, `{listen: true}`
- [ ] Wake word on the phone: Picovoice Porcupine (free tier, on-device) —
      pick its name; "Mawa"? (custom wake words are trainable on Picovoice Console)
- [ ] On wake: eyes do a "listening" expression (widen + look straight at you)
- [ ] Speech-to-text: start with Android's built-in SpeechRecognizer (free,
      offline-capable); upgrade to Whisper on the server if quality disappoints
- [ ] Brain: LLM via pluggable provider — Groq free tier by default
      (OpenAI-compatible API, `llama-3.3-70b-versatile`); swappable to
      Anthropic or local Ollama by config — with a personality system prompt
- [ ] Text-to-speech: Android TTS to start; swap for a nicer cloud voice later
- [ ] Eye choreography for the conversation loop: listening → thinking
      (pupils drift up) → speaking (subtle pulse with the audio)
- [ ] Mic privacy rule: audio leaves the phone only after the wake word,
      camera frames never leave the phone at all

## Phase 3 — The brain gets useful (calendar, email, personality)

- [ ] Move the brain to an always-on box (Pi / VPS) with a process manager
- [ ] Google Cloud project + OAuth: Calendar read-only, Gmail read-only —
      tokens live only on the server, never on the phone
- [ ] Proactive scheduler:
  - [ ] Morning brief when it first sees your face after 7am ("two meetings
        today, first one at 10")
  - [ ] Meeting heads-up 10 min before events
  - [ ] Important-email mentions (sender allowlist, not everything)
- [ ] Chattiness budget: max ~4 unprompted utterances/day, quiet hours after
      10pm — chatty is how it gets unplugged
- [ ] Mood state machine driving the eyes, perturbed by real events:
  - [ ] Sleepy after 11pm (heavy lids, slow blinks), asleep overnight
  - [ ] Grumpy at a 7am meeting on tomorrow's calendar
  - [ ] Pleased when a meeting gets cancelled
  - [ ] Suspicious squint when you approach too fast
  - [ ] Random glances at "nothing" once in a while — asymmetry sells the illusion
- [ ] Memory: a small notes file the brain reads/writes so it remembers things
      you told it

## Phase 4 — Music & polish

- [ ] Spotify Connect API on the server: "play some jazz" → starts playback on
      any linked device including the Echo (skip direct Alexa integration —
      Amazon has no good public API for it; Spotify Connect gets you the same
      outcome)
- [ ] Eyes bounce/sway subtly while music plays
- [ ] Heat check: log battery temperature; if it runs hot, drop camera fps or
      duty-cycle it
- [ ] Charging strategy: old batteries hate sitting at 100% — if the phone
      supports it, limit charge, or use a smart plug the brain toggles to keep
      the battery ~40–80%
- [ ] Remote admin: brain exposes a tiny status page (uptime, temp, last
      utterance); ADB-over-Wi-Fi for app updates
- [ ] Stretch ideas: weather glance out the "window", visitor reaction
      (unknown face → curious stare), seasonal accessories (santa hat pixels)

---

## Decisions already made

- Landscape mount — two eyes want a wide face; camera-offset correction needed
- Face detection stays 100% on-device (ML Kit); no frames leave the phone
- Thin client / smart server split; all secrets server-side
- Spotify Connect instead of direct Alexa control
- LLM: Groq free tier by default behind a pluggable `LLMProvider` interface
  (swap to Anthropic or local Ollama via config, no code changes)

## Open questions

- [ ] Kotlin native vs Flutter? (Native recommended: CameraX + ML Kit + kiosk
      behaviors are smoother without a framework in between)
- [ ] What Android version is the OnePlus on? (determines min SDK, CameraX support)
- [ ] Where does the brain live long-term — Mac, Pi, or VPS?
- [ ] Wake word name — is it "Mawa"?
