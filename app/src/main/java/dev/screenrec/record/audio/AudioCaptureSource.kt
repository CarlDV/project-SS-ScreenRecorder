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
