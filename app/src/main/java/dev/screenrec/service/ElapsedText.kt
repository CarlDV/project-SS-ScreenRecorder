package dev.screenrec.service

/** Elapsed milliseconds as One UI writes it: 0:07, 1:23, 1:02:03. */
object ElapsedText {

    fun of(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}
