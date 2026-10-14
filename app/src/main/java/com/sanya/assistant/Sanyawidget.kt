package com.sanya.assistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import java.time.LocalDate

/**
 * The home-screen widget.
 *
 *   - today's routine activities: tap one to mark it done / not done
 *   - the running timer, ticking live (a pomodoro counts down, a study timer counts up)
 *   - a Pomodoro button and a Study timer button
 *   - tap the title to open the routines screen
 *
 * Add it: long-press the home screen, choose Widgets, find Sanya.
 *
 * The timer ticks by itself (a Chronometer). The rest is redrawn
 *   - at once after anything you tap on the widget or start by voice (Pomodoro)
 *   - once a minute while the screen is on, by BubbleService
 *   - every 30 minutes by Android, even if Sanya is not running
 * so a routine you mark by voice shows up within a minute while Sanya is running.
 */
class SanyaWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Brain.setup(context)
        val views = build(context)
        manager.updateAppWidget(ids, views)
    }

    override fun onReceive(context: Context, intent: Intent) {

        val action = intent.action

        if (action != ACTION_TOGGLE && action != ACTION_POMO && action != ACTION_TIMER) {
            super.onReceive(context, intent)
            return
        }

        Brain.setup(context)

        try {
            when (action) {
                ACTION_TOGGLE -> toggle(context, intent.getStringExtra("name") ?: "")
                ACTION_POMO -> message(
                    context,
                    when {
                        Pomodoro.running(context) && Pomodoro.paused(context) -> Pomodoro.resume(context)
                        Pomodoro.running(context) -> Pomodoro.stop(context)
                        else -> Pomodoro.start(context, "study")
                    }
                )
                ACTION_TIMER -> message(context, studyTimer(context))
            }
        } catch (e: Throwable) {
            ErrorLog.add(context, "Widget action", e)
        }

        refresh(context)
    }

    private fun message(ctx: Context, text: String) {
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }

    // Flip one routine activity for today.
    private fun toggle(ctx: Context, name: String) {

        val task = Routines.tasks().firstOrNull { it.name == name } ?: return
        val live = Routines.activeOn(task, LocalDate.now())

        if (live.isEmpty()) return

        val value = !Routines.isDone(ctx, live[0])

        for (i in live) Routines.setDone(ctx, i, value)
    }

    private fun studyTimer(ctx: Context): String {

        if (Study.running(ctx) != null) return Study.stop(ctx)

        if (Pomodoro.running(ctx)) return "A pomodoro is running. Stop it first."

        return Study.start(ctx, "study")
    }

    companion object {

        const val ACTION_TOGGLE = "com.sanya.assistant.WIDGET_TOGGLE"
        const val ACTION_POMO = "com.sanya.assistant.WIDGET_POMODORO"
        const val ACTION_TIMER = "com.sanya.assistant.WIDGET_TIMER"

        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private const val MAX_ROWS = 5

        /** Redraw every copy of the widget that is on a home screen. Safe to call from anywhere. */
        fun refresh(ctx: Context) {

            try {
                val app = ctx.applicationContext
                val manager = AppWidgetManager.getInstance(app)
                val ids = manager.getAppWidgetIds(ComponentName(app, SanyaWidget::class.java))

                if (ids.isEmpty()) return

                Brain.setup(app)
                manager.updateAppWidget(ids, build(app))

            } catch (e: Throwable) {
                ErrorLog.add(ctx, "Widget refresh", e)
            }
        }

        private fun action(ctx: Context, action: String): PendingIntent {
            val i = Intent(ctx, SanyaWidget::class.java)
                .setAction(action)
                .setData(Uri.parse("sanya://widget/" + action))
            return PendingIntent.getBroadcast(ctx, 0, i, FLAGS)
        }

        private fun openRoutines(ctx: Context): PendingIntent =
            PendingIntent.getActivity(ctx, 1, Intent(ctx, RoutineActivity::class.java), FLAGS)

        private fun build(ctx: Context): RemoteViews {

            val v = RemoteViews(ctx.packageName, R.layout.widget_sanya)

            // ---- header: tap to open the routines screen ----

            val today = Routines.today(ctx)
            val all = today.remaining + today.finished
            val total = all.size

            v.setTextViewText(
                R.id.w_progress,
                if (total == 0) "No routines" else today.finished.size.toString() + "/" + total + " done"
            )
            v.setOnClickPendingIntent(R.id.w_header, openRoutines(ctx))

            // ---- the timer line ----

            var pomo = Pomodoro.state(ctx)

            // The run ran out while nothing was watching: log it and clear it.
            if (pomo != null && Pomodoro.isOver(ctx)) {
                Pomodoro.stop(ctx)
                pomo = null
            }

            val study = Study.running(ctx)
            val now = System.currentTimeMillis()

            if (pomo != null && Pomodoro.paused(ctx)) {

                v.setTextViewText(R.id.w_timer_label, "⏸ " + Pomodoro.statusLine(ctx))
                v.setChronometer(R.id.w_clock, SystemClock.elapsedRealtime(), null, false)
                v.setViewVisibility(R.id.w_clock, View.GONE)

            } else if (pomo != null) {

                val cur = Pomodoro.locate(pomo, now)
                val word = when (cur.phase) {
                    Pomodoro.Phase.FOCUS -> "Focus"
                    Pomodoro.Phase.SHORT_BREAK -> "Break"
                    Pomodoro.Phase.LONG_BREAK -> "Long break"
                }

                v.setTextViewText(R.id.w_timer_label, "\uD83C\uDF45 " + word + " · " + pomo.task)
                v.setChronometerCountDown(R.id.w_clock, true)
                v.setChronometer(R.id.w_clock, SystemClock.elapsedRealtime() + (cur.end - now), null, true)
                v.setViewVisibility(R.id.w_clock, View.VISIBLE)

            } else if (study != null) {

                v.setTextViewText(R.id.w_timer_label, "⏱ " + study.first)
                v.setChronometerCountDown(R.id.w_clock, false)
                v.setChronometer(R.id.w_clock, SystemClock.elapsedRealtime() - (now - study.second), null, true)
                v.setViewVisibility(R.id.w_clock, View.VISIBLE)

            } else {

                v.setTextViewText(R.id.w_timer_label, "No timer running")
                v.setChronometer(R.id.w_clock, SystemClock.elapsedRealtime(), null, false)
                v.setViewVisibility(R.id.w_clock, View.GONE)
            }

            // ---- today's activities ----

            val rowIds = intArrayOf(R.id.w_row0, R.id.w_row1, R.id.w_row2, R.id.w_row3, R.id.w_row4)

            val shown = if (all.size <= MAX_ROWS) all else all.take(MAX_ROWS - 1)
            val more = all.size - shown.size

            for (n in rowIds.indices) {

                val id = rowIds[n]

                if (n < shown.size) {

                    val inst = shown[n]
                    val done = n >= today.remaining.size

                    v.setTextViewText(id, (if (done) "✓  " else "○  ") + Routines.spoken(inst))
                    v.setTextColor(id, if (done) 0x99FFFFFF.toInt() else 0xFFFFFFFF.toInt())

                    val toggle = Intent(ctx, SanyaWidget::class.java)
                        .setAction(ACTION_TOGGLE)
                        .putExtra("name", inst.task.name)
                        .setData(Uri.parse("sanya://toggle/" + Uri.encode(inst.task.name)))

                    v.setOnClickPendingIntent(id, PendingIntent.getBroadcast(ctx, 0, toggle, FLAGS))
                    v.setViewVisibility(id, View.VISIBLE)

                } else if (n == shown.size && more > 0) {

                    v.setTextViewText(id, "+$more more")
                    v.setTextColor(id, 0x99FFFFFF.toInt())
                    v.setOnClickPendingIntent(id, openRoutines(ctx))
                    v.setViewVisibility(id, View.VISIBLE)

                } else {
                    v.setViewVisibility(id, View.GONE)
                }
            }

            v.setViewVisibility(R.id.w_empty, if (total == 0) View.VISIBLE else View.GONE)

            // ---- buttons ----

            v.setTextViewText(
                R.id.w_btn_pomo,
                when {
                    pomo != null && Pomodoro.paused(ctx) -> "▶ Resume pomodoro"
                    pomo != null -> "■ Stop pomodoro"
                    else -> "\uD83C\uDF45 Pomodoro"
                }
            )
            v.setOnClickPendingIntent(R.id.w_btn_pomo, action(ctx, ACTION_POMO))

            v.setTextViewText(R.id.w_btn_timer, if (study != null) "■ Stop timer" else "▶ Study timer")
            v.setOnClickPendingIntent(R.id.w_btn_timer, action(ctx, ACTION_TIMER))

            return v
        }
    }
}
