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

    /**
     * Queues as much of [pcm] as one codec input buffer will hold and returns the number of
     * bytes actually consumed, which may be less than [length] -- an AAC input buffer is
     * typically 4 KiB while an AudioRecord read is 8 KiB. Callers must loop on the remainder
     * and advance their timestamp by the returned count. Returns 0 when no buffer is
     * available, which the caller should treat as "drop the rest of this read".
     */
    fun submit(pcm: ByteArray, offset: Int, length: Int, ptsUs: Long): Int {
        try {
            val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index < 0) return 0 // encoder is behind; dropping beats blocking capture
            val buffer = codec.getInputBuffer(index) ?: return 0
            buffer.clear()
            val chunk = alignedChunk(length, buffer.remaining(), BYTES_PER_FRAME)
            if (chunk > 0) buffer.put(pcm, offset, chunk)
            codec.queueInputBuffer(index, 0, chunk, ptsUs, 0)
            return chunk
        } catch (e: IllegalStateException) {
            listener.onError(e)
            return 0
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

    companion object {
        private const val BIT_RATE = 128_000
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val END_OF_STREAM_TIMEOUT_US = 100_000L
        private const val DRAIN_JOIN_TIMEOUT_MS = 2_000L

        /** Stereo 16-bit: one frame is 4 bytes. */
        private val BYTES_PER_FRAME = AudioCaptureSource.CHANNEL_COUNT * 2

        /**
         * Largest whole-frame slice of [available] bytes that fits in [capacity]. Splitting a
         * frame across two input buffers would desynchronise the channels, so the result is
         * always a multiple of [bytesPerFrame].
         */
        fun alignedChunk(available: Int, capacity: Int, bytesPerFrame: Int): Int {
            val fits = minOf(available, capacity).coerceAtLeast(0)
            if (bytesPerFrame <= 1) return fits
            return fits - (fits % bytesPerFrame)
        }
    }
}
