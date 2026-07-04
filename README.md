# Mawa

A wall-mounted ambient companion built from an old OnePlus phone. The screen
is a pair of eyes that track you around the room via the front camera —
think the Las Vegas Sphere, shrunk to a phone on the wall — with a voice,
a personality, and a small server brain behind it.

- [TODO.md](TODO.md) — phased build checklist
- [TECHNICAL_DESIGN.md](TECHNICAL_DESIGN.md) — full architecture, protocol
  spec, mood system, milestones

## Layout

```
mawa-face/    Android app (Kotlin) — the face: eyes, face tracking, kiosk mode
mawa-brain/   Python server — LLM personality, calendar/email, Spotify (Phase 2+)
```

## Status

**Phase 1 (M1):** eyes + on-device face tracking. No network, no audio yet.

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

## Building & installing

1. Install [Android Studio](https://developer.android.com/studio) (brings
   the Android SDK + adb).
2. Open the `mawa-face/` folder in Android Studio, or from the CLI:

   ```sh
   cd mawa-face
   ./gradlew assembleDebug        # first run downloads Gradle + deps
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

3. On the phone: enable Developer options (tap Build number 7×), enable
   USB debugging, plug in, accept the prompt.
4. For updates without taking the phone off the wall:

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
