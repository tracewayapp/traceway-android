package com.tracewayapp.fieldmart.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * A small price line drawn in `onDraw`: the sparklines of the watchlist and of the Views feed (02-APP-SPEC.md
 * §2.8). A custom drawing, so the replay shows it as an outline (hypothesis H-A6).
 */
class SparklineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private var values: LongArray = LongArray(0)

    fun setValues(v: LongArray, up: Boolean) {
        values = v
        paint.color = if (up) 0xFF1B873F.toInt() else 0xFFC62828.toInt()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val v = values
        if (v.size < 2) return
        var lo = Long.MAX_VALUE
        var hi = Long.MIN_VALUE
        for (x in v) {
            if (x < lo) lo = x
            if (x > hi) hi = x
        }
        val span = (hi - lo).coerceAtLeast(1).toFloat()
        val w = width.toFloat()
        val h = height.toFloat() - paint.strokeWidth * 2
        path.reset()
        for (i in v.indices) {
            val x = i * w / (v.size - 1)
            val y = paint.strokeWidth + h - (v[i] - lo) / span * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
