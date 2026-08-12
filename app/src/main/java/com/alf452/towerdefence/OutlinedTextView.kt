package com.alf452.towerdefence

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * A TextView that draws its own text twice — once stroked, once filled — since a plain
 * TextView/XML styling has no built-in way to produce a true outlined-text look. Defaults are
 * tuned for the main menu title (purple outline); override [outlineColor]/[outlineWidth] from
 * code if another screen ever wants a different look.
 */
class OutlinedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    var outlineColor: Int = Color.rgb(140, 40, 220)
    var outlineWidth: Float = 5f * context.resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val fillColor = currentTextColor

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = outlineWidth
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = outlineColor
        super.onDraw(canvas)

        paint.style = Paint.Style.FILL
        paint.color = fillColor
        super.onDraw(canvas)
    }
}
