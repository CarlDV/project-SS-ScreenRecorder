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
import java.io.IOException
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
