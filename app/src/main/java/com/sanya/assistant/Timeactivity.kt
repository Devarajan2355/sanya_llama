package com.sanya.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * Study time: where your time went, tied to your routine.
 *
 *   - Start timer / Stop timer, and a note when one is running
 *   - Pomodoro: focus sessions with breaks, counting down live
 *   - total for today / this week / this month, with a bar chart
 *   - time by activity (for a routine activity with a target, how far along you are today)
 *   - every session, with X to delete it
 *   - + Log time to add a session by hand, Share report to send it as text
 *
 * Time logged on an activity of your routine counts toward it. Open this screen by voice:
 * "show my study time".
 */
class TimeActivity : Activity() {

    private var gran = Money.Gran.WEEK

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tabs: LinearLayout
    private lateinit var timerLine: TextView
    private lateinit var summary: TextView
    private lateinit var chartTitle: TextView
    private lateinit var chart: ValueChartView
    private lateinit var box: LinearLayout
    private lateinit var timerBtn: Button
    private lateinit var pomoBtn: Button
    private lateinit var pomoRow: LinearLayout
    private lateinit var pauseBtn: Button

    // Redraws the timer line every second so the countdown moves.
    private val ticker = object : Runnable {
        override fun run() {
            updateTimerLine()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

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
            text = "Study time"
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

        timerLine = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(timerLine)

        summary = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(2))
        }
        root.addView(summary)

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
            format = { Num.short(it.toInt()) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170))
        }
        inner.addView(chart)

        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(box)

        scroll.addView(inner)
        root.addView(scroll)

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        timerBtn = action("Start timer") { toggleTimer() }
        pomoBtn = action("Pomodoro") { togglePomodoro() }
        bottom.addView(timerBtn)
        bottom.addView(pomoBtn)
        root.addView(bottom)

        // Only shown while a pomodoro is running.
        pauseBtn = action("Pause") { togglePause() }
        pomoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        pomoRow.addView(pauseBtn)
        pomoRow.addView(action("+5 min") {
            toast(Pomodoro.extend(this, 5))
            render()
        })
        pomoRow.addView(action("Skip break") {
            toast(Pomodoro.skipBreak(this))
            render()
        })
        root.addView(pomoRow)

        val bottom2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom2.addView(action("+ Log time") { logByHand() })
        bottom2.addView(action("Share report") { share() })
        root.addView(bottom2)

        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()      // pick up sessions and timers started by voice
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
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

    // The line under the tabs, and the two timer buttons. Called every second.
    private fun updateTimerLine() {

        // A pomodoro that ran out while nothing was watching: log it and clear it.
        if (Pomodoro.isOver(this)) {
            toast(Pomodoro.stop(this))
            render()
            return
        }

        val running = Study.running(this)

        if (Pomodoro.running(this)) {
            timerLine.text = "Pomodoro: " + Pomodoro.statusLine(this)
            pomoBtn.text = "Stop pomodoro"
            pomoRow.visibility = View.VISIBLE
            pauseBtn.text = if (Pomodoro.paused(this)) "Resume" else "Pause"
        } else {
            pomoBtn.text = "Pomodoro"
            pomoRow.visibility = View.GONE
            if (running == null) {
                timerLine.text = "No timer running"
            } else {
                val mins = ((System.currentTimeMillis() - running.second) / 60_000L).toInt()
                timerLine.text = "Timer running: " + running.first + ", " + Num.short(maxOf(mins, 0))
            }
        }

        timerBtn.text = if (running == null) "Start timer" else "Stop timer"
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

        updateTimerLine()

        val sessions = Study.current(this, gran)
        val total = sessions.sumOf { it.minutes }

        summary.text = if (total == 0) {
            "Nothing logged " + Money.title(gran)
        } else {
            "Studied " + Num.short(total) + " " + Money.title(gran)
        }

        chartTitle.text = Study.chartTitle(gran)
        chart.bars = Study.bars(this, gran)

        box.removeAllViews()

        // ---- by activity ----
        val tasks = Study.byTask(this, gran)

        if (tasks.isNotEmpty()) {
            box.addView(section("BY ACTIVITY"))
            for (t in tasks) box.addView(taskRow(t.first, t.second))
        }

        // ---- sessions ----
        box.addView(section("SESSIONS"))

        if (sessions.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "Nothing yet. Say: log 45 minutes for japanese. Or tap Start timer or Pomodoro."
                setPadding(0, dp(6), 0, dp(6))
            })
        }

        for (s in sessions) box.addView(sessionRow(s))
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 12f
        alpha = 0.6f
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun taskRow(name: String, minutes: Int): View {

        // For today, show how far along a routine activity with a time target is.
        val routine = Routines.findTask(name)
        val target = routine?.spec?.target ?: -1

        val extra =
            if (gran == Money.Gran.DAY && target > 0) "   (target " + Num.short(target) + ")" else ""

        return TextView(this).apply {
            text = name.replaceFirstChar { it.uppercase() } + "   " + Num.short(minutes) + extra
            textSize = 15f
            setPadding(0, dp(4), 0, dp(4))
        }
    }

    private fun sessionRow(s: Study.Session): View {

        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }

        val tag = when (s.source) {
            "timer" -> "  (timer)"
            "pomodoro" -> "  (pomodoro)"
            else -> ""
        }

        line.addView(TextView(this).apply {
            text = s.date.dayOfMonth.toString() + "/" + s.date.monthValue + "   " + Num.short(s.minutes) + "   " +
                s.task.replaceFirstChar { it.uppercase() } + tag
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        line.addView(Button(this).apply {
            text = "✕"
            isAllCaps = false
            setOnClickListener { confirmDelete(s) }
        })

        return line
    }

    // ---------------------------------------------------------------
    // Timer, pomodoro, manual log, delete, share
    // ---------------------------------------------------------------

    private fun toggleTimer() {

        if (Study.running(this) != null) {
            toast(Study.stop(this))
            SanyaWidget.refresh(this)
            render()
            return
        }

        if (Pomodoro.running(this)) {
            toast("A pomodoro is running. Stop it first.")
            return
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val what = EditText(this).apply {
            hint = "What are you working on?"
            setText("study")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        form.addView(what)

        AlertDialog.Builder(this)
            .setTitle("Start timer")
            .setView(form)
            .setPositiveButton("Start") { _, _ ->
                toast(Study.start(this, what.text.toString()))
                SanyaWidget.refresh(this)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun togglePomodoro() {

        if (Pomodoro.running(this)) {
            toast(Pomodoro.stop(this))
            render()
            return
        }

        if (Study.running(this) != null) {
            toast("A study timer is running. Stop it first.")
            return
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val what = EditText(this).apply {
            hint = "What are you working on?"
            setText("study")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        form.addView(what)

        val plan = Pomodoro.focusMinutes().toString() + " min focus, " + Pomodoro.shortMinutes() +
            " min break, long break after " + Pomodoro.cycles() + " sessions"

        AlertDialog.Builder(this)
            .setTitle("Start pomodoro")
            .setMessage(plan)
            .setView(form)
            .setPositiveButton("Start") { _, _ ->
                toast(Pomodoro.start(this, what.text.toString()))
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun togglePause() {

        toast(if (Pomodoro.paused(this)) Pomodoro.resume(this) else Pomodoro.pause(this))

        render()
    }

    private fun logByHand() {

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val what = EditText(this).apply {
            hint = "Activity (japanese, aptitude, study...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val how = EditText(this).apply {
            hint = "How long? (45 minutes, 1h30m, 2 hours)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        form.addView(what)
        form.addView(how)

        AlertDialog.Builder(this)
            .setTitle("Log time")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val text = how.text.toString().trim()
                val minutes = Num.parseDuration(text) ?: text.toIntOrNull()?.takeIf { it in 1..1440 }
                if (minutes == null) {
                    toast("I couldn't read that length of time. Try 45 minutes or 1h30m.")
                } else {
                    toast(Study.log(this, what.text.toString(), minutes))
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(s: Study.Session) {

        AlertDialog.Builder(this)
            .setTitle("Delete this session?")
            .setMessage(Num.short(s.minutes) + " on " + s.task)
            .setPositiveButton("Delete") { _, _ ->
                Study.delete(this, s)
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
                    .putExtra(Intent.EXTRA_TEXT, Study.report(this, gran)),
                "Share study time report"
            )
        )
    }
}
