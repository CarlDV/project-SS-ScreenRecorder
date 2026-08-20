# Screen recorder for the Galaxy A17 5G

A screen recorder for Samsung phones that don't ship one. The A17 5G runs One UI 8 but
Samsung withholds its built-in recorder from the A line, so this fills the gap: Quick
Settings tile, three sound modes, recordings in the Gallery, and styling that borrows One
UI's own widgets rather than imitating them.

Kotlin, no runtime dependencies at all — no AndroidX, no Material, no coroutines. minSdk 33
makes the compat libraries unnecessary, and `Theme.DeviceDefault` on a Samsung device *is*
One UI, so the sheet inherits Samsung's font, ripple and radio styling for free.

## Status

Working, verified on a physical Galaxy A17 5G (SM-A176B, Android 16):

- Recording at 1080p/720p/480p, H.264, with correct duration and playable output
- **No sound** and **Media** capture; recordings land in `DCIM/Screen recordings` and appear
  in the Gallery
- Pause and resume without a frozen gap in playback
- The projection is released on every exit path, so consecutive recordings work

Not proven yet, and honest about it:

- **Media and mic** mixing has not been listened to end to end
- The Android 16 status bar chip ("island") counter — the app requests promotion and adapts
  if declined, but whether One UI 8 grants it is unconfirmed. The capsule you see while
  recording may be Samsung's own system indicator, which no app can write into.

## Install

```bash
adb install -r dist/screenrec-debug.apk
```

Debug-signed. The mic and notification permissions can be granted from the shell; the
overlay permission and the screen-capture consent must be tapped.

```bash
adb shell pm grant dev.screenrec android.permission.RECORD_AUDIO
adb shell pm grant dev.screenrec android.permission.POST_NOTIFICATIONS
adb shell appops set dev.screenrec SYSTEM_ALERT_WINDOW allow
```

## Using it

Add the **Screen recorder** tile to Quick Settings, or launch the app. Pick a sound mode and
a quality, press Start, accept the system consent, and a 3-2-1 countdown runs before capture
begins.

**Pause and Stop live in the notification**, not on screen. That is deliberate — see
"Keeping the controls out of the video" below.

## Keeping the controls out of the video

`MediaProjection` mirrors the whole display, overlays included, so a floating control pill
would be burned into every frame. The intended mechanism was `FLAG_SECURE`, which asks the
compositor to omit secure layers from a non-secure mirror.

On One UI 8 it does not omit them — it renders them **black**, which put a black box in the
finished video. Since the requirement is that nothing of ours reaches the recording, the pill
is not drawn during capture at all and the notification is the control surface.
`OverlayController.renderPillDuringCapture` flips it back on for anyone testing a build where
secure layers really are excluded.

The countdown overlay still appears, and is torn down with `removeViewImmediate()` plus a
short settle delay before the first frame is encoded — `removeView()` alone only queues the
removal, and the main thread then spends a few hundred milliseconds on encoder setup, which
was long enough for the countdown to show up as a black box.

## Building

Always through `./build.sh` — never the bare `gradle` binary:

```bash
./build.sh assembleDebug testDebugUnitTest
```

The sandbox this was developed in has no network beyond localhost and a read-only
`~/.gradle`, `~/.android` and `/tmp`, each of which breaks a different build stage. `build.sh`
redirects `GRADLE_USER_HOME`, `ANDROID_USER_HOME` and `java.io.tmpdir` into `.build/`, points
`GRADLE_RO_DEP_CACHE` at the immutable dependency cache, and passes `--offline`.

Pinned and mutually consistent, all resolvable offline: Gradle 9.3.1, AGP 9.0.1, Kotlin
2.3.20, JDK 21, JUnit 4.13.2, compileSdk/targetSdk 36, minSdk 33.

Three build details are load-bearing and easy to undo by accident:

- **Do not apply `org.jetbrains.kotlin.android`.** AGP 9 has built-in Kotlin and rejects that
  plugin. The `kotlin-gradle-plugin:2.3.20` entry on the root `buildscript` classpath exists
  only to pin the built-in toolchain, which otherwise defaults to an uncached 2.2.10.
- **`kotlin-reflect` is forced to 2.2.0**, because AGP's `sdk-common` asks for an uncached
  2.2.10.
- **AGP 9's Groovy DSL needs assignment syntax** — `compileSdk = 36`, not `compileSdk 36`.

`versionName` carries the short git SHA, so `adb shell dumpsys package dev.screenrec | grep
versionName` always says exactly which build is on the device.

## Layout

```
record/         session orchestration, config, state machine
  video/        MediaProjection -> VirtualDisplay -> AVC MediaCodec, size negotiation
  audio/        playback-capture and mic AudioRecords, PCM mixing, AAC, timestamps
mux/            MediaMuxer wrapper, track gating, video PTS correction
output/         pending MediaStore entry -> descriptor -> publish
service/        foreground service, notification, status bar chip
overlay/        WindowManager windows (countdown; pill disabled during capture)
ui/, tile/      start sheet, Quick Settings tile
settings/       last-used choices
```

The subtle logic lives in units with no Android dependencies so it can be tested on the JVM:
timestamp arithmetic, PCM mixing, encoder size negotiation, muxer gating, the state machine.

## Tests

```bash
./build.sh testDebugUnitTest
```

78 JVM tests, no device or Robolectric needed. They cover the parts that are easy to get
quietly wrong: video timestamps rebased off device uptime and corrected across arbitrary
pause patterns, audio timestamps derived from sample count, PCM saturation and mono upmix,
encoder sizes clamped and aligned to whatever the encoder reports, muxer start deferred until
every track is added, and the chip-promotion fallback.

Worth knowing: these tests all passed while a real bug shipped, because every one of them fed
video timestamps starting at zero while the device supplies uptime. Tests encode assumptions;
if the assumption is wrong they confirm it happily.

## Verifying a recording

```bash
python3 tools/verify_recording.py --video rec.mp4 --expect-sound
```

Checks what a person would otherwise eyeball and misjudge: that the file decodes, that the
tracks are the ones asked for, that no pause left a frozen gap, and — with `--expect-sound` —
that the audio track spans the video and is actually audible rather than silent. Add
`--pill-region x,y,w,h --screen-width 1080` to assert by pixel inspection that nothing of ours
appears where an overlay sat. Needs `ffmpeg`, `ffprobe` and Pillow.

## Known limitations

- **Rotation is frozen at the moment recording starts.** A live `MediaCodec` stream cannot be
  resized; content rotates inside the original frame.
- **Some audio records silent.** Apps may opt out of playback capture, and DRM audio is never
  captured. Platform-imposed, not a bug here.
- **Frame rate follows the content.** A mirrored display emits a frame when the screen
  changes, so a static screen legitimately produces far fewer frames than the panel refreshes.
  The encoder's hint tracks the panel's refresh rate, capped at 60.
- **No pen annotation, selfie overlay or in-app recordings list.** Samsung's own recorder has
  no recordings list either — video goes to the Gallery.

## Documents

- [Design spec](docs/superpowers/specs/2026-08-20-screen-recorder-design.md) — the pipeline
  choice, the alternatives rejected, and the API verification behind them
- [Implementation plan](docs/superpowers/plans/2026-08-20-screen-recorder.md) — the
  task-by-task build, with the offline build constraints derived from an actual build
