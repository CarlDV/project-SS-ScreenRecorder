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
