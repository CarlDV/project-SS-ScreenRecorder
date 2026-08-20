package dev.screenrec.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/**
 * The two windows a session puts on screen: the countdown, and the floating pill.
 *
 * They are treated differently on purpose. The countdown must never reach the recording, so it
 * carries FLAG_SECURE *and* is torn down before the first frame is encoded -- FLAG_SECURE alone
 * is not enough, because the compositor blacks a secure layer out in a non-secure mirror rather
 * than omitting it, which is a black box in the video instead of the countdown.
 *
 * The pill accepts the opposite trade. There is no public way to keep an overlay out of a
 * MediaProjection mirror, so it is in the recording whenever it is on screen; FLAG_SECURE would
 * only turn it into a black smear. It is on by default anyway, because on a device without the
 * Android 16 status bar chip, and in landscape or an immersive game, the notification's Stop is
 * not reachably close to hand. [showPillDuringCapture] is how a caller trades it back for clean
 * video.
 */
class OverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    /** Gap between the *capsule* and the edge of the usable display. */
    private val visibleMarginPx = (8 * context.resources.displayMetrics.density).toInt()

    private var pill: PillView? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var settle: ValueAnimator? = null
    private var countdown: CountdownView? = null

    /** Remembered as a share of the available travel so rotation maps it instead of clipping it. */
    private var xFraction = 1f
    private var yFraction = 0.08f

    var showPillDuringCapture: Boolean = true

    /**
     * The pill's window is bigger than the capsule inside it by the room reserved for the drop
     * shadow, so the window has to sit that much closer to the edge for the capsule to land
     * [visibleMarginPx] from it. Negative is normal and the arithmetic downstream expects it.
     */
    private fun marginPx(): Int = visibleMarginPx - (pill?.shadowPaddingPx ?: 0)

    fun showCountdown(from: Int, onComplete: () -> Unit) {
        if (!canDrawOverlays()) {
            onComplete()
            return
        }
        val view = CountdownView(context)
        countdown = view
        windowManager.addView(view, countdownParams())
        tick(view, from, onComplete)
    }

    fun showPill(startedAtElapsedMs: Long, onPauseToggle: () -> Unit, onStop: () -> Unit) {
        if (!showPillDuringCapture || !canDrawOverlays() || pill != null) return
        val view = PillView(
            context = context,
            onPauseToggle = onPauseToggle,
            onStop = onStop,
            onMoved = { dx, dy -> movePill(dx, dy) },
            onSettled = { settlePill() },
            onBoundsChanged = { applyPillPosition() }
        )
        view.bindStart(startedAtElapsedMs)
        val params = pillParams()
        pill = view
        pillParams = params
        // Measured up front so the very first composited frame is already in the right place.
        // Waiting for the layout pass would put one frame of pill in the top-left corner of the
        // recording, and this window is captured.
        view.measure(UNSPECIFIED, UNSPECIFIED)
        applyPillPosition()
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyPillPosition() }
        // Set before the window exists, so the first composited frame is already the small,
        // transparent one: the pill grows into place as capture begins rather than appearing
        // fully formed, which in the first frames of a video reads as a glitch.
        view.alpha = 0f
        view.scaleX = ENTER_SCALE_FROM
        view.scaleY = ENTER_SCALE_FROM
        windowManager.addView(view, params)
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(OneUiMotion.ENTER_MS)
            .setInterpolator(OneUiMotion.EASING)
            .start()
    }

    fun setPaused(paused: Boolean) {
        pill?.setPaused(paused)
    }

    /**
     * No exit animation, deliberately: hiding the pill means the session is over, and the last
     * thing in the video should not be a control shrinking away. It also has to be gone
     * synchronously -- see [remove].
     */
    fun hideAll() {
        settle?.cancel()
        settle = null
        remove(countdown)
        countdown = null
        remove(pill)
        pill = null
        pillParams = null
    }

    // --- placement ------------------------------------------------------------------------

    private fun movePill(dxPx: Int, dyPx: Int) {
        val view = pill ?: return
        val params = pillParams ?: return
        // A new drag overrides a settle still gliding from the last one.
        settle?.cancel()
        val usable = usableBounds()
        val margin = marginPx()
        val freeX = PillPlacement.free(usable.width, widthOf(view), margin)
        val freeY = PillPlacement.free(usable.height, heightOf(view), margin)
        val x = PillPlacement.clamp(params.x - usable.left - margin + dxPx, freeX)
        val y = PillPlacement.clamp(params.y - usable.top - margin + dyPx, freeY)
        xFraction = PillPlacement.fractionOf(x, freeX)
        yFraction = PillPlacement.fractionOf(y, freeY)
        place(view, params, usable, x, y)
    }

    private fun settlePill() {
        val view = pill ?: return
        val params = pillParams ?: return
        val usable = usableBounds()
        val margin = marginPx()
        val freeX = PillPlacement.free(usable.width, widthOf(view), margin)
        val freeY = PillPlacement.free(usable.height, heightOf(view), margin)
        val x = PillPlacement.snapToNearerSide(params.x - usable.left - margin, freeX)
        val y = PillPlacement.clamp(params.y - usable.top - margin, freeY)
        xFraction = PillPlacement.fractionOf(x, freeX)
        yFraction = PillPlacement.fractionOf(y, freeY)
        glide(view, params, usable.left + margin + x, usable.top + margin + y)
    }

    /**
     * Slides the window to its snapped resting place instead of teleporting it. The remembered
     * fractions are updated before this starts, so a rotation part-way through lands on the
     * destination rather than wherever the animation had got to.
     */
    private fun glide(view: View, params: WindowManager.LayoutParams, toX: Int, toY: Int) {
        settle?.cancel()
        val fromX = params.x
        val fromY = params.y
        if (fromX == toX && fromY == toY) return
        settle = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OneUiMotion.SETTLE_MS
            interpolator = OneUiMotion.EASING
            addUpdateListener {
                val t = it.animatedValue as Float
                params.x = Math.round(fromX + (toX - fromX) * t)
                params.y = Math.round(fromY + (toY - fromY) * t)
                update(view, params)
            }
            start()
        }
    }

    /** Re-derives the window position from the remembered fractions and the current bounds. */
    private fun applyPillPosition() {
        val view = pill ?: return
        val params = pillParams ?: return
        val usable = usableBounds()
        val margin = marginPx()
        val x = PillPlacement.positionFor(
            xFraction, PillPlacement.free(usable.width, widthOf(view), margin)
        )
        val y = PillPlacement.positionFor(
            yFraction, PillPlacement.free(usable.height, heightOf(view), margin)
        )
        place(view, params, usable, x, y)
    }

    /** Laid-out size once there is one, and the pre-add measurement before that. */
    private fun widthOf(view: View): Int = if (view.width > 0) view.width else view.measuredWidth

    private fun heightOf(view: View): Int = if (view.height > 0) view.height else view.measuredHeight

    private fun place(
        view: View,
        params: WindowManager.LayoutParams,
        usable: Usable,
        x: Int,
        y: Int
    ) {
        val absoluteX = usable.left + marginPx() + x
        val absoluteY = usable.top + marginPx() + y
        // Guarded because this runs from the layout listener, and an unconditional
        // updateViewLayout would request another layout and loop.
        if (params.x == absoluteX && params.y == absoluteY) return
        params.x = absoluteX
        params.y = absoluteY
        update(view, params)
    }

    private fun update(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, params)
        } catch (ignored: IllegalArgumentException) {
            // Detached between the layout pass, or an animation frame, and here.
        }
    }

    private class Usable(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * The display minus the system bars and any cutout. FLAG_LAYOUT_NO_LIMITS means the window
     * is positioned against the raw display, so the insets have to be subtracted here or the
     * pill parks under the clock or the punch-hole.
     */
    private fun usableBounds(): Usable {
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        return Usable(
            left = insets.left,
            top = insets.top,
            width = bounds.width() - insets.left - insets.right,
            height = bounds.height() - insets.top - insets.bottom
        )
    }

    // --- countdown ------------------------------------------------------------------------

    private fun tick(view: CountdownView, remaining: Int, onComplete: () -> Unit) {
        if (remaining <= 0) {
            remove(view)
            if (countdown === view) countdown = null
            // The teardown has to reach the compositor before the first frame is encoded.
            // removeView() alone is not enough: it only queues the removal, and the main
            // thread then spends a few hundred milliseconds configuring MediaCodec, the
            // MediaStore entry and AudioRecord -- during which the countdown's secure layer
            // is still composited and shows up as a black box in the middle of the video.
            // removeViewImmediate() detaches synchronously; the settle delay gives the
            // compositor a frame to drop the layer before capture starts.
            handler.postDelayed({ onComplete() }, COMPOSITOR_SETTLE_MS)
            return
        }
        view.show(remaining)
        handler.postDelayed({ tick(view, remaining - 1, onComplete) }, TICK_MS)
    }

    private fun remove(view: View?) {
        if (view == null) return
        try {
            windowManager.removeViewImmediate(view)
        } catch (ignored: IllegalArgumentException) {
            // Already detached.
        }
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    private fun countdownParams(): WindowManager.LayoutParams =
        baseParams(
            WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        ).apply { gravity = Gravity.CENTER }

    private fun pillParams(): WindowManager.LayoutParams =
        baseParams(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Out of the way of the panel it is drawn over, and it never wants a keyboard.
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

    private fun baseParams(flags: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )

    private companion object {
        const val TICK_MS = 1_000L
        const val COMPOSITOR_SETTLE_MS = 150L
        const val ENTER_SCALE_FROM = 0.85f
        val UNSPECIFIED = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    }
}
