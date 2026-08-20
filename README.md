# Screen recorder

A screen recorder for Android 13 and up: Quick Settings tile, three sound modes including
internal audio, recordings in the Gallery, and no floating controls burned into the video.

Nothing in it is device-specific — it uses framework APIs only, and `Theme.DeviceDefault`
means it wears whichever OEM skin it lands on. It exists because Samsung withholds its
built-in recorder from the A line, so a Galaxy A17 5G (SM-A176B, Android 16 / One UI 8) is
what it was written for and the only phone it has actually been run on. Everything below that
is marked as verified was verified there; on other hardware, treat it as untested rather than
broken.

Kotlin, with no runtime dependencies at all — no AndroidX, no Material, no coroutines. minSdk
33 makes the compat libraries unnecessary, and on a Samsung device `Theme.DeviceDefault` *is*
One UI, so the sheet inherits the system font, ripple and radio styling for free rather than
imitating them.

## Status

Verified on the A17:

- Recording at 1080p/720p/480p, H.264, with correct duration and playable output
- **No sound** and **Media** capture; recordings land in `DCIM/Screen recordings` and appear
  in the Gallery
- Pause and resume without a frozen gap in playback
- The projection is released on every exit path, so consecutive recordings work

Not proven yet, and honest about it:

- **Media and mic** mixing has not been listened to end to end
- The Android 16 status bar chip ("island") counter — the app requests promotion and adapts
  if declined, but whether One UI 8 grants it is unconfirmed. The capsule shown while
  recording may be the system's own recording indicator, which no app can write into.
- The floating pill, the Quick Settings Stop toggle, and recording started in landscape are all
  written and unit-tested where they can be, but have not been run on a device
- The pill's drop shadow and its animations. `Paint.setShadowLayer` on a hardware canvas needs
  API 28 or newer, which minSdk 33 guarantees, but nothing here has drawn a frame on real glass.
- Anything on non-Samsung hardware, or on Android 13–15 as opposed to 16

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

**Tap the app icon.** That is the whole of starting a recording: accept the system consent, and
a 3-2-1 countdown runs before capture begins. The Quick Settings tile does the same thing. There
is no start screen to get through, which also means there is nothing to be unreachable in
landscape.

The sound, quality and floating-control choices live behind **long-press on the app icon →
Settings**. They are remembered, and every recording uses whatever was last chosen.

There are four ways to stop, because no single one of them is reachable everywhere:

- The **floating pill** — a draggable capsule with the timer, pause and stop. It collapses to a
  small dot after a few seconds and expands again on a tap.
- The **app icon**, which stops the session it would otherwise start.
- The **Quick Settings tile**, which becomes Stop while a recording is running.
- The **notification's** Pause and Stop actions.

When the recording is saved, a notification says so — tap it and the video opens.

The pill is on by default and **is part of the recording** — see "The pill is in the video"
below. Turn off **Floating controls** in Settings for clean video; the tile, the icon and the
notification still work.

## The pill is in the video

`MediaProjection` mirrors the whole display, overlays included, and there is no public API to
exclude a window from that mirror. `FLAG_SECURE` is not it: the compositor blacks a secure layer
out in a non-secure mirror rather than omitting it, so a secure pill is a black capsule in the
finished video rather than no capsule. That was verified on One UI 8, and blacking out — not
omission — is the AOSP behaviour too.

So the choice is an on-screen control that appears in the recording, or no on-screen control.
The first shipped, because the second could not be stopped: the notification's Stop needs the
shade pulled down and the row expanded, which is awkward in landscape and impossible over an
immersive game, and the Android 16 status bar chip that would otherwise carry a Stop does not
exist before One UI 8.5. Given it is in the video, the pill is drawn to look deliberate rather
than like a leaked piece of app UI — a shadowed One UI capsule, on One UI's easing curve.

What it does *not* do is animate continuously. A mirrored display emits a frame whenever it
changes, so an idle pulse would make a static screen encode at 60fps; between transitions the
pill repaints once a second and no more. The animations it does have — growing into place,
collapsing to the dot, gliding to the edge on release, the pause glyph swapping — are a couple
of hundred milliseconds each and only run when something happened, which is frames per event
rather than a permanent tax on the encoder.

`Floating controls` off is the escape hatch, and the pixel assertion in
`tools/verify_recording.py` is how to check what actually landed in the frame.

The countdown overlay is the opposite case: it carries `FLAG_SECURE` *and* is torn down with
`removeViewImmediate()` plus a short settle delay before the first frame is encoded —
`removeView()` alone only queues the removal, and the main thread then spends a few hundred
milliseconds on encoder setup, which was long enough for the countdown to show up as a black
box.

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
service/        foreground service, notification, status bar chip, shared session flag
overlay/        WindowManager windows: countdown, and the draggable pill
ui/, tile/      invisible starter, settings sheet, Quick Settings tile
settings/       last-used choices
```

The subtle logic lives in units with no Android dependencies so it can be tested on the JVM:
timestamp arithmetic, PCM mixing, encoder size negotiation, muxer gating, the state machine,
pill placement across a rotation, and the collapse morph.

## Tests

```bash
./build.sh testDebugUnitTest
```

97 JVM tests, no device or Robolectric needed. They cover the parts that are easy to get
quietly wrong: video timestamps rebased off device uptime and corrected across arbitrary
pause patterns, audio timestamps derived from sample count, PCM saturation and mono upmix,
encoder sizes clamped and aligned to whatever the encoder reports, muxer start deferred until
every track is added, the chip-promotion fallback, and the pill's placement and morph
arithmetic.

One of them is a race: `MuxerGateTest.addingATrackWhileTheOtherWritesLosesNothing` runs the
audio track being added against video samples still being queued, which is what the two encoder
drain threads really do. With the gate's lock removed it loses samples within a handful of runs.

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
  resized; content rotates inside the original frame. Starting in landscape is fine — the mirror
  already emits frames the right way up, so the container carries no rotation hint at all.
- **Some audio records silent.** Apps may opt out of playback capture, and DRM audio is never
  captured. Platform-imposed, not a bug here. If the audio device cannot be opened at all the
  recording continues without sound and says so when it saves.
- **Frame rate follows the content.** A mirrored display emits a frame when the screen
  changes, so a static screen legitimately produces far fewer frames than the panel refreshes.
  The encoder's hint tracks the panel's refresh rate, capped at 60.
- **No pen annotation, selfie overlay or in-app recordings list.** Samsung's own recorder has
  no recordings list either — video goes to the Gallery.
- **`DCIM/Screen recordings` is Samsung's location**, used everywhere for consistency. On
  other OEMs the folder is simply created if absent.

## Documents

- [Design spec](docs/superpowers/specs/2026-08-20-screen-recorder-design.md) — the pipeline
  choice, the alternatives rejected, and the API verification behind them
- [Implementation plan](docs/superpowers/plans/2026-08-20-screen-recorder.md) — the
  task-by-task build, with the offline build constraints derived from an actual build
