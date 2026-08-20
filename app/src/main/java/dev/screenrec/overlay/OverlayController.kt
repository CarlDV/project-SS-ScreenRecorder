package dev.screenrec.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Every window this class creates carries FLAG_SECURE, which is what keeps the controls out
 * of the recording. [renderPillDuringCapture] is the escape hatch: set it false and the pill
 * is never added at all, leaving the notification as the only control surface. That is the
 * fallback if the platform turns out not to honour FLAG_SECURE for mirrored displays.
 */
class OverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var pill: PillView? = null
    private var countdown: CountdownView? = null

    var renderPillDuringCapture: Boolean = true

    fun showCountdown(from: Int, onComplete: () -> Unit) {
        if (!canDrawOverlays()) {
            onComplete()
            return
        }
        val view = CountdownView(context)
        countdown = view
        windowManager.addView(view, params(Gravity.CENTER))
        tick(view, from, onComplete)
    }

    fun showPill(onPauseToggle: () -> Unit, onStop: () -> Unit) {
        if (!renderPillDuringCapture || !canDrawOverlays() || pill != null) return
        val view = PillView(context, onPauseToggle, onStop)
        pill = view
        val p = params(Gravity.TOP or Gravity.END).apply {
            y = (24 * context.resources.displayMetrics.density).toInt()
            x = y
        }
        windowManager.addView(view, p)
    }

    fun setPaused(paused: Boolean) {
        pill?.setPaused(paused)
    }

    fun hideAll() {
        remove(countdown)
        countdown = null
        remove(pill)
        pill = null
    }

    private fun tick(view: CountdownView, remaining: Int, onComplete: () -> Unit) {
        if (remaining <= 0) {
            remove(view)
            if (countdown === view) countdown = null
            onComplete()
            return
        }
        view.show(remaining)
        handler.postDelayed({ tick(view, remaining - 1, onComplete) }, TICK_MS)
    }

    private fun remove(view: View?) {
        if (view == null) return
        try {
            windowManager.removeView(view)
        } catch (ignored: IllegalArgumentException) {
            // Already detached.
        }
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    private fun params(gravity: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_SECURE is the whole mechanism. FLAG_NOT_FOCUSABLE keeps the keyboard and
            // back button behaviour of the app underneath intact.
            WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
        }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
