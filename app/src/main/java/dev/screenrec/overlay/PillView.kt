package dev.screenrec.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.screenrec.service.ElapsedText

/**
 * The floating control while capturing: a blinking record dot, the elapsed time, pause and stop.
 *
 * Drawn rather than assembled from widgets. DeviceDefault buttons would make it look like a
 * fragment of an app sitting on top of the screen, and this ends up *inside* the recording --
 * there is no public way to keep an overlay out of a MediaProjection mirror, and FLAG_SECURE
 * blacks the region out rather than omitting it -- so it may as well look deliberate. What it is
 * built to look like is a One UI floating control: a shadowed capsule, One UI's easing on every
 * transition, and touch targets the size the system's own are.
 *
 * Nothing animates *continuously*, on purpose: a mirrored display emits a frame whenever it
 * changes, so an idle 60fps pulse here would make an otherwise static screen encode 60fps of
 * nothing. Between transitions the whole pill costs the recording one repaint per second. The
 * animations that do exist are short and only run when something actually happened -- a touch, a
 * pause, the collapse timer expiring -- which is a few hundred milliseconds of frames per event
 * rather than a permanent tax on the encoder.
 */
class PillView(
    context: Context,
    private val onPauseToggle: () -> Unit,
    private val onStop: () -> Unit,
    private val onMoved: (dxPx: Int, dyPx: Int) -> Unit,
    private val onSettled: () -> Unit,
    private val onBoundsChanged: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val shadowBlur = dp(10f)
    private val shadowDy = dp(2f)

    /**
     * Room reserved inside the window for the drop shadow, which is drawn by the pill rather than
     * by the compositor -- an overlay window has no elevation to speak of. Everything visible is
     * inset by this much, so [OverlayController] has to subtract it to keep the *capsule*, not the
     * window, the intended distance from the edge of the screen.
     */
    val shadowPaddingPx: Int = Math.ceil((shadowBlur + shadowDy).toDouble()).toInt()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SURFACE
        setShadowLayer(shadowBlur, 0f, shadowDy, SHADOW)
    }
    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = HAIRLINE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PRESSED }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DIVIDER }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FOREGROUND
        textSize = sp(14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        // Tabular figures: without them the width shifts as digits change and the pill twitches.
        fontFeatureSettings = "tnum"
    }

    private val rect = RectF()
    private val resumePath = Path()

    private var startedAtElapsedMs = SystemClock.elapsedRealtime()
    private var pausedTotalMs = 0L
    private var pausedAtMs = -1L

    private var expanded = true
    private var dotLit = true
    private var wideTimer = false

    /** 0 collapsed, 1 expanded, in between while the morph runs. */
    private var expandFraction = 1f
    private var morph: ValueAnimator? = null

    /** Scale applied to the pause/resume glyph when it changes, so the switch is not a jump cut. */
    private var glyphScale = 1f
    private var glyphPop: ValueAnimator? = null

    private var pressed = PRESS_NONE

    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f

    private val tick = object : Runnable {
        override fun run() {
            // The dot blinks on the same beat as the counter, so the whole pill costs the
            // recording one repaint per second and no more.
            if (pausedAtMs < 0) dotLit = !dotLit
            // An hour in, the counter gains a field and needs more room than it was measured
            // for; without this the text runs under the divider.
            val wide = elapsedMs() >= HOUR_MS
            if (wide != wideTimer) {
                wideTimer = wide
                requestLayout()
                onBoundsChanged()
            }
            invalidate()
            postDelayed(this, TICK_MS)
        }
    }

    private val collapse = Runnable { if (expanded && pausedAtMs < 0) setExpanded(false) }

    init {
        isClickable = true
    }

    fun bindStart(startedAtElapsedMs: Long) {
        this.startedAtElapsedMs = startedAtElapsedMs
        pausedTotalMs = 0L
        pausedAtMs = -1L
        invalidate()
    }

    fun setPaused(paused: Boolean) {
        if (paused && pausedAtMs < 0) {
            pausedAtMs = SystemClock.elapsedRealtime()
            dotLit = true
            setExpanded(true)
            popGlyph()
        } else if (!paused && pausedAtMs >= 0) {
            pausedTotalMs += SystemClock.elapsedRealtime() - pausedAtMs
            pausedAtMs = -1L
            scheduleCollapse()
            popGlyph()
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(tick, TICK_MS)
        scheduleCollapse()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        removeCallbacks(collapse)
        morph?.cancel()
        glyphPop?.cancel()
        super.onDetachedFromWindow()
    }

    /** Rotation swaps the display's width and height, so the position has to be remapped. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        onBoundsChanged()
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        removeCallbacks(collapse)
        if (value) scheduleCollapse()
        animateMorph(if (value) 1f else 0f)
    }

    /**
     * Drives the size change frame by frame rather than snapping between two measured states.
     * requestLayout on every frame is affordable here -- one capsule, no children -- and each pass
     * re-derives the window position through [onBoundsChanged], which is what keeps a pill parked
     * against the right edge growing inwards instead of off the screen.
     */
    private fun animateMorph(target: Float) {
        morph?.cancel()
        if (expandFraction == target) return
        morph = ValueAnimator.ofFloat(expandFraction, target).apply {
            duration = OneUiMotion.MORPH_MS
            interpolator = OneUiMotion.EASING
            addUpdateListener {
                expandFraction = it.animatedValue as Float
                requestLayout()
                invalidate()
                onBoundsChanged()
            }
            start()
        }
    }

    /** A short scale-up when pause becomes resume, so the glyph swap is not a jump cut. */
    private fun popGlyph() {
        glyphPop?.cancel()
        glyphPop = ValueAnimator.ofFloat(GLYPH_POP_FROM, 1f).apply {
            duration = OneUiMotion.GLYPH_POP_MS
            interpolator = OneUiMotion.EASING
            addUpdateListener {
                glyphScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun scheduleCollapse() {
        removeCallbacks(collapse)
        if (expanded && pausedAtMs < 0) postDelayed(collapse, COLLAPSE_AFTER_MS)
    }

    // --- geometry -------------------------------------------------------------------------

    /** Everything visible is inset by this much; see [shadowPaddingPx]. */
    private val pad = shadowPaddingPx.toFloat()

    // Sized against One UI's own floating controls rather than as small as the content allows:
    // this is a Stop button people reach for while a game is running.
    private val expandedHeight = dp(48f)
    private val collapsedSize = dp(40f)
    private val padStart = dp(14f)
    private val padEnd = dp(5f)
    private val dotRadius = dp(4.5f)
    private val gapDotText = dp(8f)
    private val gapTextDivider = dp(10f)
    private val dividerWidth = dp(1f)
    private val dividerHeight = dp(20f)
    private val gapDividerButtons = dp(3f)
    private val buttonSize = dp(42f)

    private fun timeText(): String = ElapsedText.of(elapsedMs())

    private fun elapsedMs(): Long {
        val end = if (pausedAtMs >= 0) pausedAtMs else SystemClock.elapsedRealtime()
        return end - startedAtElapsedMs - pausedTotalMs
    }

    /**
     * Measured off a fixed digit string rather than the current time, so the pill keeps one
     * width for a whole minute instead of resizing as the seconds roll over.
     */
    private fun timeWidth(): Float =
        timePaint.measureText(if (wideTimer) "0:00:00" else "00:00")

    private fun expandedContentWidth(): Float =
        padStart + dotRadius * 2 + gapDotText + timeWidth() + gapTextDivider +
            dividerWidth + gapDividerButtons + buttonSize * 2 + padEnd

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val contentWidth = PillMorph.size(collapsedSize, expandedContentWidth(), expandFraction)
        val contentHeight = PillMorph.size(collapsedSize, expandedHeight, expandFraction)
        setMeasuredDimension(
            Math.round(contentWidth + pad * 2),
            Math.round(contentHeight + pad * 2)
        )
    }

    /** The buttons hang off the right-hand end, so the morph slides them with it. */
    private fun pauseCentreX(): Float = width - pad - padEnd - buttonSize * 1.5f

    private fun stopCentreX(): Float = width - pad - padEnd - buttonSize * 0.5f

    // --- drawing --------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val inset = pad + hairlinePaint.strokeWidth / 2f
        rect.set(inset, inset, width - inset, height - inset)
        val radius = rect.height() / 2f
        canvas.drawRoundRect(rect, radius, radius, backgroundPaint)
        canvas.drawRoundRect(rect, radius, radius, hairlinePaint)

        val midY = height / 2f
        val paused = pausedAtMs >= 0
        val alpha = PillMorph.contentAlpha(expandFraction)

        // The dot belongs to both states, so it travels between them instead of being redrawn
        // somewhere else: from the middle of the collapsed circle to its seat before the timer.
        val dotX = PillMorph.size(width / 2f, pad + padStart + dotRadius, expandFraction)
        drawDot(canvas, dotX, midY, paused)
        if (alpha <= 0f) return

        val textX = pad + padStart + dotRadius * 2 + gapDotText
        val baseline = midY - (timePaint.descent() + timePaint.ascent()) / 2f
        fade(timePaint, if (paused) FOREGROUND_DIM else FOREGROUND, alpha)
        canvas.drawText(timeText(), textX, baseline, timePaint)

        val dividerX = textX + timeWidth() + gapTextDivider
        fade(dividerPaint, DIVIDER, alpha)
        canvas.drawRect(
            dividerX,
            midY - dividerHeight / 2f,
            dividerX + dividerWidth,
            midY + dividerHeight / 2f,
            dividerPaint
        )

        val pauseX = pauseCentreX()
        val stopX = stopCentreX()
        // Drawn under the glyph, and instant rather than animated: a press indicator that fades
        // in has already missed the moment it exists to acknowledge.
        if (pressed == PRESS_PAUSE) drawPressHighlight(canvas, pauseX, midY, alpha)
        if (pressed == PRESS_STOP) drawPressHighlight(canvas, stopX, midY, alpha)

        if (paused) {
            drawResume(canvas, pauseX, midY, alpha)
        } else {
            drawPause(canvas, pauseX, midY, alpha)
        }
        drawStop(canvas, stopX, midY, alpha)
    }

    /** Scales a paint's own opacity by the morph's, without compounding it across frames. */
    private fun fade(paint: Paint, color: Int, factor: Float) {
        paint.color = color
        paint.alpha = Math.round(paint.alpha * factor)
    }

    private fun drawPressHighlight(canvas: Canvas, cx: Float, cy: Float, alpha: Float) {
        fade(pressPaint, PRESSED, alpha)
        canvas.drawCircle(cx, cy, buttonSize * 0.42f, pressPaint)
    }

    private fun drawDot(canvas: Canvas, cx: Float, cy: Float, paused: Boolean) {
        dotPaint.color = when {
            paused -> FOREGROUND_DIM
            dotLit -> RECORD_RED
            else -> RECORD_RED_DIM
        }
        canvas.drawCircle(cx, cy, dotRadius, dotPaint)
    }

    private fun drawPause(canvas: Canvas, cx: Float, cy: Float, alpha: Float) {
        fade(glyphPaint, FOREGROUND, alpha)
        val barWidth = dp(3.5f) * glyphScale
        val barHeight = dp(14f) * glyphScale
        val gap = dp(3.5f) * glyphScale
        val top = cy - barHeight / 2f
        val bottom = cy + barHeight / 2f
        val left = cx - gap / 2f - barWidth
        rect.set(left, top, left + barWidth, bottom)
        canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, glyphPaint)
        rect.set(cx + gap / 2f, top, cx + gap / 2f + barWidth, bottom)
        canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, glyphPaint)
    }

    private fun drawResume(canvas: Canvas, cx: Float, cy: Float, alpha: Float) {
        fade(glyphPaint, FOREGROUND, alpha)
        val size = dp(14f) * glyphScale
        resumePath.reset()
        resumePath.moveTo(cx - size * 0.35f, cy - size / 2f)
        resumePath.lineTo(cx + size * 0.45f, cy)
        resumePath.lineTo(cx - size * 0.35f, cy + size / 2f)
        resumePath.close()
        canvas.drawPath(resumePath, glyphPaint)
    }

    private fun drawStop(canvas: Canvas, cx: Float, cy: Float, alpha: Float) {
        fade(glyphPaint, RECORD_RED, alpha)
        val size = dp(13f)
        rect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        canvas.drawRoundRect(rect, dp(3f), dp(3f), glyphPaint)
    }

    // --- touch ----------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = downRawX
                lastRawY = downRawY
                dragging = false
                removeCallbacks(collapse)
                pressed = buttonAt(event.x)
                if (pressed != PRESS_NONE) invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging &&
                    PillPlacement.isDrag(event.rawX - downRawX, event.rawY - downRawY, touchSlop)
                ) {
                    dragging = true
                    // What started on a button turned out to be a drag of the whole pill.
                    clearPressed()
                }
                if (dragging) {
                    onMoved(
                        Math.round(event.rawX - lastRawX),
                        Math.round(event.rawY - lastRawY)
                    )
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                clearPressed()
                if (dragging) {
                    onSettled()
                } else {
                    handleTap(event.x)
                }
                scheduleCollapse()
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                clearPressed()
                if (dragging) onSettled()
                dragging = false
                scheduleCollapse()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun clearPressed() {
        if (pressed == PRESS_NONE) return
        pressed = PRESS_NONE
        invalidate()
    }

    /** Which button a touch at [x] lands on. Only the expanded pill has any. */
    private fun buttonAt(x: Float): Int {
        if (!expanded) return PRESS_NONE
        val half = buttonSize / 2f
        return when {
            Math.abs(x - stopCentreX()) <= half -> PRESS_STOP
            Math.abs(x - pauseCentreX()) <= half -> PRESS_PAUSE
            else -> PRESS_NONE
        }
    }

    private fun handleTap(x: Float) {
        if (!expanded) {
            setExpanded(true)
            return
        }
        // Posted, not called: stop tears this window down with removeViewImmediate, and doing
        // that from inside the touch dispatch of the window being removed is asking for a crash
        // in ViewRootImpl once the callback returns.
        when (buttonAt(x)) {
            PRESS_STOP -> post { onStop() }
            PRESS_PAUSE -> post { onPauseToggle() }
            // A tap on the timer half is the way back to the small dot without waiting.
            else -> setExpanded(false)
        }
    }

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        const val TICK_MS = 1_000L
        const val COLLAPSE_AFTER_MS = 4_000L
        const val HOUR_MS = 3_600_000L
        const val GLYPH_POP_FROM = 0.6f

        const val PRESS_NONE = 0
        const val PRESS_PAUSE = 1
        const val PRESS_STOP = 2

        /**
         * Charcoal rather than near-black, and paired with a real shadow: One UI's floating
         * controls read as a surface lifted off the screen, where a flat black slab reads as a hole
         * cut in it.
         */
        const val SURFACE = 0xF5202327.toInt()
        const val SHADOW = 0x59000000
        const val HAIRLINE = 0x24FFFFFF
        const val DIVIDER = 0x1FFFFFFF
        const val PRESSED = 0x26FFFFFF
        const val FOREGROUND = 0xFFF2F3F5.toInt()
        const val FOREGROUND_DIM = 0x8AF2F3F5.toInt()

        /** The red One UI uses for its own recording indicator, and its blink-off shade. */
        const val RECORD_RED = 0xFFE0342B.toInt()
        const val RECORD_RED_DIM = 0x59E0342B
    }
}
