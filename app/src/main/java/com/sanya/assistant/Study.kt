package com.sanya.assistant

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Study: keep track of the time you spend, and tie it to your routine.
 *
 * Logging by voice:
 *   log 45 minutes for japanese            log two hours for study
 *   i studied japanese for an hour and a half
 *   study from 6 pm to 8 pm                japanese from 7 to 7 45
 * Timer:
 *   start study timer / start timer for japanese / start studying aptitude
 *   stop timer / i'm done studying         timer status
 * Totals:
 *   how long did i study today / this week / how much time did i spend on japanese this week
 *   show my study time                     (opens the Study time screen with charts)
 *   undo last session
 *
 * LINKED TO ROUTINES: if what you log matches a routine activity, the time counts toward it.
 * An activity with no target is marked done as soon as you log time on it. With a target
 * ("daily for 2h") it is marked done once the day's time reaches the target, and she tells you
 * how far along you are.
 *
 * Sessions are kept in files/time.log:   start <tab> minutes <tab> task <tab> timer or manual
 * A running timer is remembered in preferences, so it survives a restart.
 */
object Study {

    data class Session(
        val start: Long,
        val minutes: Int,
        val task: String,
        val source: String,
        val line: String
    ) {
        val date: LocalDate
            get() = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    private const val PREFS = "sanya_study"
    private const val MINUTE_MS = 60_000L

    private fun file(ctx: Context) = File(ctx.filesDir, "time.log")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cap(s: String) = s.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    // ---------------------------------------------------------------
    // Names
    // ---------------------------------------------------------------

    /** The task you meant: the matching routine activity if there is one, else your own words. */
    fun resolve(name: String): String {

        val clean = Brain.clean(name)
        if (clean.isEmpty()) return "study"

        val routine = Routines.findTask(clean)

        return if (routine != null) Brain.clean(routine.name) else clean
    }

    // ---------------------------------------------------------------
    // The log
    // ---------------------------------------------------------------

    @Synchronized
    fun all(ctx: Context): List<Session> {

        val out = ArrayList<Session>()

        try {
            val f = file(ctx)
            if (!f.exists()) return out

            for (line in f.readLines()) {
                val p = line.split("\t")
                if (p.size < 4) continue
                val start = p[0].toLongOrNull() ?: continue
                val minutes = p[1].toIntOrNull() ?: continue
                out.add(Session(start, minutes, p[2], p[3], line))
            }
        } catch (e: Exception) {
        }

        return out
    }

    fun sessions(ctx: Context, from: LocalDate, to: LocalDate): List<Session> =
        all(ctx).filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.sortedBy { it.start }

    /** Minutes logged for [task] (or everything if null) between two dates. */
    fun minutesIn(ctx: Context, task: String?, from: LocalDate, to: LocalDate): Int {

        val wanted = if (task == null) null else Brain.clean(task)

        return sessions(ctx, from, to)
            .filter { wanted == null || it.task == wanted }
            .sumOf { it.minutes }
    }

    @Synchronized
    private fun append(ctx: Context, start: Long, minutes: Int, task: String, source: String) {
        file(ctx).appendText(start.toString() + "\t" + minutes + "\t" + task + "\t" + source + "\n")
    }

    @Synchronized
    private fun removeLine(ctx: Context, s: Session): Boolean {

        val f = file(ctx)
        if (!f.exists()) return false

        val lines = f.readLines().toMutableList()
        val i = lines.indexOf(s.line)
        if (i < 0) return false

        lines.removeAt(i)
        f.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n")

        return true
    }

    /** Removes one session. If it had counted toward a routine activity, that is checked again. */
    fun delete(ctx: Context, s: Session): Boolean {

        if (!removeLine(ctx, s)) return false

        Routines.reviewTime(ctx, s.task, s.date)

        return true
    }

    /**
     * Log [minutes] on [name] that ended at [endMs]. Returns what she says,
     * including how it counts toward your routine.
     */
    fun log(ctx: Context, name: String, minutes: Int, endMs: Long = System.currentTimeMillis(), source: String = "manual"): String {

        val task = resolve(name)
        val start = endMs - minutes * MINUTE_MS

        append(ctx, start, minutes, task, source)

        val date = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()

        val whenText = when {
            date == today -> ""
            date == today.minusDays(1) -> " for yesterday"
            else -> " for " + date.dayOfMonth + "/" + date.monthValue
        }

        val dayTotal = minutesIn(ctx, task, date, date)

        var reply = "Logged " + Num.durText(minutes) + " on " + task + whenText + ". " +
            (if (date == today) "Today" else "That day") + " you've done " + Num.durText(dayTotal) + " on " + task + "."

        val credit = Routines.creditTime(ctx, task, date)
        if (credit != null) reply += " $credit"

        return reply
    }

    fun undoLast(ctx: Context): String {

        val last = all(ctx).maxByOrNull { it.start } ?: return "There is nothing to undo."

        removeLine(ctx, last)

        val note = Routines.reviewTime(ctx, last.task, last.date)

        return "Removed " + Num.durText(last.minutes) + " on " + last.task + "." +
            (if (note != null) " $note" else "")
    }

    // ---------------------------------------------------------------
    // The timer
    // ---------------------------------------------------------------

    /** (task, start time in ms) of the running timer, or null. */
    fun running(ctx: Context): Pair<String, Long>? {

        val p = prefs(ctx)
        val start = p.getLong("run_start", 0L)

        if (start == 0L) return null

        return Pair(p.getString("run_task", "study") ?: "study", start)
    }

    fun start(ctx: Context, name: String): String {

        if (Pomodoro.running(ctx)) {
            return "A pomodoro is running. Say stop pomodoro first, then start the timer."
        }

        val now = System.currentTimeMillis()
        val r = running(ctx)

        if (r != null) {
            val mins = ((now - r.second) / MINUTE_MS).toInt()
            return "A timer is already running for " + r.first + " (" + Num.durText(maxOf(mins, 0)) +
                "). Say stop timer to log it."
        }

        val task = resolve(name)

        prefs(ctx).edit().putLong("run_start", now).putString("run_task", task).apply()

        return "Started the timer for $task. Say stop timer when you're done."
    }

    fun stop(ctx: Context): String {

        val r = running(ctx) ?: return "No study timer is running."

        val now = System.currentTimeMillis()
        val seconds = (now - r.second) / 1000

        prefs(ctx).edit().remove("run_start").remove("run_task").apply()

        if (seconds < 30) return "That was under a minute, so I didn't log it."

        val minutes = ((seconds + 30) / 60).toInt().coerceAtLeast(1)

        return log(ctx, r.first, minutes, now, "timer")
    }

    fun status(ctx: Context): String {

        val r = running(ctx)
        val today = LocalDate.now()
        val total = minutesIn(ctx, null, today, today)

        if (r == null) {
            return if (total == 0) {
                "No timer is running, and nothing is logged today."
            } else {
                "No timer is running. Today you've logged " + Num.durText(total) + "."
            }
        }

        val mins = ((System.currentTimeMillis() - r.second) / MINUTE_MS).toInt()

        return "You've been on " + r.first + " for " + Num.durText(maxOf(mins, 0)) +
            ". Today's total so far: " + Num.durText(total + maxOf(mins, 0)) + "."
    }

    /** A gentle break reminder once per session, after "study break" minutes (default 50). */
    fun breakDue(ctx: Context, nowMs: Long = System.currentTimeMillis()): String? {

        val r = running(ctx) ?: return null

        val limit = Brain.setting("study break", "50").trim().toIntOrNull() ?: 50
        if (limit <= 0) return null

        val mins = ((nowMs - r.second) / MINUTE_MS).toInt()
        if (mins < limit) return null

        val p = prefs(ctx)
        if (p.getLong("nudged", 0L) == r.second) return null

        p.edit().putLong("nudged", r.second).apply()

        return "You've been on " + r.first + " for " + Num.durText(mins) + ". Time for a short break?"
    }

    // ---------------------------------------------------------------
    // Totals
    // ---------------------------------------------------------------

    private fun spanName(span: String) = span

    fun total(ctx: Context, task: String?, span: String): String {

        val r = Span.range(span, LocalDate.now())
        val list = sessions(ctx, r.first, r.second)

        if (task != null) {

            val t = resolve(task)
            val minutes = list.filter { it.task == t }.sumOf { it.minutes }

            return if (minutes == 0) {
                "You haven't logged any time on $t ${spanName(span)}."
            } else {
                cap(spanName(span)) + " you spent " + Num.durText(minutes) + " on " + t + "."
            }
        }

        val minutes = list.sumOf { it.minutes }

        if (minutes == 0) return "You haven't logged any study time ${spanName(span)}."

        val top = list.groupBy { it.task }
            .map { Pair(it.key, it.value.sumOf { s -> s.minutes }) }
            .sortedByDescending { it.second }
            .take(3)
            .joinToString(", ") { it.first + " " + Num.durText(it.second) }

        return cap(spanName(span)) + " you studied for " + Num.durText(minutes) + ": " + top + "."
    }

    // ---------------------------------------------------------------
    // For the Study time screen
    // ---------------------------------------------------------------

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun currentRange(gran: Money.Gran, today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> =
        Money.currentRange(gran, today)

    fun title(gran: Money.Gran): String = Money.title(gran)

    fun chartTitle(gran: Money.Gran): String = when (gran) {
        Money.Gran.DAY -> "Minutes per day, last 14 days"
        Money.Gran.WEEK -> "Minutes per week, last 8 weeks"
        Money.Gran.MONTH -> "Minutes per month, last 12 months"
    }

    /** Oldest to newest: minutes logged in each day, week or month. */
    fun bars(ctx: Context, gran: Money.Gran, today: LocalDate = LocalDate.now()): List<ValueBar> {

        val everything = all(ctx)
        val out = ArrayList<ValueBar>()

        val n = when (gran) {
            Money.Gran.DAY -> 14
            Money.Gran.WEEK -> 8
            Money.Gran.MONTH -> 12
        }

        for (k in (n - 1) downTo 0) {

            val b: Triple<LocalDate, LocalDate, String> = when (gran) {
                Money.Gran.DAY -> {
                    val d = today.minusDays(k.toLong())
                    Triple(d, d, d.dayOfMonth.toString())
                }
                Money.Gran.WEEK -> {
                    val d = Money.currentRange(Money.Gran.WEEK, today).first.minusWeeks(k.toLong())
                    Triple(d, d.plusDays(6), d.dayOfMonth.toString() + " " + MONTHS[d.monthValue - 1])
                }
                Money.Gran.MONTH -> {
                    val ym = java.time.YearMonth.from(today).minusMonths(k.toLong())
                    Triple(ym.atDay(1), ym.atEndOfMonth(), MONTHS[ym.monthValue - 1])
                }
            }

            val minutes = everything
                .filter { !it.date.isBefore(b.first) && !it.date.isAfter(b.second) }
                .sumOf { it.minutes }

            out.add(ValueBar(b.third, minutes.toLong()))
        }

        return out
    }

    fun current(ctx: Context, gran: Money.Gran): List<Session> {
        val r = currentRange(gran)
        return sessions(ctx, r.first, r.second).reversed()
    }

    /** Time by task in the current stretch, biggest first. */
    fun byTask(ctx: Context, gran: Money.Gran): List<Pair<String, Int>> =
        current(ctx, gran)
            .groupBy { it.task }
            .map { Pair(it.key, it.value.sumOf { s -> s.minutes }) }
            .sortedByDescending { it.second }

    private fun dateText(d: LocalDate) = d.dayOfMonth.toString() + " " + MONTHS[d.monthValue - 1] + " " + d.year

    /** A plain-text report you can share or save. */
    fun report(ctx: Context, gran: Money.Gran): String {

        val today = LocalDate.now()
        val cur = current(ctx, gran)
        val total = cur.sumOf { it.minutes }
        val sb = StringBuilder()

        sb.append("Study time report, ").append(dateText(today)).append("\n\n")
        sb.append("Time ").append(title(gran)).append(": ").append(Num.short(total)).append("\n")

        val tasks = byTask(ctx, gran)

        if (tasks.isNotEmpty()) {
            sb.append("\nBy activity\n")
            for (t in tasks) {
                val pct = if (total == 0) 0 else t.second * 100 / total
                sb.append("  ").append(cap(t.first).padEnd(16)).append(Num.short(t.second).padEnd(8))
                    .append("(").append(pct).append("%)\n")
            }
        }

        if (cur.isNotEmpty()) {
            sb.append("\nSessions\n")
            for (s in cur) {
                sb.append("  ").append(s.date.dayOfMonth).append(" ").append(MONTHS[s.date.monthValue - 1]).append("  ")
                    .append(Num.short(s.minutes).padEnd(8)).append(cap(s.task))
                    .append(if (s.source == "timer") "  (timer)" else "").append("\n")
            }
        }

        sb.append("\n").append(chartTitle(gran)).append("\n")

        val series = bars(ctx, gran, today)
        val max = series.maxOfOrNull { it.value } ?: 0L

        for (bar in series) {
            val len = if (max == 0L) 0 else (bar.value * 12 / max).toInt()
            sb.append("  ").append(bar.label.padEnd(7)).append("█".repeat(len)).append(" ")
                .append(Num.short(bar.value.toInt())).append("\n")
        }

        return sb.toString()
    }

    // ---------------------------------------------------------------
    // Voice commands
    // ---------------------------------------------------------------

    sealed class Cmd {
        data class Log(val task: String, val minutes: Int, val endMs: Long?) : Cmd()
        data class Start(val task: String) : Cmd()
        data class Total(val task: String?, val span: String) : Cmd()
        data class Show(val gran: Money.Gran) : Cmd()
        data class Say(val text: String) : Cmd()
        object Stop : Cmd()
        object Status : Cmd()
        object Undo : Cmd()
    }

    private val SPAN = Span.WORDS

    private val L_LOG = Regex("""^(?:log|add|record|put) (.+?) (?:for|to|on|in|of|towards) (.+)$""")
    private val L_RANGE = Regex("""^(?:log|add|record) (.+?) from (.+?) (?:to|till|until) (.+)$""")
    private val L_DID = Regex(
        """^(?:i )?(?:studied|practised|practiced|worked on|worked|revised|read|learned|learnt|did) (.+?) (?:for|about) (.+)$"""
    )
    private val L_VERB = Regex("""^(?:i )?(studied|practised|practiced|worked|revised|read) (?:for|about) (.+)$""")
    private val L_SPENT = Regex(
        """^(?:i )?spent (.+?) (?:on|studying|doing|working on|practising|practicing|learning) (.+)$"""
    )
    private val L_SPENT_VERB = Regex(
        """^(?:i )?spent (.+?) (studying|practising|practicing|working|revising|reading)$"""
    )
    private val L_VERB_RANGE = Regex(
        """^(?:i )?(study|studied|practised|practiced|worked on|worked|revised|read|did) ?(.*?) from (.+?) (?:to|till|until) (.+)$"""
    )
    private val L_BARE_RANGE = Regex("""^(.+?) from (.+?) (?:to|till|until) (.+)$""")

    private val S_A = Regex("""^start (?:a |the |my )?(?:study )?(?:session|timer|stopwatch)(?: (?:for|on) (.+))?$""")
    private val S_B = Regex("""^(?:start|begin) (?:studying|practising|practicing|revising|working on|working) ?(.*)$""")
    private val S_C = Regex("""^(?:start|begin) (.+?) (?:session|timer)$""")

    private val STOP_A = Regex("""^(?:stop|end|finish) (?:the |my )?(?:study )?(?:session|timer|stopwatch)$""")
    private val STOP_B = Regex("""^(?:stop|finish|end) studying$|^(?:i am|i'm|im) done studying$|^done studying$""")
    private val STATUS = Regex(
        """^(?:timer status|study timer status|how long have i been studying|how long am i studying)$"""
    )

    private val T_HOW = Regex(
        """^how (?:long|much time) (?:did|have) i (?:study|studied|practise|practised|practice|practiced|work|worked|spend|spent)(?: on (.+?))?(?: ($SPAN))?$"""
    )
    private val T_WHAT = Regex("""^(?:what is|what's|whats) my (?:study|time|study time)(?: ($SPAN))?$""")
    private val SHOW_A = Regex(
        """^(?:show|open|display) (?:my )?(?:study|time|study time)(?: report| chart| log| tracker)?$"""
    )
    private val SHOW_B = Regex("""^(?:study|time) (?:report|chart|log)$""")
    private val UNDO = Regex("""^(?:undo|delete|remove|cancel) (?:the )?last (?:time )?(?:log|session|study session)$""")

    private val VERB_TASK = mapOf(
        "study" to "study", "studied" to "study", "practised" to "practice", "practiced" to "practice",
        "worked" to "work", "worked on" to "work", "revised" to "revision", "read" to "reading", "did" to "study"
    )

    private fun norm(raw: String): String =
        raw.lowercase()
            .replace(Regex("""[^a-z0-9.,:' ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun tidy(s: String): String =
        s.trim().removePrefix("my ").removePrefix("the ").removePrefix("some ").removePrefix("a ").trim()

    private fun toMinutes(hhmm: String): Int {
        val p = hhmm.split(":")
        return p[0].toInt() * 60 + p[1].toInt()
    }

    private fun clock(text: String): Int? = Mind.parseClock(text)?.let { toMinutes(it) }

    /** Length of a time range in minutes, allowing "11 to 1" and "9 pm to 1 am". Null if it makes no sense. */
    fun rangeLength(start: Int, end: Int): Int? {

        val len = when {
            end > start -> end - start
            end + 720 > start -> end + 720 - start
            else -> end + 1440 - start
        }

        return if (len in 1..960) len else null
    }

    // When a range that ended at this time of day last ended (today, or yesterday if that is still to come).
    private fun endMillis(endClock: Int): Long {

        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)

        var end = now.toLocalDate().atStartOfDay(zone).plusMinutes((endClock % 1440).toLong())

        if (end.isAfter(now)) end = end.minusDays(1)

        return end.toInstant().toEpochMilli()
    }

    private fun rangeCmd(task: String, from: String, to: String): Cmd? {

        val a = clock(from) ?: return null
        val b = clock(to) ?: return null
        val len = rangeLength(a, b) ?: return Cmd.Say("That time range doesn't look right. Try: study from 6 pm to 8 pm.")

        return Cmd.Log(tidy(task).ifEmpty { "study" }, len, endMillis(b))
    }

    /** Is [raw] a study-time command? */
    fun parse(raw: String): Cmd? {

        val t = norm(raw)
        if (t.isEmpty()) return null

        if (UNDO.matches(t)) return Cmd.Undo
        if (STATUS.matches(t)) return Cmd.Status
        if (STOP_A.matches(t) || STOP_B.matches(t)) return Cmd.Stop
        if (SHOW_A.matches(t) || SHOW_B.matches(t)) return Cmd.Show(Money.Gran.WEEK)

        T_HOW.matchEntire(t)?.let { m ->
            return Cmd.Total(m.groupValues[1].ifEmpty { null }, m.groupValues[2].ifEmpty { "today" })
        }
        T_WHAT.matchEntire(t)?.let { m ->
            return Cmd.Total(null, m.groupValues[1].ifEmpty { "today" })
        }

        // ---- the timer ----

        S_A.matchEntire(t)?.let { m ->
            val what = m.groupValues[1]
            // "start timer for 5 minutes" is a countdown, not a study timer.
            if (what.isNotEmpty() && Num.parseDuration(what) != null) return null
            return Cmd.Start(tidy(what).ifEmpty { "study" })
        }

        S_B.matchEntire(t)?.let { m -> return Cmd.Start(tidy(m.groupValues[1]).ifEmpty { "study" }) }

        S_C.matchEntire(t)?.let { m ->
            val what = m.groupValues[1]
            if (Num.parseDuration(what) != null) return null
            return Cmd.Start(tidy(what))
        }

        // ---- a time range ----

        L_RANGE.matchEntire(t)?.let { m ->
            val c = rangeCmd(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            if (c != null) return c
        }

        L_VERB_RANGE.matchEntire(t)?.let { m ->
            val task = m.groupValues[2].ifEmpty { VERB_TASK[m.groupValues[1]] ?: "study" }
            val c = rangeCmd(task, m.groupValues[3], m.groupValues[4])
            if (c != null) return c
        }

        L_BARE_RANGE.matchEntire(t)?.let { m ->
            // Only for your own routine activities, so "meeting from 3 to 4" is never taken.
            if (Routines.findTask(tidy(m.groupValues[1])) != null) {
                val c = rangeCmd(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                if (c != null) return c
            }
        }

        // ---- a length of time ----

        L_LOG.matchEntire(t)?.let { m ->
            val mins = Num.parseDuration(m.groupValues[1]) ?: return@let
            return Cmd.Log(tidy(m.groupValues[2]).ifEmpty { "study" }, mins, null)
        }

        L_DID.matchEntire(t)?.let { m ->
            val mins = Num.parseDuration(m.groupValues[2]) ?: return@let
            return Cmd.Log(tidy(m.groupValues[1]).ifEmpty { "study" }, mins, null)
        }

        L_VERB.matchEntire(t)?.let { m ->
            val mins = Num.parseDuration(m.groupValues[2]) ?: return@let
            return Cmd.Log(VERB_TASK[m.groupValues[1]] ?: "study", mins, null)
        }

        L_SPENT_VERB.matchEntire(t)?.let { m ->
            val mins = Num.parseDuration(m.groupValues[1]) ?: return@let
            val task = when (m.groupValues[2]) {
                "studying" -> "study"
                "practising", "practicing" -> "practice"
                "working" -> "work"
                "revising" -> "revision"
                else -> "reading"
            }
            return Cmd.Log(task, mins, null)
        }

        L_SPENT.matchEntire(t)?.let { m ->
            val mins = Num.parseDuration(m.groupValues[1]) ?: return@let
            return Cmd.Log(tidy(m.groupValues[2]).ifEmpty { "study" }, mins, null)
        }

        return null
    }
}
