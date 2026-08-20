package dev.screenrec.record

/** User-facing labels are Samsung's exact wording; do not reword. */
enum class SoundMode(val label: String, val needsMic: Boolean) {
    NONE("No sound", false),
    MEDIA("Media", false),
    MEDIA_AND_MIC("Media and mic", true)
}

enum class QualityPreset(val shortEdge: Int, val bitrate: Int, val label: String) {
    P1080(1080, 12_000_000, "1080p"),
    P720(720, 8_000_000, "720p"),
    P480(480, 4_000_000, "480p");

    fun lower(): QualityPreset? = when (this) {
        P1080 -> P720
        P720 -> P480
        P480 -> null
    }
}

data class RecordingConfig(
    val soundMode: SoundMode,
    val preset: QualityPreset
)

data class VideoFormatSpec(
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val frameRate: Int = 30,
    val iFrameIntervalSeconds: Int = 1
)
