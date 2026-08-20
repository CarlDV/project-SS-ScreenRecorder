package dev.screenrec.overlay

import android.view.animation.PathInterpolator

/**
 * The motion the floating pill is built on, in one place because two classes have to agree on it:
 * [PillView] animates its own size and glyphs, [OverlayController] animates the window's position.
 *
 * One UI's standard easing is a firm start and a long settle. Every OEM has its own curve, and
 * getting it wrong is most of what makes an animation feel borrowed from a different phone.
 *
 * The durations are all short, and every one of them is triggered by something that happened
 * rather than by time passing. That is the constraint the pill lives under: it is inside the
 * recording, so a mirrored display encodes a frame for each one of these -- a few hundred
 * milliseconds per event is affordable, an idle animation would not be.
 */
object OneUiMotion {

    val EASING = PathInterpolator(0.22f, 0.25f, 0f, 1f)

    /** Collapse and expand. */
    const val MORPH_MS = 260L

    /** The glide to the nearer edge when a drag is released. */
    const val SETTLE_MS = 280L

    /** The pill growing into place as capture starts. */
    const val ENTER_MS = 250L

    /** The pause glyph becoming the resume glyph, and back. */
    const val GLYPH_POP_MS = 220L
}
