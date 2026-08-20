package dev.screenrec.mux

enum class TrackKind { VIDEO, AUDIO }

interface MuxerTarget<F> {
    fun addTrack(format: F): Int
    fun start()
    fun writeSample(trackIndex: Int, data: ByteArray, offset: Int, size: Int, ptsUs: Long, flags: Int)
    fun stop()
}

/**
 * Holds the muxer closed until every expected track has been added, queueing samples that
 * arrive in the meantime and flushing them in timestamp order.
 *
 * Every method is synchronised. The video and audio encoders drain on separate threads, so
 * [addTrack] and [writeSample] genuinely run concurrently: an unguarded [queue] could be
 * appended to while [startAndFlush] iterates it, and a ConcurrentModificationException thrown
 * from an encoder thread takes the process down rather than failing the recording.
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

    private val lock = Any()
    private val trackIndices = HashMap<TrackKind, Int>()
    private val queue = ArrayList<Pending>()
    private var stopped = false

    var isStarted: Boolean = false
        private set

    fun addTrack(kind: TrackKind, format: F) {
        synchronized(lock) {
            if (kind !in expected || trackIndices.containsKey(kind) || isStarted) return
            trackIndices[kind] = target.addTrack(format)
            if (trackIndices.keys == expected) startAndFlush()
        }
    }

    fun writeSample(
        kind: TrackKind,
        data: ByteArray,
        offset: Int,
        size: Int,
        ptsUs: Long,
        flags: Int
    ) {
        synchronized(lock) {
            if (stopped || kind !in expected) return
            val index = trackIndices[kind]
            if (isStarted && index != null) {
                target.writeSample(index, data, offset, size, ptsUs, flags)
                return
            }
            queue += Pending(kind, data.copyOfRange(offset, offset + size), ptsUs, flags)
        }
    }

    fun stop() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            queue.clear()
            if (isStarted) target.stop()
        }
    }

    /** Caller holds [lock]. */
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
