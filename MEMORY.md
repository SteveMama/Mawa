# Mawa project memory

Last updated: 2026-07-04

This is the durable handoff for future build sessions. `TODO.md` is the
checklist; this file records why the system looks the way it does and the order
in which to extend it.

## Current system

- `mawa-face` is a native Kotlin/Canvas Android wall appliance on a landscape
  OnePlus running Android 13/OxygenOS.
- Vision is local: CameraX + bundled ML Kit detection at ~5 fps and optional
  MobileFaceNet identity embeddings. Camera frames never leave the phone.
- The face works offline: tracking, animation, sleep, weather fallback,
  calibration, TTS, boot recovery, crash recovery, and OTA update checks.
- `mawa-brain` is Next.js 16 on Vercel, deployed at
  `https://mawa-brain.vercel.app`.
- Brain-to-phone state uses a versioned, declarative HTTP scene manifest. This
  replaces the original Python/FastAPI/WebSocket plan.
- Weather is connector #1. Android sends coordinates rounded to two decimals
  (~1 km), receives a weather scene plus a short status panel, and falls back
  to direct Open-Meteo when the brain cannot be reached.
- Lux-triggered sleep is gated to 22:00–05:59; darkness during the day does not
  force sleep.
- Room-music beat detection runs on-device at 8 kHz, discards each microphone
  buffer after calculating RMS energy, and now drives pupil pulse, whole-face
  bounce, a pulsing background aura, equalizer bars, floating music glyphs, and
  faster expressive blinking.
- Face enrollment now implies an identity-lock mode. Double-tap toggles between
  locked-to-Pranav behavior and a relaxed ambient mode; the lock state is
  persisted on-device.
- Personal and Work Google Calendar connectors now use Google OAuth instead of
  ICS feeds. The dashboard can connect one Google account per slot, stores
  refresh tokens encrypted at rest, reads each account's primary calendar
  for the next 24 hours, and uses the Google OAuth callback to unlock the
  current dashboard session.
- Groq personality is now implemented in the brain. If `GROQ_API_KEY` is set,
  the manifest can emit a private ambient thought panel and cloud-driven mood,
  and the dashboard exposes a protected chat tester using the same prompt.
- A shared `MAWA_DEVICE_TOKEN` is paired through Vercel and GitHub Actions.
  Private calendar panels require it and never appear in public previews.

## Non-negotiable boundaries

- Never send or store camera frames outside the phone.
- Ambient microphone access is permitted only for local beat-energy analysis;
  discard samples immediately and never store or upload them. Future voice STT
  should prefer on-device recognition and send only final transcript text.
- OAuth and LLM secrets live only in Vercel environment variables.
- Google integrations use read-only scopes. Encrypt refresh tokens at rest.
- Every cloud feature must degrade cleanly; the eyes cannot depend on Vercel.
- Manifest payloads are data, never code. Keep schema versions explicit and
  cap panel count/text length on the phone.
- The manifest now carries a stable remote animation direction pack
  (`palette`, `gazeMode`, `energy`, `aura`, `bars`, `glyphs`, `sway`,
  `bounce`, `blinkRate`, `openness`, `pupilScale`, `squint`). Future
  personality and expression tweaks should prefer extending this declarative
  surface before shipping new APK behavior.

## Next build order

1. **Wall verification:** set OxygenOS battery mode to Unrestricted, receive
   the OTA build, long-press to enroll, confirm the brain status is online in
   the five-tap overlay, and collect genuine/impostor recognition scores.
2. **Recognition diagnostics:** the overlay now shows model/enrollment state,
   current cosine score, ME/OTHER, and threshold. Collect genuine/impostor
   readings, then add an optional local score sample and make “new person”
   identity-aware.
3. **Voice vertical slice:** listening/thinking/speaking eye states, Android
   `SpeechRecognizer`, transcript POST endpoint, pluggable Groq provider, and
   reply through existing Android TTS. Wake word comes immediately after this
   push-to-talk/debug path proves the loop.
4. **Calendar activation:** create Google OAuth web credentials, add the
   production secrets (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`,
   `MAWA_SIGNING_SECRET`, `MAWA_STATE_ENCRYPTION_SECRET`), ensure the Vercel
   Blob store/project attachment is valid, then connect the
   Personal and Work accounts from the dashboard and verify panels on the
   phone.
5. **Calendar behaviors:** morning brief on first sighting and meeting heads-up
   budget rules.
6. **Voice loop:** Android STT -> Groq reply -> TTS, reusing the new companion
   prompt and keeping transcripts text-only.
7. **Gmail, then Spotify:** keep each connector independently degradable.
8. **Thermals/battery:** phone telemetry in the manifest request or a separate
   endpoint; adapt camera cadence before adding always-listening wake word.

## User-required wall actions

- OxygenOS Settings → Battery → Mawa → **Unrestricted**.
- After the OTA update, five-tap and confirm `brain: online` appears.
- Long-press while centered to calibrate and enroll.
- Grant microphone permission for local beat reactivity and test with music at
  normal room volume.
- Create a Google OAuth web client with callback URLs for local dev and
  `https://mawa-brain.vercel.app/api/google/callback`, then set the production
  env vars listed above before connecting the Personal and Work accounts.
- Test named greeting with Pranav and at least one other person under normal and
  dim lighting; report false accepts/rejects before changing threshold 0.62.

## Deployment workflow

- Pushes to `main` trigger the signed Android APK workflow and replace assets
  on the rolling GitHub `latest` release.
- Run `npm run typecheck`, `npm run build`, and `npm audit` in `mawa-brain`.
- Deploy the brain from `mawa-brain` with `vercel deploy --prod --yes`.
- Production alias: `https://mawa-brain.vercel.app`.
- Never commit `.vercel`, signing material, OAuth tokens, or `.env` files.
