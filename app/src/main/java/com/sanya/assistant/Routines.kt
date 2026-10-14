package com.sanya.assistant

import android.content.Context
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/**
 * Routines: daily, weekly, monthly and yearly activities you want to keep up.
 *
 * The activities live in the Brain file, so you can edit them in the organised editor:
 *
 *     routine | morning walk = daily 06:30
 *     routine | gym         = weekly mon wed 18:00
 *     routine | clean room  = weekly                  (any day that week)
 *     routine | pay rent    = monthly 1
 *     routine | review      = monthly                 (any day that month)
 *     routine | insurance   = yearly 12 march
 *     routine | japanese    = daily 19:00 for 2h      (done once 2 hours are logged)
 *
 * What you finish is remembered in files/routine.log (separate from the Brain file,
 * so the history and the charts survive any edit to the activities).
 *
 * By voice:
 *   add morning walk to my daily routine at 6 30 am
 *   add japanese to my daily routine for 2 hours
 *   what are the remaining activities      (she reads them one by one)
 *   mark morning walk as done / mark gym as not done
 *   show my weekly routine                 (opens the chart screen)
 *   how is my routine                      (spoken summary)
 *
 * Study time logged by Study.kt counts toward an activity of the same name
 * (see creditTime).
 *
 * Proactive (Proactive.kt): a reminder at an activity's time, and an evening check.
 */
object Routines {

    // ---------------------------------------------------------------
    // Model
    // ---------------------------------------------------------------

    enum class Period(val word: String) {
        DAILY("daily"),
        WEEKLY("weekly"),
        MONTHLY("monthly"),
        YEARLY("yearly")
    }

    /**
     * When an activity is due.
     * weekdays      weekly: the days. Empty means any day that week.
     * dayOfMonth    monthly: the day. 0 means any day that month.
     * month, day    yearly: the date.
     * minutes       optional time of day (minutes after midnight), -1 for none.
     * target        optional minutes of logged study time that finish it, -1 for none.
     */
    data class Spec(
        val period: Period,
        val weekdays: Set<DayOfWeek> = emptySet(),
        val dayOfMonth: Int = 0,
        val month: Int = 0,
        val day: Int = 0,
        val minutes: Int = -1,
        val target: Int = -1
    ) {
        // Open-ended: due "sometime this week/month", not on a fixed day.
        val openEnded: Boolean
            get() = (period == Period.WEEKLY && weekdays.isEmpty()) ||
                (period == Period.MONTHLY && dayOfMonth == 0)
    }

    data class Task(val name: String, val spec: Spec, val line: String)

    /** One occurrence of a task: key identifies it in the log ("2026-10-10", "2026-W41", "2026-10"). */
    data class Instance(val task: Task, val key: String, val due: LocalDate)

    data class Today(val finished: List<Instance>, val remaining: List<Instance>)

    data class Bar(val label: String, val done: Int, val total: Int)

    data class Row(val inst: Instance, val done: Boolean, val label: String)

    // ---------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------

    private val DAY_NAMES = listOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
    )

    private val MONTH_NAMES = listOf(
        "january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december"
    )

    private fun two(n: Int) = n.toString().padStart(2, '0')

    private fun hhmm(m: Int) = two(m / 60) + ":" + two(m % 60)

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    private fun dayName(d: DayOfWeek) = DAY_NAMES[d.value - 1]

    private fun monthAbbr(m: Int) = cap(MONTH_NAMES[m - 1].take(3))

    fun clock12(m: Int): String {
        val h = m / 60
        val mi = m % 60
        val ap = if (h < 12) "am" else "pm"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return if (mi == 0) "$h12 $ap" else "$h12:${two(mi)} $ap"
    }

    private fun weekStart(d: LocalDate): LocalDate =
        d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun weekKey(d: LocalDate): String =
        d.get(IsoFields.WEEK_BASED_YEAR).toString() + "-W" +
            two(d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    private fun monthKey(d: LocalDate): String = d.year.toString() + "-" + two(d.monthValue)

    private fun weekday(tok: String): DayOfWeek? {
        if (tok.length < 3) return null
        val i = DAY_NAMES.indexOfFirst { it.startsWith(tok) }
        return if (i < 0) null else DayOfWeek.of(i + 1)
    }

    private fun toMinutes(hhmm: String): Int {
        val p = hhmm.split(":")
        return p[0].toInt() * 60 + p[1].toInt()
    }

    private fun norm(raw: String): String =
        raw.lowercase()
            .replace(Regex("""[^a-z0-9' ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun names(items: List<String>): String {

        val shown = items.take(5)
        val extra = items.size - shown.size

        val base = when (shown.size) {
            1 -> shown[0]
            2 -> shown[0] + " and " + shown[1]
            else -> shown.dropLast(1).joinToString(", ") + " and " + shown.last()
        }

        return if (extra > 0) "$base, and $extra more" else base
    }

    // ---------------------------------------------------------------
    // Reading and writing the schedule text:
    //   daily 06:30 | weekly mon wed | monthly 1 | yearly 12 march | daily 19:00 for 2h
    // ---------------------------------------------------------------

    private val TIME_TOKEN = Regex("""^(\d{1,2}):(\d{2})$""")
    private val DAY_TOKEN = Regex("""^(\d{1,2})(?:st|nd|rd|th)?$""")

    fun parseSpec(text: String): Spec? {

        var toks = text.lowercase().replace(",", " ").split(" ").filter { it.isNotEmpty() }
        if (toks.isEmpty()) return null

        // "daily 06:30 for 2h" -> a target of 120 minutes
        var target = -1
        val f = toks.indexOf("for")

        if (f >= 0) {
            target = Num.parseDuration(toks.drop(f + 1).joinToString(" ")) ?: return null
            toks = toks.take(f)
            if (toks.isEmpty()) return null
        }

        val period = Period.values().firstOrNull { it.word == toks[0] } ?: return null

        var minutes = -1
        val rest = ArrayList<String>()

        for (t in toks.drop(1)) {
            val m = TIME_TOKEN.matchEntire(t)
            if (m != null && minutes < 0) {
                val h = m.groupValues[1].toInt()
                val mi = m.groupValues[2].toInt()
                if (h > 23 || mi > 59) return null
                minutes = h * 60 + mi
            } else {
                rest.add(t)
            }
        }

        val spec: Spec? = when (period) {

            Period.DAILY ->
                if (rest.isEmpty()) Spec(Period.DAILY, minutes = minutes) else null

            Period.WEEKLY -> {
                val days = rest.filter { it != "and" }.map { weekday(it) }
                if (days.any { it == null }) {
                    null
                } else {
                    Spec(Period.WEEKLY, weekdays = days.filterNotNull().toSet(), minutes = minutes)
                }
            }

            Period.MONTHLY -> {
                if (rest.isEmpty()) {
                    Spec(Period.MONTHLY, minutes = minutes)
                } else {
                    val m = if (rest.size == 1) DAY_TOKEN.matchEntire(rest[0]) else null
                    val dom = m?.groupValues?.get(1)?.toInt() ?: 0
                    if (dom in 1..31) Spec(Period.MONTHLY, dayOfMonth = dom, minutes = minutes) else null
                }
            }

            Period.YEARLY -> {
                val dm = Mind.parseDM(rest.joinToString(" "))
                if (dm == null) null else Spec(Period.YEARLY, month = dm.second, day = dm.first, minutes = minutes)
            }
        }

        return spec?.copy(target = target)
    }

    // 90 -> "1h30m", 120 -> "2h", 45 -> "45m"  (the form parseSpec reads back)
    private fun targetText(m: Int): String {
        val h = m / 60
        val mi = m % 60
        return if (h == 0) "${mi}m" else if (mi == 0) "${h}h" else "${h}h${mi}m"
    }

    fun specText(s: Spec): String {

        val b = StringBuilder(s.period.word)

        when (s.period) {
            Period.WEEKLY ->
                for (d in s.weekdays.sortedBy { it.value }) b.append(' ').append(dayName(d).take(3))
            Period.MONTHLY ->
                if (s.dayOfMonth > 0) b.append(' ').append(s.dayOfMonth)
            Period.YEARLY ->
                b.append(' ').append(s.day).append(' ').append(MONTH_NAMES[s.month - 1])
            Period.DAILY -> {}
        }

        if (s.minutes >= 0) b.append(' ').append(hhmm(s.minutes))

        if (s.target > 0) b.append(" for ").append(targetText(s.target))

        return b.toString()
    }

    // "every day at 6:30 am", "on Monday and Wednesday at 6 pm", "any day this week" ...
    fun whenText(s: Spec): String {

        val base = when (s.period) {
            Period.DAILY -> "every day"
            Period.WEEKLY ->
                if (s.weekdays.isEmpty()) {
                    "any day this week"
                } else {
                    "on " + names(s.weekdays.sortedBy { it.value }.map { cap(dayName(it)) })
                }
            Period.MONTHLY ->
                if (s.dayOfMonth == 0) "any day this month" else "on day ${s.dayOfMonth} of the month"
            Period.YEARLY -> "on ${s.day} ${cap(MONTH_NAMES[s.month - 1])}"
        }

        val timed = if (s.minutes >= 0) base + " at " + clock12(s.minutes) else base

        return if (s.target > 0) timed + ", target " + Num.durText(s.target) else timed
    }

    // ---------------------------------------------------------------
    // Tasks (from the Brain file)
    // ---------------------------------------------------------------

    fun tasks(): List<Task> {

        val out = ArrayList<Task>()

        for (e in Brain.entries("routine")) {
            val spec = parseSpec(e.right) ?: continue
            val name = e.left.trim()
            if (name.isEmpty()) continue
            out.add(Task(name, spec, e.line))
        }

        return out
    }

    fun findTask(name: String): Task? {

        val q = Brain.clean(name)
        if (q.isEmpty()) return null

        val ts = tasks()
        if (ts.isEmpty()) return null

        val exact = ts.firstOrNull { Brain.clean(it.name) == q }
        if (exact != null) return exact

        var best: Task? = null
        var bestScore = 0.0

        for (t in ts) {
            val n = Brain.clean(t.name)
            var s = Mind.similarity(q, n)
            if (Mind.soundScore(q, n) >= 55) s = maxOf(s, 0.7)
            if (s > bestScore) {
                bestScore = s
                best = t
            }
        }

        return if (bestScore >= 0.6) best else null
    }

    // ---------------------------------------------------------------
    // When things are due
    // ---------------------------------------------------------------

    private fun dueOn(s: Spec, d: LocalDate): Boolean = when (s.period) {
        Period.DAILY -> true
        Period.WEEKLY -> s.weekdays.isNotEmpty() && d.dayOfWeek in s.weekdays
        Period.MONTHLY -> s.dayOfMonth > 0 && d.dayOfMonth == minOf(s.dayOfMonth, d.lengthOfMonth())
        Period.YEARLY ->
            d.monthValue == s.month &&
                (d.dayOfMonth == s.day ||
                    (s.month == 2 && s.day == 29 && !d.isLeapYear && d.dayOfMonth == 28))
    }

    // The occurrences of [t] that are live on day [d].
    fun activeOn(t: Task, d: LocalDate): List<Instance> {

        val s = t.spec

        return when {
            s.period == Period.WEEKLY && s.weekdays.isEmpty() ->
                listOf(Instance(t, weekKey(d), weekStart(d)))
            s.period == Period.MONTHLY && s.dayOfMonth == 0 ->
                listOf(Instance(t, monthKey(d), d.withDayOfMonth(1)))
            dueOn(s, d) ->
                listOf(Instance(t, d.toString(), d))
            else -> emptyList()
        }
    }

    // Every occurrence of [t] between two dates (open-ended ones sit on their first day).
    fun instancesIn(t: Task, from: LocalDate, to: LocalDate): List<Instance> {

        val s = t.spec
        val out = ArrayList<Instance>()

        if (s.openEnded) {
            if (s.period == Period.WEEKLY) {
                var w = weekStart(from)
                while (!w.isAfter(to)) {
                    out.add(Instance(t, weekKey(w), w))
                    w = w.plusWeeks(1)
                }
            } else {
                var m = from.withDayOfMonth(1)
                while (!m.isAfter(to)) {
                    out.add(Instance(t, monthKey(m), m))
                    m = m.plusMonths(1)
                }
            }
            return out
        }

        var d = from
        while (!d.isAfter(to)) {
            if (dueOn(s, d)) out.add(Instance(t, d.toString(), d))
            d = d.plusDays(1)
        }

        return out
    }

    // The last day an occurrence can still be done.
    private fun endOf(i: Instance): LocalDate {
        val s = i.task.spec
        return when {
            s.period == Period.WEEKLY && s.weekdays.isEmpty() -> i.due.plusDays(6)
            s.period == Period.MONTHLY && s.dayOfMonth == 0 -> i.due.withDayOfMonth(i.due.lengthOfMonth())
            else -> i.due
        }
    }

    // ---------------------------------------------------------------
    // What you have finished (files/routine.log)
    //   D <tab> task id <tab> key <tab> 1 or 0     finished / not finished
    //   S <tab> task id <tab> date                 first day she knew about the task
    //   R <tab> task id <tab> date                 a reminder was already spoken that day
    // ---------------------------------------------------------------

    private val done = HashMap<String, Boolean>()
    private val seen = HashMap<String, String>()
    private val reminded = HashSet<String>()
    private val credited = HashSet<String>()      // done because logged time reached it, not by hand
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "routine.log")

    private fun idOf(t: Task) = Brain.clean(t.name) + "|" + t.spec.period.word

    private fun load(ctx: Context) {

        if (loaded) return
        loaded = true

        try {
            val f = file(ctx)
            if (!f.exists()) return
            for (line in f.readLines()) {
                val p = line.split("\t")
                when (p[0]) {
                    "D" -> if (p.size >= 4) done[p[1] + "\t" + p[2]] = p[3] == "1"
                    "S" -> if (p.size >= 3) seen[p[1]] = p[2]
                    "R" -> if (p.size >= 3) reminded.add(p[1] + "\t" + p[2])
                    "C" -> if (p.size >= 3) credited.add(p[1] + "\t" + p[2])
                    "U" -> if (p.size >= 3) credited.remove(p[1] + "\t" + p[2])
                }
            }
        } catch (e: Exception) {
            // A damaged log just means an empty history.
        }
    }

    private fun append(ctx: Context, line: String) {
        try {
            file(ctx).appendText(line + "\n")
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun isDone(ctx: Context, i: Instance): Boolean {
        load(ctx)
        return done[idOf(i.task) + "\t" + i.key] == true
    }

    @Synchronized
    fun setDone(ctx: Context, i: Instance, value: Boolean) {
        load(ctx)
        val id = idOf(i.task)
        done[id + "\t" + i.key] = value
        append(ctx, "D\t" + id + "\t" + i.key + "\t" + (if (value) "1" else "0"))

        // Marking it yourself (either way) means it is no longer "done by logged time".
        if (credited.remove(id + "\t" + i.key)) append(ctx, "U\t" + id + "\t" + i.key)
    }

    @Synchronized
    private fun markCredited(ctx: Context, i: Instance) {
        load(ctx)
        val id = idOf(i.task)
        if (credited.add(id + "\t" + i.key)) append(ctx, "C\t" + id + "\t" + i.key)
    }

    // The first day she knew about this task. History before that is not counted against you.
    @Synchronized
    private fun seenDate(ctx: Context, t: Task, today: LocalDate): LocalDate {

        load(ctx)

        val id = idOf(t)
        val s = seen[id]

        if (s != null) {
            try {
                return LocalDate.parse(s)
            } catch (e: Exception) {
            }
        }

        seen[id] = today.toString()
        append(ctx, "S\t" + id + "\t" + today.toString())

        return today
    }

    @Synchronized
    private fun wasReminded(ctx: Context, i: Instance, date: LocalDate): Boolean {
        load(ctx)
        return reminded.contains(idOf(i.task) + "\t" + date.toString())
    }

    @Synchronized
    fun markReminded(ctx: Context, i: Instance, date: LocalDate = LocalDate.now()) {
        load(ctx)
        val id = idOf(i.task)
        if (reminded.add(id + "\t" + date.toString())) {
            append(ctx, "R\t" + id + "\t" + date.toString())
        }
    }

    // ---------------------------------------------------------------
    // Study time counts toward an activity (called by Study.log)
    // ---------------------------------------------------------------

    /**
     * Counts logged time toward the routine activity of that name.
     * No target: done as soon as time is logged. With a target: done once the day's
     * (or week's / month's, for open-ended ones) time reaches it.
     * Returns what she should add to her reply, or null.
     */
    @Synchronized
    fun creditTime(ctx: Context, task: String, date: LocalDate): String? {

        val t = findTask(task) ?: return null
        val live = activeOn(t, date)
        if (live.isEmpty()) return null

        val i = live[0]
        if (isDone(ctx, i)) return null

        val target = t.spec.target

        if (target <= 0) {
            setDone(ctx, i, true)
            markCredited(ctx, i)
            return "That counts toward ${t.name}, so I marked it done."
        }

        val minutes = Study.minutesIn(ctx, task, i.due, endOf(i))

        if (minutes >= target) {
            setDone(ctx, i, true)
            markCredited(ctx, i)
            return "${t.name} target reached: ${Num.durText(minutes)} of ${Num.durText(target)}. Marked done."
        }

        return "${t.name}: ${Num.durText(minutes)} of ${Num.durText(target)} so far, " +
            Num.durText(target - minutes) + " to go."
    }

    // ---------------------------------------------------------------
    // Today
    // ---------------------------------------------------------------

    /**
     * Called after logged time is deleted. If the activity was marked done only because of
     * that time, and the time left no longer earns it, it is un-marked. Something you
     * marked by hand is never touched. Returns what she should say, or null.
     */
    @Synchronized
    fun reviewTime(ctx: Context, task: String, date: LocalDate): String? {

        val t = findTask(task) ?: return null
        val live = activeOn(t, date)
        if (live.isEmpty()) return null

        val i = live[0]

        load(ctx)

        if (!credited.contains(idOf(t) + "\t" + i.key) || !isDone(ctx, i)) return null

        val target = t.spec.target
        val minutes = Study.minutesIn(ctx, task, i.due, endOf(i))
        val enough = if (target <= 0) minutes > 0 else minutes >= target

        if (enough) return null

        setDone(ctx, i, false)

        return "${t.name} is no longer marked done, because that time was removed."
    }

    private fun byTime(a: Instance, b: Instance): Int {
        val ta = if (a.task.spec.minutes >= 0) a.task.spec.minutes else 99999
        val tb = if (b.task.spec.minutes >= 0) b.task.spec.minutes else 99999
        return ta.compareTo(tb)
    }

    @Synchronized
    fun today(ctx: Context, date: LocalDate = LocalDate.now()): Today {

        val fin = ArrayList<Instance>()
        val rem = ArrayList<Instance>()

        for (t in tasks()) {
            for (i in activeOn(t, date)) {
                seenDate(ctx, t, date)
                if (isDone(ctx, i)) fin.add(i) else rem.add(i)
            }
        }

        val order = Comparator<Instance> { a, b -> byTime(a, b) }

        return Today(fin.sortedWith(order), rem.sortedWith(order))
    }

    fun spoken(i: Instance): String =
        i.task.name + (if (i.task.spec.minutes >= 0) " at " + clock12(i.task.spec.minutes) else "")

    fun todayLine(ctx: Context, date: LocalDate = LocalDate.now()): String {

        val t = today(ctx, date)
        val total = t.finished.size + t.remaining.size

        if (total == 0) return "You have no routine activities today."

        if (t.remaining.isEmpty()) {
            return "All $total activities are finished today. Well done."
        }

        val fin =
            if (t.finished.isEmpty()) {
                "Nothing is finished yet."
            } else {
                "Finished: " + names(t.finished.map { it.task.name }) + "."
            }

        return "Today you have $total activities. $fin " +
            "Remaining: " + names(t.remaining.map { it.task.name }) + "."
    }

    /** One sentence for the morning brief, or "". */
    fun briefLine(ctx: Context): String {

        val t = today(ctx)
        val total = t.finished.size + t.remaining.size

        if (total == 0) return ""

        return if (t.remaining.isEmpty()) {
            "All $total routine activities are finished today."
        } else {
            "You have ${t.remaining.size} routine activities to do today."
        }
    }

    // ---------------------------------------------------------------
    // Counting over a stretch of days
    // ---------------------------------------------------------------

    // (finished, total) of the given tasks' occurrences between two dates, up to today.
    private fun count(ctx: Context, ts: List<Task>, from: LocalDate, to: LocalDate, today: LocalDate): Pair<Int, Int> {

        var d = 0
        var total = 0

        for (t in ts) {

            val since = seenDate(ctx, t, today)

            for (i in instancesIn(t, from, to)) {
                if (i.due.isAfter(today)) continue          // not due yet
                if (endOf(i).isBefore(since)) continue      // before she knew about this task
                total++
                if (isDone(ctx, i)) d++
            }
        }

        return Pair(d, total)
    }

    private fun percent(done: Int, total: Int): Int = if (total == 0) 0 else done * 100 / total

    private fun periodLine(ctx: Context, from: LocalDate, to: LocalDate, today: LocalDate): String {

        val c = count(ctx, tasks(), from, to, today)

        return if (c.second == 0) {
            "nothing was scheduled."
        } else {
            "${c.first} of ${c.second} activities finished (${percent(c.first, c.second)} percent)."
        }
    }

    // What she says in the evening: today, plus the week on Sundays and the month on its last day.
    @Synchronized
    fun eveningText(ctx: Context, date: LocalDate = LocalDate.now()): String {

        val t = today(ctx, date)
        var s = todayLine(ctx, date)

        if (t.remaining.isNotEmpty()) {
            s += " Say what is remaining, and I'll go through them with you."
        }

        if (date.dayOfWeek == DayOfWeek.SUNDAY) {
            s += " This week: " + periodLine(ctx, weekStart(date), date, date)
        }

        if (date == YearMonth.from(date).atEndOfMonth()) {
            s += " This month: " + periodLine(ctx, date.withDayOfMonth(1), date, date)
        }

        return s
    }

    // ---------------------------------------------------------------
    // Proactive hooks (used by Proactive.kt)
    // ---------------------------------------------------------------

    /** Minutes after midnight for the evening check, or null if switched off. */
    fun checkMinutes(): Int? {

        val raw = Brain.setting("routine check", "20:00").trim()

        val m = TIME_TOKEN.matchEntire(raw) ?: return null

        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()

        return if (h > 23 || mi > 59) null else h * 60 + mi
    }

    /** The evening message if it is time and there is something scheduled today, else null. */
    fun eveningDue(ctx: Context, minutesNow: Int, date: LocalDate = LocalDate.now()): String? {

        val at = checkMinutes() ?: return null

        if (minutesNow < at || minutesNow >= at + 90) return null

        val t = today(ctx, date)

        if (t.finished.isEmpty() && t.remaining.isEmpty()) return null

        return eveningText(ctx, date)
    }

    /** An unfinished activity whose time is now (within 45 minutes) and not yet reminded. */
    fun reminder(ctx: Context, minutesNow: Int, date: LocalDate = LocalDate.now()): Instance? {

        for (i in today(ctx, date).remaining) {

            val m = i.task.spec.minutes

            if (m < 0 || i.due != date) continue
            if (minutesNow < m || minutesNow >= m + 45) continue
            if (wasReminded(ctx, i, date)) continue

            return i
        }

        return null
    }

    // ---------------------------------------------------------------
    // Charts and lists for the screen
    // ---------------------------------------------------------------

    private fun bucket(period: Period, today: LocalDate, k: Int): Triple<LocalDate, LocalDate, String> =
        when (period) {
            Period.DAILY -> {
                val d = today.minusDays(k.toLong())
                Triple(d, d, d.dayOfMonth.toString())
            }
            Period.WEEKLY -> {
                val from = weekStart(today).minusWeeks(k.toLong())
                Triple(from, from.plusDays(6), from.dayOfMonth.toString() + " " + monthAbbr(from.monthValue))
            }
            Period.MONTHLY -> {
                val ym = YearMonth.from(today).minusMonths(k.toLong())
                Triple(ym.atDay(1), ym.atEndOfMonth(), monthAbbr(ym.monthValue))
            }
            Period.YEARLY -> {
                val y = today.year - k
                Triple(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), y.toString())
            }
        }

    private fun barCount(period: Period): Int = when (period) {
        Period.DAILY -> 14
        Period.WEEKLY -> 8
        Period.MONTHLY -> 12
        Period.YEARLY -> 5
    }

    fun chartTitle(period: Period): String = when (period) {
        Period.DAILY -> "Last 14 days"
        Period.WEEKLY -> "Last 8 weeks"
        Period.MONTHLY -> "Last 12 months"
        Period.YEARLY -> "Last 5 years"
    }

    /** Oldest to newest: how much of that stretch's activities (of this kind) you finished. */
    @Synchronized
    fun series(ctx: Context, period: Period, today: LocalDate = LocalDate.now()): List<Bar> {

        val ts = tasks().filter { it.spec.period == period }
        val bars = ArrayList<Bar>()

        for (k in (barCount(period) - 1) downTo 0) {
            val b = bucket(period, today, k)
            val c = count(ctx, ts, b.first, b.second, today)
            bars.add(Bar(b.third, c.first, c.second))
        }

        return bars
    }

    private fun rowLabel(i: Instance): String {

        val s = i.task.spec
        val time = if (s.minutes >= 0) ", " + clock12(s.minutes) else ""

        return when (s.period) {
            Period.DAILY -> i.task.name + (if (s.minutes >= 0) " (" + clock12(s.minutes) + ")" else "")
            Period.WEEKLY ->
                if (s.weekdays.isEmpty()) {
                    i.task.name + " (any day this week)"
                } else {
                    i.task.name + " (" + cap(dayName(i.due.dayOfWeek).take(3)) + " " + i.due.dayOfMonth + time + ")"
                }
            Period.MONTHLY ->
                if (s.dayOfMonth == 0) {
                    i.task.name + " (any day this month)"
                } else {
                    i.task.name + " (" + i.due.dayOfMonth + " " + monthAbbr(i.due.monthValue) + time + ")"
                }
            Period.YEARLY ->
                i.task.name + " (" + i.due.dayOfMonth + " " + monthAbbr(i.due.monthValue) + ")"
        }
    }

    /** The activities of this kind in the current day, week, month or year. */
    @Synchronized
    fun rows(ctx: Context, period: Period, today: LocalDate = LocalDate.now()): List<Row> {

        val range: Pair<LocalDate, LocalDate> = when (period) {
            Period.DAILY -> Pair(today, today)
            Period.WEEKLY -> Pair(weekStart(today), weekStart(today).plusDays(6))
            Period.MONTHLY -> Pair(today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()))
            Period.YEARLY -> Pair(LocalDate.of(today.year, 1, 1), LocalDate.of(today.year, 12, 31))
        }

        val out = ArrayList<Row>()

        for (t in tasks().filter { it.spec.period == period }) {
            seenDate(ctx, t, today)
            for (i in instancesIn(t, range.first, range.second)) {
                out.add(Row(i, isDone(ctx, i), rowLabel(i)))
            }
        }

        return out.sortedWith(compareBy({ it.inst.due }, { it.inst.task.name }))
    }

    private fun bar10(pct: Int): String {
        val filled = (pct + 5) / 10
        return "█".repeat(filled) + "░".repeat(10 - filled)
    }

    private fun dateText(d: LocalDate) = d.dayOfMonth.toString() + " " + monthAbbr(d.monthValue) + " " + d.year

    /** A plain-text report you can share or save. */
    @Synchronized
    fun report(ctx: Context, period: Period, today: LocalDate = LocalDate.now()): String {

        val rows = rows(ctx, period, today)
        val bars = series(ctx, period, today)
        val sb = StringBuilder()

        sb.append(cap(period.word)).append(" routine report, ").append(dateText(today)).append("\n\n")

        if (rows.isEmpty()) {
            sb.append("No ").append(period.word).append(" activities yet.\n")
        } else {
            val d = rows.count { it.done }
            sb.append("This ").append(
                when (period) {
                    Period.DAILY -> "day"
                    Period.WEEKLY -> "week"
                    Period.MONTHLY -> "month"
                    Period.YEARLY -> "year"
                }
            ).append(": ").append(d).append(" of ").append(rows.size).append(" finished\n")
            for (r in rows) sb.append(if (r.done) "  [x] " else "  [ ] ").append(r.label).append("\n")
        }

        sb.append("\n").append(chartTitle(period)).append("\n")

        for (b in bars) {
            sb.append("  ").append(b.label.padEnd(7))
            if (b.total == 0) {
                sb.append("no activities\n")
            } else {
                val p = percent(b.done, b.total)
                sb.append(bar10(p)).append("  ").append(p.toString().padStart(3)).append("%  (")
                    .append(b.done).append("/").append(b.total).append(")\n")
            }
        }

        return sb.toString()
    }

    // ---------------------------------------------------------------
    // Changing things (used by the voice commands)
    // ---------------------------------------------------------------

    fun add(name: String, spec: String): String {

        val key = Brain.clean(name)

        if (key.isEmpty()) return "What should I call it?"

        val parsed = parseSpec(spec) ?: return "I couldn't read that schedule."

        val existing = tasks().firstOrNull { Brain.clean(it.name) == key }

        Brain.put("routine", key, spec)

        val verb = if (existing != null) "Updated" else "Added"

        return "$verb $key: ${whenText(parsed)}."
    }

    fun remove(name: String): String {

        val t = findTask(name) ?: return "I couldn't find $name in your routines."

        Brain.replaceLine(t.line, null)

        return "Removed ${t.name} from your routines."
    }

    /** Mark an activity finished or not finished for today (or this week/month for open-ended ones). */
    @Synchronized
    fun mark(ctx: Context, name: String, value: Boolean): String {

        val t = findTask(name) ?: return "I couldn't find $name in your routines."

        val date = LocalDate.now()
        val live = activeOn(t, date)

        if (live.isEmpty()) return "${t.name} isn't due today."

        for (i in live) setDone(ctx, i, value)

        val now = today(ctx, date)
        val total = now.finished.size + now.remaining.size

        val head = "Marked ${t.name} as " + (if (value) "finished" else "not finished") + "."

        return if (now.remaining.isEmpty()) {
            "$head All $total activities are finished today. Well done."
        } else {
            "$head ${now.finished.size} of $total finished. Remaining: " +
                names(now.remaining.map { it.task.name }) + "."
        }
    }

    /** What is in this routine, in words. */
    fun describe(period: Period): String {

        val ts = tasks().filter { it.spec.period == period }

        if (ts.isEmpty()) {
            return "Your ${period.word} routine is empty. Say, for example: add morning walk to my " +
                "${period.word} routine."
        }

        return "Your ${period.word} routine: " +
            names(ts.map { it.name + " " + whenText(it.spec) }) + "."
    }

    fun setCheck(minutes: Int?): String {

        return if (minutes == null) {
            Brain.put("setting", "routine check", "off")
            "Okay. I won't check your routine in the evening."
        } else {
            Brain.put("setting", "routine check", hhmm(minutes))
            Brain.put("setting", "proactive", "on")
            "Okay. I'll check your routine every day at ${clock12(minutes)}."
        }
    }

    // ---------------------------------------------------------------
    // Voice commands
    // ---------------------------------------------------------------

    sealed class Cmd {
        data class Add(val name: String, val spec: String) : Cmd()
        data class Remove(val name: String) : Cmd()
        data class Mark(val name: String, val done: Boolean) : Cmd()
        data class Show(val period: Period) : Cmd()
        data class Describe(val period: Period) : Cmd()
        data class CheckAt(val minutes: Int?) : Cmd()
        data class Say(val text: String) : Cmd()
        object Remaining : Cmd()
        object TodayStatus : Cmd()
    }

    private const val P = "daily|weekly|monthly|yearly"

    private val CHECK_SET = Regex("""^(?:set )?(?:my )?routine check (?:at|for|to) (.+)$""")
    private val CHECK_OFF = Regex("""^(?:stop|turn off|cancel) (?:my )?routine check$""")

    private val SHOW_A = Regex(
        """^(?:show|open|display|see|give) (?:me )?(?:my )?($P) routine(?: chart| report| progress| file)?$"""
    )
    private val SHOW_B = Regex("""^(?:my )?($P) routine (?:chart|report|progress|file)$""")
    private val SHOW_C = Regex("""^what(?:'s| is) my ($P) routine$""")
    private val SHOW_D = Regex("""^(?:show|open|display) (?:my )?routines?$""")
    private val DESCRIBE = Regex("""^(?:read|list|tell me) my ($P) routine$""")

    private val REMAINING = Regex(
        """^(?:what(?:'s| is| are| do i have)? (?:the |my )?(?:remaining|pending|left)(?: (?:activities|activity|tasks|task|routines|routine|items))?(?: today| now)?|what(?:'s| is) left(?: today)?|what do i have left(?: today)?|(?:remaining|pending) (?:activities|activity|tasks|routines)(?: today)?)$"""
    )

    private val STATUS = Regex(
        """^(?:how is my routine|how are my routines|routine status|what is my routine today|today'?s routine|my routine today|routine today|how am i doing today|my routine)$"""
    )

    private val ADD_A = Regex(
        """^add (?:a |an )?($P) (?:routine|task|activity|habit)(?: called| named)? (.+)$"""
    )
    private val ADD_B = Regex(
        """^add (.+?) to my ($P) (?:routine|routines|tasks|activities)(?: (.+))?$"""
    )

    private val REMOVE_A = Regex("""^(?:remove|delete|drop) (.+?) from my (?:(?:$P) )?routine$""")
    private val REMOVE_B = Regex("""^(?:remove|delete|drop) (?:the )?routine (.+)$""")

    private val UNDONE_A = Regex(
        """^(?:mark )?(.+?) (?:as )?(?:unfinished|not done|undone|not finished|not completed|pending|incomplete)$"""
    )
    private val UNDONE_B = Regex(
        """^(?:i )?(?:did not|didn't|didnt|have not|haven't|havent) (?:finish|finished|complete|completed|do|done) (.+)$"""
    )
    private val UNDONE_C = Regex("""^unmark (.+)$""")

    private val DONE_A = Regex("""^(?:mark )?(.+?) (?:as )?(?:done|finished|completed|complete)$""")
    private val DONE_B = Regex("""^(?:i )?(?:have |just )?(?:finished|completed|done) (.+)$""")

    private fun period(word: String): Period = Period.values().first { it.word == word }

    private fun tidyName(s: String): String =
        s.trim().removePrefix("my ").removePrefix("the ").removePrefix("a ").removeSuffix(" is").trim()

    // "morning walk at 6 30 am" -> ("morning walk", "at 6 30 am")
    // "japanese for 2 hours"    -> ("japanese", "for 2 hours")
    private fun splitSchedule(text: String): Pair<String, String> {
        val m = Regex("""^(.+?) ((?:at|on|every|each|for) .+)$""").matchEntire(text)
        return if (m == null) Pair(text, "") else Pair(m.groupValues[1], m.groupValues[2])
    }

    // "on monday and wednesday at 6 pm for 2 hours" -> a schedule, or null if it can't be read.
    private fun buildSpec(period: Period, sched: String): Spec? {

        var rest = Mind.numWords(sched.trim())
        var target = -1

        // "for 2 hours" can come before or after "at 6 pm"
        val fm = Regex("""(?:^|\s)for (.+?)(?=\s+at\s|$)""").find(rest)

        if (fm != null) {
            target = Num.parseDuration(fm.groupValues[1]) ?: return null
            rest = (rest.substring(0, fm.range.first) + " " + rest.substring(fm.range.last + 1)).trim()
        }

        var dayPart = rest
        var minutes = -1

        val at = Regex("""(?:^|\s)at (.+)$""").find(rest)

        if (at != null) {
            val clock = Mind.parseClock(at.groupValues[1]) ?: return null
            minutes = toMinutes(clock)
            dayPart = rest.substring(0, at.range.first).trim()
        }

        dayPart = dayPart.removePrefix("on ").removePrefix("every ").removePrefix("each ").trim()

        val spec: Spec? = when (period) {

            Period.DAILY ->
                if (dayPart.isEmpty() || dayPart == "day") Spec(Period.DAILY, minutes = minutes) else null

            Period.WEEKLY -> {
                val toks = dayPart.replace(",", " ").split(" ").filter { it.isNotEmpty() && it != "and" }
                val days = toks.map { weekday(it) }
                if (days.any { it == null }) null
                else Spec(Period.WEEKLY, weekdays = days.filterNotNull().toSet(), minutes = minutes)
            }

            Period.MONTHLY -> {
                if (dayPart.isEmpty() || dayPart == "month") {
                    Spec(Period.MONTHLY, minutes = minutes)
                } else {
                    val m = Regex("""^(?:the )?(\d{1,2})(?:st|nd|rd|th)?(?: of (?:the |every )?month)?$""")
                        .matchEntire(dayPart)
                    val dom = m?.groupValues?.get(1)?.toInt() ?: 0
                    if (dom in 1..31) Spec(Period.MONTHLY, dayOfMonth = dom, minutes = minutes) else null
                }
            }

            Period.YEARLY -> {
                val dm = Mind.parseDM(dayPart)
                if (dm == null) null else Spec(Period.YEARLY, month = dm.second, day = dm.first, minutes = minutes)
            }
        }

        return spec?.copy(target = target)
    }

    // text = what comes after "add ..." or before "to my daily routine"
    private fun makeAdd(period: Period, text: String, extraSched: String): Cmd {

        val split = splitSchedule(text)

        var name = split.first
        var sched = (split.second + " " + extraSched).trim()

        var spec: Spec? = if (sched.isEmpty()) null else buildSpec(period, sched)

        // The words after "at/on/every/for" were part of the name ("yoga at home"), not a schedule.
        if (sched.isNotEmpty() && spec == null) {
            name = (text + " " + extraSched).trim()
            sched = ""
        }

        if (spec == null && sched.isEmpty()) {
            spec = when (period) {
                Period.DAILY -> Spec(Period.DAILY)
                Period.WEEKLY -> Spec(Period.WEEKLY)
                Period.MONTHLY -> Spec(Period.MONTHLY)
                Period.YEARLY -> null
            }
        }

        if (spec == null) {
            return Cmd.Say("Which date should I use? Say, for example: add pay insurance to my yearly routine on 12 march.")
        }

        val clean = tidyName(name)

        if (clean.isEmpty()) return Cmd.Say("What should I call it?")

        return Cmd.Add(clean, specText(spec))
    }

    /** Is [raw] a routine command? Returns what to do, or null. */
    fun parse(raw: String): Cmd? {

        val t = norm(raw)
        if (t.isEmpty()) return null

        if (CHECK_OFF.matches(t)) return Cmd.CheckAt(null)

        CHECK_SET.matchEntire(t)?.let { m ->
            val clock = Mind.parseClock(m.groupValues[1])
                ?: return Cmd.Say("I didn't catch the time. Say it like: routine check at 8 pm.")
            return Cmd.CheckAt(toMinutes(clock))
        }

        SHOW_A.matchEntire(t)?.let { return Cmd.Show(period(it.groupValues[1])) }
        SHOW_B.matchEntire(t)?.let { return Cmd.Show(period(it.groupValues[1])) }
        SHOW_C.matchEntire(t)?.let { return Cmd.Show(period(it.groupValues[1])) }
        if (SHOW_D.matches(t)) return Cmd.Show(Period.DAILY)

        DESCRIBE.matchEntire(t)?.let { return Cmd.Describe(period(it.groupValues[1])) }

        if (REMAINING.matches(t)) return Cmd.Remaining
        if (STATUS.matches(t)) return Cmd.TodayStatus

        ADD_A.matchEntire(t)?.let { m ->
            return makeAdd(period(m.groupValues[1]), m.groupValues[2], "")
        }
        ADD_B.matchEntire(t)?.let { m ->
            return makeAdd(period(m.groupValues[2]), m.groupValues[1], m.groupValues[3])
        }

        REMOVE_A.matchEntire(t)?.let { return Cmd.Remove(tidyName(it.groupValues[1])) }
        REMOVE_B.matchEntire(t)?.let { return Cmd.Remove(tidyName(it.groupValues[1])) }

        // "mark X as not done" must be tried before "X done", and only counts if X is one of your activities.
        for (rx in listOf(UNDONE_A, UNDONE_B, UNDONE_C)) {
            val m = rx.matchEntire(t) ?: continue
            val name = tidyName(m.groupValues[1])
            if (findTask(name) != null) return Cmd.Mark(name, false)
        }

        for (rx in listOf(DONE_A, DONE_B)) {
            val m = rx.matchEntire(t) ?: continue
            val name = tidyName(m.groupValues[1])
            if (findTask(name) != null) return Cmd.Mark(name, true)
        }

        return null
    }
}
