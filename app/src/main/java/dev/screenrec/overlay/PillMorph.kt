package dev.screenrec.overlay

/**
 * The collapse and expand morph, as arithmetic with no Android types.
 *
 * The pill changes size rather than swapping between two layouts, so both its dimensions and the
 * visibility of everything except the record dot come from a single 0..1 progress value. Keeping
 * that here means the awkward part -- content that must be gone before the pill is too small to
 * hold it -- is testable on the JVM.
 */
object PillMorph {

    /**
     * Progress at which the timer, divider and buttons start to appear.
     *
     * Deliberately late: fading them in from the first frame draws half-scale glyphs outside the
     * capsule they belong in, which reads as a rendering fault rather than a transition.
     */
    const val CONTENT_FADE_START = 0.45f

    /** A dimension part-way through the morph, with progress outside 0..1 pinned to the ends. */
    fun size(collapsedPx: Float, expandedPx: Float, progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return collapsedPx + (expandedPx - collapsedPx) * t
    }

    /** Opacity of everything the collapsed pill has no room for. */
    fun contentAlpha(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        if (t <= CONTENT_FADE_START) return 0f
        return (t - CONTENT_FADE_START) / (1f - CONTENT_FADE_START)
    }
}
