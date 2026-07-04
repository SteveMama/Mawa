# Mawa

A wall-mounted ambient companion built from an old OnePlus phone. The screen
is a pair of eyes that track you around the room via the front camera —
think the Las Vegas Sphere, shrunk to a phone on the wall — with a voice,
a personality, and a small server brain behind it.

- [TODO.md](TODO.md) — phased build checklist
- [TECHNICAL_DESIGN.md](TECHNICAL_DESIGN.md) — full architecture, protocol
  spec, mood system, milestones
- [MEMORY.md](MEMORY.md) — durable project state, decisions, and next build order

## Layout

```
mawa-face/    Android app (Kotlin) — the face: eyes, face tracking, kiosk mode
mawa-brain/   Next.js app on Vercel — scene manifests, dashboard, connectors
```

## Status

**Phase 1 (M1) is complete. The first cloud-brain slice is live.**

What works in this phase:

- Two capsule eyes on a pure-black OLED background, 60 fps
- Face tracking at ~5 fps via CameraX + ML Kit (fully on-device — no frame
  ever leaves the phone)
- Blinks, micro-saccades, idle wander, moods, burn-in drift
- Sleeps (eyes close, breathing lids) after 10 min alone; wakes when it
  sees you
- Kiosk behaviors: fullscreen immersive, screen always on, relaunch on
  boot, auto-restart on crash
- Calibration overlay: tap the screen 5 times to see raw/mapped gaze values
- Ambient awareness: sleeps (with a floating ZZZ) when the lights go off,
  animates live weather (rain/snow/fog/thunder) over the eyes, greets you by
  time of day, blinks back when you blink, closes its eyes when you cover the
  camera, warms the black slightly at golden hour
- Vercel brain at [mawa-brain.vercel.app](https://mawa-brain.vercel.app):
  connector dashboard and a schema-versioned `/api/manifest`
- Weather connector #1: the phone polls the brain every five minutes and
  renders a cloud-composed status panel plus the matching weather animation;
  local Open-Meteo remains the offline fallback
- Night-aware light sleep: lux can force sleep only from 10 PM through 5:59 AM
- On-device music beat reactivity: pupils pulse and the face bounces without
  recording, identifying, or uploading room audio
- OAuth-backed Personal and Work Google Calendar slots with encrypted refresh
  tokens, dashboard connect buttons, and private next-event panels

## Face recognition

CI downloads and bundles a compatible MobileFaceNet TFLite model. The
recognition pipeline is active in CI builds but still needs wall testing:

1. On the phone, long-press once while it sees your face — that
   calibrates gaze *and* enrolls you.
2. Verify strangers do not trigger the named greeting.
3. Tune `FaceRecognizer.THRESHOLD`: same person scores high on
   cosine similarity, strangers low.

## Building & installing

Android APKs are normally built and signed by GitHub Actions on every push to
`main`, then published to the rolling `latest` release for OTA installation.
For a local build, install Android Studio and run:

```sh
cd mawa-face
./gradlew assembleDebug        # first run downloads Gradle + deps
adb install app/build/outputs/apk/debug/app-debug.apk
```

On the phone: enable Developer options (tap Build number 7×), enable
USB debugging, plug in, accept the prompt.
For updates without taking the phone off the wall:

```sh
adb tcpip 5555
adb connect <phone-ip>:5555
```

## Phone setup (wall appliance)

- Screen timeout → the app holds the screen on itself, but disable the
  lockscreen (Settings → Security → None)
- Grant Mawa **Display over other apps** (needed for relaunch-after-boot on
  Android 10+)
- Grant camera permission on first launch
- Mount landscape, camera edge unobstructed
- Stand dead-center in front of the wall, 5-tap for the debug overlay, and
  tune `GazeMapper.offsetX/offsetY/gain*` until the eyes look straight at you
