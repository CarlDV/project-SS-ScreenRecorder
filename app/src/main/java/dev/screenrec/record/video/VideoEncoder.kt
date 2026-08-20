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
