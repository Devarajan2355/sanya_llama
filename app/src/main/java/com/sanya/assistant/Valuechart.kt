package com.sanya.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** One bar: what it is called and how big it is. */
data class ValueBar(val label: String, val value: Long)

/**
 * A simple bar chart where the height is the value (money spent, minutes studied).
 * The newest bar (the last one) is green, the others blue, and an empty one is a thin grey stub.
 * [format] turns a value into the short label drawn above its bar.
 */
class ValueChartView(context: Context) : View(context) {

    var bars: List<ValueBar> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var format: (Long) -> String = { it.toString() }

    private val d = resources.displayMetrics.density

    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
        strokeWidth = 1f * d
    }
    private val old = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3F7FD6.toInt() }
    private val latest = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E9E5B.toInt() }
    private val none = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF444444.toInt()
        textSize = 9f * d
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val n = bars.size
        if (n == 0) return

        val w = width.toFloat()
        val h = height.toFloat()

        val labelH = 18f * d
        val topPad = 16f * d
        val chartH = h - labelH - topPad
        val base = topPad + chartH

        val slot = w / n
        val barW = slot * 0.62f

        val max = bars.maxOf { it.value }

        canvas.drawLine(0f, topPad, w, topPad, axis)
        canvas.drawLine(0f, topPad + chartH / 2f, w, topPad + chartH / 2f, axis)
        canvas.drawLine(0f, base, w, base, axis)

        for (i in 0 until n) {

            val b = bars[i]
            val cx = slot * i + slot / 2f

            val frac = if (max <= 0L) 0f else b.value.toFloat() / max.toFloat()
            val bh = if (b.value <= 0L) 3f * d else maxOf(chartH * frac, 3f * d)

            val paint = when {
                b.value <= 0L -> none
                i == n - 1 -> latest
                else -> old
            }

            canvas.drawRect(cx - barW / 2f, base - bh, cx + barW / 2f, base, paint)

            if (b.value > 0L) {
                canvas.drawText(format(b.value), cx, base - bh - 3f * d, text)
            }

            canvas.drawText(b.label, cx, h - 4f * d, text)
        }
    }
}

/** Short labels for chart bars. */
object ChartFormat {

    /** 950 -> "950", 1200 -> "1.2k", 25000 -> "25k". */
    fun compact(v: Long): String = when {
        v >= 100_000L -> (v / 1000).toString() + "k"
        v >= 10_000L -> (v / 1000).toString() + "k"
        v >= 1_000L -> (v / 100 / 10.0).toString() + "k"
        else -> v.toString()
    }
}
