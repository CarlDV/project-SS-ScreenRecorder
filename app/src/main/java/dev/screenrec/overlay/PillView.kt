package dev.screenrec.overlay

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.Chronometer
import android.widget.LinearLayout
import dev.screenrec.R

/**
 * Timer plus pause and stop, built in code rather than XML because it is three widgets in a
 * row and inflating a layout for it would be more indirection than it saves.
 */
class PillView(
    context: Context,
    private val onPauseToggle: () -> Unit,
    private val onStop: () -> Unit
) : LinearLayout(context) {

    private val chronometer = Chronometer(context)
    private val pauseButton =
        Button(context, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless)
    private val stopButton =
        Button(context, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless)

    private var pausedElapsedMs = 0L

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = context.getDrawable(R.drawable.bg_pill)
        setPadding(dp(12), dp(6), dp(12), dp(6))

        chronometer.base = SystemClock.elapsedRealtime()
        chronometer.setTextColor(Color.WHITE)
        chronometer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        chronometer.start()
        addView(chronometer)

        pauseButton.text = context.getString(R.string.action_pause)
        pauseButton.setTextColor(Color.WHITE)
        pauseButton.setOnClickListener { onPauseToggle() }
        addView(pauseButton)

        stopButton.text = context.getString(R.string.action_stop)
        stopButton.setTextColor(Color.WHITE)
        stopButton.setOnClickListener { onStop() }
        addView(stopButton)
    }

    fun setPaused(paused: Boolean) {
        if (paused) {
            pausedElapsedMs = SystemClock.elapsedRealtime() - chronometer.base
            chronometer.stop()
            pauseButton.text = context.getString(R.string.action_resume)
        } else {
            // Rebase so the displayed time excludes the pause, matching the recording.
            chronometer.base = SystemClock.elapsedRealtime() - pausedElapsedMs
            chronometer.start()
            pauseButton.text = context.getString(R.string.action_pause)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
