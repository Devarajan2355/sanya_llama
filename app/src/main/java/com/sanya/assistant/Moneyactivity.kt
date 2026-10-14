package com.sanya.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Money: what you spent and earned, today / this week / this month.
 *
 *   - summary (spent, earned, net) and, for the month, how much of your budget is used
 *   - a bar chart of spending per day / week / month
 *   - spending by category
 *   - every entry in the period, with X to delete it
 *   - + Spent / + Earned to add one by hand, Share report to send it as text
 *
 * Open it by voice: "show my money report".
 */
class MoneyActivity : Activity() {

    private var gran = Money.Gran.MONTH

    private lateinit var tabs: LinearLayout
    private lateinit var summary: TextView
    private lateinit var budgetLine: TextView
    private lateinit var chartTitle: TextView
    private lateinit var chart: ValueChartView
    private lateinit var box: LinearLayout

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Brain.setup(this)

        val pad = dp(12)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "Money"
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

        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tabs)

        summary = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(2))
        }
        root.addView(summary)

        budgetLine = TextView(this).apply {
            textSize = 13f
            alpha = 0.8f
        }
        root.addView(budgetLine)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        chartTitle = TextView(this).apply {
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(10), 0, 0)
        }
        inner.addView(chartTitle)

        chart = ValueChartView(this).apply {
            format = { ChartFormat.compact(it) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170))
        }
        inner.addView(chart)

        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(box)

        scroll.addView(inner)
        root.addView(scroll)

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(action("+ Spent") { add(true) })
        bottom.addView(action("+ Earned") { add(false) })
        bottom.addView(action("Share report") { share() })
        root.addView(bottom)

        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()      // pick up anything added by voice
    }

    private fun action(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    // ---------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------

    private fun tabName(g: Money.Gran) = when (g) {
        Money.Gran.DAY -> "Today"
        Money.Gran.WEEK -> "This week"
        Money.Gran.MONTH -> "This month"
    }

    private fun render() {

        tabs.removeAllViews()

        for (g in Money.Gran.values()) {
            tabs.addView(Button(this).apply {
                text = tabName(g)
                isAllCaps = false
                alpha = if (g == gran) 1f else 0.55f
                if (g == gran) setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    gran = g
                    render()
                }
            })
        }

        val cur = Money.current(this, gran)
        val spent = cur.filter { it.spent }.sumOf { it.paise }
        val earned = cur.filter { !it.spent }.sumOf { it.paise }
        val sym = Money.symbol()

        summary.text = "Spent $sym" + Num.money(spent) + "    Earned $sym" + Num.money(earned) +
            "    Net $sym" + Num.money(earned - spent)

        val b = Money.budgetPaise()

        if (b > 0L && gran == Money.Gran.MONTH) {
            budgetLine.visibility = View.VISIBLE
            budgetLine.text = "Budget $sym" + Num.money(b) + "  -  " + (spent * 100 / b) + "% used"
        } else {
            budgetLine.visibility = View.GONE
        }

        chartTitle.text = Money.chartTitle(gran)
        chart.bars = Money.bars(this, gran)

        box.removeAllViews()

        // ---- by category ----
        val cats = Money.breakdown(this, gran)

        if (cats.isNotEmpty()) {
            box.addView(section("BY CATEGORY"))
            for (c in cats) box.addView(categoryRow(c.first, c.second, spent))
        }

        // ---- entries ----
        box.addView(section("ENTRIES"))

        if (cur.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "Nothing yet. Say: spent 120 on food. Or tap + Spent."
                setPadding(0, dp(6), 0, dp(6))
            })
        }

        for (e in cur) box.addView(entryRow(e))
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 12f
        alpha = 0.6f
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun categoryRow(name: String, paise: Long, total: Long): View {

        val pct = if (total == 0L) 0 else (paise * 100 / total).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }

        col.addView(TextView(this).apply {
            text = name.replaceFirstChar { it.uppercase() } + "   " + Money.symbol() + Num.money(paise) + "   ($pct%)"
            textSize = 14f
        })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6))
        }
        bar.addView(View(this).apply {
            setBackgroundColor(0xFF3F7FD6.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, maxOf(pct, 1).toFloat())
        })
        bar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, maxOf(100 - pct, 0).toFloat())
        })
        col.addView(bar)

        return col
    }

    private fun entryRow(e: Money.Entry): View {

        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }

        val sign = if (e.spent) "-" else "+"
        val note = if (e.note.isEmpty()) "" else " (" + e.note + ")"

        line.addView(TextView(this).apply {
            text = e.date.dayOfMonth.toString() + "/" + e.date.monthValue + "   " + sign + Money.symbol() +
                Num.money(e.paise) + "   " + e.category.replaceFirstChar { it.uppercase() } + note
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        line.addView(Button(this).apply {
            text = "✕"
            isAllCaps = false
            setOnClickListener { confirmDelete(e) }
        })

        return line
    }

    // ---------------------------------------------------------------
    // Adding, deleting, sharing
    // ---------------------------------------------------------------

    private fun add(spent: Boolean) {

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val amount = EditText(this).apply {
            hint = "Amount"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val what = EditText(this).apply {
            hint = if (spent) "What for? (food, bus, rent...)" else "From? (salary, freelance...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        box.addView(amount)
        box.addView(what)

        AlertDialog.Builder(this)
            .setTitle(if (spent) "Add spending" else "Add income")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val v = amount.text.toString().replace(",", "").trim().toDoubleOrNull()
                if (v == null || v <= 0.0) {
                    toast("Please enter an amount")
                } else {
                    Money.record(this, spent, Math.round(v * 100.0), what.text.toString())
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(e: Money.Entry) {

        AlertDialog.Builder(this)
            .setTitle("Delete this entry?")
            .setMessage((if (e.spent) "Spent " else "Earned ") + Money.symbol() + Num.money(e.paise) +
                (if (e.note.isEmpty()) "" else " (" + e.note + ")"))
            .setPositiveButton("Delete") { _, _ ->
                Money.delete(this, e)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun share() {

        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, Money.report(this, gran)),
                "Share money report"
            )
        )
    }
}
