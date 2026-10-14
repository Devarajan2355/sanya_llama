package com.sanya.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate

/**
 * My routines: a chart and a checklist for the daily, weekly, monthly and yearly routine.
 *
 *  - the checklist shows this day / week / month / year; tap a row to mark it finished or not
 *  - the bar chart shows how much of each earlier stretch you finished (last 14 days, 8 weeks,
 *    12 months or 5 years)
 *  - Share report sends the same thing as plain text
 *
 * Open it by voice: "show my weekly routine".
 */
class RoutineActivity : Activity() {

    private var period = Routines.Period.DAILY

    private lateinit var tabs: LinearLayout
    private lateinit var summary: TextView
    private lateinit var chartTitle: TextView
    private lateinit var chart: BarChartView
    private lateinit var listBox: LinearLayout

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Brain.setup(this)

        val wanted = intent?.getStringExtra("period")
        period = Routines.Period.values().firstOrNull { it.word == wanted } ?: Routines.Period.DAILY

        val pad = dp(12)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        // Title and Done
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "My routines"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(Button(this).apply {
            text = "Done"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        root.addView(top)

        // Daily / Weekly / Monthly / Yearly
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tabs)

        summary = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(summary)

        chartTitle = TextView(this).apply {
            textSize = 12f
            alpha = 0.7f
        }
        root.addView(chartTitle)

        chart = BarChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(190)
            )
        }
        root.addView(chart)

        // The checklist
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox)
        root.addView(scroll)

        // Bottom buttons
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(Button(this).apply {
            text = "Share report"
            isAllCaps = false
            setOnClickListener { share() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        bottom.addView(Button(this).apply {
            text = "Edit routines"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@RoutineActivity, BrainActivity::class.java).putExtra("section", "routine"))
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(bottom)

        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()      // pick up edits and anything marked by voice
    }

    // ---------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------

    private fun periodWord(p: Routines.Period) = p.word.replaceFirstChar { it.uppercase() }

    private fun render() {

        val today = LocalDate.now()

        tabs.removeAllViews()

        for (p in Routines.Period.values()) {
            tabs.addView(Button(this).apply {
                text = periodWord(p)
                isAllCaps = false
                alpha = if (p == period) 1f else 0.55f
                if (p == period) setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    period = p
                    render()
                }
            })
        }

        val rows = Routines.rows(this, period, today)

        val unit = when (period) {
            Routines.Period.DAILY -> "today"
            Routines.Period.WEEKLY -> "this week"
            Routines.Period.MONTHLY -> "this month"
            Routines.Period.YEARLY -> "this year"
        }

        val finished = rows.count { it.done }

        summary.text =
            if (rows.isEmpty()) {
                "No " + period.word + " activities yet"
            } else {
                finished.toString() + " of " + rows.size + " finished " + unit
            }

        chartTitle.text = Routines.chartTitle(period) + "  (% finished)"
        chart.bars = Routines.series(this, period, today)

        listBox.removeAllViews()

        if (rows.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "Say: add morning walk to my " + period.word + " routine.\nOr tap Edit routines."
                setPadding(0, dp(16), 0, 0)
            })
            return
        }

        for (r in rows) listBox.addView(row(r))
    }

    private fun row(r: Routines.Row): View {

        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }

        line.addView(TextView(this).apply {
            text = r.label
            textSize = 16f
            if (r.done) {
                alpha = 0.6f
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { toggle(r) }
        })

        line.addView(Button(this).apply {
            text = if (r.done) "✓ Done" else "○ Not yet"
            isAllCaps = false
            setOnClickListener { toggle(r) }
        })

        return line
    }

    private fun toggle(r: Routines.Row) {
        Routines.setDone(this, r.inst, !r.done)
        render()
    }

    private fun share() {

        val text = Routines.report(this, period, LocalDate.now())

        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text),
                "Share my " + period.word + " routine report"
            )
        )
    }
}

/**
 * A simple bar chart: one bar per stretch, tall and green when you finished everything,
 * blue when you finished some, a thin grey stub when nothing was scheduled.
 */
class BarChartView(context: Context) : View(context) {

    var bars: List<Routines.Bar> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val d = resources.displayMetrics.density

    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
        strokeWidth = 1f * d
    }
    private val full = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E9E5B.toInt() }
    private val part = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3F7FD6.toInt() }
    private val none = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF444444.toInt()
        textSize = 10f * d
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val n = bars.size
        if (n == 0) return

        val w = width.toFloat()
        val h = height.toFloat()

        val labelH = 18f * d
        val topPad = 14f * d
        val chartH = h - labelH - topPad

        val slot = w / n
        val barW = slot * 0.62f

        val base = topPad + chartH

        // lines at 50% and 100%
        canvas.drawLine(0f, topPad, w, topPad, axis)
        canvas.drawLine(0f, topPad + chartH / 2f, w, topPad + chartH / 2f, axis)
        canvas.drawLine(0f, base, w, base, axis)

        for (i in 0 until n) {

            val b = bars[i]
            val cx = slot * i + slot / 2f

            val frac = if (b.total == 0) 0f else b.done.toFloat() / b.total.toFloat()
            val bh = if (b.total == 0) 3f * d else maxOf(chartH * frac, 3f * d)

            val paint = when {
                b.total == 0 -> none
                frac >= 1f -> full
                else -> part
            }

            canvas.drawRect(cx - barW / 2f, base - bh, cx + barW / 2f, base, paint)

            if (b.total > 0) {
                canvas.drawText((frac * 100f).toInt().toString(), cx, base - bh - 3f * d, text)
            }

            canvas.drawText(b.label, cx, h - 4f * d, text)
        }
    }
}
