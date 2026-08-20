package dev.screenrec.output

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Samsung's own naming: Screen_recording_20260820_171203.mp4 */
object RecordingFilename {

    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun forEpochMillis(millisSinceEpoch: Long, zone: ZoneId): String {
        val stamp = FORMAT.format(Instant.ofEpochMilli(millisSinceEpoch).atZone(zone))
        return "Screen_recording_$stamp.mp4"
    }
}
