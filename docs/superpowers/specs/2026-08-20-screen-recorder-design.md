# Screen Recorder for Galaxy A17 5G — Design

**Date:** 2026-08-20
**Target device:** Samsung Galaxy A17 5G, Android 16 (One UI 8), API 36
**Goal:** Replace the screen recorder Samsung ships on its S/Z lines but withholds from the A line, matching One UI's version closely enough that it feels like a system feature.

## Purpose and success criteria

The A17 has no built-in screen recorder. This app supplies one that a One UI user would not recognise as third-party.

Success means all of the following:

1. A Quick Settings tile starts recording, the way Samsung's does.
2. The sound picker offers **No sound**, **Media**, and **Media and mic**, and all three genuinely work — media audio is the reason to record a screen at all.
3. **The floating control pill never appears in the recorded video.** This is a hard requirement, not a preference.
4. Recordings land in the Gallery automatically, playable and shareable.
5. Visual styling reads as One UI: bottom-anchored rounded sheet, generous spacing, Samsung's blue accent, system font.

Explicitly out of scope for v1: pen annotation, selfie-camera overlay, in-app recordings list. Samsung's own recorder has no recordings list either — video goes to the Gallery — so omitting it costs no fidelity.

## Chosen approach: MediaCodec + MediaMuxer

Three pipelines were considered.

**Rejected — `MediaRecorder` + VirtualDisplay.** Roughly 100 lines and pause/resume come free, but `MediaRecorder` accepts only named audio sources (`MIC`, `CAMCORDER`, …). Internal audio requires an `AudioRecord` constructed from an `AudioPlaybackCaptureConfiguration`, which `MediaRecorder` cannot ingest; the one source that would serve, `REMOTE_SUBMIX`, needs the privileged `CAPTURE_AUDIO_OUTPUT` permission. This pipeline can deliver no-sound and mic-only, which removes success criterion 2.

**Rejected — hybrid.** `MediaRecorder` for the quiet modes, a manual pipeline for the media modes. Two capture engines means two sets of timestamp, rotation and failure bugs while the manual pipeline already covers every mode. Pure duplication.

**Chosen — manual pipeline.** `MediaProjection` → `VirtualDisplay` → `MediaCodec` input Surface → `MediaMuxer` for video; one or two `AudioRecord`s → software PCM mix → AAC `MediaCodec` → the same muxer for audio. The cost is hand-written pause handling and track synchronisation. It is the only design in which "media and mic" is even expressible.

### API verification

Every API below was checked against `~/Android/Sdk/platforms/android-36/android.jar` with `javap` rather than assumed:

| API | Signature confirmed |
|---|---|
| `AudioPlaybackCaptureConfiguration.Builder` | takes `MediaProjection`; `addMatchingUsage`, `excludeUid` |
| `AudioRecord.Builder.setAudioPlaybackCaptureConfig` | present |
| `MediaProjection.createVirtualDisplay` / `registerCallback` | present |
| `MediaProjectionConfig.createConfigForDefaultDisplay()` | present — forces whole-screen capture, suppressing the system's "single app" choice so the consent dialog matches Samsung's |
| `MediaProjectionManager.createScreenCaptureIntent(MediaProjectionConfig)` | present |
| `MediaMuxer(FileDescriptor, int)`, `setOrientationHint` | present — allows muxing straight into a MediaStore descriptor |
| `MediaCodec.createInputSurface`, `signalEndOfInputStream` | present |
| `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` | `= 32` |
| `WindowManager.LayoutParams.FLAG_SECURE` / `TYPE_APPLICATION_OVERLAY` | `= 8192` / `= 2038` |
| `MediaStore.MediaColumns.IS_PENDING`, `RELATIVE_PATH` | present |
| `VirtualDisplay.setSurface` / `resize` | present — `setSurface(null)` is the pause mechanism |
| `VideoCapabilities.getWidthAlignment` / `getHeightAlignment` / `isSizeSupported` | present — encoder reports its own alignment, so none is assumed |
| `TileService.onClick`, `startActivityAndCollapse(PendingIntent)` | present |

## Keeping the pill out of the recording

`MediaProjection` mirrors the whole display, overlays included, so a naive floating pill would be burned into every frame.

**Mechanism:** the pill's window sets `FLAG_SECURE`. The compositor omits secure layers when mirroring to a non-secure virtual display, so the captured frame shows whatever sits behind the pill.

**This will be proven, not assumed.** Verification: record with the pill on screen, `adb pull` the MP4, extract frames, inspect the pixel region the pill occupied. If `FLAG_SECURE` fails on One UI 8 — either not excluding the layer, or blacking out the whole capture — the fallback is unconditional: the overlay does not render at all while capture is active, and the notification becomes the sole control surface. Under no outcome does the pill reach the video.

Related detail: the countdown overlay carries `FLAG_SECURE` for the same reason, though it is dismissed before the first frame is encoded.

## Architecture

Small units, each with one purpose and a testable boundary.

```
ui/         StartSheetActivity + XML layouts, One UI drawables/colors
tile/       RecorderTileService              — QS tile
service/    RecorderService                  — foreground service, owns the session
            RecorderNotifications            — channel, chronometer, pause/stop actions
record/     RecordingController              — orchestrates sources, encoders, sink
            RecordingStateMachine            — Idle→Countdown→Recording→Paused→Stopping
            RecordingConfig                  — resolved sound mode, dimensions, bitrate
  video/    ScreenCaptureSource              — MediaProjection + VirtualDisplay
            VideoEncoder                     — AVC MediaCodec + input Surface
            EncoderConfigFactory             — display metrics + preset → codec format
  audio/    AudioCaptureSource               — mic and/or playback-capture AudioRecord
            PcmMixer                         — saturating mix, mono→stereo upmix
            AudioEncoder                     — AAC MediaCodec, sample-count PTS
  mux/      MuxerSink                        — MediaMuxer wrapper, lock-guarded
            MuxerGate                        — defers start() until all tracks added
            PtsOffsetTracker                 — removes paused spans from video PTS
output/     MediaStoreOutput                 — pending entry → descriptor → publish
            RecordingFilename                — Screen_recording_yyyyMMdd_HHmmss.mp4
overlay/    OverlayController                — WindowManager windows, FLAG_SECURE
            PillView, CountdownView          — plain Views
settings/   SettingsRepository               — SharedPreferences
```

The entire UI is XML Views, including the start sheet. Compose was dropped: it cannot resolve offline at all — `compose-runtime` needs the root `kotlinx-coroutines-core` module, which is absent from the cache — and on merit a single static sheet of radio buttons gains nothing from recomposition while the overlay had to be plain Views regardless. One paradigm beats two.

Further, the UI uses **framework widgets only** — no AppCompat, no Material Components — and themes derive from `android:Theme.DeviceDefault`. This began as a constraint (see Build constraints) but is the better choice on merit: `DeviceDefault` on a Samsung device *is* One UI, so the system font, ripple, switch and radio styling come from Samsung's own framework rather than from Google's Material library imitating it. Importing Material Components would mean fighting One UI to look like One UI.

## Flow and ordering

The order below is forced by platform rules, not preference. On API 34+ the foreground service must already be running, with type `mediaProjection`, *before* `getMediaProjection()` is called; and `MediaProjection.Callback` must be registered before `createVirtualDisplay`.

1. QS tile (or launcher icon) → `StartSheetActivity`.
2. Sheet resolves permissions in order: `POST_NOTIFICATIONS`; overlay permission via `Settings.canDrawOverlays` → `ACTION_MANAGE_OVERLAY_PERMISSION`; `RECORD_AUDIO` only if the chosen sound mode needs it.
3. Sheet launches `createScreenCaptureIntent(createConfigForDefaultDisplay())`; user consents.
4. Result data is handed to `RecorderService`, which calls `startForeground` with type `mediaProjection` **first**, then `getMediaProjection(...)`, then `registerCallback`.
5. Countdown overlay: 3 → 2 → 1.
6. `RecordingController` builds the sink, encoders and sources, then capture begins. Pill appears.
7. Stop, from pill or notification: signal end of stream, drain encoders, `muxer.stop()`, clear `IS_PENDING`, post "Recording saved".

Consent is requested fresh for every session — Android 14+ will not let a token be reused after `stop()`.

## Component detail

**EncoderConfigFactory.** Reads real display size from `WindowMetrics`; scales the short edge to 1080/720/480 preserving aspect; rounds each dimension to the multiple the encoder itself reports via `VideoCapabilities.getWidthAlignment()`/`getHeightAlignment()` rather than assuming 2; then clamps against `getSupportedWidths`/`getSupportedHeightsFor` and `isSizeSupported` so an unsupported size never reaches the encoder. Bitrate 12/8/4 Mbps by preset, 30 fps, 1 s I-frame interval. Codec is AVC (H.264) rather than HEVC: Samsung's own recorder defaults to H.264, and it shares without transcoding surprises.

**AudioCaptureSource.** *No sound* builds nothing. *Media* builds one `AudioRecord` from an `AudioPlaybackCaptureConfiguration` matching usages `MEDIA`, `GAME` and `UNKNOWN`. *Media and mic* builds that plus a `MIC` record and mixes. Inherent platform limitation, to be documented in-app rather than worked around: apps may opt out of playback capture, and DRM-protected audio is always excluded, so some content records silent.

**PcmMixer.** Sums 16-bit samples with saturation instead of wrapping, and tolerates unequal buffer lengths by treating the shorter stream as silence past its end. Also upmixes the mic's mono capture to stereo before mixing, since the mic commonly refuses a stereo channel mask while playback capture yields stereo.

**PtsOffsetTracker.** Pause detaches the virtual display's surface (`setSurface(null)`) so frames stop arriving, and stops draining audio. Video timestamps come from the capture surface and therefore track wall clock, so the paused span must be subtracted or playback shows a frozen gap; the tracker accumulates paused duration and subtracts it, guaranteeing monotonically non-decreasing timestamps across any pause pattern. Audio needs no equivalent: its presentation timestamps are derived from the cumulative sample count, which simply stops advancing while paused, so pause correctness falls out for free. The tracker is video-only.

**MuxerGate.** `MediaMuxer.start()` is illegal before every track is added, and encoders emit `INFO_OUTPUT_FORMAT_CHANGED` at unpredictable moments. The gate collects expected formats — one track for *No sound*, two otherwise — queues any samples arriving early, then starts and flushes the queue in timestamp order.

**MediaStoreOutput.** Inserts into `MediaStore.Video` under `DCIM/Screen recordings` (Samsung's own location) with `IS_PENDING=1`, hands the muxer the `ParcelFileDescriptor`, and clears the flag on success. Failure deletes the pending row so no zero-byte entry pollutes the Gallery.

## Error handling

| Condition | Behaviour |
|---|---|
| Consent denied | Sheet dismisses, service never starts |
| Encoder init fails | Retry once at the next lower preset, then error notification |
| Storage full mid-recording | Stop, finalise and publish what was written |
| Projection stopped by system/user | `Callback.onStop` finalises and publishes |
| Service killed | Orphaned `IS_PENDING` rows cleaned up on next launch |
| Incoming call, screen off | Recording continues; expected behaviour |

## Testing

Seven units carry the subtle logic and are pure JVM-testable, driven test-first:

1. `PtsOffsetTracker` — monotonic non-decreasing output across arbitrary pause/resume patterns.
2. `PcmMixer` — saturation at both rails, unequal lengths, mono→stereo upmix.
3. `EncoderConfigFactory` — aspect preserved, alignment honoured for arbitrary reported values (2, 16, …), bitrate mapping, capability clamping, with fake capabilities injected so no device is needed.
4. `RecordingStateMachine` — legal transitions, illegal ones rejected.
5. `MuxerGate` — no start before tracks complete, queued samples flushed in order.
6. `RecordingFilename` — timestamp format under a fixed clock and zone.
7. `AudioPts` — sample-count-derived timestamps, including that a paused gap produces no discontinuity.

Everything else — projection consent, real encoding, audio routing, and the `FLAG_SECURE` exclusion — is verified on the physical A17 over `adb`: install, drive the app, pull the MP4, confirm the video decodes, the audio track exists, pause produces no gap, and the pill's region shows app content rather than the pill.

## Known limitations, stated rather than hidden

- **Rotation mid-recording** cannot resize a live `MediaCodec` stream. v1 captures at the orientation recording began in; content rotates inside that frame. This matches most third-party recorders.
- **Silent recordings** occur for apps that opt out of playback capture or play DRM audio. Platform-imposed.
- **minSdk 33** (Android 13). The device is Android 16; 33 keeps the APK usable on other recent phones without forcing legacy branches. targetSdk/compileSdk 36.

## Build constraints

The sandbox reaches only `localhost`, so Gradle builds **offline** against the existing cache. Rather than reason about the cache from a directory listing, the entire stack was scaffolded in a scratch project and built: `clean assembleDebug testDebugUnitTest` green, including a probe source file that references every platform API in the table above. What follows is what that build proved.

- **AGP 9 supplies Kotlin itself.** AGP 9.0.1 *rejects* `org.jetbrains.kotlin.android` — "no longer required for Kotlin support since AGP 9.0" — so the missing plugin marker is moot. But its built-in Kotlin defaults to **2.2.10**, whose compiler artifacts are uncached; putting `kotlin-gradle-plugin:2.3.20` on the root `buildscript` classpath pins the toolchain to the cached version. Removing that entry fails the build, so it is load-bearing rather than decorative. AGP itself is applied the same way; the `com.android.application` marker exists at **9.0.1 only**, and 9.1.1 is independently unusable offline because its `sdk-common:32.1.1` has no POM cached.
- **One forced version.** AGP 9.0.1 pulls `sdk-common:32.0.1`, which asks for `kotlin-reflect:2.2.10` — absent. Forced to **2.2.0**, cached with its POM.
- **No `kotlin-test`, no `kotlinx-coroutines-core`.** Tests use **JUnit 4.13.2** with hand-written fakes, and the app uses **no coroutines** — plain threads and `Handler`, which suits explicit MediaCodec drain loops anyway.
- **No `androidx.databinding:viewbinding`.** viewBinding cannot be enabled; views are found with `findViewById`.
- **Zero runtime dependencies.** Beyond `kotlin-stdlib` the app declares nothing — no AndroidX, no Material. This began as a constraint and three separate cache holes would each have enforced it on their own: the root `androidx.annotation:annotation` and `kotlinx-coroutines-core` modules are absent (only `-jvm` variants cached), and AppCompat 1.7.1 transitively demands `androidx.activity:activity:1.8.0` while only 1.13.0 is present. minSdk 33 is what makes it painless — `Notification.Builder`, `checkSelfPermission` and platform themes are all native at that level — and as noted above `Theme.DeviceDefault` is the higher-fidelity choice anyway. The risk class disappears with the dependencies.
- **AGP 9 Groovy DSL requires assignment syntax.** `compileSdk = 36`, not `compileSdk 36`; the method form is rejected outright.

**Sandbox writability is a build input, not a footnote.** `~/.gradle`, `~/.android` and `/tmp` are all read-only here, and each breaks the build at a different stage — native-library extraction, metrics init, and Kotlin compilation respectively. Builds therefore run through a checked-in `build.sh` that redirects `GRADLE_USER_HOME`, `ANDROID_USER_HOME` and `java.io.tmpdir` to project-local directories and points `GRADLE_RO_DEP_CACHE` at `~/.gradle/caches` — Gradle's shared read-only dependency cache, which is the mechanism that makes an immutable cache usable at all. The Kotlin daemon cannot start under these constraints, so `kotlin.compiler.execution.strategy=in-process` is set; without it the build still passes by fallback, but spends a doomed daemon attempt and a stack trace on every run.

Pinned and verified together: Gradle **9.3.1** (run directly from `~/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin/gradle`, avoiding a wrapper download), AGP **9.0.1**, Kotlin **2.3.20**, JUnit **4.13.2** with Hamcrest **1.3**, JDK **21**. compileSdk/targetSdk **36**, minSdk **33**. Debug signing, sideloaded via `adb install`.

Host-side verification tooling is present and needs no network, all confirmed on PATH: `ffprobe` and `ffmpeg` for track inspection and frame extraction, and Python **3** with **PIL 10.2.0** for pixel assertions — which is what makes the `FLAG_SECURE` proof a measurement rather than an opinion. `adb` is not on PATH but present at `~/Android/Sdk/platform-tools/adb`.

**On-device verification is blocked for now:** `adb devices` reports none attached. Every JVM-testable unit can be driven green without hardware; consent, real encoding, audio routing and the `FLAG_SECURE` proof wait on the A17 being plugged in. The plan holds those in a final task rather than quietly claiming them.
