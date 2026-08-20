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
 * of the recording.
 *
 * [renderPillDuringCapture] defaults to **false** because FLAG_SECURE turned out not to omit
 * the layer on One UI 8 -- it blacks the region out in the mirror instead, which puts a black
 * box in the finished video. The spec's requirement is that nothing of ours reaches the
 * recording, so the pill is not rendered during capture at all and the notification's
 * Pause/Stop actions are the control surface. Set this true to experiment on a build where
 * secure layers really are omitted.
 */
class OverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var pill: PillView? = null
    private var countdown: CountdownView? = null

    var renderPillDuringCapture: Boolean = false

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

    private fun params(gravity: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_SECURE stays even though One UI blacks the layer out rather than omitting
            // it: no overlay is ever meant to be on screen while capturing, and if the
            // ordering above ever slips, a black box is a better failure than leaking our UI
            // into someone's recording. FLAG_NOT_FOCUSABLE keeps the keyboard and back
            // button behaviour of the app underneath intact.
            WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
        }

    private companion object {
        const val TICK_MS = 1_000L
        const val COMPOSITOR_SETTLE_MS = 150L
    }
}
