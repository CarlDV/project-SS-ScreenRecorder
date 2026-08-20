package dev.screenrec.mux

import android.media.MediaCodec
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

    override fun start() {
        synchronized(lock) {
            muxer.start()
            started = true
        }
    }

    override fun writeSample(
        trackIndex: Int,
        data: ByteArray,
        offset: Int,
        size: Int,
        ptsUs: Long,
        flags: Int
    ) {
        synchronized(lock) {
            val info = MediaCodec.BufferInfo().apply { set(0, size, ptsUs, flags) }
            muxer.writeSampleData(trackIndex, ByteBuffer.wrap(data, offset, size), info)
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (started) {
                muxer.stop()
                started = false
            }
        }
    }

    /** Releases the muxer and the descriptor. Safe to call after a failed start. */
    fun release() {
        synchronized(lock) {
            try {
                muxer.release()
            } finally {
                descriptor.close()
            }
        }
    }
}
