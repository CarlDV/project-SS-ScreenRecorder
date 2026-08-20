package dev.screenrec.overlay

/**
 * Where the floating pill sits, as pixels from the top-left of the display.
 *
 * Kept as arithmetic with no Android types so the awkward parts -- staying on screen when the
 * pill grows, and landing somewhere sensible when the display swaps width for height on
 * rotation -- are testable on the JVM.
 *
 * Position is remembered as a *fraction of the free space* rather than an absolute pixel, which
 * is what makes rotation work: a pill three quarters of the way down a 2340px-tall portrait
 * display belongs three quarters of the way down a 1080px-tall landscape one, not 1755px off
 * the bottom of it.
 */
object PillPlacement {

    /** Keeps [value] inside 0..[free], where free space can legitimately be zero or negative. */
    fun clamp(value: Int, free: Int): Int = value.coerceIn(0, free.coerceAtLeast(0))

    /** Free travel along one axis: how far the pill can move before it leaves the screen. */
    fun free(displaySize: Int, viewSize: Int, marginPx: Int): Int =
        displaySize - viewSize - marginPx * 2

    fun fractionOf(value: Int, free: Int): Float {
        if (free <= 0) return 0f
        return (value.toFloat() / free).coerceIn(0f, 1f)
    }

    fun positionFor(fraction: Float, free: Int): Int {
        if (free <= 0) return 0
        return Math.round(fraction.coerceIn(0f, 1f) * free)
    }

    /**
     * Snaps to whichever vertical edge is nearer, the way every floating bubble does: a control
     * parked mid-screen is in the way of the thing being recorded, and a half-off-screen one
     * cannot be tapped.
     */
    fun snapToNearerSide(x: Int, free: Int): Int = if (x * 2 <= free) 0 else free.coerceAtLeast(0)

    /**
     * True once a drag has moved further than the tap slop, so a tap that wobbles a couple of
     * pixels still toggles the pill instead of being swallowed as a drag.
     */
    fun isDrag(dxPx: Float, dyPx: Float, slopPx: Int): Boolean =
        dxPx * dxPx + dyPx * dyPx > slopPx.toFloat() * slopPx
}
