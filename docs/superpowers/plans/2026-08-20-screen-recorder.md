# One UI Screen Recorder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a screen recorder for the Galaxy A17 5G that captures the display with media and/or mic audio, is driven from a Quick Settings tile, keeps its own floating controls out of the recording, and saves to the Gallery — looking and behaving like the One UI recorder Samsung withholds from the A line.

**Architecture:** A foreground service owns one recording session. `MediaProjection` feeds a `VirtualDisplay` that renders into an AVC `MediaCodec` input Surface; one or two `AudioRecord`s feed a software PCM mixer into an AAC `MediaCodec`; both encoders drain into a single `MediaMuxer` writing to a pending `MediaStore` entry. The subtle logic (timestamp arithmetic, mixing, size negotiation, muxer gating) lives in dependency-free units tested on the JVM; the platform glue is thin enough to verify on-device.

**Tech Stack:** Kotlin 2.3.20, AGP 9.0.1, Gradle 9.3.1, JDK 21, compileSdk/targetSdk 36, minSdk 33, JUnit 4.13.2. Zero runtime dependencies — no AndroidX, no Material, no coroutines. Framework widgets under `android:Theme.DeviceDefault`.

**Spec:** [docs/superpowers/specs/2026-08-20-screen-recorder-design.md](../specs/2026-08-20-screen-recorder-design.md)

## Global Constraints

Every task's requirements implicitly include this section.

- **Build offline, always.** Invoke builds only through `./build.sh` (Task 1). It sets `GRADLE_USER_HOME`, `ANDROID_USER_HOME`, `GRADLE_RO_DEP_CACHE` and `java.io.tmpdir`; `~/.gradle`, `~/.android` and `/tmp` are read-only in this sandbox and each breaks a different build stage. Never run the `gradle` binary bare, never add `gradlew`, never remove `--offline`.
- **No new dependencies.** The only declared dependency is `testImplementation 'junit:junit:4.13.2'`. Adding anything else — AndroidX, Material, coroutines, `kotlin-test`, mockito — will fail offline. If a task seems to need one, use the framework API or a hand-written fake.
- **Do not apply `org.jetbrains.kotlin.android`.** AGP 9 has built-in Kotlin and rejects that plugin. Kotlin's version is pinned only by the `kotlin-gradle-plugin:2.3.20` entry on the root `buildscript` classpath.
- **Versions are fixed:** Gradle 9.3.1, AGP 9.0.1, Kotlin 2.3.20, JUnit 4.13.2, Hamcrest 1.3, JDK 21, compileSdk 36, targetSdk 36, minSdk 33. `kotlin-reflect` forced to 2.2.0.
- **AGP 9 Groovy DSL uses assignment syntax:** `compileSdk = 36`, never `compileSdk 36`.
- **Package root:** `dev.screenrec`. **applicationId:** `dev.screenrec`.
- **No viewBinding** (`androidx.databinding:viewbinding` is not cached). Use `findViewById`.
- **Filename format:** `Screen_recording_yyyyMMdd_HHmmss.mp4`. **Output location:** `DCIM/Screen recordings`.
- **Sound modes, exact user-facing copy:** `No sound`, `Media`, `Media and mic`.
- **Video:** AVC (H.264), 30 fps, 1 s I-frame interval, bitrate 12/8/4 Mbps for the 1080/720/480 presets. **Audio:** AAC LC, 44100 Hz, stereo, 128 kbps.
- **The floating pill must never appear in the recorded video.** Every overlay window sets `FLAG_SECURE`. This is a hard requirement; Task 16 proves it by pixel inspection and switches to the notification-only fallback if the platform does not honour it.
- **JVM tests must not touch the Android framework.** Units under test take injected interfaces (`EncoderCapabilities`, `MuxerTarget`) so `testDebugUnitTest` runs without a device or Robolectric.
- **Commit after every task.** Conventional-commit subject, imperative mood.

---

## File Structure

Sources live under `app/src/main/java/dev/screenrec/`, JVM tests under `app/src/test/java/dev/screenrec/`.

| File | Responsibility |
|---|---|
| `build.sh` | Sole build entry point; sets the four sandbox redirections |
| `settings.gradle`, `build.gradle`, `gradle.properties`, `local.properties` | Offline build configuration |
| `app/build.gradle` | Android module config, single test dependency |
| `app/src/main/AndroidManifest.xml` | Permissions, service types, tile, activity, overlay |
| `record/RecordingConfig.kt` | `SoundMode`, `QualityPreset`, `RecordingConfig`, `VideoFormatSpec` |
| `record/RecordingStateMachine.kt` | Legal transitions between the five session states |
| `output/RecordingFilename.kt` | Timestamped filename |
| `output/MediaStoreOutput.kt` | Pending `MediaStore` row → descriptor → publish or delete |
| `record/video/EncoderConfigFactory.kt` | Display size + preset + encoder capabilities → `VideoFormatSpec` |
| `record/video/EncoderCapabilities.kt` | Injectable view of `MediaCodecInfo.VideoCapabilities` |
| `record/video/VideoEncoder.kt` | AVC `MediaCodec`, input Surface, drain loop |
| `record/video/ScreenCaptureSource.kt` | `MediaProjection` + `VirtualDisplay`, pause via `setSurface(null)` |
| `record/audio/AudioPts.kt` | Sample-count-derived presentation timestamps |
| `record/audio/PcmMixer.kt` | Saturating 16-bit mix, mono→stereo upmix |
| `record/audio/AudioCaptureSource.kt` | Playback-capture and/or mic `AudioRecord`, reader thread |
| `record/audio/AudioEncoder.kt` | AAC `MediaCodec`, drain loop |
| `mux/PtsOffsetTracker.kt` | Removes paused spans from video PTS |
| `mux/MuxerGate.kt` | Defers `start()` until all tracks added; queues early samples |
| `mux/MuxerSink.kt` | `MediaMuxer` wrapper implementing `MuxerTarget<MediaFormat>` |
| `record/RecordingController.kt` | Builds and tears down the whole pipeline |
| `service/RecorderService.kt` | Foreground service, session owner, action intents |
| `service/RecorderNotifications.kt` | Channel, chronometer notification, actions |
| `overlay/OverlayController.kt` | `WindowManager` windows with `FLAG_SECURE` |
| `overlay/PillView.kt`, `overlay/CountdownView.kt` | Floating controls, 3-2-1 countdown |
| `ui/StartSheetActivity.kt` | Bottom sheet: sound mode, quality, permissions, consent |
| `settings/SettingsRepository.kt` | `SharedPreferences` for last-used choices |
| `tile/RecorderTileService.kt` | Quick Settings tile |
| `tools/verify_recording.py` | Host-side MP4 assertions incl. the `FLAG_SECURE` pixel proof |

---

### Task 1: Offline build harness and the first real unit

The stack below was proven by a scratch build before this plan was written: `clean assembleDebug testDebugUnitTest` green, offline, with a source file referencing every platform API the design needs. Copy it exactly — every line of `build.gradle` is load-bearing, and the comments say why.

**Files:**
- Create: `build.sh`, `settings.gradle`, `build.gradle`, `gradle.properties`, `local.properties`, `.gitignore` (modify if present)
- Create: `app/build.gradle`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/dev/screenrec/output/RecordingFilename.kt`
- Test: `app/src/test/java/dev/screenrec/output/RecordingFilenameTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `RecordingFilename.forEpochMillis(millisSinceEpoch: Long, zone: java.time.ZoneId): String` returning e.g. `Screen_recording_20260820_171203.mp4`. A working `./build.sh` accepting Gradle task names as arguments.

- [ ] **Step 1: Write the build harness**

`build.sh` — mark it executable (`chmod +x build.sh`):

```bash
#!/usr/bin/env bash
# Sole build entry point. ~/.gradle, ~/.android and /tmp are read-only in this
# sandbox; each one breaks a different build stage, so all four redirections below
# are required. GRADLE_RO_DEP_CACHE is Gradle's shared read-only dependency cache --
# the mechanism that makes the immutable ~/.gradle/caches usable offline.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_BIN="$HOME/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin/gradle"

export GRADLE_USER_HOME="$ROOT/.build/gradle-home"
export ANDROID_USER_HOME="$ROOT/.build/android-home"
export GRADLE_RO_DEP_CACHE="$HOME/.gradle/caches"
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME" "$ROOT/.build/jvmtmp"

exec "$GRADLE_BIN" --offline --console=plain "$@"
```

`settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = 'screenrec'
include ':app'
```

- [ ] **Step 2: Write the root build script**

`build.gradle`:

```groovy
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 has built-in Kotlin support and REJECTS the org.jetbrains.kotlin.android
        // plugin. Its built-in Kotlin defaults to 2.2.10, which is not in the offline
        // cache; this classpath entry pins the toolchain to cached 2.3.20. Removing it
        // fails the build with missing kotlin-build-tools-impl-2.2.10.
        classpath 'com.android.tools.build:gradle:9.0.1'
        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20'
    }
    // AGP 9.0.1 -> sdk-common 32.0.1 asks for kotlin-reflect 2.2.10, absent from the
    // cache. 2.2.0 is cached with its POM and serves AGP's internal reflection.
    configurations.classpath {
        resolutionStrategy {
            force 'org.jetbrains.kotlin:kotlin-reflect:2.2.0'
        }
    }
}
```

`gradle.properties` — replace `<ROOT>` with the absolute repository path, because `java.io.tmpdir` must be absolute:

```properties
org.gradle.jvmargs=-Xmx2048m -Djava.io.tmpdir=<ROOT>/.build/jvmtmp
# The Kotlin daemon cannot start when /tmp is read-only. Without this the build still
# passes by fallback, but wastes a doomed daemon attempt and a stack trace every run.
kotlin.compiler.execution.strategy=in-process
android.useAndroidX=false
```

`local.properties`:

```properties
sdk.dir=/home/david/Android/Sdk
```

Append to `.gitignore`:

```gitignore
.build/
app/build/
local.properties
```

- [ ] **Step 3: Write the app module script and a minimal manifest**

`app/build.gradle` — note every value uses `=`; AGP 9 rejects the method form:

```groovy
apply plugin: 'com.android.application'

android {
    namespace = 'dev.screenrec'
    compileSdk = 36

    defaultConfig {
        applicationId = 'dev.screenrec'
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = '1.0'
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            minifyEnabled = false
        }
    }
}

dependencies {
    testImplementation 'junit:junit:4.13.2'
}
```

`app/src/main/AndroidManifest.xml` — grown in later tasks:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:label="Screen recorder"
        android:theme="@android:style/Theme.DeviceDefault.DayNight" />

</manifest>
```

- [ ] **Step 4: Write the failing test**

`app/src/test/java/dev/screenrec/output/RecordingFilenameTest.kt`:

```kotlin
package dev.screenrec.output

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingFilenameTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun formatsTimestampToSecondPrecision() {
        // 2026-08-20T17:12:03Z
        assertEquals(
            "Screen_recording_20260820_171203.mp4",
            RecordingFilename.forEpochMillis(1_787_245_923_000L, utc)
        )
    }

    @Test
    fun usesSuppliedZoneRatherThanSystemDefault() {
        val tokyo = ZoneId.of("Asia/Tokyo") // UTC+9, so 17:12:03Z is 02:12:03 next day
        assertEquals(
            "Screen_recording_20260821_021203.mp4",
            RecordingFilename.forEpochMillis(1_787_245_923_000L, tokyo)
        )
    }

    @Test
    fun padsSingleDigitFields() {
        // 2026-01-02T03:04:05Z
        assertEquals(
            "Screen_recording_20260102_030405.mp4",
            RecordingFilename.forEpochMillis(1_767_323_045_000L, utc)
        )
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

```bash
./build.sh :app:testDebugUnitTest
```

Expected: FAIL — `Unresolved reference: RecordingFilename`. (First run also downloads nothing but takes ~1 minute to configure.)

- [ ] **Step 6: Write the minimal implementation**

`app/src/main/java/dev/screenrec/output/RecordingFilename.kt`:

```kotlin
package dev.screenrec.output

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Samsung's own naming: Screen_recording_20260820_171203.mp4 */
object RecordingFilename {

    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun forEpochMillis(millisSinceEpoch: Long, zone: ZoneId): String {
        val stamp = FORMAT.format(Instant.ofEpochMilli(millisSinceEpoch).atZone(zone))
        return "Screen_recording_$stamp.mp4"
    }
}
```

- [ ] **Step 7: Run the tests and the APK build**

```bash
./build.sh :app:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`, three tests passing, and `app/build/outputs/apk/debug/app-debug.apk` on disk. If Gradle reports `Could not initialize native services` you invoked the bare `gradle` binary instead of `./build.sh`.

- [ ] **Step 8: Commit**

```bash
git add build.sh settings.gradle build.gradle gradle.properties .gitignore app/build.gradle app/src && git commit -m "feat: offline build harness and recording filename"
```

---

### Task 2: Recording state machine

**Files:**
- Create: `app/src/main/java/dev/screenrec/record/RecordingStateMachine.kt`
- Test: `app/src/test/java/dev/screenrec/record/RecordingStateMachineTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum class RecordingState { IDLE, COUNTDOWN, RECORDING, PAUSED, STOPPING }` and `class RecordingStateMachine(initial: RecordingState = RecordingState.IDLE)` with `val state: RecordingState`, `fun transitionTo(target: RecordingState): Boolean` (returns false and leaves `state` untouched when illegal), and `val isActive: Boolean` (true for `RECORDING` or `PAUSED`).

- [ ] **Step 1: Write the failing test**

`app/src/test/java/dev/screenrec/record/RecordingStateMachineTest.kt`:

```kotlin
package dev.screenrec.record

import dev.screenrec.record.RecordingState.COUNTDOWN
import dev.screenrec.record.RecordingState.IDLE
import dev.screenrec.record.RecordingState.PAUSED
import dev.screenrec.record.RecordingState.RECORDING
import dev.screenrec.record.RecordingState.STOPPING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStateMachineTest {

    @Test
    fun startsIdle() {
        assertEquals(IDLE, RecordingStateMachine().state)
    }

    @Test
    fun walksTheHappyPath() {
        val m = RecordingStateMachine()
        assertTrue(m.transitionTo(COUNTDOWN))
        assertTrue(m.transitionTo(RECORDING))
        assertTrue(m.transitionTo(PAUSED))
        assertTrue(m.transitionTo(RECORDING))
        assertTrue(m.transitionTo(STOPPING))
        assertTrue(m.transitionTo(IDLE))
        assertEquals(IDLE, m.state)
    }

    @Test
    fun countdownCanBeCancelledBackToIdle() {
        val m = RecordingStateMachine(COUNTDOWN)
        assertTrue(m.transitionTo(IDLE))
    }

    @Test
    fun pausedCanStopDirectly() {
        val m = RecordingStateMachine(PAUSED)
        assertTrue(m.transitionTo(STOPPING))
    }

    @Test
    fun rejectsIllegalTransitionsAndKeepsState() {
        val m = RecordingStateMachine()
        assertFalse(m.transitionTo(RECORDING)) // must count down first
        assertFalse(m.transitionTo(PAUSED))
        assertFalse(m.transitionTo(STOPPING))
        assertEquals(IDLE, m.state)
    }

    @Test
    fun rejectsPauseWhileAlreadyPausedAndResumeWhileRecording() {
        assertFalse(RecordingStateMachine(PAUSED).transitionTo(PAUSED))
        assertFalse(RecordingStateMachine(RECORDING).transitionTo(RECORDING))
    }

    @Test
    fun stoppingOnlyGoesIdle() {
        assertFalse(RecordingStateMachine(STOPPING).transitionTo(RECORDING))
        assertFalse(RecordingStateMachine(STOPPING).transitionTo(PAUSED))
        assertTrue(RecordingStateMachine(STOPPING).transitionTo(IDLE))
    }

    @Test
    fun reportsActiveOnlyWhileRecordingOrPaused() {
        assertFalse(RecordingStateMachine(IDLE).isActive)
        assertFalse(RecordingStateMachine(COUNTDOWN).isActive)
        assertTrue(RecordingStateMachine(RECORDING).isActive)
        assertTrue(RecordingStateMachine(PAUSED).isActive)
        assertFalse(RecordingStateMachine(STOPPING).isActive)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./build.sh :app:testDebugUnitTest
```

Expected: FAIL — `Unresolved reference: RecordingState`.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/dev/screenrec/record/RecordingStateMachine.kt`:

```kotlin
package dev.screenrec.record

enum class RecordingState { IDLE, COUNTDOWN, RECORDING, PAUSED, STOPPING }

/**
 * Guards the session lifecycle. Not thread-safe by itself; the service confines all
 * transitions to its main-thread Handler.
 */
class RecordingStateMachine(initial: RecordingState = RecordingState.IDLE) {

    var state: RecordingState = initial
        private set

    val isActive: Boolean
        get() = state == RecordingState.RECORDING || state == RecordingState.PAUSED

    fun transitionTo(target: RecordingState): Boolean {
        if (target !in legalTargets(state)) return false
        state = target
        return true
    }

    private fun legalTargets(from: RecordingState): Set<RecordingState> = when (from) {
        RecordingState.IDLE -> setOf(RecordingState.COUNTDOWN)
        // Countdown can be cancelled, or the user can revoke consent before frame one.
        RecordingState.COUNTDOWN -> setOf(RecordingState.RECORDING, RecordingState.IDLE)
        RecordingState.RECORDING -> setOf(RecordingState.PAUSED, RecordingState.STOPPING)
        RecordingState.PAUSED -> setOf(RecordingState.RECORDING, RecordingState.STOPPING)
        RecordingState.STOPPING -> setOf(RecordingState.IDLE)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./build.sh :app:testDebugUnitTest
```

Expected: PASS — 11 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: recording state machine"
```

---

### Task 3: Video PTS offset tracker

Pause detaches the virtual display's surface, so frames stop arriving — but the capture surface stamps frames from the system clock, which keeps running. Without subtracting the paused span, playback shows a frozen gap of exactly the pause duration.

**Files:**
- Create: `app/src/main/java/dev/screenrec/mux/PtsOffsetTracker.kt`
- Test: `app/src/test/java/dev/screenrec/mux/PtsOffsetTrackerTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `class PtsOffsetTracker` with `fun pause(atUs: Long)`, `fun resume(atUs: Long)`, `fun adjust(rawUs: Long): Long`, `val isPaused: Boolean`, `val pausedTotalUs: Long`. `adjust` returns a monotonically non-decreasing stream: raw minus accumulated paused time, clamped to never regress below the previous return value.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/dev/screenrec/mux/PtsOffsetTrackerTest.kt`:

```kotlin
package dev.screenrec.mux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PtsOffsetTrackerTest {

    @Test
    fun passesTimestampsThroughUntouchedWhenNeverPaused() {
        val t = PtsOffsetTracker()
        assertEquals(0L, t.adjust(0L))
        assertEquals(33_000L, t.adjust(33_000L))
        assertEquals(66_000L, t.adjust(66_000L))
    }

    @Test
    fun subtractsASinglePausedSpan() {
        val t = PtsOffsetTracker()
        assertEquals(100L, t.adjust(100L))
        t.pause(200L)
        t.resume(1_200L) // paused for 1000us
        assertEquals(300L, t.adjust(1_300L))
        assertEquals(1_000L, t.pausedTotalUs)
    }

    @Test
    fun accumulatesAcrossManyPauses() {
        val t = PtsOffsetTracker()
        t.pause(1_000L); t.resume(3_000L)   // +2000
        t.pause(5_000L); t.resume(5_500L)   // +500
        t.pause(9_000L); t.resume(19_000L)  // +10000
        assertEquals(12_500L, t.pausedTotalUs)
        assertEquals(7_500L, t.adjust(20_000L))
    }

    @Test
    fun clampsFramesThatArriveDuringAPause() {
        val t = PtsOffsetTracker()
        assertEquals(500L, t.adjust(500L))
        t.pause(600L)
        // A frame already in flight when the surface detached must not regress.
        assertEquals(500L, t.adjust(1_000L))
        assertEquals(500L, t.adjust(1_500L))
        t.resume(2_600L)
        assertEquals(700L, t.adjust(2_700L))
    }

    @Test
    fun neverRegressesUnderOutOfOrderInput() {
        val t = PtsOffsetTracker()
        assertEquals(1_000L, t.adjust(1_000L))
        assertEquals(1_000L, t.adjust(900L))
        assertEquals(1_100L, t.adjust(1_100L))
    }

    @Test
    fun ignoresRedundantPauseAndResumeCalls() {
        val t = PtsOffsetTracker()
        t.pause(100L)
        t.pause(400L) // ignored; span still starts at 100
        assertTrue(t.isPaused)
        t.resume(600L)
        t.resume(900L) // ignored
        assertFalse(t.isPaused)
        assertEquals(500L, t.pausedTotalUs)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./build.sh :app:testDebugUnitTest
```

Expected: FAIL — `Unresolved reference: PtsOffsetTracker`.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/dev/screenrec/mux/PtsOffsetTracker.kt`:

```kotlin
package dev.screenrec.mux

/**
 * Video-only. The capture surface stamps frames from the system clock, which keeps
 * advancing while the virtual display's surface is detached, so paused spans must be
 * subtracted or playback freezes for the pause duration.
 *
 * Audio needs no equivalent: its timestamps come from a cumulative sample count that
 * simply stops advancing while paused.
 */
class PtsOffsetTracker {

    private var pauseStartedAtUs = -1L
    private var lastEmittedUs = Long.MIN_VALUE

    var pausedTotalUs: Long = 0L
        private set

    val isPaused: Boolean
        get() = pauseStartedAtUs >= 0L

    fun pause(atUs: Long) {
        if (isPaused) return
        pauseStartedAtUs = atUs
    }

    fun resume(atUs: Long) {
        if (!isPaused) return
        pausedTotalUs += (atUs - pauseStartedAtUs).coerceAtLeast(0L)
        pauseStartedAtUs = -1L
    }

    /**
     * Frames already in flight when the surface detached still arrive, and the muxer
     * rejects a regressing timestamp, so the result is clamped to the last value emitted.
     */
    fun adjust(rawUs: Long): Long {
        val shifted = rawUs - pausedTotalUs
        val monotonic = if (lastEmittedUs == Long.MIN_VALUE) shifted else maxOf(shifted, lastEmittedUs)
        val result = if (isPaused && lastEmittedUs != Long.MIN_VALUE) lastEmittedUs else monotonic
        lastEmittedUs = result
        return result
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./build.sh :app:testDebugUnitTest
```

Expected: PASS — 17 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: video pts offset tracker"
```

---

### Task 4: Audio presentation timestamps

**Files:**
- Create: `app/src/main/java/dev/screenrec/record/audio/AudioPts.kt`
- Test: `app/src/test/java/dev/screenrec/record/audio/AudioPtsTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `class AudioPts(sampleRate: Int, channelCount: Int, bytesPerSample: Int = 2)` with `fun nextPtsUs(byteCount: Int): Long` — returns the timestamp of the buffer about to be written, then advances the frame counter — plus `val framesWritten: Long` and `fun reset()`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/dev/screenrec/record/audio/AudioPtsTest.kt`:

```kotlin
package dev.screenrec.record.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPtsTest {

    /** 44100 Hz stereo 16-bit: one frame is 4 bytes. */
    private fun stereo() = AudioPts(sampleRate = 44_100, channelCount = 2)

    @Test
    fun firstBufferStartsAtZero() {
        assertEquals(0L, stereo().nextPtsUs(4_096))
    }

    @Test
    fun advancesByBufferDuration() {
        val pts = stereo()
        assertEquals(0L, pts.nextPtsUs(4_410 * 4)) // 4410 frames = exactly 100ms
        assertEquals(100_000L, pts.nextPtsUs(4_410 * 4))
        assertEquals(200_000L, pts.nextPtsUs(4_410 * 4))
    }

    @Test
    fun countsFramesNotBytes() {
        val pts = stereo()
        pts.nextPtsUs(44_100 * 4) // one full second of stereo frames
        assertEquals(44_100L, pts.framesWritten)
        assertEquals(1_000_000L, pts.nextPtsUs(4))
    }

    @Test
    fun monoUsesTwoBytesPerFrame() {
        val pts = AudioPts(sampleRate = 44_100, channelCount = 1)
        pts.nextPtsUs(44_100 * 2)
        assertEquals(1_000_000L, pts.nextPtsUs(2))
    }

    @Test
    fun aPausedGapProducesNoDiscontinuity() {
        val pts = stereo()
        assertEquals(0L, pts.nextPtsUs(4_410 * 4))
        assertEquals(100_000L, pts.nextPtsUs(4_410 * 4))
        // Reader thread is parked for five wall-clock seconds while paused; no writes.
        // The next buffer continues exactly where the previous one ended.
        assertEquals(200_000L, pts.nextPtsUs(4_410 * 4))
    }

    @Test
    fun ignoresTrailingPartialFrameBytes() {
        val pts = stereo()
        pts.nextPtsUs(4_410 * 4 + 3) // 3 stray bytes are not a whole frame
        assertEquals(4_410L, pts.framesWritten)
    }

    @Test
    fun resetReturnsToZero() {
        val pts = stereo()
        pts.nextPtsUs(44_100 * 4)
        pts.reset()
        assertEquals(0L, pts.framesWritten)
        assertEquals(0L, pts.nextPtsUs(4))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./build.sh :app:testDebugUnitTest
```

Expected: FAIL — `Unresolved reference: AudioPts`.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/dev/screenrec/record/audio/AudioPts.kt`:

```kotlin
package dev.screenrec.record.audio

/**
 * Derives AAC presentation timestamps from the cumulative frame count rather than the
 * clock. This is what makes pause correctness free: while paused nothing is written, so
 * the counter does not advance and the resumed audio abuts the paused audio exactly.
 */
class AudioPts(
    private val sampleRate: Int,
    private val channelCount: Int,
    private val bytesPerSample: Int = 2
) {
    private val bytesPerFrame = channelCount * bytesPerSample

    var framesWritten: Long = 0L
        private set

    /** Timestamp for the buffer about to be written; then accounts for it. */
    fun nextPtsUs(byteCount: Int): Long {
        val pts = framesWritten * 1_000_000L / sampleRate
        framesWritten += byteCount / bytesPerFrame
        return pts
    }

    fun reset() {
        framesWritten = 0L
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./build.sh :app:testDebugUnitTest
```

Expected: PASS — 24 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: sample-count audio timestamps"
```

---

### Task 5: PCM mixer

**Files:**
- Create: `app/src/main/java/dev/screenrec/record/audio/PcmMixer.kt`
- Test: `app/src/test/java/dev/screenrec/record/audio/PcmMixerTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object PcmMixer` with
  - `fun mix(a: ByteArray, aLen: Int, b: ByteArray, bLen: Int, out: ByteArray): Int` — sums little-endian 16-bit samples with saturation, treats the shorter input as silence past its end, returns bytes written (`max(aLen, bLen)` rounded down to an even number).
  - `fun upmixMonoToStereo(src: ByteArray, srcLen: Int, out: ByteArray): Int` — duplicates each sample into both channels, returns bytes written (`srcLen * 2`).

- [ ] **Step 1: Write the failing test**

`app/src/test/java/dev/screenrec/record/audio/PcmMixerTest.kt`:

```kotlin
package dev.screenrec.record.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmMixerTest {

    /** Little-endian 16-bit PCM, the only format this pipeline uses. */
    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun samplesOf(bytes: ByteArray, len: Int): IntArray =
        IntArray(len / 2) { i ->
            ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toInt()
        }

    @Test
    fun sumsSamples() {
        val a = pcm(100, -200, 3000)
        val b = pcm(50, -50, -1000)
        val out = ByteArray(6)
        assertEquals(6, PcmMixer.mix(a, 6, b, 6, out))
        assertArrayEquals(intArrayOf(150, -250, 2000), samplesOf(out, 6))
    }

    @Test
    fun saturatesAtThePositiveRail() {
        val out = ByteArray(2)
        PcmMixer.mix(pcm(30_000), 2, pcm(30_000), 2, out)
        assertArrayEquals(intArrayOf(32_767), samplesOf(out, 2))
    }

    @Test
    fun saturatesAtTheNegativeRail() {
        val out = ByteArray(2)
        PcmMixer.mix(pcm(-30_000), 2, pcm(-30_000), 2, out)
        assertArrayEquals(intArrayOf(-32_768), samplesOf(out, 2))
    }

    @Test
    fun treatsTheShorterStreamAsSilencePastItsEnd() {
        val a = pcm(100, 200, 300, 400)
        val b = pcm(10, 20)
        val out = ByteArray(8)
        assertEquals(8, PcmMixer.mix(a, 8, b, 4, out))
        assertArrayEquals(intArrayOf(110, 220, 300, 400), samplesOf(out, 8))
    }

    @Test
    fun handlesTheLongerStreamInEitherPosition() {
        val out = ByteArray(8)
        assertEquals(8, PcmMixer.mix(pcm(10, 20), 4, pcm(100, 200, 300, 400), 8, out))
        assertArrayEquals(intArrayOf(110, 220, 300, 400), samplesOf(out, 8))
    }

    @Test
    fun mixingWithAnEmptyStreamCopiesTheOther() {
        val out = ByteArray(4)
        assertEquals(4, PcmMixer.mix(pcm(7, -7), 4, ByteArray(0), 0, out))
        assertArrayEquals(intArrayOf(7, -7), samplesOf(out, 4))
    }

    @Test
    fun ignoresAStrayOddTrailingByte() {
        val out = ByteArray(4)
        // 3 readable bytes is one whole sample plus a stray byte.
        assertEquals(2, PcmMixer.mix(pcm(5, 0), 3, pcm(5), 2, out))
        assertArrayEquals(intArrayOf(10), samplesOf(out, 2))
    }

    @Test
    fun upmixesMonoToStereoByDuplicatingEachSample() {
        val out = ByteArray(8)
        assertEquals(8, PcmMixer.upmixMonoToStereo(pcm(1_000, -2_000), 4, out))
        assertArrayEquals(intArrayOf(1_000, 1_000, -2_000, -2_000), samplesOf(out, 8))
    }

    @Test
    fun upmixOfEmptyInputWritesNothing() {
        assertEquals(0, PcmMixer.upmixMonoToStereo(ByteArray(0), 0, ByteArray(0)))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./build.sh :app:testDebugUnitTest
```

Expected: FAIL — `Unresolved reference: PcmMixer`.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/dev/screenrec/record/audio/PcmMixer.kt`:

```kotlin
package dev.screenrec.record.audio

/**
 * Software mixing for "Media and mic". Saturating rather than wrapping: a wrapped sum
 * turns a loud moment into a burst of noise, a clipped one merely sounds loud.
 */
object PcmMixer {

    fun mix(a: ByteArray, aLen: Int, b: ByteArray, bLen: Int, out: ByteArray): Int {
        val samples = maxOf(aLen, bLen) / 2
        for (i in 0 until samples) {
            val sum = sampleAt(a, aLen, i) + sampleAt(b, bLen, i)
            writeSample(out, i, sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
        }
        return samples * 2
    }

    /** The mic often refuses a stereo mask while playback capture yields stereo. */
    fun upmixMonoToStereo(src: ByteArray, srcLen: Int, out: ByteArray): Int {
        val samples = srcLen / 2
        for (i in 0 until samples) {
            val s = sampleAt(src, srcLen, i)
            writeSample(out, i * 2, s)
            writeSample(out, i * 2 + 1, s)
        }
        return samples * 4
    }

    /** Past the end of a stream, silence. */
    private fun sampleAt(buf: ByteArray, len: Int, index: Int): Int {
        val lo = index * 2
        if (lo + 1 >= len) return 0
        return ((buf[lo].toInt() and 0xFF) or (buf[lo + 1].toInt() shl 8)).toShort().toInt()
    }

    private fun writeSample(out: ByteArray, index: Int, value: Int) {
        out[index * 2] = (value and 0xFF).toByte()
        out[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./build.sh :app:testDebugUnitTest
```

Expected: PASS — 33 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: saturating pcm mixer with mono upmix"
```

---

### Task 6: Recording configuration and encoder size negotiation

Encoders reject sizes they do not support, and each reports its own alignment — assuming "even is fine" is how third-party recorders end up with a black screen on one device in ten. This task asks the encoder and obeys it.

**Files:**
- Create: `app/src/main/java/dev/screenrec/record/RecordingConfig.kt`
- Create: `app/src/main/java/dev/screenrec/record/video/EncoderCapabilities.kt`
- Create: `app/src/main/java/dev/screenrec/record/video/EncoderConfigFactory.kt`
- Test: `app/src/test/java/dev/screenrec/record/video/EncoderConfigFactoryTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class SoundMode { NONE, MEDIA, MEDIA_AND_MIC }` with `val label: String` (`"No sound"`, `"Media"`, `"Media and mic"`) and `val needsMic: Boolean`.
  - `enum class QualityPreset(val shortEdge: Int, val bitrate: Int, val label: String)`: `P1080(1080, 12_000_000, "1080p")`, `P720(720, 8_000_000, "720p")`, `P480(480, 4_000_000, "480p")`, plus `fun lower(): QualityPreset?`.
  - `data class RecordingConfig(val soundMode: SoundMode, val preset: QualityPreset)`.
  - `data class VideoFormatSpec(val width: Int, val height: Int, val bitrate: Int, val frameRate: Int = 30, val iFrameIntervalSeconds: Int = 1)`.
  - `interface EncoderCapabilities { val widthAlignment: Int; val heightAlignment: Int; val supportedWidths: IntRange; fun supportedHeightsFor(width: Int): IntRange; fun isSizeSupported(width: Int, height: Int): Boolean }`.
  - `object EncoderConfigFactory { fun create(displayWidth: Int, displayHeight: Int, preset: QualityPreset, caps: EncoderCapabilities): VideoFormatSpec }`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/dev/screenrec/record/video/EncoderConfigFactoryTest.kt`:

```kotlin
package dev.screenrec.record.video

import dev.screenrec.record.QualityPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderConfigFactoryTest {

    /** Hand-written stand-in for MediaCodecInfo.VideoCapabilities. */
    private class FakeCaps(
        override val widthAlignment: Int = 2,
        override val heightAlignment: Int = 2,
        override val supportedWidths: IntRange = 2..4096,
        private val heights: IntRange = 2..4096,
        private val supported: (Int, Int) -> Boolean = { _, _ -> true }
    ) : EncoderCapabilities {
        override fun supportedHeightsFor(width: Int): IntRange = heights
        override fun isSizeSupported(width: Int, height: Int): Boolean = supported(width, height)
    }

    // The A17's panel.
    private val dw = 1080
    private val dh = 2340

    @Test
    fun keepsNativeSizeAtTheMatchingPreset() {
        val spec = EncoderConfigFactory.create(dw, dh, QualityPreset.P1080, FakeCaps())
        assertEquals(1080, spec.width)
        assertEquals(2340, spec.height)
        assertEquals(12_000_000, spec.bitrate)
        assertEquals(30, spec.frameRate)
        assertEquals(1, spec.iFrameIntervalSeconds)
    }

    @Test
    fun scalesShortEdgeToPresetPreservingAspect() {
        val spec = EncoderConfigFactory.create(dw, dh, QualityPreset.P720, FakeCaps())
        assertEquals(720, spec.width)
        assertEquals(1560, spec.height)
        assertEquals(8_000_000, spec.bitrate)
    }

    @Test
    fun scalesTheShortEdgeInLandscapeToo() {
        val spec = EncoderConfigFactory.create(2340, 1080, QualityPreset.P720, FakeCaps())
        assertEquals(1560, spec.width)
        assertEquals(720, spec.height)
    }

    @Test
    fun neverUpscalesASmallerDisplay() {
        val spec = EncoderConfigFactory.create(720, 1560, QualityPreset.P1080, FakeCaps())
        assertEquals(720, spec.width)
        assertEquals(1560, spec.height)
        assertEquals(12_000_000, spec.bitrate)
    }

    @Test
    fun honoursTheAlignmentTheEncoderReports() {
        val spec = EncoderConfigFactory.create(dw, dh, QualityPreset.P1080, FakeCaps(16, 16))
        assertEquals(1072, spec.width) // 1080 -> largest multiple of 16 below
        assertEquals(2320, spec.height)
    }

    @Test
    fun clampsToTheEncodersMaximumWidthAndRecomputesHeight() {
        val spec = EncoderConfigFactory.create(
            dw, dh, QualityPreset.P1080, FakeCaps(supportedWidths = 2..1024)
        )
        assertEquals(1024, spec.width)
        assertEquals(2218, spec.height)
    }

    @Test
    fun clampsToTheEncodersMaximumHeightAndPullsWidthBackToMatch() {
        val spec = EncoderConfigFactory.create(
            dw, dh, QualityPreset.P1080, FakeCaps(heights = 2..2000)
        )
        assertEquals(922, spec.width)
        assertEquals(2000, spec.height)
    }

    @Test
    fun clampsUpToTheEncodersMinimumWidth() {
        val spec = EncoderConfigFactory.create(
            320, 640, QualityPreset.P480, FakeCaps(64, 64, supportedWidths = 640..4096)
        )
        assertEquals(640, spec.width)
        assertEquals(1280, spec.height)
    }

    @Test
    fun stepsDownUntilTheEncoderAcceptsTheSize() {
        val spec = EncoderConfigFactory.create(
            dw, dh, QualityPreset.P1080,
            FakeCaps(supported = { w, _ -> w <= 1000 })
        )
        assertEquals(1000, spec.width)
        assertEquals(2166, spec.height)
    }

    @Test
    fun mapsEveryPresetToItsBitrate() {
        val caps = FakeCaps()
        assertEquals(12_000_000, EncoderConfigFactory.create(dw, dh, QualityPreset.P1080, caps).bitrate)
        assertEquals(8_000_000, EncoderConfigFactory.create(dw, dh, QualityPreset.P720, caps).bitrate)
        assertEquals(4_000_000, EncoderConfigFactory.create(dw, dh, QualityPreset.P480, caps).bitrate)
    }

    @Test
    fun givesUpGracefullyWhenNothingIsSupported() {
        // Never throws: the controller retries at a lower preset and then reports an error.
        val spec = EncoderConfigFactory.create(
            dw, dh, QualityPreset.P480, FakeCaps(supported = { _, _ -> false })
        )
        assertEquals(4_000_000, spec.bitrate)
        assertEquals(0, spec.width % 2)
        assertEquals(0, spec.height % 2)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./build.sh :app:testDebugUnitTest
```

Expected: FAIL — `Unresolved reference: EncoderConfigFactory`.

- [ ] **Step 3: Write the configuration types**

`app/src/main/java/dev/screenrec/record/RecordingConfig.kt`:

```kotlin
package dev.screenrec.record

/** User-facing labels are Samsung's exact wording; do not reword. */
enum class SoundMode(val label: String, val needsMic: Boolean) {
    NONE("No sound", false),
    MEDIA("Media", false),
    MEDIA_AND_MIC("Media and mic", true)
}

enum class QualityPreset(val shortEdge: Int, val bitrate: Int, val label: String) {
    P1080(1080, 12_000_000, "1080p"),
    P720(720, 8_000_000, "720p"),
    P480(480, 4_000_000, "480p");

    /** Next step down, used when encoder init fails. Null at the bottom. */
    fun lower(): QualityPreset? = when (this) {
        P1080 -> P720
        P720 -> P480
        P480 -> null
    }
}

data class RecordingConfig(
    val soundMode: SoundMode,
    val preset: QualityPreset
)

data class VideoFormatSpec(
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val frameRate: Int = 30,
    val iFrameIntervalSeconds: Int = 1
)
```

- [ ] **Step 4: Write the capabilities abstraction and the factory**

`app/src/main/java/dev/screenrec/record/video/EncoderCapabilities.kt`:

```kotlin
package dev.screenrec.record.video

import android.media.MediaCodecInfo

/**
 * The slice of MediaCodecInfo.VideoCapabilities the size negotiation needs, behind an
 * interface so the negotiation is JVM-testable without a device.
 */
interface EncoderCapabilities {
    val widthAlignment: Int
    val heightAlignment: Int
    val supportedWidths: IntRange
    fun supportedHeightsFor(width: Int): IntRange
    fun isSizeSupported(width: Int, height: Int): Boolean
}

/** Adapter over the real platform capabilities. */
class PlatformEncoderCapabilities(
    private val caps: MediaCodecInfo.VideoCapabilities
) : EncoderCapabilities {

    override val widthAlignment: Int get() = caps.widthAlignment
    override val heightAlignment: Int get() = caps.heightAlignment

    override val supportedWidths: IntRange
        get() = caps.supportedWidths.lower..caps.supportedWidths.upper

    override fun supportedHeightsFor(width: Int): IntRange =
        try {
            val r = caps.getSupportedHeightsFor(width)
            r.lower..r.upper
        } catch (e: IllegalArgumentException) {
            // Width outside the encoder's range; fall back to the unconditional range.
            caps.supportedHeights.lower..caps.supportedHeights.upper
        }

    override fun isSizeSupported(width: Int, height: Int): Boolean =
        try {
            caps.isSizeSupported(width, height)
        } catch (e: IllegalArgumentException) {
            false
        }
}
```

`app/src/main/java/dev/screenrec/record/video/EncoderConfigFactory.kt`:

```kotlin
package dev.screenrec.record.video

import dev.screenrec.record.QualityPreset
import dev.screenrec.record.VideoFormatSpec

/**
 * Turns a display size and a quality preset into a size the encoder will actually accept:
 * aspect preserved, never upscaled, aligned to whatever multiple the encoder reports, and
 * clamped to its supported ranges. Never throws — an unusable result is better handled by
 * the controller's retry-at-lower-preset path than by an exception here.
 */
object EncoderConfigFactory {

    private const val STEP_PX = 16
    private const val MAX_ATTEMPTS = 32

    fun create(
        displayWidth: Int,
        displayHeight: Int,
        preset: QualityPreset,
        caps: EncoderCapabilities
    ): VideoFormatSpec {
        val shortEdge = minOf(displayWidth, displayHeight)
        var targetShort = minOf(preset.shortEdge, shortEdge) // never upscale
        var size = sizeFor(displayWidth, displayHeight, targetShort, caps)
        var attempts = 0
        while (!caps.isSizeSupported(size.first, size.second) && attempts < MAX_ATTEMPTS) {
            targetShort -= STEP_PX
            if (targetShort < STEP_PX) break
            size = sizeFor(displayWidth, displayHeight, targetShort, caps)
            attempts++
        }
        return VideoFormatSpec(size.first, size.second, preset.bitrate)
    }

    private fun sizeFor(
        displayWidth: Int,
        displayHeight: Int,
        targetShort: Int,
        caps: EncoderCapabilities
    ): Pair<Int, Int> {
        val shortEdge = minOf(displayWidth, displayHeight)
        val width = fit(
            scaled(displayWidth, targetShort, shortEdge),
            caps.supportedWidths,
            caps.widthAlignment
        )
        val heightRange = caps.supportedHeightsFor(width)
        val wantedHeight = scaled(displayHeight, width, displayWidth)
        val clampedHeight = wantedHeight.coerceIn(heightRange.first, heightRange.last)
        val height = fit(clampedHeight, heightRange, caps.heightAlignment)
        if (clampedHeight == wantedHeight) return width to height
        // The encoder's height limit, not rounding, changed the shape: restore the aspect
        // ratio by pulling the width back to match the height we can actually use.
        val correctedWidth = fit(
            scaled(displayWidth, height, displayHeight),
            caps.supportedWidths,
            caps.widthAlignment
        )
        return correctedWidth to height
    }

    /** value * numerator / denominator, rounded to nearest. */
    private fun scaled(value: Int, numerator: Int, denominator: Int): Int =
        ((value.toLong() * numerator + denominator / 2) / denominator).toInt()

    private fun fit(value: Int, range: IntRange, alignment: Int): Int {
        val step = alignment.coerceAtLeast(1)
        val clamped = value.coerceIn(range.first, range.last)
        val alignedDown = clamped / step * step
        if (alignedDown >= range.first) return alignedDown
        val alignedUp = (range.first + step - 1) / step * step
        return alignedUp.coerceAtMost(range.last)
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./build.sh :app:testDebugUnitTest
```

Expected: PASS — 44 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src && git commit -m "feat: encoder size negotiation from reported capabilities"
```

---

### Task 7: Muxer gate

`MediaMuxer.start()` is illegal before every track is added, and the two encoders emit
`INFO_OUTPUT_FORMAT_CHANGED` at unpredictable moments — audio often first, sometimes with
video samples already in hand. The gate absorbs that ordering problem.

**Files:**
- Create: `app/src/main/java/dev/screenrec/mux/MuxerGate.kt`
- Test: `app/src/test/java/dev/screenrec/mux/MuxerGateTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class TrackKind { VIDEO, AUDIO }`
  - `interface MuxerTarget<F> { fun addTrack(format: F): Int; fun start(); fun writeSample(trackIndex: Int, data: ByteArray, offset: Int, size: Int, ptsUs: Long, flags: Int); fun stop() }`
  - `class MuxerGate<F>(target: MuxerTarget<F>, expected: Set<TrackKind>)` with `fun addTrack(kind: TrackKind, format: F)`, `fun writeSample(kind: TrackKind, data: ByteArray, offset: Int, size: Int, ptsUs: Long, flags: Int)`, `fun stop()`, `val isStarted: Boolean`.
  - Generic in the format type so the gate is testable without `android.media.MediaFormat`; production code instantiates it as `MuxerGate<MediaFormat>`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/dev/screenrec/mux/MuxerGateTest.kt`:

```kotlin
package dev.screenrec.mux

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuxerGateTest {

    private data class Written(val track: Int, val bytes: List<Byte>, val ptsUs: Long, val flags: Int)

    private class FakeTarget : MuxerTarget<String> {
        val formats = mutableListOf<String>()
        val written = mutableListOf<Written>()
        var startCount = 0
        var stopCount = 0

        override fun addTrack(format: String): Int {
            formats += format
            return formats.size - 1
        }

        override fun start() {
            startCount++
        }

        override fun writeSample(
            trackIndex: Int, data: ByteArray, offset: Int, size: Int, ptsUs: Long, flags: Int
        ) {
            written += Written(
                trackIndex,
                data.copyOfRange(offset, offset + size).toList(),
                ptsUs,
                flags
            )
        }

        override fun stop() {
            stopCount++
        }
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun doesNotStartUntilEveryExpectedTrackIsAdded() {
        val target = FakeTarget()
        val gate = MuxerGate(target, setOf(TrackKind.VIDEO, TrackKind.AUDIO))

        gate.addTrack(TrackKind.VIDEO, "video/avc")
        assertEquals(0, target.startCount)
        assertFalse(gate.isStarted)

        gate.addTrack(TrackKind.AUDIO, "audio/mp4a-latm")
        assertEquals(1, target.startCount)
        assertTrue(gate.isStarted)
    }

    @Test
    fun startsImmediatelyWhenOnlyVideoIsExpected() {
        val target = FakeTarget()
        val gate = MuxerGate(target, setOf(TrackKind.VIDEO))
        gate.addTrack(TrackKind.VIDEO, "video/avc")
        assertTrue(gate.isStarted)
        assertEquals(1, target.startCount)
    }

    @Test
    fun queuesEarlySamplesAndFlushesThemInTimestampOrder() {
        val target = FakeTarget()
        val gate = MuxerGate(target, setOf(TrackKind.VIDEO, TrackKind.AUDIO))

        gate.addTrack(TrackKind.VIDEO, "video/avc")
        gate.writeSample(TrackKind.VIDEO, bytes(3), 0, 1, 300L, 0)
        gate.writeSample(TrackKind.VIDEO, bytes(1), 0, 1, 100L, 0)
        assertTrue(target.written.isEmpty())

        gate.addTrack(TrackKind.AUDIO, "audio/mp4a-latm")

        assertEquals(listOf(100L, 300L), target.written.map { it.ptsUs })
        assertEquals(listOf(0, 0), target.written.map { it.track })
    }

    @Test
    fun queuedSamplesKeepTheirOwnTrackIndexAndFlags() {
        val target = FakeTarget()
        val gate = MuxerGate(target, setOf(TrackKind.VIDEO, TrackKind.AUDIO))

        gate.writeSample(TrackKind.AUDIO, bytes(9), 0, 1, 50L, 4)
        gate.addTrack(TrackKind.VIDEO, "video/avc")   // index 0
        gate.addTrack(TrackKind.AUDIO, "audio/mp4a-latm") // index 1

        assertEquals(1, target.written.single().track)
        assertEquals(4, target.written.single().flags)
    }

    @Test
    fun copiesQueuedSampleDataSoTheCallerCanReuseTheBuffer() {
        val target = FakeTarget()
        val gate = MuxerGate(target, setOf(TrackKind.VIDEO, TrackKind.AUDIO))
        val scratch = bytes(7, 7, 7)

        gate.addTrack(TrackKind.VIDEO, "video/avc")
        gate.writeSample(TrackKind.VIDEO, scratch, 1, 2, 10L, 0)
        scratch.fill(0) // encoder reuses its output buffer immediately
        gate.addTrack(TrackKind.AUDIO, "audio/mp4a-latm")

        assertArrayEquals(byteArrayOf(7, 7), target.written.single().bytes.toByteArray())
    }

    @Test
    fun writesStraightThroughOnceStarted() {
        val target = FakeTarget()
        val gate = MuxerGate(target, setOf(TrackKind.VIDEO))
        gate.addTrack(TrackKind.VIDEO, "video/avc")

        gate.writeSample(TrackKind.VIDEO, bytes(1), 0, 1, 900L, 0)
        gate.writeSample(TrackKind.VIDEO, bytes(2), 0, 1, 800L, 0) // order not the gate's job now

        assertEquals(listOf(900L, 800L), target.written.map { it.ptsUs })
    }

    @Test
    fun dropsSamplesForKindsThatWereNeverExpected() {
        val target = FakeTarget()
        val gate = MuxerGate(target, setOf(TrackKind.VIDEO))
        gate.addTrack(TrackKind.VIDEO, "video/avc")

        gate.writeSample(TrackKind.AUDIO, bytes(1), 0, 1, 10L, 0)

        assertTrue(target.written.isEmpty())
    }

    @Test
    fun ignoresADuplicateTrackRegistration() {
        val target = FakeTarget()
        val gate = MuxerGate(target, setOf(TrackKind.VIDEO))
        gate.addTrack(TrackKind.VIDEO, "video/avc")
        gate.addTrack(TrackKind.VIDEO, "video/avc")

        assertEquals(1, target.formats.size)
        assertEquals(1, target.startCount)
    }

    @Test
    fun stopBeforeStartDiscardsTheQueueWithoutStoppingTheMuxer() {
        val target = FakeTarget()
        val gate = MuxerGate(target, setOf(TrackKind.VIDEO, TrackKind.AUDIO))
        gate.addTrack(TrackKind.VIDEO, "video/avc")
        gate.writeSample(TrackKind.VIDEO, bytes(1), 0, 1, 10L, 0)

        gate.stop() // MediaMuxer.stop() before start() throws

        assertEquals(0, target.stopCount)
        assertTrue(target.written.isEmpty())
    }

    @Test
    fun stopAfterStartStopsTheMuxerExactlyOnce() {
        val target = FakeTarget()
        val gate = MuxerGate(target, setOf(TrackKind.VIDEO))
        gate.addTrack(TrackKind.VIDEO, "video/avc")

        gate.stop()
        gate.stop()

        assertEquals(1, target.stopCount)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./build.sh :app:testDebugUnitTest
```

Expected: FAIL — `Unresolved reference: MuxerGate`.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/dev/screenrec/mux/MuxerGate.kt`:

```kotlin
package dev.screenrec.mux

enum class TrackKind { VIDEO, AUDIO }

/**
 * The muxer operations the gate needs. Generic in the format type so the gate can be
 * tested without android.media.MediaFormat; production uses MuxerTarget<MediaFormat>.
 */
interface MuxerTarget<F> {
    fun addTrack(format: F): Int
    fun start()
    fun writeSample(trackIndex: Int, data: ByteArray, offset: Int, size: Int, ptsUs: Long, flags: Int)
    fun stop()
}

/**
 * Holds the muxer closed until every expected track has been added, queueing samples that
 * arrive in the meantime and flushing them in timestamp order. Queued samples remember
 * their *kind*, not an index, because a sample can arrive before its own track exists.
 *
 * Confined to the drain thread by the controller; not internally synchronised.
 */
class MuxerGate<F>(
    private val target: MuxerTarget<F>,
    private val expected: Set<TrackKind>
) {
    private class Pending(
        val kind: TrackKind,
        val data: ByteArray,
        val ptsUs: Long,
        val flags: Int
    )

    private val trackIndices = HashMap<TrackKind, Int>()
    private val queue = ArrayList<Pending>()
    private var stopped = false

    var isStarted: Boolean = false
        private set

    fun addTrack(kind: TrackKind, format: F) {
        if (kind !in expected || trackIndices.containsKey(kind) || isStarted) return
        trackIndices[kind] = target.addTrack(format)
        if (trackIndices.keys == expected) startAndFlush()
    }

    fun writeSample(
        kind: TrackKind,
        data: ByteArray,
        offset: Int,
        size: Int,
        ptsUs: Long,
        flags: Int
    ) {
        if (stopped || kind !in expected) return
        val index = trackIndices[kind]
        if (isStarted && index != null) {
            target.writeSample(index, data, offset, size, ptsUs, flags)
            return
        }
        // Copy: the encoder reclaims its output buffer the moment we release it.
        queue += Pending(kind, data.copyOfRange(offset, offset + size), ptsUs, flags)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        queue.clear()
        // stop() before start() throws on the real MediaMuxer.
        if (isStarted) target.stop()
    }

    private fun startAndFlush() {
        target.start()
        isStarted = true
        queue.sortBy { it.ptsUs }
        for (p in queue) {
            val track = trackIndices[p.kind] ?: continue
            target.writeSample(track, p.data, 0, p.data.size, p.ptsUs, p.flags)
        }
        queue.clear()
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./build.sh :app:testDebugUnitTest
```

Expected: PASS — 54 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: muxer gate deferring start until tracks complete"
```

---

### Task 8: MediaStore output and muxer sink

**Files:**
- Create: `app/src/main/java/dev/screenrec/output/MediaStoreOutput.kt`
- Create: `app/src/main/java/dev/screenrec/mux/MuxerSink.kt`
- Modify: `app/src/main/AndroidManifest.xml` (nothing yet — no permission is needed for own-app MediaStore inserts on API 33+; leave as is and note it in the commit)

**Interfaces:**
- Consumes: `RecordingFilename.forEpochMillis` (Task 1), `MuxerTarget<F>`/`TrackKind` (Task 7).
- Produces:
  - `class MediaStoreOutput(context: Context)` with `fun createPending(nowMillis: Long, zone: ZoneId): PendingRecording`, and `data class PendingRecording(val uri: Uri, val descriptor: ParcelFileDescriptor, val displayName: String)`; plus `fun publish(uri: Uri)`, `fun discard(uri: Uri)`, `fun cleanUpOrphans()`.
  - `class MuxerSink(descriptor: ParcelFileDescriptor, orientationHintDegrees: Int) : MuxerTarget<MediaFormat>`, with `fun release()`.

- [ ] **Step 1: Write MediaStoreOutput**

`app/src/main/java/dev/screenrec/output/MediaStoreOutput.kt`:

```kotlin
package dev.screenrec.output

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.time.ZoneId

/**
 * Writes into the Gallery the way the platform wants: insert a row flagged IS_PENDING so
 * nothing half-written is visible, hand the muxer that row's file descriptor, then clear
 * the flag. A failure deletes the row rather than leaving a zero-byte item behind.
 */
class MediaStoreOutput(private val context: Context) {

    data class PendingRecording(
        val uri: Uri,
        val descriptor: ParcelFileDescriptor,
        val displayName: String
    )

    fun createPending(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): PendingRecording {
        val name = RecordingFilename.forEpochMillis(nowMillis, zone)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.DATE_ADDED, nowMillis / 1000)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(context.contentResolver.insert(collection, values)) {
            "MediaStore refused to create $name"
        }
        val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "rw")) {
            "MediaStore returned no descriptor for $uri"
        }
        return PendingRecording(uri, descriptor, name)
    }

    /** Clears IS_PENDING, making the recording visible in the Gallery. */
    fun publish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    fun discard(uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not delete pending row $uri", e)
        }
    }

    /**
     * If the process was killed mid-recording, its row is still pending and invisible.
     * Called at launch so those never accumulate.
     */
    fun cleanUpOrphans() {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.IS_PENDING} = 1 AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$RELATIVE_PATH%")
        context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            val idColumn = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (c.moveToNext()) {
                discard(ContentUris.withAppendedId(collection, c.getLong(idColumn)))
            }
        }
    }

    private companion object {
        const val TAG = "MediaStoreOutput"
        const val MIME_TYPE = "video/mp4"

        /** Samsung's own location, so recordings land where users already look. */
        const val RELATIVE_PATH = "DCIM/Screen recordings"
    }
}
```

- [ ] **Step 2: Write MuxerSink**

`app/src/main/java/dev/screenrec/mux/MuxerSink.kt`:

```kotlin
package dev.screenrec.mux

import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer

/**
 * MediaMuxer behind MuxerTarget. Every call is synchronised because video and audio drain
 * on separate threads and MediaMuxer is not thread-safe.
 */
class MuxerSink(
    private val descriptor: ParcelFileDescriptor,
    orientationHintDegrees: Int
) : MuxerTarget<MediaFormat> {

    private val lock = Any()
    private val muxer = MediaMuxer(
        descriptor.fileDescriptor,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
    ).apply { setOrientationHint(orientationHintDegrees) }

    private var started = false

    override fun addTrack(format: MediaFormat): Int = synchronized(lock) { muxer.addTrack(format) }

    override fun start() = synchronized(lock) {
        muxer.start()
        started = true
    }

    override fun writeSample(
        trackIndex: Int,
        data: ByteArray,
        offset: Int,
        size: Int,
        ptsUs: Long,
        flags: Int
    ) = synchronized(lock) {
        val info = android.media.MediaCodec.BufferInfo().apply {
            set(0, size, ptsUs, flags)
        }
        muxer.writeSampleData(trackIndex, ByteBuffer.wrap(data, offset, size), info)
    }

    override fun stop() = synchronized(lock) {
        if (started) {
            muxer.stop()
            started = false
        }
    }

    /** Releases the muxer and the descriptor. Safe to call after a failed start. */
    fun release() = synchronized(lock) {
        try {
            muxer.release()
        } finally {
            descriptor.close()
        }
    }
}
```

- [ ] **Step 3: Verify it compiles**

```bash
./build.sh :app:assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, still 54 tests passing. These two classes wrap platform
objects and have no JVM-testable logic of their own; Task 16 exercises them on the device.

- [ ] **Step 4: Commit**

```bash
git add app/src && git commit -m "feat: pending MediaStore output and muxer sink"
```

---

### Task 9: Video encoder and screen capture source

**Files:**
- Create: `app/src/main/java/dev/screenrec/record/video/VideoEncoder.kt`
- Create: `app/src/main/java/dev/screenrec/record/video/ScreenCaptureSource.kt`

**Interfaces:**
- Consumes: `VideoFormatSpec` (Task 6), `EncoderCapabilities`/`PlatformEncoderCapabilities`/`EncoderConfigFactory` (Task 6).
- Produces:
  - `class VideoEncoder(spec: VideoFormatSpec, listener: Listener)` where `interface Listener { fun onFormat(format: MediaFormat); fun onSample(data: ByteArray, offset: Int, size: Int, ptsUs: Long, flags: Int); fun onEndOfStream(); fun onError(e: Exception) }`; members `val inputSurface: Surface`, `fun start()`, `fun signalEndOfStream()`, `fun release()`. Timestamps are reported **raw** — the caller applies `PtsOffsetTracker`.
  - `object VideoEncoderFactory { fun capabilitiesFor(mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC): EncoderCapabilities? }`
  - `class ScreenCaptureSource(projection: MediaProjection)` with `fun start(width: Int, height: Int, densityDpi: Int, surface: Surface, onStopped: () -> Unit)`, `fun pause()`, `fun resume(surface: Surface)`, `fun release()`.

- [ ] **Step 1: Write the video encoder**

`app/src/main/java/dev/screenrec/record/video/VideoEncoder.kt`:

```kotlin
package dev.screenrec.record.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import dev.screenrec.record.VideoFormatSpec

/** Finds the AVC encoder's real capabilities so sizes are negotiated, not guessed. */
object VideoEncoderFactory {

    fun capabilitiesFor(mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC): EncoderCapabilities? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val info = list.codecInfos.firstOrNull { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        } ?: return null
        val video = info.getCapabilitiesForType(mimeType).videoCapabilities ?: return null
        return PlatformEncoderCapabilities(video)
    }
}

/**
 * AVC encoder fed by a Surface. The virtual display renders into [inputSurface]; this class
 * only drains the compressed side, on its own thread, and reports raw timestamps.
 */
class VideoEncoder(
    private val spec: VideoFormatSpec,
    private val listener: Listener
) {
    interface Listener {
        fun onFormat(format: MediaFormat)
        fun onSample(data: ByteArray, offset: Int, size: Int, ptsUs: Long, flags: Int)
        fun onEndOfStream()
        fun onError(e: Exception)
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var drainThread: Thread? = null
    @Volatile private var draining = false

    val inputSurface: Surface

    init {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, spec.width, spec.height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, spec.iFrameIntervalSeconds)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    fun start() {
        codec.start()
        draining = true
        drainThread = Thread({ drainLoop() }, "video-drain").also { it.start() }
    }

    /** Ends the stream so the encoder emits its final frames. */
    fun signalEndOfStream() {
        try {
            codec.signalEndOfInputStream()
        } catch (e: IllegalStateException) {
            listener.onError(e)
        }
    }

    fun release() {
        draining = false
        drainThread?.join(DRAIN_JOIN_TIMEOUT_MS)
        drainThread = null
        try {
            codec.stop()
        } catch (ignored: IllegalStateException) {
            // Already stopped or never started; release below is what matters.
        }
        codec.release()
        inputSurface.release()
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (draining) {
                val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> listener.onFormat(codec.outputFormat)
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        // Codec config bytes travel in the track format, not as a sample.
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buffer != null && info.size > 0 && !isConfig) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(bytes, 0, info.size)
                            listener.onSample(bytes, 0, info.size, info.presentationTimeUs, info.flags)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            draining = false
                            listener.onEndOfStream()
                        }
                    }
                }
            }
        } catch (e: IllegalStateException) {
            if (draining) listener.onError(e)
        }
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val DRAIN_JOIN_TIMEOUT_MS = 2_000L
    }
}
```

- [ ] **Step 2: Write the screen capture source**

`app/src/main/java/dev/screenrec/record/video/ScreenCaptureSource.kt`:

```kotlin
package dev.screenrec.record.video

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Surface

/**
 * MediaProjection plus the VirtualDisplay that mirrors the screen into the encoder surface.
 *
 * Ordering rule from the platform: the callback must be registered before
 * createVirtualDisplay, and on API 34+ the foreground service must already be running with
 * type mediaProjection before the projection is obtained at all (see RecorderService).
 */
class ScreenCaptureSource(private val projection: MediaProjection) {

    private val handler = Handler(Looper.getMainLooper())
    private var virtualDisplay: VirtualDisplay? = null
    private var callback: MediaProjection.Callback? = null

    fun start(
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        onStopped: () -> Unit
    ) {
        val cb = object : MediaProjection.Callback() {
            override fun onStop() = onStopped()
        }
        projection.registerCallback(cb, handler)
        callback = cb
        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            handler
        )
    }

    /** Detaching the surface stops frames without tearing the session down. */
    fun pause() {
        virtualDisplay?.setSurface(null)
    }

    fun resume(surface: Surface) {
        virtualDisplay?.setSurface(surface)
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        callback?.let { projection.unregisterCallback(it) }
        callback = null
        projection.stop()
    }

    private companion object {
        const val VIRTUAL_DISPLAY_NAME = "screenrec"
    }
}
```

- [ ] **Step 3: Verify it compiles**

```bash
./build.sh :app:assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 54 tests still passing.

- [ ] **Step 4: Commit**

```bash
git add app/src && git commit -m "feat: video encoder and screen capture source"
```

---

### Task 10: Audio capture and AAC encoder

**Files:**
- Create: `app/src/main/java/dev/screenrec/record/audio/AudioCaptureSource.kt`
- Create: `app/src/main/java/dev/screenrec/record/audio/AudioEncoder.kt`
- Modify: `app/src/main/AndroidManifest.xml` — add `RECORD_AUDIO`

**Interfaces:**
- Consumes: `SoundMode` (Task 6), `PcmMixer` (Task 5), `AudioPts` (Task 4).
- Produces:
  - `class AudioCaptureSource(projection: MediaProjection, soundMode: SoundMode)` with `fun start(onPcm: (ByteArray, Int) -> Unit)`, `fun pause()`, `fun resume()`, `fun release()`, and `companion object { const val SAMPLE_RATE = 44_100; const val CHANNEL_COUNT = 2 }`.
  - `class AudioEncoder(listener: Listener)` — same `Listener` shape as `VideoEncoder.Listener` — with `fun start()`, `fun submit(pcm: ByteArray, length: Int, ptsUs: Long)`, `fun signalEndOfStream()`, `fun release()`.

- [ ] **Step 1: Add the permission**

In `app/src/main/AndroidManifest.xml`, above `<application>`:

```xml
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
```

- [ ] **Step 2: Write the audio capture source**

`app/src/main/java/dev/screenrec/record/audio/AudioCaptureSource.kt`:

```kotlin
package dev.screenrec.record.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import dev.screenrec.record.SoundMode

/**
 * Zero, one or two AudioRecords depending on the sound mode, read on one thread and mixed
 * in software when both are present.
 *
 * Platform limits worth stating plainly: an app may opt out of playback capture, and DRM
 * audio is never captured, so some content records silent. That is not a bug here.
 */
@SuppressLint("MissingPermission") // callers gate on RECORD_AUDIO before constructing
class AudioCaptureSource(
    private val projection: MediaProjection,
    private val soundMode: SoundMode
) {
    private var playbackRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var readThread: Thread? = null

    @Volatile private var reading = false
    @Volatile private var paused = false

    fun start(onPcm: (ByteArray, Int) -> Unit) {
        if (soundMode == SoundMode.NONE) return

        playbackRecord = buildPlaybackRecord().also { it.startRecording() }
        if (soundMode.needsMic) {
            micRecord = buildMicRecord().also { it.startRecording() }
        }

        reading = true
        readThread = Thread({ readLoop(onPcm) }, "audio-read").also { it.start() }
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun release() {
        reading = false
        readThread?.join(READ_JOIN_TIMEOUT_MS)
        readThread = null
        listOf(playbackRecord, micRecord).forEach { record ->
            try {
                record?.stop()
            } catch (ignored: IllegalStateException) {
                // Never started; release is what matters.
            }
            record?.release()
        }
        playbackRecord = null
        micRecord = null
    }

    private fun buildPlaybackRecord(): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(stereoFormat())
            .setBufferSizeInBytes(BUFFER_BYTES)
            .build()
    }

    /** The mic commonly refuses a stereo mask, so it is captured mono and upmixed. */
    private fun buildMicRecord(): AudioRecord =
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(BUFFER_BYTES)
            .build()

    private fun stereoFormat(): AudioFormat =
        AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

    private fun readLoop(onPcm: (ByteArray, Int) -> Unit) {
        val playback = ByteArray(BUFFER_BYTES)
        val mic = ByteArray(BUFFER_BYTES / 2)
        val micStereo = ByteArray(BUFFER_BYTES)
        val mixed = ByteArray(BUFFER_BYTES)

        while (reading) {
            val playbackRead = playbackRecord?.read(playback, 0, playback.size) ?: 0
            if (playbackRead <= 0) continue
            // Reads must keep draining while paused, or the buffer overruns and the audio
            // that follows the pause is stale. Paused data is simply discarded.
            if (paused) {
                micRecord?.read(mic, 0, mic.size)
                continue
            }
            val micRead = micRecord?.read(mic, 0, mic.size) ?: 0
            if (micRead > 0) {
                val stereoLen = PcmMixer.upmixMonoToStereo(mic, micRead, micStereo)
                val mixedLen = PcmMixer.mix(playback, playbackRead, micStereo, stereoLen, mixed)
                onPcm(mixed, mixedLen)
            } else {
                onPcm(playback, playbackRead)
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNEL_COUNT = 2
        private const val BUFFER_BYTES = 8_192
        private const val READ_JOIN_TIMEOUT_MS = 2_000L
    }
}
```

- [ ] **Step 3: Write the AAC encoder**

`app/src/main/java/dev/screenrec/record/audio/AudioEncoder.kt`:

```kotlin
package dev.screenrec.record.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * AAC LC encoder fed by PCM buffers. Unlike the video encoder there is no input Surface, so
 * PCM is queued explicitly and the caller supplies the timestamp from [AudioPts].
 */
class AudioEncoder(private val listener: Listener) {

    interface Listener {
        fun onFormat(format: MediaFormat)
        fun onSample(data: ByteArray, offset: Int, size: Int, ptsUs: Long, flags: Int)
        fun onEndOfStream()
        fun onError(e: Exception)
    }

    private val codec: MediaCodec =
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private var drainThread: Thread? = null
    @Volatile private var draining = false

    init {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AudioCaptureSource.SAMPLE_RATE,
            AudioCaptureSource.CHANNEL_COUNT
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    fun start() {
        codec.start()
        draining = true
        drainThread = Thread({ drainLoop() }, "audio-drain").also { it.start() }
    }

    fun submit(pcm: ByteArray, length: Int, ptsUs: Long) {
        try {
            val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index < 0) return // encoder is behind; dropping is better than blocking capture
            val buffer = codec.getInputBuffer(index) ?: return
            buffer.clear()
            buffer.put(pcm, 0, length)
            codec.queueInputBuffer(index, 0, length, ptsUs, 0)
        } catch (e: IllegalStateException) {
            listener.onError(e)
        }
    }

    fun signalEndOfStream() {
        try {
            val index = codec.dequeueInputBuffer(END_OF_STREAM_TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        } catch (e: IllegalStateException) {
            listener.onError(e)
        }
    }

    fun release() {
        draining = false
        drainThread?.join(DRAIN_JOIN_TIMEOUT_MS)
        drainThread = null
        try {
            codec.stop()
        } catch (ignored: IllegalStateException) {
            // Already stopped or never started.
        }
        codec.release()
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (draining) {
                val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> listener.onFormat(codec.outputFormat)
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buffer != null && info.size > 0 && !isConfig) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(bytes, 0, info.size)
                            listener.onSample(bytes, 0, info.size, info.presentationTimeUs, info.flags)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            draining = false
                            listener.onEndOfStream()
                        }
                    }
                }
            }
        } catch (e: IllegalStateException) {
            if (draining) listener.onError(e)
        }
    }

    private companion object {
        const val BIT_RATE = 128_000
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val END_OF_STREAM_TIMEOUT_US = 100_000L
        const val DRAIN_JOIN_TIMEOUT_MS = 2_000L
    }
}
```

- [ ] **Step 4: Verify it compiles**

```bash
./build.sh :app:assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 54 tests still passing.

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: audio capture and aac encoder"
```

---

### Task 11: Recording controller

This is the only class that knows the whole pipeline. Everything it coordinates has already
been built and, where it carries logic, tested.

**Files:**
- Create: `app/src/main/java/dev/screenrec/record/DisplayMetricsSnapshot.kt`
- Create: `app/src/main/java/dev/screenrec/record/RecordingController.kt`

**Interfaces:**
- Consumes: `MediaStoreOutput` (Task 8), `MuxerSink` (Task 8), `MuxerGate`/`TrackKind` (Task 7), `EncoderConfigFactory`/`VideoEncoderFactory`/`VideoEncoder`/`ScreenCaptureSource` (Tasks 6, 9), `AudioCaptureSource`/`AudioEncoder`/`AudioPts` (Tasks 4, 10), `PtsOffsetTracker` (Task 3), `RecordingConfig`/`SoundMode`/`QualityPreset` (Task 6).
- Produces:
  - `data class DisplayMetricsSnapshot(val widthPx: Int, val heightPx: Int, val densityDpi: Int, val rotationDegrees: Int)`
  - `class RecordingController(output: MediaStoreOutput, clockMillis: () -> Long = System::currentTimeMillis)` with `interface Callbacks { fun onStarted(); fun onSaved(displayName: String); fun onError(message: String); fun onProjectionLost() }`, and `fun start(projection: MediaProjection, config: RecordingConfig, metrics: DisplayMetricsSnapshot, callbacks: Callbacks): Boolean`, `fun pause()`, `fun resume()`, `fun stop()`.

- [ ] **Step 1: Write the display metrics snapshot**

`app/src/main/java/dev/screenrec/record/DisplayMetricsSnapshot.kt`:

```kotlin
package dev.screenrec.record

import android.content.Context
import android.view.Surface
import android.view.WindowManager

/**
 * The display facts a recording needs, captured once at start. Rotation is frozen here on
 * purpose: a live MediaCodec stream cannot be resized, so v1 records at the orientation it
 * began in (see the spec's known limitations).
 */
data class DisplayMetricsSnapshot(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val rotationDegrees: Int
) {
    companion object {
        fun from(context: Context): DisplayMetricsSnapshot {
            val wm = context.getSystemService(WindowManager::class.java)
            val bounds = wm.currentWindowMetrics.bounds
            val rotation = when (context.display?.rotation ?: Surface.ROTATION_0) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            return DisplayMetricsSnapshot(
                widthPx = bounds.width(),
                heightPx = bounds.height(),
                densityDpi = context.resources.configuration.densityDpi,
                rotationDegrees = rotation
            )
        }
    }
}
```

- [ ] **Step 2: Write the controller**

`app/src/main/java/dev/screenrec/record/RecordingController.kt`:

```kotlin
package dev.screenrec.record

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.net.Uri
import android.util.Log
import dev.screenrec.mux.MuxerGate
import dev.screenrec.mux.MuxerSink
import dev.screenrec.mux.PtsOffsetTracker
import dev.screenrec.mux.TrackKind
import dev.screenrec.output.MediaStoreOutput
import dev.screenrec.record.audio.AudioCaptureSource
import dev.screenrec.record.audio.AudioEncoder
import dev.screenrec.record.audio.AudioPts
import dev.screenrec.record.video.EncoderConfigFactory
import dev.screenrec.record.video.ScreenCaptureSource
import dev.screenrec.record.video.VideoEncoder
import dev.screenrec.record.video.VideoEncoderFactory
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns one recording session: builds sink, encoders and sources in the order the platform
 * requires, routes samples through the gate, and finalises exactly once however the session
 * ends — user stop, projection revoked, or a write failure.
 */
class RecordingController(
    private val output: MediaStoreOutput,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    interface Callbacks {
        fun onStarted()
        fun onSaved(displayName: String)
        fun onError(message: String)
        fun onProjectionLost()
    }

    private var callbacks: Callbacks? = null
    private var sink: MuxerSink? = null
    private var gate: MuxerGate<MediaFormat>? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var capture: ScreenCaptureSource? = null
    private var audioCapture: AudioCaptureSource? = null
    private var pendingUri: Uri? = null
    private var displayName: String = ""

    private val tracker = PtsOffsetTracker()
    private var audioPts: AudioPts? = null
    private val videoDone = CountDownLatch(1)
    private var audioDone: CountDownLatch? = null

    @Volatile private var finalised = false
    @Volatile private var paused = false

    fun start(
        projection: MediaProjection,
        config: RecordingConfig,
        metrics: DisplayMetricsSnapshot,
        callbacks: Callbacks
    ): Boolean {
        this.callbacks = callbacks
        val caps = VideoEncoderFactory.capabilitiesFor()
        if (caps == null) {
            callbacks.onError("This device has no H.264 encoder")
            return false
        }

        // Encoder init can fail on a size the capabilities claimed to support; step down once.
        var preset: QualityPreset? = config.preset
        var encoder: VideoEncoder? = null
        var spec: VideoFormatSpec? = null
        while (preset != null && encoder == null) {
            val candidate = EncoderConfigFactory.create(
                metrics.widthPx, metrics.heightPx, preset, caps
            )
            encoder = try {
                VideoEncoder(candidate, videoListener())
            } catch (e: Exception) {
                Log.w(TAG, "Encoder init failed at ${preset.label}", e)
                null
            }
            if (encoder != null) spec = candidate else preset = preset.lower()
        }
        if (encoder == null || spec == null) {
            callbacks.onError("Could not start the encoder")
            return false
        }

        val pending = try {
            output.createPending(clockMillis())
        } catch (e: Exception) {
            encoder.release()
            callbacks.onError("Could not create the recording file")
            return false
        }
        pendingUri = pending.uri
        displayName = pending.displayName

        val muxerSink = MuxerSink(pending.descriptor, metrics.rotationDegrees)
        val expected = if (config.soundMode == SoundMode.NONE) {
            setOf(TrackKind.VIDEO)
        } else {
            setOf(TrackKind.VIDEO, TrackKind.AUDIO)
        }
        sink = muxerSink
        gate = MuxerGate(muxerSink, expected)
        videoEncoder = encoder

        if (config.soundMode != SoundMode.NONE) {
            audioDone = CountDownLatch(1)
            audioPts = AudioPts(AudioCaptureSource.SAMPLE_RATE, AudioCaptureSource.CHANNEL_COUNT)
            audioEncoder = AudioEncoder(audioListener()).also { it.start() }
            audioCapture = AudioCaptureSource(projection, config.soundMode).also { source ->
                source.start { pcm, length ->
                    val pts = audioPts?.nextPtsUs(length) ?: return@start
                    audioEncoder?.submit(pcm, length, pts)
                }
            }
        }

        encoder.start()
        capture = ScreenCaptureSource(projection).also {
            it.start(spec.width, spec.height, metrics.densityDpi, encoder.inputSurface) {
                // Projection revoked from the system UI, or stopped by another app.
                callbacks.onProjectionLost()
                finalise()
            }
        }
        callbacks.onStarted()
        return true
    }

    fun pause() {
        if (paused || finalised) return
        paused = true
        tracker.pause(nowUs())
        capture?.pause()
        audioCapture?.pause()
    }

    fun resume() {
        if (!paused || finalised) return
        val surface = videoEncoder?.inputSurface ?: return
        tracker.resume(nowUs())
        capture?.resume(surface)
        audioCapture?.resume()
        paused = false
    }

    fun stop() {
        if (finalised) return
        // Ask both encoders to flush, then wait briefly for their end-of-stream.
        videoEncoder?.signalEndOfStream()
        audioEncoder?.signalEndOfStream()
        videoDone.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        audioDone?.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        finalise()
    }

    private fun videoListener() = object : VideoEncoder.Listener {
        override fun onFormat(format: MediaFormat) {
            gate?.addTrack(TrackKind.VIDEO, format)
        }

        override fun onSample(data: ByteArray, offset: Int, size: Int, ptsUs: Long, flags: Int) {
            writeGuarded { gate?.writeSample(TrackKind.VIDEO, data, offset, size, tracker.adjust(ptsUs), flags) }
        }

        override fun onEndOfStream() {
            videoDone.countDown()
        }

        override fun onError(e: Exception) {
            Log.w(TAG, "Video encoder error", e)
        }
    }

    private fun audioListener() = object : AudioEncoder.Listener {
        override fun onFormat(format: MediaFormat) {
            gate?.addTrack(TrackKind.AUDIO, format)
        }

        override fun onSample(data: ByteArray, offset: Int, size: Int, ptsUs: Long, flags: Int) {
            writeGuarded { gate?.writeSample(TrackKind.AUDIO, data, offset, size, ptsUs, flags) }
        }

        override fun onEndOfStream() {
            audioDone?.countDown()
        }

        override fun onError(e: Exception) {
            Log.w(TAG, "Audio encoder error", e)
        }
    }

    /** A full disk surfaces here; keep what was written rather than losing the take. */
    private inline fun writeGuarded(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            Log.w(TAG, "Write failed; finalising early", e)
            finalise()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Muxer rejected a sample; finalising early", e)
            finalise()
        }
    }

    private fun finalise() {
        if (finalised) return
        finalised = true

        audioCapture?.release()
        capture?.release()
        videoEncoder?.release()
        audioEncoder?.release()

        val wroteSomething = gate?.isStarted == true
        gate?.stop()
        sink?.release()

        val uri = pendingUri
        if (uri != null && wroteSomething) {
            output.publish(uri)
            callbacks?.onSaved(displayName)
        } else {
            if (uri != null) output.discard(uri)
            callbacks?.onError("Nothing was recorded")
        }

        audioCapture = null
        capture = null
        videoEncoder = null
        audioEncoder = null
        gate = null
        sink = null
        pendingUri = null
    }

    /**
     * Surface timestamps come from CLOCK_MONOTONIC, which is what nanoTime reads, so pause
     * marks are on the same timeline as the frames they bracket.
     */
    private fun nowUs(): Long = System.nanoTime() / 1_000L

    private companion object {
        const val TAG = "RecordingController"
        const val DRAIN_TIMEOUT_SECONDS = 3L
    }
}
```

- [ ] **Step 3: Verify it compiles**

```bash
./build.sh :app:assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 54 tests still passing. `MediaCodec` is imported for its
buffer flags via the encoders; if the compiler reports it unused, delete that one import.

- [ ] **Step 4: Commit**

```bash
git add app/src && git commit -m "feat: recording controller wiring the pipeline"
```

---

### Task 12: Overlay windows with FLAG_SECURE

The hard requirement of the whole project lives in this file: `FLAG_SECURE` on every overlay
window. The compositor omits secure layers when mirroring to a non-secure virtual display, so
the pill should be invisible to the capture while visible to the user. Task 16 proves it by
pixel inspection; this task makes the claim testable.

**Files:**
- Create: `app/src/main/java/dev/screenrec/overlay/PillView.kt`
- Create: `app/src/main/java/dev/screenrec/overlay/CountdownView.kt`
- Create: `app/src/main/java/dev/screenrec/overlay/OverlayController.kt`
- Create: `app/src/main/res/drawable/bg_pill.xml`

**Interfaces:**
- Consumes: nothing.
- Produces: `class OverlayController(context: Context)` with `fun showCountdown(from: Int, onComplete: () -> Unit)`, `fun showPill(onPauseToggle: () -> Unit, onStop: () -> Unit)`, `fun setPaused(paused: Boolean)`, `fun hideAll()`, and `var renderPillDuringCapture: Boolean` (the kill switch for the notification-only fallback).

- [ ] **Step 1: Write the pill background**

`app/src/main/res/drawable/bg_pill.xml`:

```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#CC1B1B1B" />
    <corners android:radius="26dp" />
</shape>
```

- [ ] **Step 2: Write the two views**

`app/src/main/java/dev/screenrec/overlay/PillView.kt`:

```kotlin
package dev.screenrec.overlay

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.Chronometer
import android.widget.LinearLayout
import dev.screenrec.R

/**
 * Timer plus pause and stop, built in code rather than XML because it is three widgets in a
 * row and inflating a layout for it would be more indirection than it saves.
 */
class PillView(
    context: Context,
    private val onPauseToggle: () -> Unit,
    private val onStop: () -> Unit
) : LinearLayout(context) {

    private val chronometer = Chronometer(context)
    private val pauseButton = Button(context, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless)
    private val stopButton = Button(context, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = context.getDrawable(R.drawable.bg_pill)
        val pad = dp(12)
        setPadding(pad, dp(6), pad, dp(6))

        chronometer.base = SystemClock.elapsedRealtime()
        chronometer.setTextColor(Color.WHITE)
        chronometer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        chronometer.start()
        addView(chronometer)

        pauseButton.text = context.getString(R.string.action_pause)
        pauseButton.setTextColor(Color.WHITE)
        pauseButton.setOnClickListener { onPauseToggle() }
        addView(pauseButton)

        stopButton.text = context.getString(R.string.action_stop)
        stopButton.setTextColor(Color.WHITE)
        stopButton.setOnClickListener { onStop() }
        addView(stopButton)
    }

    private var pausedElapsedMs = 0L

    fun setPaused(paused: Boolean) {
        if (paused) {
            pausedElapsedMs = SystemClock.elapsedRealtime() - chronometer.base
            chronometer.stop()
            pauseButton.text = context.getString(R.string.action_resume)
        } else {
            // Rebase so the displayed time excludes the pause, matching the recording.
            chronometer.base = SystemClock.elapsedRealtime() - pausedElapsedMs
            chronometer.start()
            pauseButton.text = context.getString(R.string.action_pause)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
```

`app/src/main/java/dev/screenrec/overlay/CountdownView.kt`:

```kotlin
package dev.screenrec.overlay

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/** The 3-2-1 One UI shows before recording begins. */
class CountdownView(context: Context) : TextView(context) {
    init {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 96f)
        setShadowLayer(24f, 0f, 0f, Color.BLACK)
    }

    fun show(value: Int) {
        text = value.toString()
    }
}
```

- [ ] **Step 3: Write the overlay controller**

`app/src/main/java/dev/screenrec/overlay/OverlayController.kt`:

```kotlin
package dev.screenrec.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Every window this class creates carries FLAG_SECURE, which is what keeps the controls out
 * of the recording. [renderPillDuringCapture] is the escape hatch: set it false and the pill
 * is never added at all, leaving the notification as the only control surface. That is the
 * fallback if the platform turns out not to honour FLAG_SECURE for mirrored displays.
 */
class OverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var pill: PillView? = null
    private var countdown: CountdownView? = null

    var renderPillDuringCapture: Boolean = true

    fun showCountdown(from: Int, onComplete: () -> Unit) {
        if (!canDrawOverlays()) {
            onComplete()
            return
        }
        val view = CountdownView(context)
        countdown = view
        windowManager.addView(view, params(Gravity.CENTER))
        tick(view, from, onComplete)
    }

    fun showPill(onPauseToggle: () -> Unit, onStop: () -> Unit) {
        if (!renderPillDuringCapture || !canDrawOverlays() || pill != null) return
        val view = PillView(context, onPauseToggle, onStop)
        pill = view
        val p = params(Gravity.TOP or Gravity.END).apply {
            y = (24 * context.resources.displayMetrics.density).toInt()
            x = y
        }
        windowManager.addView(view, p)
    }

    fun setPaused(paused: Boolean) {
        pill?.setPaused(paused)
    }

    fun hideAll() {
        remove(countdown)
        countdown = null
        remove(pill)
        pill = null
    }

    private fun tick(view: CountdownView, remaining: Int, onComplete: () -> Unit) {
        if (remaining <= 0) {
            remove(view)
            if (countdown === view) countdown = null
            onComplete()
            return
        }
        view.show(remaining)
        handler.postDelayed({ tick(view, remaining - 1, onComplete) }, TICK_MS)
    }

    private fun remove(view: View?) {
        if (view == null) return
        try {
            windowManager.removeView(view)
        } catch (ignored: IllegalArgumentException) {
            // Already detached.
        }
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    private fun params(gravity: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_SECURE is the whole mechanism. FLAG_NOT_FOCUSABLE keeps the keyboard and
            // back button behaviour of the app underneath intact.
            WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
        }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
```

- [ ] **Step 4: Verify it compiles**

```bash
./build.sh :app:assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 54 tests still passing.

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: FLAG_SECURE overlay pill and countdown"
```

---

### Task 13: Notifications and the foreground service

The ordering here is dictated by the platform, not by taste: on API 34+ the foreground
service must already be running **with type `mediaProjection`** before `getMediaProjection()`
is called, or the call throws.

**Files:**
- Create: `app/src/main/java/dev/screenrec/service/RecorderNotifications.kt`
- Create: `app/src/main/java/dev/screenrec/service/RecorderService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `RecordingController` + `Callbacks` (Task 11), `DisplayMetricsSnapshot` (Task 11), `RecordingConfig`/`SoundMode`/`QualityPreset` (Task 6), `MediaStoreOutput` (Task 8), `RecordingStateMachine`/`RecordingState` (Task 2).
- Produces:
  - `class RecorderNotifications(context: Context)` with `fun ensureChannel()`, `fun ongoing(startedAtElapsedMs: Long, paused: Boolean): Notification`, `fun saved(displayName: String)`, `fun error(message: String)`, `companion object { const val ONGOING_ID = 1 }`.
  - `class RecorderService : Service()` with a companion exposing `fun startIntent(context: Context, resultData: Intent, config: RecordingConfig): Intent`, `fun pauseIntent(context: Context): Intent`, `fun resumeIntent(context: Context): Intent`, `fun stopIntent(context: Context): Intent`.

- [ ] **Step 1: Write the notifications**

`app/src/main/java/dev/screenrec/service/RecorderNotifications.kt`:

```kotlin
package dev.screenrec.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import dev.screenrec.R

/**
 * The ongoing notification is the fallback control surface: if FLAG_SECURE turns out not to
 * exclude the pill from the capture, this is the only way to stop a recording, so pause and
 * stop actions live here regardless.
 */
class RecorderNotifications(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW // silent: a recorder must not beep into its own audio
        ).apply {
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun ongoing(startedAtElapsedMs: Long, paused: Boolean): Notification {
        // Notification.setWhen takes wall-clock time, but the session is timed on
        // elapsedRealtime; convert rather than mixing the two clocks.
        val startedAtWallMs =
            System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedAtElapsedMs)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(context.getString(R.string.notif_recording_title))
            .setOngoing(true)
            .setWhen(startedAtWallMs)
            .setShowWhen(!paused)
            .setUsesChronometer(!paused)

        if (paused) {
            builder.setContentText(context.getString(R.string.notif_paused))
            builder.addAction(action(R.string.action_resume, RecorderService.resumeIntent(context), 1))
        } else {
            builder.addAction(action(R.string.action_pause, RecorderService.pauseIntent(context), 2))
        }
        builder.addAction(action(R.string.action_stop, RecorderService.stopIntent(context), 3))
        return builder.build()
    }

    fun saved(displayName: String) {
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(context.getString(R.string.notif_saved_title))
            .setContentText(displayName)
            .setAutoCancel(true)
            .build()
        manager.notify(SAVED_ID, notification)
    }

    fun error(message: String) {
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(context.getString(R.string.notif_error_title))
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        manager.notify(ERROR_ID, notification)
    }

    private fun action(labelRes: Int, intent: android.content.Intent, requestCode: Int): Notification.Action {
        val pending = PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(null, context.getString(labelRes), pending).build()
    }

    companion object {
        const val ONGOING_ID = 1
        private const val SAVED_ID = 2
        private const val ERROR_ID = 3
        private const val CHANNEL_ID = "recording"
    }
}
```

- [ ] **Step 2: Write the string and icon resources**

Create `app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Screen recorder</string>
    <string name="channel_name">Screen recording</string>
    <string name="notif_recording_title">Recording screen</string>
    <string name="notif_paused">Paused</string>
    <string name="notif_saved_title">Recording saved</string>
    <string name="notif_error_title">Recording failed</string>
    <string name="action_pause">Pause</string>
    <string name="action_resume">Resume</string>
    <string name="action_stop">Stop</string>
    <string name="sheet_title">Screen recorder</string>
    <string name="sheet_sound">Sound</string>
    <string name="sheet_quality">Video quality</string>
    <string name="sheet_start">Start recording</string>
    <string name="sound_none">No sound</string>
    <string name="sound_media">Media</string>
    <string name="sound_media_mic">Media and mic</string>
    <string name="sound_hint">Some apps block recording of their audio.</string>
    <string name="tile_label">Screen recorder</string>
</resources>
```

Create `app/src/main/res/drawable/ic_record.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M12,4a8,8 0 1,0 0,16a8,8 0 1,0 0,-16z" />
</vector>
```

- [ ] **Step 3: Write the service**

`app/src/main/java/dev/screenrec/service/RecorderService.kt`:

```kotlin
package dev.screenrec.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import dev.screenrec.output.MediaStoreOutput
import dev.screenrec.overlay.OverlayController
import dev.screenrec.record.DisplayMetricsSnapshot
import dev.screenrec.record.QualityPreset
import dev.screenrec.record.RecordingConfig
import dev.screenrec.record.RecordingController
import dev.screenrec.record.RecordingState
import dev.screenrec.record.RecordingStateMachine
import dev.screenrec.record.SoundMode

/**
 * Owns the session for its whole life. Every transition runs on the main thread so the state
 * machine needs no locking.
 */
class RecorderService : Service(), RecordingController.Callbacks {

    private val handler = Handler(Looper.getMainLooper())
    private val machine = RecordingStateMachine()
    private lateinit var notifications: RecorderNotifications
    private lateinit var controller: RecordingController
    private lateinit var overlay: OverlayController

    private var projection: MediaProjection? = null
    private var startedAtElapsedMs = 0L

    override fun onCreate() {
        super.onCreate()
        notifications = RecorderNotifications(this).also { it.ensureChannel() }
        controller = RecordingController(MediaStoreOutput(this))
        overlay = OverlayController(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (machine.state != RecordingState.IDLE) return
        val resultData: Intent = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) ?: return
        val config = RecordingConfig(
            soundMode = SoundMode.valueOf(intent.getStringExtra(EXTRA_SOUND_MODE) ?: SoundMode.MEDIA.name),
            preset = QualityPreset.valueOf(intent.getStringExtra(EXTRA_PRESET) ?: QualityPreset.P1080.name)
        )

        // Order is mandatory: foreground first, with the mediaProjection type, THEN the token.
        startForeground(
            RecorderNotifications.ONGOING_ID,
            notifications.ongoing(SystemClock.elapsedRealtime(), paused = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        val manager = getSystemService(MediaProjectionManager::class.java)
        val token = manager.getMediaProjection(android.app.Activity.RESULT_OK, resultData)
        if (token == null) {
            onError(getString(dev.screenrec.R.string.notif_error_title))
            stopSelf()
            return
        }
        projection = token
        machine.transitionTo(RecordingState.COUNTDOWN)

        overlay.showCountdown(COUNTDOWN_FROM) {
            if (machine.state != RecordingState.COUNTDOWN) return@showCountdown
            val started = controller.start(token, config, DisplayMetricsSnapshot.from(this), this)
            if (!started) {
                machine.transitionTo(RecordingState.IDLE)
                stopSelf()
            }
        }
    }

    private fun handlePause() {
        if (!machine.transitionTo(RecordingState.PAUSED)) return
        controller.pause()
        overlay.setPaused(true)
        notifications.ongoing(startedAtElapsedMs, paused = true).also {
            startForeground(RecorderNotifications.ONGOING_ID, it, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        }
    }

    private fun handleResume() {
        if (!machine.transitionTo(RecordingState.RECORDING)) return
        controller.resume()
        overlay.setPaused(false)
        notifications.ongoing(startedAtElapsedMs, paused = false).also {
            startForeground(RecorderNotifications.ONGOING_ID, it, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        }
    }

    private fun handleStop() {
        if (!machine.transitionTo(RecordingState.STOPPING)) return
        overlay.hideAll()
        // Draining blocks briefly; keep it off the main thread.
        Thread({ controller.stop() }, "stop-session").start()
    }

    override fun onStarted() {
        machine.transitionTo(RecordingState.RECORDING)
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        overlay.showPill(
            onPauseToggle = {
                if (machine.state == RecordingState.PAUSED) handleResume() else handlePause()
            },
            onStop = { handleStop() }
        )
    }

    override fun onSaved(displayName: String) {
        handler.post {
            notifications.saved(displayName)
            finish()
        }
    }

    override fun onError(message: String) {
        handler.post {
            notifications.error(message)
            finish()
        }
    }

    override fun onProjectionLost() {
        handler.post {
            machine.transitionTo(RecordingState.STOPPING)
            overlay.hideAll()
        }
    }

    private fun finish() {
        machine.transitionTo(RecordingState.IDLE)
        overlay.hideAll()
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        overlay.hideAll()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "dev.screenrec.START"
        private const val ACTION_PAUSE = "dev.screenrec.PAUSE"
        private const val ACTION_RESUME = "dev.screenrec.RESUME"
        private const val ACTION_STOP = "dev.screenrec.STOP"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_SOUND_MODE = "sound_mode"
        private const val EXTRA_PRESET = "preset"
        private const val COUNTDOWN_FROM = 3

        fun startIntent(context: Context, resultData: Intent, config: RecordingConfig): Intent =
            Intent(context, RecorderService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_SOUND_MODE, config.soundMode.name)
                putExtra(EXTRA_PRESET, config.preset.name)
            }

        fun pauseIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java).apply { action = ACTION_PAUSE }

        fun resumeIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java).apply { action = ACTION_RESUME }

        fun stopIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java).apply { action = ACTION_STOP }
    }
}
```

- [ ] **Step 4: Declare the service and its permissions**

Replace `app/src/main/AndroidManifest.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

    <application
        android:icon="@drawable/ic_record"
        android:label="@string/app_name"
        android:theme="@android:style/Theme.DeviceDefault.DayNight">

        <service
            android:name=".service.RecorderService"
            android:exported="false"
            android:foregroundServiceType="mediaProjection" />

    </application>

</manifest>
```

- [ ] **Step 5: Verify it compiles**

```bash
./build.sh :app:assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 54 tests still passing. `OverlayController` already exists from
Task 12, so this is the first build with the whole recording path present.

- [ ] **Step 6: Commit**

```bash
git add app/src && git commit -m "feat: foreground service and notification controls"
```

---

### Task 14: Start sheet and settings

No AndroidX means no `ActivityResultLauncher`; the framework's `requestPermissions` /
`onRequestPermissionsResult` and `startActivityForResult` / `onActivityResult` are the tools,
and they are perfectly adequate. The permission gates are re-entrant: each result calls
`proceed()` again, which advances to the next unmet requirement.

**Files:**
- Create: `app/src/main/java/dev/screenrec/settings/SettingsRepository.kt`
- Create: `app/src/main/java/dev/screenrec/ui/StartSheetActivity.kt`
- Create: `app/src/main/res/layout/activity_start_sheet.xml`
- Create: `app/src/main/res/drawable/bg_sheet.xml`
- Create: `app/src/main/res/values/styles.xml`
- Modify: `app/src/main/AndroidManifest.xml` — add the activity

**Interfaces:**
- Consumes: `SoundMode`/`QualityPreset`/`RecordingConfig` (Task 6), `RecorderService.startIntent` (Task 13), `MediaStoreOutput.cleanUpOrphans` (Task 8).
- Produces: `class SettingsRepository(context: Context)` with `var soundMode: SoundMode` and `var preset: QualityPreset` (persisted); `class StartSheetActivity : Activity()`.

- [ ] **Step 1: Write the settings repository**

`app/src/main/java/dev/screenrec/settings/SettingsRepository.kt`:

```kotlin
package dev.screenrec.settings

import android.content.Context
import dev.screenrec.record.QualityPreset
import dev.screenrec.record.SoundMode

/** Remembers the last choices, the way Samsung's recorder does. */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("screenrec", Context.MODE_PRIVATE)

    var soundMode: SoundMode
        get() = runCatching { SoundMode.valueOf(prefs.getString(KEY_SOUND, null) ?: "") }
            .getOrDefault(SoundMode.MEDIA)
        set(value) = prefs.edit().putString(KEY_SOUND, value.name).apply()

    var preset: QualityPreset
        get() = runCatching { QualityPreset.valueOf(prefs.getString(KEY_PRESET, null) ?: "") }
            .getOrDefault(QualityPreset.P1080)
        set(value) = prefs.edit().putString(KEY_PRESET, value.name).apply()

    private companion object {
        const val KEY_SOUND = "sound_mode"
        const val KEY_PRESET = "preset"
    }
}
```

- [ ] **Step 2: Write the sheet resources**

`app/src/main/res/drawable/bg_sheet.xml`:

```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="?android:attr/colorBackground" />
    <corners android:topLeftRadius="26dp" android:topRightRadius="26dp" />
</shape>
```

`app/src/main/res/values/styles.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- DeviceDefault on a Samsung device *is* One UI: system font, ripple, radio styling. -->
    <style name="Theme.Sheet" parent="@android:style/Theme.DeviceDefault.DayNight.NoActionBar">
        <item name="android:windowBackground">@android:color/transparent</item>
        <item name="android:windowIsTranslucent">true</item>
        <item name="android:backgroundDimEnabled">true</item>
        <item name="android:windowAnimationStyle">@android:style/Animation.InputMethod</item>
    </style>
</resources>
```

`app/src/main/res/layout/activity_start_sheet.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <LinearLayout
        android:id="@+id/sheet"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        android:background="@drawable/bg_sheet"
        android:orientation="vertical"
        android:paddingStart="24dp"
        android:paddingTop="28dp"
        android:paddingEnd="24dp"
        android:paddingBottom="32dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/sheet_title"
            android:textSize="22sp"
            android:textStyle="bold" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="24dp"
            android:text="@string/sheet_sound"
            android:textSize="14sp" />

        <RadioGroup
            android:id="@+id/sound_group"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <RadioButton
                android:id="@+id/sound_none"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:minHeight="52dp"
                android:text="@string/sound_none" />

            <RadioButton
                android:id="@+id/sound_media"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:minHeight="52dp"
                android:text="@string/sound_media" />

            <RadioButton
                android:id="@+id/sound_media_mic"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:minHeight="52dp"
                android:text="@string/sound_media_mic" />
        </RadioGroup>

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:alpha="0.6"
            android:text="@string/sound_hint"
            android:textSize="12sp" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="24dp"
            android:text="@string/sheet_quality"
            android:textSize="14sp" />

        <RadioGroup
            android:id="@+id/quality_group"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal">

            <RadioButton
                android:id="@+id/quality_1080"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:minHeight="52dp"
                android:text="1080p" />

            <RadioButton
                android:id="@+id/quality_720"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:minHeight="52dp"
                android:text="720p" />

            <RadioButton
                android:id="@+id/quality_480"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:minHeight="52dp"
                android:text="480p" />
        </RadioGroup>

        <Button
            android:id="@+id/start"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="28dp"
            android:minHeight="56dp"
            android:text="@string/sheet_start" />
    </LinearLayout>
</FrameLayout>
```

- [ ] **Step 3: Write the activity**

`app/src/main/java/dev/screenrec/ui/StartSheetActivity.kt`:

```kotlin
package dev.screenrec.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import dev.screenrec.R
import dev.screenrec.output.MediaStoreOutput
import dev.screenrec.record.QualityPreset
import dev.screenrec.record.RecordingConfig
import dev.screenrec.record.SoundMode
import dev.screenrec.service.RecorderService
import dev.screenrec.settings.SettingsRepository

/**
 * Bottom sheet: pick sound and quality, clear the permission gates, consent to capture.
 * Each gate returns here and calls proceed() again, so the order is explicit and there is
 * exactly one path to starting the service.
 */
class StartSheetActivity : Activity() {

    private lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_sheet)
        settings = SettingsRepository(this)

        // A previous session killed mid-recording leaves an invisible pending row behind.
        MediaStoreOutput(this).cleanUpOrphans()

        val soundGroup = findViewById<RadioGroup>(R.id.sound_group)
        val qualityGroup = findViewById<RadioGroup>(R.id.quality_group)

        soundGroup.check(
            when (settings.soundMode) {
                SoundMode.NONE -> R.id.sound_none
                SoundMode.MEDIA -> R.id.sound_media
                SoundMode.MEDIA_AND_MIC -> R.id.sound_media_mic
            }
        )
        qualityGroup.check(
            when (settings.preset) {
                QualityPreset.P1080 -> R.id.quality_1080
                QualityPreset.P720 -> R.id.quality_720
                QualityPreset.P480 -> R.id.quality_480
            }
        )

        soundGroup.setOnCheckedChangeListener { _, id ->
            settings.soundMode = when (id) {
                R.id.sound_none -> SoundMode.NONE
                R.id.sound_media_mic -> SoundMode.MEDIA_AND_MIC
                else -> SoundMode.MEDIA
            }
        }
        qualityGroup.setOnCheckedChangeListener { _, id ->
            settings.preset = when (id) {
                R.id.quality_720 -> QualityPreset.P720
                R.id.quality_480 -> QualityPreset.P480
                else -> QualityPreset.P1080
            }
        }

        findViewById<Button>(R.id.start).setOnClickListener { proceed() }
    }

    private fun config() = RecordingConfig(settings.soundMode, settings.preset)

    /** Advances to the next unmet requirement, or launches the consent dialog. */
    private fun proceed() {
        if (!granted(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            return
        }
        if (config().soundMode != SoundMode.NONE && !granted(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ),
                REQ_OVERLAY
            )
            return
        }
        requestCapture()
    }

    private fun requestCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        // Forcing the default display suppresses the system's "single app" choice, so the
        // consent dialog matches the one Samsung's own recorder shows.
        val intent = manager.createScreenCaptureIntent(
            MediaProjectionConfig.createConfigForDefaultDisplay()
        )
        startActivityForResult(intent, REQ_CONSENT)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // A denied mic falls back to no sound rather than blocking the recording outright.
        if (requestCode == REQ_MIC && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            settings.soundMode = SoundMode.NONE
        }
        proceed()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> proceed()
            REQ_CONSENT -> {
                if (resultCode == RESULT_OK && data != null) {
                    startForegroundService(RecorderService.startIntent(this, data, config()))
                }
                // Either way the sheet's work is done; consent denial simply dismisses it.
                finish()
            }
        }
    }

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val REQ_NOTIFICATIONS = 1
        const val REQ_MIC = 2
        const val REQ_OVERLAY = 3
        const val REQ_CONSENT = 4
    }
}
```

- [ ] **Step 4: Declare the activity**

In `app/src/main/AndroidManifest.xml`, inside `<application>` before the `<service>`:

```xml
        <activity
            android:name=".ui.StartSheetActivity"
            android:excludeFromRecents="true"
            android:exported="true"
            android:label="@string/app_name"
            android:launchMode="singleTop"
            android:theme="@style/Theme.Sheet">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
```

- [ ] **Step 5: Verify it builds**

```bash
./build.sh :app:assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 54 tests still passing.

- [ ] **Step 6: Commit**

```bash
git add app/src && git commit -m "feat: start sheet with permission gating and settings"
```

---

### Task 15: Quick Settings tile

**Files:**
- Create: `app/src/main/java/dev/screenrec/tile/RecorderTileService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `StartSheetActivity` (Task 14).
- Produces: `class RecorderTileService : TileService()`.

- [ ] **Step 1: Write the tile**

`app/src/main/java/dev/screenrec/tile/RecorderTileService.kt`:

```kotlin
package dev.screenrec.tile

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.screenrec.ui.StartSheetActivity

/**
 * The entry point that makes this feel like a system feature: tap the tile, get the sheet.
 * startActivityAndCollapse takes a PendingIntent on API 34+; the Intent overload throws.
 */
class RecorderTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, StartSheetActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        startActivityAndCollapse(pending)
    }
}
```

- [ ] **Step 2: Declare the tile**

In `app/src/main/AndroidManifest.xml`, inside `<application>`:

```xml
        <service
            android:name=".tile.RecorderTileService"
            android:exported="true"
            android:icon="@drawable/ic_record"
            android:label="@string/tile_label"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>
        </service>
```

- [ ] **Step 3: Verify it builds**

```bash
./build.sh :app:assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 54 tests still passing, APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 4: Commit**

```bash
git add app/src && git commit -m "feat: quick settings tile"
```

---

### Task 16: On-device verification, including the FLAG_SECURE proof

Everything above is either JVM-tested or compile-checked. This task is where the claims that
cannot be tested on a JVM get measured: real encoding, real audio routing, pause with no gap,
Gallery publication, and above all that the pill does not appear in the video.

**Files:**
- Create: `tools/verify_recording.py`
- Modify (only if the proof fails): `app/src/main/java/dev/screenrec/overlay/OverlayController.kt`

**Interfaces:**
- Consumes: the debug APK from Task 15.
- Produces: `tools/verify_recording.py --video <path> [--expect-audio] [--pill-region x,y,w,h]` exiting non-zero on any failed assertion.

- [ ] **Step 0: Establish adb connectivity**

`adb` cannot see USB devices from inside the sandbox — `/dev/bus/usb` is not mounted, so the
client has no device nodes to claim even though the phone is plugged in and its ADB interface
is live (visible in sysfs as interface class `ff`, subclass `42`, protocol `01`). The
sandboxed client *can* reach an adb **server** on `localhost:5037`, so the server has to run
outside the sandbox. In a normal terminal on the host, run:

```bash
~/Android/Sdk/platform-tools/adb devices
```

Accept the "Allow USB debugging?" prompt on the phone if it appears. Then, from inside the
sandbox, confirm the client attaches to that server:

```bash
~/Android/Sdk/platform-tools/adb devices -l
```

Expected: one device listed, `model:SM_A176B` or similar, state `device`. If it says
`unauthorized`, accept the prompt on the phone. If the list is empty while the host's own
`adb devices` shows the phone, the sandboxed client started its own server — run
`~/Android/Sdk/platform-tools/adb kill-server` inside the sandbox and re-run the host command
first, so port 5037 belongs to the host's server.

- [ ] **Step 1: Write the verification script**

`tools/verify_recording.py`:

```python
#!/usr/bin/env python3
"""Assertions over a pulled screen recording. Exits non-zero on any failure.

Checks the things a human would otherwise eyeball and get wrong: that the file really
decodes, that the tracks are the ones asked for, that pause left no frozen gap, and that
the floating pill is absent from the pixels it occupied on screen.
"""
import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

MAX_FRAME_GAP_SECONDS = 0.75  # a pause must not leave a visible freeze
NEAR_BLACK = 40               # 0-255 luminance below this counts as pill background
MAX_DARK_FRACTION = 0.01      # 1% tolerance for genuinely dark UI pixels


def ffprobe(video: Path, *args: str) -> dict:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-print_format", "json", *args, str(video)],
        check=True, capture_output=True, text=True,
    ).stdout
    return json.loads(out)


def check_tracks(video: Path, expect_audio: bool) -> list[str]:
    failures = []
    info = ffprobe(video, "-show_streams", "-show_format")
    streams = info.get("streams", [])
    video_streams = [s for s in streams if s.get("codec_type") == "video"]
    audio_streams = [s for s in streams if s.get("codec_type") == "audio"]

    if len(video_streams) != 1:
        failures.append(f"expected exactly 1 video track, found {len(video_streams)}")
    else:
        v = video_streams[0]
        if v.get("codec_name") != "h264":
            failures.append(f"video codec is {v.get('codec_name')}, expected h264")
        if int(v.get("width", 0)) <= 0 or int(v.get("height", 0)) <= 0:
            failures.append("video track reports no dimensions")
        print(f"  video: {v.get('codec_name')} {v.get('width')}x{v.get('height')}")

    if expect_audio and len(audio_streams) != 1:
        failures.append(f"expected 1 audio track, found {len(audio_streams)}")
    elif not expect_audio and audio_streams:
        failures.append(f"expected no audio track, found {len(audio_streams)}")
    elif audio_streams:
        a = audio_streams[0]
        if a.get("codec_name") != "aac":
            failures.append(f"audio codec is {a.get('codec_name')}, expected aac")
        print(f"  audio: {a.get('codec_name')} {a.get('sample_rate')}Hz {a.get('channels')}ch")

    duration = float(info.get("format", {}).get("duration", 0.0))
    if duration <= 0.5:
        failures.append(f"duration is {duration}s; the file is effectively empty")
    print(f"  duration: {duration:.2f}s")
    return failures


def check_no_frozen_gap(video: Path) -> list[str]:
    """A pause that failed to shift timestamps shows up as one huge inter-frame gap."""
    info = ffprobe(video, "-select_streams", "v:0", "-show_entries", "frame=pts_time")
    times = sorted(
        float(f["pts_time"]) for f in info.get("frames", []) if f.get("pts_time") is not None
    )
    if len(times) < 2:
        return ["fewer than 2 video frames; cannot assess timing"]
    gaps = [b - a for a, b in zip(times, times[1:])]
    worst = max(gaps)
    print(f"  frames: {len(times)}, largest inter-frame gap {worst * 1000:.0f}ms")
    if worst > MAX_FRAME_GAP_SECONDS:
        return [f"largest frame gap {worst:.2f}s exceeds {MAX_FRAME_GAP_SECONDS}s"]
    return []


def check_pill_absent(
    video: Path, region: tuple[int, int, int, int], screen_width: int
) -> list[str]:
    """The pill's background is near-black; on a light screen its absence is measurable.

    The region comes from dumpsys in *screen* pixels, but the video may be encoded at a
    lower preset, so it is scaled into frame coordinates before cropping.
    """
    with tempfile.TemporaryDirectory() as tmp:
        pattern = str(Path(tmp) / "frame_%03d.png")
        subprocess.run(
            ["ffmpeg", "-v", "error", "-i", str(video), "-vf", "fps=1", "-frames:v", "5", pattern],
            check=True,
        )
        frames = sorted(Path(tmp).glob("frame_*.png"))
        if not frames:
            return ["no frames could be extracted"]
        worst_fraction = 0.0
        for frame in frames:
            with Image.open(frame) as img:
                scale = img.width / screen_width
                x, y, w, h = (int(v * scale) for v in region)
                if w <= 0 or h <= 0:
                    return [f"pill region {region} scales to nothing at {img.width}px wide"]
                if x + w > img.width or y + h > img.height:
                    return [
                        f"scaled pill region {(x, y, w, h)} lies outside the "
                        f"{img.width}x{img.height} frame"
                    ]
                crop = img.convert("L").crop((x, y, x + w, y + h))
                pixels = list(crop.getdata())
                dark = sum(1 for p in pixels if p < NEAR_BLACK)
                worst_fraction = max(worst_fraction, dark / len(pixels))
        print(f"  pill region darkest frame: {worst_fraction * 100:.2f}% near-black pixels")
        if worst_fraction > MAX_DARK_FRACTION:
            return [
                f"{worst_fraction * 100:.1f}% of the pill region is near-black; "
                "FLAG_SECURE did not exclude the overlay"
            ]
    return []


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", required=True, type=Path)
    parser.add_argument("--expect-audio", action="store_true")
    parser.add_argument(
        "--pill-region",
        help="x,y,w,h of the pill's on-screen bounds, from dumpsys window",
    )
    parser.add_argument(
        "--screen-width",
        type=int,
        default=1080,
        help="physical screen width the pill region was measured in (adb shell wm size)",
    )
    args = parser.parse_args()

    if not args.video.exists():
        print(f"FAIL: {args.video} does not exist")
        return 1

    print(f"Verifying {args.video} ({args.video.stat().st_size} bytes)")
    failures = check_tracks(args.video, args.expect_audio)
    failures += check_no_frozen_gap(args.video)
    if args.pill_region:
        region = tuple(int(part) for part in args.pill_region.split(","))
        if len(region) != 4:
            failures.append("--pill-region needs exactly x,y,w,h")
        else:
            failures += check_pill_absent(args.video, region, args.screen_width)

    if failures:
        print("\nFAILED:")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("\nAll checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: Install and grant what can be granted from the shell**

```bash
ADB=~/Android/Sdk/platform-tools/adb
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell pm grant dev.screenrec android.permission.RECORD_AUDIO
$ADB shell pm grant dev.screenrec android.permission.POST_NOTIFICATIONS
$ADB shell appops set dev.screenrec SYSTEM_ALERT_WINDOW allow
```

Expected: `Success`, then no output from the grants. Overlay permission via `appops` avoids
the Settings round-trip; `MediaProjection` consent cannot be granted this way and must be
tapped, which is the point of the next step.

- [ ] **Step 3: Record a take with a light background and the pill on screen**

Light background matters: the proof measures the *absence* of the pill's near-black
background, so the content behind it must not itself be near-black.

```bash
ADB=~/Android/Sdk/platform-tools/adb
$ADB shell cmd uimode night no          # force light theme
$ADB shell am start -a android.settings.SETTINGS   # a reliably light, static screen
$ADB shell am start -n dev.screenrec/.ui.StartSheetActivity
$ADB shell input tap 540 1900           # "Start recording"; adjust from uiautomator if needed
sleep 1
$ADB shell uiautomator dump /sdcard/sheet.xml && $ADB shell grep -o 'text="[^"]*"' /sdcard/sheet.xml | sort -u
```

Tap "Start now" in the system consent dialog (its coordinates vary by One UI build; read them
from the `uiautomator dump` output). Then let it run, exercise pause, and stop:

```bash
sleep 4
$ADB shell dumpsys window windows | grep -A 6 "screenrec"   # note the pill window's frame
sleep 3
$ADB shell am startservice -a dev.screenrec.PAUSE -n dev.screenrec/.service.RecorderService
sleep 3
$ADB shell am startservice -a dev.screenrec.RESUME -n dev.screenrec/.service.RecorderService
sleep 3
$ADB shell am startservice -a dev.screenrec.STOP -n dev.screenrec/.service.RecorderService
sleep 3
$ADB shell ls -l "/sdcard/DCIM/Screen recordings/"
```

Expected: a `Screen_recording_*.mp4` of non-trivial size, and a "Recording saved"
notification. The `dumpsys` frame line looks like `Frames: containing=[0,0][1080,2340]
parent=[...]` for the overlay window — take the pill's own `frame=[x,y][x2,y2]` and convert it
to `x,y,w,h` for the next step.

- [ ] **Step 4: Pull it and run the assertions**

```bash
ADB=~/Android/Sdk/platform-tools/adb
mkdir -p "$TMPDIR/verify"
NAME=$($ADB shell ls "/sdcard/DCIM/Screen recordings/" | tail -1 | tr -d '\r')
$ADB pull "/sdcard/DCIM/Screen recordings/$NAME" "$TMPDIR/verify/$NAME"
SCREEN_W=$($ADB shell wm size | sed -E 's/.*: ([0-9]+)x[0-9]+/\1/' | tr -d '\r')
python3 tools/verify_recording.py --video "$TMPDIR/verify/$NAME" --expect-audio \
    --pill-region <x,y,w,h from step 3> --screen-width "$SCREEN_W"
```

Expected: `All checks passed.` — meaning the file decodes, carries one h264 track and one aac
track, has no frozen gap across the pause, and shows no near-black pixels where the pill was.
The region is measured in screen pixels and scaled into frame coordinates by the script, so a
720p or 480p take is checked correctly too.

- [ ] **Step 5: Record the two remaining sound modes**

Repeat step 3 choosing **No sound** and then **Media and mic**, verifying each:

```bash
python3 tools/verify_recording.py --video "$TMPDIR/verify/<no-sound-take>.mp4"
python3 tools/verify_recording.py --video "$TMPDIR/verify/<mic-take>.mp4" --expect-audio
```

Expected: the No sound take has **no** audio track (the script fails if one appears), and the
mic take has one. For the mic take, also confirm audibly that both sources are present —
play media on the phone and speak while recording, then listen to the pulled file.

- [ ] **Step 6: If the pill leaked, switch to the notification-only fallback**

Only if step 4 reported near-black pixels in the pill region. `FLAG_SECURE` is then not
excluding the layer on this One UI build, and the hard requirement wins over the convenience
of a floating control. In `OverlayController.kt` change the default:

```kotlin
    var renderPillDuringCapture: Boolean = false
```

Then rebuild, reinstall, and re-run steps 3 and 4. Expected afterwards: no pill on screen
during capture, the notification's Pause/Stop actions still drive the session, and the pill
region check passes trivially. Record the outcome — which branch was taken and the measured
percentage — in the commit message, because it is the answer to the project's one hard
requirement.

- [ ] **Step 7: Confirm the tile**

```bash
ADB=~/Android/Sdk/platform-tools/adb
$ADB shell cmd statusbar add-tile dev.screenrec/.tile.RecorderTileService
$ADB shell cmd statusbar click-tile dev.screenrec/.tile.RecorderTileService
```

Expected: the start sheet appears. (`add-tile` places it in Quick Settings; on some One UI
builds the user must add it manually from the QS edit screen — note which was needed.)

- [ ] **Step 8: Commit**

```bash
git add tools app/src && git commit -m "test: on-device verification and FLAG_SECURE proof"
```

---

## Self-Review

Checked after writing, against the spec:

**Spec coverage.** Each numbered success criterion maps to a task: QS tile → 15; three working
sound modes → 10 (capture), 5 (mixing), 16 step 5 (proof); pill absent from video → 12
(mechanism), 16 steps 4 and 6 (proof and fallback); Gallery publication → 8, verified in 16
step 3; One UI styling → 14 (`Theme.DeviceDefault`, bottom sheet, 52dp rows). The spec's
component detail maps one-to-one onto Tasks 3–11, its error-handling table onto the controller
in Task 11 (`writeGuarded`, encoder step-down, `onProjectionLost`, `cleanUpOrphans`), and all
seven JVM-testable units onto Tasks 1–7. Out-of-scope items — pen annotation, selfie overlay,
recordings list — appear nowhere, as intended.

**Deliberate omissions to be aware of.** The spec's "retry once at the next lower preset" is
implemented as retry down the whole preset ladder, which is a superset and cheaper to express.
Rotation mid-recording is frozen at start by `DisplayMetricsSnapshot`, matching the stated
limitation rather than fixing it. Storage-full handling finalises and publishes what was
written rather than distinguishing the cause.

**Type consistency.** `MuxerTarget<F>`/`MuxerGate<F>` are generic in Tasks 7 and instantiated
as `MuxerGate<MediaFormat>` in Task 11; `MuxerSink` implements `MuxerTarget<MediaFormat>` in
Task 8. `VideoEncoder.Listener` and `AudioEncoder.Listener` share the same four-method shape,
both consumed in Task 11. `SoundMode`/`QualityPreset` are defined once in Task 6 and used by
Tasks 10, 11, 13 and 14. `OverlayController`'s four methods plus `renderPillDuringCapture` are
defined in Task 12 and called from Task 13. `RecorderService`'s four intent factories are
defined in Task 13 and used from Tasks 13 and 14.

**Ordering.** Task 12 (overlay) precedes Task 13 (service) precisely so that no task ends on
code that does not compile.

