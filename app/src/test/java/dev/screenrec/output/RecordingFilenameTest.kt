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
