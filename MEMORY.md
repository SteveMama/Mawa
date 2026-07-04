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

## Non-negotiable boundaries

- Never send or store camera frames outside the phone.
- Do not open or upload microphone audio before the wake word. Prefer local
  Android STT and send only final transcript text.
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
5. **Calendar connector:** dashboard connect flow, read-only events, manifest
   panel, first-sighting morning brief, and meeting heads-up budget rules.
6. **Gmail, then Spotify:** keep each connector independently degradable.
7. **Thermals/battery:** phone telemetry in the manifest request or a separate
   endpoint; adapt camera cadence before adding always-listening wake word.

## User-required wall actions

- OxygenOS Settings → Battery → Mawa → **Unrestricted**.
- After the OTA update, five-tap and confirm `brain: online` appears.
- Long-press while centered to calibrate and enroll.
- Test named greeting with Pranav and at least one other person under normal and
  dim lighting; report false accepts/rejects before changing threshold 0.62.

## Deployment workflow

- Pushes to `main` trigger the signed Android APK workflow and replace assets
  on the rolling GitHub `latest` release.
- Run `npm run typecheck`, `npm run build`, and `npm audit` in `mawa-brain`.
- Deploy the brain from `mawa-brain` with `vercel deploy --prod --yes`.
- Production alias: `https://mawa-brain.vercel.app`.
- Never commit `.vercel`, signing material, OAuth tokens, or `.env` files.
