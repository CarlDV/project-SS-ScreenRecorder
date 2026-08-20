package dev.screenrec.record

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns one recording session: builds sink, encoders and sources in the order the platform
 * requires, routes samples through the gate, and finalises exactly once however the session
 * ends -- user stop, projection revoked, or a write failure.
 */
class RecordingController(
    private val output: MediaStoreOutput,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    interface Callbacks {
        fun onStarted()

        /** [warning] is non-null when the take is usable but not what was asked for. */
        fun onSaved(uri: Uri, displayName: String, warning: String?)
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

    /** Set when the session was usable but not what the user asked for; see [Callbacks.onSaved]. */
    private var warning: String? = null

    @Volatile private var finalised = false
    @Volatile private var paused = false
    private var started = false

    /**
     * One controller per session. The [finalised] latch is deliberately sticky -- finalising
     * twice would publish a released descriptor -- so a reused instance can never save or stop
     * again, which previously left the notification and the projection up until the process was
     * killed. Failing loudly here is better than that.
     */
    fun start(
        projection: MediaProjection,
        config: RecordingConfig,
        metrics: DisplayMetricsSnapshot,
        callbacks: Callbacks
    ): Boolean {
        check(!started) { "RecordingController is single-use" }
        started = true
        this.callbacks = callbacks
        val caps = VideoEncoderFactory.capabilitiesFor()
        if (caps == null) {
            callbacks.onError("This device has no H.264 encoder")
            return false
        }

        // Encoder init can fail on a size the capabilities claimed to support; step down.
        var preset: QualityPreset? = config.preset
        var encoder: VideoEncoder? = null
        var spec: VideoFormatSpec? = null
        while (preset != null && encoder == null) {
            val candidate = EncoderConfigFactory.create(
                metrics.widthPx, metrics.heightPx, preset, caps, metrics.frameRate
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
            Log.w(TAG, "Could not create the output file", e)
            encoder.release()
            callbacks.onError("Could not create the recording file")
            return false
        }
        pendingUri = pending.uri
        displayName = pending.displayName

        val muxerSink = MuxerSink(pending.descriptor, ORIENTATION_HINT_DEGREES)

        // Open the audio records before the gate exists. They are the failure-prone part of a
        // session, and a gate told to expect an audio track that never arrives never opens the
        // muxer at all -- which would lose the video as well as the sound.
        val audioSource = if (config.soundMode == SoundMode.NONE) {
            null
        } else {
            AudioCaptureSource(projection, config.soundMode).takeIf { it.prepare() }
        }
        if (audioSource == null && config.soundMode != SoundMode.NONE) {
            warning = "Saved without sound: this device or app would not share its audio"
        }

        val expected = if (audioSource == null) {
            setOf(TrackKind.VIDEO)
        } else {
            setOf(TrackKind.VIDEO, TrackKind.AUDIO)
        }
        sink = muxerSink
        gate = MuxerGate(muxerSink, expected)
        videoEncoder = encoder

        if (audioSource != null) {
            audioDone = CountDownLatch(1)
            audioPts = AudioPts(AudioCaptureSource.SAMPLE_RATE, AudioCaptureSource.CHANNEL_COUNT)
            audioEncoder = AudioEncoder(audioListener()).also { it.start() }
            audioCapture = audioSource
            audioSource.start { pcm, length ->
                val pts = audioPts ?: return@start
                val encoder = audioEncoder ?: return@start
                // One read is larger than one AAC input buffer, so feed it in slices and
                // advance the timestamp by what the encoder actually took.
                var offset = 0
                while (offset < length) {
                    val consumed = encoder.submit(pcm, offset, length - offset, pts.currentPtsUs())
                    if (consumed <= 0) break // encoder is behind; drop the remainder
                    pts.advance(consumed)
                    offset += consumed
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

    /**
     * Releases whatever a [start] that threw part-way managed to build, without a callback --
     * the caller is the one reporting the failure.
     */
    fun abandon() {
        callbacks = null
        finalise()
    }

    private fun videoListener() = object : VideoEncoder.Listener {
        override fun onFormat(format: MediaFormat) {
            gate?.addTrack(TrackKind.VIDEO, format)
        }

        override fun onSample(data: ByteArray, offset: Int, size: Int, ptsUs: Long, flags: Int) {
            writeGuarded {
                gate?.writeSample(TrackKind.VIDEO, data, offset, size, tracker.adjust(ptsUs), flags)
            }
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

    /**
     * A full disk surfaces here, and so does anything else the sample path can throw. The catch
     * is deliberately broad: this runs on an encoder drain thread, where an escaping exception
     * takes the whole process down and loses the take along with it.
     */
    private inline fun writeGuarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Sample rejected; finalising early", e)
            finalise()
        }
    }

    /**
     * Runs on whichever thread noticed the session was over, so every step is guarded
     * individually: a release that throws must not skip the ones after it, and must not skip
     * the callback that lets the service tear the notification and the projection down.
     */
    private fun finalise() {
        if (finalised) return
        finalised = true

        closeQuietly("audio capture") { audioCapture?.release() }
        closeQuietly("screen capture") { capture?.release() }
        closeQuietly("video encoder") { videoEncoder?.release() }
        closeQuietly("audio encoder") { audioEncoder?.release() }

        val wroteSomething = gate?.isStarted == true
        closeQuietly("muxer gate") { gate?.stop() }
        closeQuietly("muxer") { sink?.release() }

        val uri = pendingUri
        if (uri != null && wroteSomething) {
            closeQuietly("publish") { output.publish(uri) }
            callbacks?.onSaved(uri, displayName, warning)
        } else {
            if (uri != null) closeQuietly("discard") { output.discard(uri) }
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

    private inline fun closeQuietly(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release $what", e)
        }
    }

    /**
     * Surface timestamps come from CLOCK_MONOTONIC, which is what nanoTime reads, so pause
     * marks are on the same timeline as the frames they bracket.
     */
    private fun nowUs(): Long = System.nanoTime() / 1_000L

    private companion object {
        const val TAG = "RecordingController"
        const val DRAIN_TIMEOUT_SECONDS = 3L

        /**
         * Always zero. A mirrored VirtualDisplay emits frames in the display's *current*
         * orientation -- which is why rotating mid-recording rotates the content inside the
         * frame rather than resizing it -- so the pixels are already the right way up. Passing
         * the display rotation here instead made a recording started in landscape carry a 90
         * degree hint on top of already-landscape pixels, and every player turned it sideways.
         */
        const val ORIENTATION_HINT_DEGREES = 0
    }
}
