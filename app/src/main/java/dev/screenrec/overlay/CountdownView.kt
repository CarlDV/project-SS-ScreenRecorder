package dev.screenrec.overlay

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/** The 3-2-1 One UI shows before recording begins. */
class CountdownView(context: Context) : TextView(context) {
    init {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 96f)
        setShadowLayer(24f, 0f, 0f, Color.BLACK)
    }

    fun show(value: Int) {
        text = value.toString()
    }
}
