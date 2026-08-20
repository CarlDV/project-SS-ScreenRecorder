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
            if (index < 0) return // encoder is behind; dropping beats blocking capture
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
