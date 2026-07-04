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
  buffer after calculating RMS energy, and drives pupil pulse/face bounce.
- Personal and Work Google Calendar ICS connectors are implemented. They need
  `PERSONAL_CALENDAR_ICS_URL` and `WORK_CALENDAR_ICS_URL` in Vercel before they
  become active. Recurring events are expanded for the next 24 hours.
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
4. **Authentication/storage foundation:** device bearer token, encrypted OAuth
   token store, and durable state suitable for Vercel (managed KV/Postgres, not
   local files).
5. **Calendar activation:** add both private Google ICS URLs in Vercel, verify
   Personal/Work panels on the phone, then add first-sighting morning briefs
   and meeting heads-up budget rules.
6. **Gmail, then Spotify:** keep each connector independently degradable.
7. **Thermals/battery:** phone telemetry in the manifest request or a separate
   endpoint; adapt camera cadence before adding always-listening wake word.

## User-required wall actions

- OxygenOS Settings → Battery → Mawa → **Unrestricted**.
- After the OTA update, five-tap and confirm `brain: online` appears.
- Long-press while centered to calibrate and enroll.
- Grant microphone permission for local beat reactivity and test with music at
  normal room volume.
- Provide the private ICS URLs from Google Calendar settings for the Personal
  and Work calendars; never paste public share links.
- Test named greeting with Pranav and at least one other person under normal and
  dim lighting; report false accepts/rejects before changing threshold 0.62.

## Deployment workflow

- Pushes to `main` trigger the signed Android APK workflow and replace assets
  on the rolling GitHub `latest` release.
- Run `npm run typecheck`, `npm run build`, and `npm audit` in `mawa-brain`.
- Deploy the brain from `mawa-brain` with `vercel deploy --prod --yes`.
- Production alias: `https://mawa-brain.vercel.app`.
- Never commit `.vercel`, signing material, OAuth tokens, or `.env` files.
