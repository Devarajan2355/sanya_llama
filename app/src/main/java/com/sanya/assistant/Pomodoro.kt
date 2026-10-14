package com.sanya.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Pomodoro: focus sessions with breaks, tied to your study log and routines.
 *
 *   start pomodoro / start pomodoro for japanese / start focus mode
 *   stop pomodoro            pomodoro status (or: how long is left in my pomodoro)
 *   pause pomodoro           resume pomodoro
 *   extend pomodoro by 5 minutes      (or: give me five more minutes)
 *   skip break
 *   set pomodoro focus to 50 minutes     set pomodoro break to 10 minutes
 *   set pomodoro long break to 20 minutes
 *
 * The cycle:  focus, short break, focus, short break, focus, short break, focus, LONG break, ...
 * Defaults are 25 / 5 / 15 minutes and 4 focus sessions before the long break.
 * Change them in the Brain file (Settings section):
 *
 *   setting | pomodoro focus = 25
 *   setting | pomodoro break = 5
 *   setting | pomodoro long break = 15
 *   setting | pomodoro cycles = 4        focus sessions before the long break
 *   setting | pomodoro sessions = 8      she stops by herself after this many focus sessions
 *
 * LINKED TO STUDY AND ROUTINES: every finished focus session is logged with Study.log
 * (source "pomodoro"), so it counts toward a routine activity of the same name and can
 * mark it done. Stopping early logs the part you did if it was 5 minutes or more.
 *
 * EXACT CUES: the end of every phase is an exact alarm (AlarmManager). It posts a
 * notification with sound and vibration, and speaks too if Sanya is running. So the cue
 * comes on time and does not need her service. BubbleService's once-a-minute check is the
 * backup if an alarm is delayed. Whoever gets there first announces the phase; the other
 * finds nothing left to announce.
 *
 * The state is just the start time, the settings and any minutes you extended a phase by.
 * The current phase is worked out from the clock, so it survives restarts. Pause freezes
 * the clock at the moment you paused.
 */
object Pomodoro {

    enum class Phase { FOCUS, SHORT_BREAK, LONG_BREAK }

    /**
     * The settings are copied in at the start, so changing them mid-run does not shift the clock.
     * extra: minutes added to one phase by "extend" (phase index -> minutes).
     */
    data class State(
        val task: String,
        val startedAt: Long,
        val focus: Int,
        val short: Int,
        val long: Int,
        val cycles: Int,
        val extra: Map<Int, Int> = emptyMap()
    )

    /** Where the clock says you are. [session] counts focus sessions from 1 across the whole run. */
    data class Now(
        val phase: Phase,
        val index: Int,
        val start: Long,
        val end: Long,
        val session: Int
    )

    const val ACTION_PHASE = "com.sanya.assistant.POMODORO_PHASE"

    private const val PREFS = "sanya_pomodoro"
    private const val MIN_MS = 60_000L
    private const val MIN_PARTIAL = 5          // minutes: a stopped session shorter than this is not logged
    private const val MAX_EXTEND = 60          // most minutes one "extend" can add
    private const val ALARM_CODE = 7
    private const val CHANNEL = "sanya_pomodoro"
    private const val NOTIFY_ID = 2
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------

    private fun setting(key: String, def: Int, range: IntRange): Int {
        val v = Brain.setting(key, def.toString()).trim().toIntOrNull() ?: def
        return if (v in range) v else def
    }

    fun focusMinutes(): Int = setting("pomodoro focus", 25, 1..180)
    fun shortMinutes(): Int = setting("pomodoro break", 5, 1..60)
    fun longMinutes(): Int = setting("pomodoro long break", 15, 1..90)
    fun cycles(): Int = setting("pomodoro cycles", 4, 1..12)
    fun maxSessions(): Int = setting("pomodoro sessions", 8, 1..24)

    // ---------------------------------------------------------------
    // State and the clock
    // ---------------------------------------------------------------

    private fun readExtra(raw: String?): Map<Int, Int> {

        if (raw.isNullOrEmpty()) return emptyMap()

        val out = HashMap<Int, Int>()

        for (part in raw.split(",")) {
            val kv = part.split(":")
            if (kv.size != 2) continue
            val k = kv[0].toIntOrNull()
            val v = kv[1].toIntOrNull()
            if (k != null && v != null) out[k] = v
        }

        return out
    }

    private fun writeExtra(m: Map<Int, Int>): String =
        m.entries.joinToString(",") { it.key.toString() + ":" + it.value }

    fun state(ctx: Context): State? {

        val p = prefs(ctx)
        val start = p.getLong("start", 0L)

        if (start == 0L) return null

        return State(
            p.getString("task", "study") ?: "study",
            start,
            p.getInt("focus", 25),
            p.getInt("short", 5),
            p.getInt("long", 15),
            p.getInt("cycles", 4),
            readExtra(p.getString("extra", ""))
        )
    }

    fun running(ctx: Context): Boolean = state(ctx) != null

    /** True while the pomodoro is paused (the clock is frozen). */
    fun paused(ctx: Context): Boolean = prefs(ctx).getLong("paused", 0L) != 0L

    /** "Now" as the pomodoro sees it: the moment you paused, or the real time. */
    fun clock(ctx: Context): Long {
        val pa = prefs(ctx).getLong("paused", 0L)
        return if (pa > 0L) pa else System.currentTimeMillis()
    }

    // Phase k of the run: even numbers are focus, odd numbers are breaks,
    // and the last break of every round of [cycles] sessions is the long one.
    private fun isFocus(k: Int) = k % 2 == 0

    private fun isLong(s: State, k: Int) = !isFocus(k) && k % (2 * s.cycles) == 2 * s.cycles - 1

    private fun minutesOf(s: State, k: Int): Int {
        val base = if (isFocus(k)) s.focus else if (isLong(s, k)) s.long else s.short
        return base + (s.extra[k] ?: 0)
    }

    private fun phaseOf(s: State, k: Int): Phase =
        if (isFocus(k)) Phase.FOCUS else if (isLong(s, k)) Phase.LONG_BREAK else Phase.SHORT_BREAK

    private fun endOfPhase(s: State, k: Int): Long {
        var t = s.startedAt
        for (i in 0..k) t += minutesOf(s, i) * MIN_MS
        return t
    }

    // Minutes of the first [count] focus sessions, extensions included.
    private fun focusTotal(s: State, count: Int): Int {
        var sum = 0
        for (n in 0 until count) sum += minutesOf(s, 2 * n)
        return sum
    }

    fun locate(s: State, nowMs: Long): Now {

        var k = 0
        var begin = s.startedAt

        while (true) {

            val end = begin + minutesOf(s, k) * MIN_MS

            if (nowMs < end || k > 5000) {
                val session = if (isFocus(k)) k / 2 + 1 else (k + 1) / 2
                return Now(phaseOf(s, k), k, begin, end, session)
            }

            begin = end
            k++
        }
    }

    // Focus sessions fully finished by the time the clock is in phase [index].
    private fun finishedSessions(index: Int) = (index + 1) / 2

    /** True once the last focus session of the run has finished. */
    fun isOver(ctx: Context, nowMs: Long = clock(ctx)): Boolean {
        val s = state(ctx) ?: return false
        return finishedSessions(locate(s, nowMs).index) >= maxSessions()
    }

    private fun two(n: Long) = n.toString().padStart(2, '0')

    private fun roundOf(s: State, session: Int) = ((session - 1) % s.cycles) + 1

    private fun phaseWord(p: Phase) = when (p) {
        Phase.FOCUS -> "Focus"
        Phase.SHORT_BREAK -> "Break"
        Phase.LONG_BREAK -> "Long break"
    }

    /** For screens: "Focus 12:30 left, japanese, session 2 of 4". Empty if none is running. */
    fun statusLine(ctx: Context, nowMs: Long = clock(ctx)): String {

        val s = state(ctx) ?: return ""
        val cur = locate(s, nowMs)
        val left = maxOf(0L, (cur.end - nowMs) / 1000)

        val line = phaseWord(cur.phase) + " " + two(left / 60) + ":" + two(left % 60) + " left, " +
            s.task + ", session " + roundOf(s, cur.session) + " of " + s.cycles

        return if (paused(ctx)) "Paused: $line" else line
    }

    // ---------------------------------------------------------------
    // Exact alarms and notifications
    // ---------------------------------------------------------------

    private fun alarmIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, ALARM_CODE,
            Intent(ctx, PomodoroReceiver::class.java).setAction(ACTION_PHASE),
            FLAGS
        )

    /**
     * Sets (or cancels) the alarm for the end of the current phase.
     * Safe to call any time: it always matches the saved state.
     */
    fun schedule(ctx: Context) {

        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = alarmIntent(ctx)
            val s = state(ctx)

            if (s == null || paused(ctx)) {
                am.cancel(pi)
                return
            }

            val at = locate(s, System.currentTimeMillis()).end + 500L

            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

            try {
                if (exact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                }
            } catch (e: SecurityException) {
                // The phone refused an exact alarm: fall back to the best it allows.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }

        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Pomodoro alarm", e)
        }
    }

    /** A notification with sound and vibration, so the cue works without her voice. */
    fun notify(ctx: Context, text: String) {

        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)

            val channel = NotificationChannel(CHANNEL, "Pomodoro", NotificationManager.IMPORTANCE_HIGH)
            channel.enableVibration(true)
            nm.createNotificationChannel(channel)

            val open = PendingIntent.getActivity(ctx, 8, Intent(ctx, TimeActivity::class.java), FLAGS)

            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Pomodoro")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()

            nm.notify(NOTIFY_ID, n)

        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Pomodoro notification", e)
        }
    }

    /** Notification, plus her voice if the service is running. */
    fun cue(ctx: Context, text: String) {

        notify(ctx, text)

        if (BubbleService.alive) {
            try {
                ctx.startService(Intent(ctx, BubbleService::class.java).putExtra("say", text))
            } catch (e: Throwable) {
                ErrorLog.add(ctx, "Pomodoro cue", e)
            }
        }
    }

    // ---------------------------------------------------------------
    // Logging finished sessions
    // ---------------------------------------------------------------

    // Logs every focus session that has finished and is not logged yet. Returns how many.
    private fun settle(ctx: Context, s: State, cur: Now): Int {

        val p = prefs(ctx)
        val done = minOf(finishedSessions(cur.index), maxSessions())
        val logged = p.getInt("logged", 0)

        if (done <= logged) return 0

        for (n in logged until done) {
            Study.log(ctx, s.task, minutesOf(s, 2 * n), endOfPhase(s, 2 * n), "pomodoro")
        }

        p.edit().putInt("logged", done).apply()

        return done - logged
    }

    private fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        schedule(ctx)                 // no state any more: this cancels the alarm
        SanyaWidget.refresh(ctx)
    }

    // ---------------------------------------------------------------
    // Start, stop, pause, extend, skip, status
    // ---------------------------------------------------------------

    fun start(ctx: Context, name: String): String {

        if (running(ctx)) {
            return "A pomodoro is already running. " + status(ctx) + " Say stop pomodoro to end it."
        }

        if (Study.running(ctx) != null) {
            return "A study timer is running. Say stop timer first, then start the pomodoro."
        }

        val task = Study.resolve(name)
        val f = focusMinutes()
        val sh = shortMinutes()
        val lg = longMinutes()
        val cy = cycles()

        prefs(ctx).edit()
            .clear()
            .putLong("start", System.currentTimeMillis())
            .putString("task", task)
            .putInt("focus", f)
            .putInt("short", sh)
            .putInt("long", lg)
            .putInt("cycles", cy)
            .putInt("logged", 0)
            .putInt("announced", 0)
            .apply()

        schedule(ctx)
        SanyaWidget.refresh(ctx)

        return "Pomodoro started for $task. Focus for ${Num.durText(f)}, then a ${Num.durText(sh)} break. " +
            "After $cy sessions you get a ${Num.durText(lg)} break."
    }

    fun stop(ctx: Context): String {

        val s = state(ctx) ?: return "No pomodoro is running."

        val now = clock(ctx)
        val cur = locate(s, now)

        settle(ctx, s, cur)

        var minutes = focusTotal(s, prefs(ctx).getInt("logged", 0))
        var partial = 0

        // Stopped in the middle of a focus session: keep the part you did if it was long enough.
        if (cur.phase == Phase.FOCUS && finishedSessions(cur.index) < maxSessions()) {
            val done = ((now - cur.start) / MIN_MS).toInt()
            if (done >= MIN_PARTIAL) {
                Study.log(ctx, s.task, done, now, "pomodoro")
                partial = done
            }
        }

        clear(ctx)

        minutes += partial

        return if (minutes == 0) {
            "Pomodoro stopped. Nothing was long enough to log."
        } else {
            "Pomodoro stopped. Logged ${Num.durText(minutes)} on ${s.task}."
        }
    }

    fun pause(ctx: Context): String {

        if (!running(ctx)) return "No pomodoro is running."
        if (paused(ctx)) return "The pomodoro is already paused. Say resume pomodoro."

        prefs(ctx).edit().putLong("paused", System.currentTimeMillis()).apply()

        schedule(ctx)                 // paused: this cancels the alarm
        SanyaWidget.refresh(ctx)

        return "Pomodoro paused. " + status(ctx) + " Say resume pomodoro when you're back."
    }

    fun resume(ctx: Context): String {

        val s = state(ctx) ?: return "No pomodoro is running."

        val p = prefs(ctx)
        val at = p.getLong("paused", 0L)

        if (at == 0L) return "The pomodoro isn't paused."

        // Slide the start forward by the time spent paused, so the phase carries on where it stopped.
        val gap = System.currentTimeMillis() - at

        p.edit()
            .putLong("start", s.startedAt + gap)
            .remove("paused")
            .apply()

        schedule(ctx)
        SanyaWidget.refresh(ctx)

        return "Back to it. " + status(ctx)
    }

    /** Adds [minutes] to the phase you are in. */
    fun extend(ctx: Context, minutes: Int): String {

        val s = state(ctx) ?: return "No pomodoro is running."

        if (paused(ctx)) return "The pomodoro is paused. Say resume pomodoro first."

        if (minutes !in 1..MAX_EXTEND) {
            return "I can extend by 1 to $MAX_EXTEND minutes at a time."
        }

        val now = System.currentTimeMillis()
        val cur = locate(s, now)

        val extra = HashMap(s.extra)
        extra[cur.index] = (extra[cur.index] ?: 0) + minutes

        prefs(ctx).edit().putString("extra", writeExtra(extra)).apply()

        schedule(ctx)
        SanyaWidget.refresh(ctx)

        val word = if (cur.phase == Phase.FOCUS) "focus" else "break"

        return "Added ${Num.durText(minutes)} to this $word. " + status(ctx)
    }

    fun skipBreak(ctx: Context): String {

        val s = state(ctx) ?: return "No pomodoro is running."

        if (paused(ctx)) return "The pomodoro is paused. Say resume pomodoro first."

        val now = System.currentTimeMillis()
        val cur = locate(s, now)

        if (cur.phase == Phase.FOCUS) return "You're in a focus session, not a break."

        // Pull the start time back by what is left of the break, so the next focus starts now.
        val shifted = s.copy(startedAt = s.startedAt - (cur.end - now))
        val next = locate(shifted, now)

        prefs(ctx).edit()
            .putLong("start", shifted.startedAt)
            .putInt("announced", next.index)
            .apply()

        schedule(ctx)
        SanyaWidget.refresh(ctx)

        return "Skipped the break. Focus on ${s.task} for ${Num.durText(minutesOf(shifted, next.index))}."
    }

    fun status(ctx: Context): String {

        val s = state(ctx) ?: return "No pomodoro is running. Say start pomodoro."

        val now = clock(ctx)
        val cur = locate(s, now)
        val mins = ((cur.end - now + MIN_MS - 1) / MIN_MS).toInt()
        val left = if (mins <= 0) "less than a minute" else Num.durText(mins)
        val what = if (cur.phase == Phase.FOCUS) "Focus on ${s.task}" else phaseWord(cur.phase)

        return "$what: $left left. Session ${roundOf(s, cur.session)} of ${s.cycles}."
    }

    private fun setLength(which: String, minutes: Int): String {

        val key = when (which) {
            "focus" -> "pomodoro focus"
            "break" -> "pomodoro break"
            else -> "pomodoro long break"
        }

        Brain.put("setting", key, minutes.toString())

        return "Okay. Pomodoro $which is now ${Num.durText(minutes)}. It applies from the next pomodoro."
    }

    /**
     * Logs finished focus sessions and returns what should be announced now
     * (a new phase began, or the run is over), or null.
     * Called by PomodoroReceiver at every phase end, and by BubbleService once a minute.
     */
    fun due(ctx: Context, nowMs: Long = System.currentTimeMillis()): String? {

        val s = state(ctx) ?: return null

        if (paused(ctx)) return null

        val cur = locate(s, nowMs)
        val max = maxSessions()

        // The last focus session is finished: log everything and stop.
        if (finishedSessions(cur.index) >= max) {
            settle(ctx, s, cur)
            val total = focusTotal(s, max)
            clear(ctx)
            return "Pomodoro finished. $max focus sessions, ${Num.durText(total)} on ${s.task}. Well done."
        }

        settle(ctx, s, cur)

        val p = prefs(ctx)

        if (cur.index <= p.getInt("announced", 0)) return null

        p.edit().putInt("announced", cur.index).apply()

        schedule(ctx)

        val round = roundOf(s, cur.session)

        return when (cur.phase) {
            Phase.FOCUS ->
                "Break over. Focus on ${s.task} for ${Num.durText(minutesOf(s, cur.index))}. Session $round of ${s.cycles}."
            Phase.SHORT_BREAK ->
                "Good work. That was session $round of ${s.cycles}. Take a ${Num.durText(minutesOf(s, cur.index))} break."
            Phase.LONG_BREAK ->
                "Round complete, ${s.cycles} sessions done. Take a ${Num.durText(minutesOf(s, cur.index))} break."
        }
    }

    // ---------------------------------------------------------------
    // Voice commands
    // ---------------------------------------------------------------

    sealed class Cmd {
        data class Start(val task: String) : Cmd()
        data class Length(val which: String, val minutes: Int) : Cmd()
        data class Extend(val minutes: Int) : Cmd()
        data class Say(val text: String) : Cmd()
        object Stop : Cmd()
        object Pause : Cmd()
        object Resume : Cmd()
        object Status : Cmd()
        object Skip : Cmd()
    }

    private const val MODE = "(?:pomodoro|pomodoros|focus mode)"

    private val START_A = Regex(
        """^(?:start|begin|run|turn on) (?:a |an |the |my )?$MODE(?: (?:timer|session|mode))?(?: (?:for|on|with) (.+))?$"""
    )
    private val START_B = Regex("""^$MODE(?: (?:for|on) (.+))?$""")
    private val STOP = Regex(
        """^(?:stop|end|finish|cancel|quit|turn off) (?:the |my )?$MODE(?: (?:timer|session|mode))?$"""
    )
    private val PAUSE = Regex(
        """^(?:pause|hold) (?:the |my )?$MODE(?: (?:timer|session|mode))?$"""
    )
    private val RESUME = Regex(
        """^(?:resume|continue|unpause) (?:the |my )?$MODE(?: (?:timer|session|mode))?$"""
    )
    private val STATUS = Regex(
        """^$MODE (?:status|time left|progress)$|^how (?:much time|long) (?:is )?left in (?:my |the )?(?:pomodoro|focus session)$|^(?:what is|what's|whats) my pomodoro(?: status)?$"""
    )
    private val SKIP = Regex("""^(?:skip|end) (?:the |my |this )?(?:pomodoro )?break$""")
    private val EXTEND_A = Regex(
        """^(?:extend|prolong|lengthen)(?: (?:the |my |this )?(?:pomodoro|focus|break|session))?(?: by)? (.+)$"""
    )
    private val EXTEND_B = Regex(
        """^(?:give me |i need |need |add )?(.+?) more (?:minutes?|mins?)$"""
    )
    private val LENGTH = Regex(
        """^set (?:the |my )?pomodoros? (?:(focus|long break|break) )?(?:length |time |duration )?(?:to|as|at) (.+)$"""
    )

    private fun norm(raw: String): String =
        raw.lowercase()
            .replace(Regex("""[^a-z0-9' ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun tidy(s: String): String =
        s.trim().removePrefix("my ").removePrefix("the ").removePrefix("some ").removePrefix("a ").trim()

    private fun startCmd(what: String): Cmd {

        // "start pomodoro for 30 minutes": the lengths are settings, not part of the start command.
        if (what.isNotEmpty() && Num.parseDuration(what) != null) {
            return Cmd.Say("I set the pomodoro lengths separately. Say, for example: set pomodoro focus to 50 minutes.")
        }

        return Cmd.Start(tidy(what).ifEmpty { "study" })
    }

    private fun extendCmd(text: String): Cmd {

        val minutes = Num.parseDuration(text) ?: text.trim().toIntOrNull()

        if (minutes == null || minutes !in 1..MAX_EXTEND) {
            return Cmd.Say("I didn't catch how long. Say, for example: extend pomodoro by 5 minutes.")
        }

        return Cmd.Extend(minutes)
    }

    /** Is [raw] a pomodoro command? */
    fun parse(raw: String): Cmd? {

        val t = norm(raw)
        if (t.isEmpty()) return null

        if (STOP.matches(t)) return Cmd.Stop
        if (PAUSE.matches(t)) return Cmd.Pause
        if (RESUME.matches(t)) return Cmd.Resume
        if (STATUS.matches(t)) return Cmd.Status
        if (SKIP.matches(t)) return Cmd.Skip

        EXTEND_A.matchEntire(t)?.let { return extendCmd(it.groupValues[1]) }

        // "five more minutes": only ours if the number reads as a length of time.
        EXTEND_B.matchEntire(t)?.let { m ->
            val minutes = Num.parseDuration(m.groupValues[1] + " minutes")
            if (minutes != null) return extendCmd(m.groupValues[1] + " minutes")
        }

        LENGTH.matchEntire(t)?.let { m ->

            val which = m.groupValues[1].ifEmpty { "focus" }
            val text = m.groupValues[2].trim()
            val minutes = Num.parseDuration(text) ?: text.toIntOrNull()
            val max = when (which) {
                "focus" -> 180
                "break" -> 60
                else -> 90
            }

            if (minutes == null || minutes !in 1..max) {
                return Cmd.Say(
                    "I didn't catch the length. Say it like: set pomodoro $which to " +
                        (if (which == "focus") "25" else "5") + " minutes."
                )
            }

            return Cmd.Length(which, minutes)
        }

        START_A.matchEntire(t)?.let { return startCmd(it.groupValues[1]) }
        START_B.matchEntire(t)?.let { return startCmd(it.groupValues[1]) }

        return null
    }

    /** Does what the command says and returns what she should say. */
    fun run(ctx: Context, c: Cmd): String = when (c) {
        is Cmd.Start -> start(ctx, c.task)
        is Cmd.Length -> setLength(c.which, c.minutes)
        is Cmd.Extend -> extend(ctx, c.minutes)
        is Cmd.Say -> c.text
        Cmd.Stop -> stop(ctx)
        Cmd.Pause -> pause(ctx)
        Cmd.Resume -> resume(ctx)
        Cmd.Status -> status(ctx)
        Cmd.Skip -> skipBreak(ctx)
    }
}

/**
 * Fires at the end of every pomodoro phase (an exact alarm), and after a reboot to set the
 * alarm again. Logs the finished session, then posts the notification and speaks the cue.
 */
class PomodoroReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {

        Brain.setup(ctx)

        try {
            if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
                val cue = Pomodoro.due(ctx)
                if (cue != null) Pomodoro.cue(ctx, cue)
            }
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Pomodoro alarm", e)
        }

        // Always set the next alarm. After a reboot a phase end in the past fires at once.
        Pomodoro.schedule(ctx)
        SanyaWidget.refresh(ctx)
    }
}
