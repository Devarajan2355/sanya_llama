package com.sanya.assistant

import android.content.Context
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Money: an offline spending and earning tracker you run by voice.
 *
 *   spent 120 on food          earned 500 from freelance        bought tea for 20
 *   how much did i spend today / this week / this month / on food this month
 *   what is my balance this month        what did i spend today
 *   set my monthly budget to 5000        undo last expense
 *   show my money report                 (opens the Money screen with charts)
 *
 * Entries are kept in files/money.log (private, on the phone only):
 *   time <tab> S or E <tab> paise <tab> category <tab> note
 * Amounts are whole paise (1/100 rupee), so totals never drift.
 *
 * Categories come from what you say ("tea" -> food). Teach her your own in the Brain file:
 *   category | tea, chai, snacks = food
 * Settings:  currency symbol = ₹     currency word = rupees     monthly budget = 5000
 */
object Money {

    data class Entry(
        val time: Long,
        val spent: Boolean,
        val paise: Long,
        val category: String,
        val note: String,
        val line: String
    ) {
        val date: LocalDate
            get() = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    enum class Gran { DAY, WEEK, MONTH }

    // ---------------------------------------------------------------
    // Currency and categories
    // ---------------------------------------------------------------

    fun symbol(): String = Brain.setting("currency symbol", "₹")

    fun word(): String = Brain.setting("currency word", "rupees")

    private val KEYWORDS: List<Pair<String, String>> = listOf(
        "food" to "tea coffee chai snack snacks lunch dinner breakfast food biryani juice meal meals " +
            "restaurant hotel groceries grocery vegetables vegetable milk fruits fruit tiffin sweets pizza burger",
        "transport" to "bus auto cab taxi petrol fuel diesel train metro ticket bike ola uber travel parking toll",
        "bills" to "electricity current water bill bills gas wifi internet broadband",
        "recharge" to "recharge data mobile sim dth",
        "rent" to "rent",
        "shopping" to "shopping clothes shirt shoes dress amazon flipkart bag gift",
        "health" to "medicine doctor hospital medical tablet pharmacy",
        "study" to "book books course fees fee exam stationery pen printout xerox college class",
        "entertainment" to "movie game games netflix subscription party outing ott",
        "salary" to "salary",
        "freelance" to "freelance project client",
        "pocket money" to "pocket allowance",
        "bonus" to "bonus",
        "refund" to "refund",
        "interest" to "interest"
    )

    private val WORD_CATEGORY: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for ((cat, words) in KEYWORDS) {
            for (w in words.split(" ")) if (w.isNotEmpty()) m[w] = cat
        }
        m
    }

    /** The category for what you said: your own Brain rules first, then built-in words, else "other". */
    fun categoryOf(note: String): String {

        val clean = Brain.clean(note)
        if (clean.isEmpty()) return "other"

        val mine = Brain.map("category")
        val toks = clean.split(" ")

        mine[clean]?.let { return Brain.clean(it) }
        for (t in toks) mine[t]?.let { return Brain.clean(it) }
        for (t in toks) WORD_CATEGORY[t]?.let { return it }

        return "other"
    }

    // ---------------------------------------------------------------
    // The log
    // ---------------------------------------------------------------

    private fun file(ctx: Context) = File(ctx.filesDir, "money.log")

    @Synchronized
    fun all(ctx: Context): List<Entry> {

        val out = ArrayList<Entry>()

        try {
            val f = file(ctx)
            if (!f.exists()) return out

            for (line in f.readLines()) {
                val p = line.split("\t")
                if (p.size < 5) continue
                val t = p[0].toLongOrNull() ?: continue
                val paise = p[2].toLongOrNull() ?: continue
                out.add(Entry(t, p[1] == "S", paise, p[3], p[4], line))
            }
        } catch (e: Exception) {
        }

        return out
    }

    fun entries(ctx: Context, from: LocalDate, to: LocalDate): List<Entry> =
        all(ctx).filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.sortedBy { it.time }

    @Synchronized
    fun record(ctx: Context, spent: Boolean, paise: Long, note: String, time: Long = System.currentTimeMillis()): Entry {

        val n = note.replace('\t', ' ').replace('\n', ' ').trim()
        val cat = categoryOf(n)
        val line = time.toString() + "\t" + (if (spent) "S" else "E") + "\t" + paise + "\t" + cat + "\t" + n

        file(ctx).appendText(line + "\n")

        return Entry(time, spent, paise, cat, n, line)
    }

    /** Remove one exact entry. */
    @Synchronized
    fun delete(ctx: Context, e: Entry): Boolean {

        val f = file(ctx)
        if (!f.exists()) return false

        val lines = f.readLines().toMutableList()
        val i = lines.indexOf(e.line)
        if (i < 0) return false

        lines.removeAt(i)
        f.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n")

        return true
    }

    private fun sum(list: List<Entry>, spent: Boolean): Long =
        list.filter { it.spent == spent }.sumOf { it.paise }

    // ---------------------------------------------------------------
    // Budget
    // ---------------------------------------------------------------

    fun budgetPaise(): Long {
        val v = Brain.setting("monthly budget", "").trim()
        val d = v.replace(",", "").toDoubleOrNull() ?: return 0L
        return Math.round(d * 100.0)
    }

    private fun monthSpent(ctx: Context, today: LocalDate = LocalDate.now()): Long =
        sum(entries(ctx, today.withDayOfMonth(1), today), true)

    private fun budgetWarning(before: Long, after: Long): String {

        val b = budgetPaise()
        if (b <= 0L) return ""

        val pb = before * 100 / b
        val pa = after * 100 / b

        return when {
            pb < 100 && pa >= 100 -> " You have passed your monthly budget."
            pb < 80 && pa >= 80 -> " Careful: that's $pa percent of your monthly budget."
            else -> ""
        }
    }

    fun setBudget(paise: Long): String {
        Brain.put("setting", "monthly budget", (paise / 100).toString())
        return "Okay. Your monthly budget is " + Num.spokenMoney(paise, word()) + "."
    }

    fun budgetStatus(ctx: Context): String {

        val b = budgetPaise()

        if (b <= 0L) {
            return "You haven't set a monthly budget. Say, for example: set my monthly budget to 5000."
        }

        val spent = monthSpent(ctx)
        val pct = spent * 100 / b
        val left = b - spent

        return "Your monthly budget is " + Num.spokenMoney(b, word()) + ". You've spent " +
            Num.spokenMoney(spent, word()) + " ($pct percent). " +
            if (left >= 0) {
                Num.spokenMoney(left, word()) + " left."
            } else {
                "You are over by " + Num.spokenMoney(-left, word()) + "."
            }
    }

    // ---------------------------------------------------------------
    // Saying things
    // ---------------------------------------------------------------

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    private fun spanName(span: String): String = when (span) {
        "today" -> "today"
        "yesterday" -> "yesterday"
        else -> span
    }

    fun add(ctx: Context, spent: Boolean, paise: Long, note: String): String {

        val today = LocalDate.now()
        val before = if (spent) monthSpent(ctx, today) else 0L

        val e = record(ctx, spent, paise, note)

        val list = entries(ctx, today, today)
        val money = Num.spokenMoney(paise, word())
        val about = if (e.note.isEmpty()) "" else (if (spent) " on " else " from ") + e.note

        return if (spent) {
            "Noted: spent $money$about. Today you've spent " + Num.spokenMoney(sum(list, true), word()) + "." +
                budgetWarning(before, before + paise)
        } else {
            "Noted: earned $money$about. Today you've earned " + Num.spokenMoney(sum(list, false), word()) + "."
        }
    }

    private fun matchesCategory(e: Entry, wanted: String): Boolean {

        val w = Brain.clean(wanted)
        if (w.isEmpty()) return true

        val mine = Brain.map("category")[w]?.let { Brain.clean(it) }

        return e.category == w ||
            (mine != null && e.category == mine) ||
            e.note.lowercase().contains(w)
    }

    fun total(ctx: Context, what: String, category: String?, span: String): String {

        val today = LocalDate.now()
        val r = Span.range(span, today)
        var list = entries(ctx, r.first, r.second)

        if (!category.isNullOrBlank()) list = list.filter { matchesCategory(it, category) }

        val spent = sum(list, true)
        val earned = sum(list, false)
        val on = if (category.isNullOrBlank()) "" else " on " + category.trim()
        val when_ = cap(spanName(span))

        return when (what) {

            "spent" ->
                if (spent == 0L) {
                    "You haven't spent anything$on ${spanName(span)}."
                } else {
                    "$when_ you spent " + Num.spokenMoney(spent, word()) + on + "."
                }

            "earned" ->
                if (earned == 0L) {
                    "You haven't earned anything ${spanName(span)}."
                } else {
                    "$when_ you earned " + Num.spokenMoney(earned, word()) + "."
                }

            else -> {
                val net = earned - spent
                "$when_: you earned " + Num.spokenMoney(earned, word()) + " and spent " +
                    Num.spokenMoney(spent, word()) + ". " +
                    if (net >= 0) {
                        "That leaves " + Num.spokenMoney(net, word()) + "."
                    } else {
                        "You are " + Num.spokenMoney(-net, word()) + " over."
                    }
            }
        }
    }

    fun recent(ctx: Context, span: String): String {

        val today = LocalDate.now()
        val r = Span.range(span, today)
        val list = entries(ctx, r.first, r.second).filter { it.spent }.takeLast(5).reversed()

        if (list.isEmpty()) return "You haven't spent anything ${spanName(span)}."

        val parts = list.map {
            Num.spokenMoney(it.paise, word()) + (if (it.note.isEmpty()) "" else " on " + it.note)
        }

        return "Latest: " + parts.joinToString(", ") + ". Total " + spanName(span) + ": " +
            Num.spokenMoney(sum(entries(ctx, r.first, r.second), true), word()) + "."
    }

    fun undoLast(ctx: Context): String {

        val last = all(ctx).maxByOrNull { it.time } ?: return "There is nothing to undo."

        delete(ctx, last)

        return "Removed: " + (if (last.spent) "spent " else "earned ") +
            Num.spokenMoney(last.paise, word()) + (if (last.note.isEmpty()) "" else " on " + last.note) + "."
    }

    // ---------------------------------------------------------------
    // For the Money screen
    // ---------------------------------------------------------------

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** The current day, week or month. */
    fun currentRange(gran: Gran, today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> = when (gran) {
        Gran.DAY -> Pair(today, today)
        Gran.WEEK -> Pair(weekStart(today), today)
        Gran.MONTH -> Pair(today.withDayOfMonth(1), today)
    }

    fun title(gran: Gran): String = when (gran) {
        Gran.DAY -> "today"
        Gran.WEEK -> "this week"
        Gran.MONTH -> "this month"
    }

    fun chartTitle(gran: Gran): String = when (gran) {
        Gran.DAY -> "Spent per day, last 14 days"
        Gran.WEEK -> "Spent per week, last 8 weeks"
        Gran.MONTH -> "Spent per month, last 12 months"
    }

    /** Oldest to newest: how much you spent in each day, week or month. */
    fun bars(ctx: Context, gran: Gran, today: LocalDate = LocalDate.now()): List<ValueBar> {

        val everything = all(ctx).filter { it.spent }
        val out = ArrayList<ValueBar>()

        val n = when (gran) {
            Gran.DAY -> 14
            Gran.WEEK -> 8
            Gran.MONTH -> 12
        }

        for (k in (n - 1) downTo 0) {

            val b: Triple<LocalDate, LocalDate, String> = when (gran) {
                Gran.DAY -> {
                    val d = today.minusDays(k.toLong())
                    Triple(d, d, d.dayOfMonth.toString())
                }
                Gran.WEEK -> {
                    val d = weekStart(today).minusWeeks(k.toLong())
                    Triple(d, d.plusDays(6), d.dayOfMonth.toString() + " " + MONTHS[d.monthValue - 1])
                }
                Gran.MONTH -> {
                    val ym = YearMonth.from(today).minusMonths(k.toLong())
                    Triple(ym.atDay(1), ym.atEndOfMonth(), MONTHS[ym.monthValue - 1])
                }
            }

            val total = everything.filter { !it.date.isBefore(b.first) && !it.date.isAfter(b.second) }.sumOf { it.paise }

            out.add(ValueBar(b.third, total / 100))
        }

        return out
    }

    /** Everything in the current day, week or month, newest first. */
    fun current(ctx: Context, gran: Gran): List<Entry> {
        val r = currentRange(gran)
        return entries(ctx, r.first, r.second).reversed()
    }

    /** Spending by category in the current stretch, biggest first. */
    fun breakdown(ctx: Context, gran: Gran): List<Pair<String, Long>> =
        current(ctx, gran)
            .filter { it.spent }
            .groupBy { it.category }
            .map { Pair(it.key, it.value.sumOf { e -> e.paise }) }
            .sortedByDescending { it.second }

    private fun dateText(d: LocalDate) = d.dayOfMonth.toString() + " " + MONTHS[d.monthValue - 1] + " " + d.year

    /** A plain-text report you can share or save. */
    fun report(ctx: Context, gran: Gran): String {

        val today = LocalDate.now()
        val cur = current(ctx, gran)
        val spent = sum(cur, true)
        val earned = sum(cur, false)
        val sym = symbol()
        val sb = StringBuilder()

        sb.append("Money report, ").append(dateText(today)).append("\n\n")
        sb.append("Spent ").append(title(gran)).append(": ").append(sym).append(Num.money(spent)).append("\n")
        sb.append("Earned ").append(title(gran)).append(": ").append(sym).append(Num.money(earned)).append("\n")
        sb.append("Net: ").append(sym).append(Num.money(earned - spent)).append("\n")

        val b = budgetPaise()
        if (b > 0L && gran == Gran.MONTH) {
            sb.append("Budget: ").append(sym).append(Num.money(b)).append(" (").append(spent * 100 / b).append("% used)\n")
        }

        val cats = breakdown(ctx, gran)

        if (cats.isNotEmpty()) {
            sb.append("\nBy category\n")
            for (c in cats) {
                val pct = if (spent == 0L) 0 else c.second * 100 / spent
                sb.append("  ").append(cap(c.first).padEnd(14)).append(sym).append(Num.money(c.second))
                    .append("  (").append(pct).append("%)\n")
            }
        }

        if (cur.isNotEmpty()) {
            sb.append("\nEntries\n")
            for (e in cur) {
                sb.append("  ").append(e.date.dayOfMonth).append(" ").append(MONTHS[e.date.monthValue - 1]).append("  ")
                    .append(if (e.spent) "-" else "+").append(sym).append(Num.money(e.paise)).append("  ")
                    .append(cap(e.category)).append(if (e.note.isEmpty()) "" else " (" + e.note + ")").append("\n")
            }
        }

        sb.append("\n").append(chartTitle(gran)).append("\n")
        val series = bars(ctx, gran, today)
        val max = series.maxOfOrNull { it.value } ?: 0L
        for (bar in series) {
            val len = if (max == 0L) 0 else (bar.value * 12 / max).toInt()
            sb.append("  ").append(bar.label.padEnd(7)).append("█".repeat(len)).append(" ").append(sym)
                .append(Num.grouped(bar.value)).append("\n")
        }

        return sb.toString()
    }

    // ---------------------------------------------------------------
    // Voice commands
    // ---------------------------------------------------------------

    sealed class Cmd {
        data class Add(val spent: Boolean, val paise: Long, val note: String) : Cmd()
        data class Total(val what: String, val category: String?, val span: String) : Cmd()
        data class Recent(val span: String) : Cmd()
        data class Show(val gran: Gran) : Cmd()
        data class Budget(val paise: Long?) : Cmd()
        object Undo : Cmd()
    }

    private const val CUR = "rupees|rupee|rs|inr|bucks"

    private val SPENT_A = Regex(
        """^(?:i )?(?:spent|paid|gave|used) (?:rs |inr )?(.+?)(?: (?:$CUR))?(?: (?:on|for|to|at|towards) (.+))?$"""
    )
    private val BUY = Regex(
        """^(?:i )?(?:bought|purchased|ordered) (.+?) (?:for|at) (?:rs |inr )?(.+?)(?: (?:$CUR))?$"""
    )
    private val EARN = Regex(
        """^(?:i )?(?:earned|received|got|made|won) (?:rs |inr )?(.+?)(?: (?:$CUR))?(?: (?:from|for|as|by|in) (.+))?$"""
    )
    // "received 2000 salary": income that names its source without "from"
    private val EARN_B = Regex(
        """^(?:i )?(?:received|earned|got) (?:rs |inr )?(.+?) (salary|bonus|refund|interest|stipend|allowance|pocket money|commission)$"""
    )
    private val EXP = Regex(
        """^(?:add )?(expense|income|spending|earning) (?:of )?(?:rs |inr )?(.+?)(?: (?:$CUR))?(?: (?:for|on|from) (.+))?$"""
    )

    private val SPAN = Span.WORDS

    private val Q_SPENT = Regex(
        """^how much (?:money )?(?:did i|have i|do i) (?:spend|spent)(?: on (.+?))?(?: ($SPAN))?$"""
    )
    private val Q_EARNED = Regex(
        """^how much (?:money )?(?:did i|have i) (?:earn|earned|get|got|receive|received|make|made)(?: ($SPAN))?$"""
    )
    private val Q_NET = Regex(
        """^(?:what is|what's|whats) my (?:balance|net|savings)(?: ($SPAN))?$"""
    )
    private val Q_RECENT = Regex(
        """^(?:what did i spend|list my (?:expenses|spending)|show my expenses|read my expenses)(?: on)?(?: ($SPAN))?$"""
    )
    private val SHOW_A = Regex(
        """^(?:show|open|display) (?:my )?(?:money|expenses?|spending|finances)(?: report| chart| tracker| screen)?$"""
    )
    private val SHOW_B = Regex("""^(?:money|expense|spending) (?:report|chart|tracker)$""")
    private val BUDGET_SET = Regex("""^set (?:my )?(?:monthly )?budget (?:to|as|at) (.+?)(?: (?:$CUR))?$""")
    private val BUDGET_Q = Regex(
        """^(?:what is|what's|whats) my (?:monthly )?budget$|^how is my budget$|^how much (?:budget )?(?:is )?left(?: in my budget)?$"""
    )
    private val UNDO = Regex(
        """^(?:undo|delete|remove|cancel) (?:the )?last (?:expense|entry|spending|income|transaction|payment)$"""
    )

    private fun norm(raw: String): String =
        raw.lowercase()
            .replace(Regex("""[^a-z0-9.,' ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    // An amount in paise from words or digits, or null if [text] is not a sensible amount.
    private fun amount(text: String): Long? {
        val v = Num.parse(text) ?: return null
        if (v <= 0.0 || v >= 1_000_000_000.0) return null
        return Math.round(v * 100.0)
    }

    private fun spanOr(group: String, fallback: String): String = if (group.isEmpty()) fallback else group

    /** Is [raw] a money command? */
    fun parse(raw: String): Cmd? {

        val t = norm(raw)
        if (t.isEmpty()) return null

        if (UNDO.matches(t)) return Cmd.Undo

        BUDGET_SET.matchEntire(t)?.let { m ->
            val a = amount(m.groupValues[1]) ?: return null
            return Cmd.Budget(a)
        }
        if (BUDGET_Q.matches(t)) return Cmd.Budget(null)

        if (SHOW_A.matches(t) || SHOW_B.matches(t)) return Cmd.Show(Gran.MONTH)

        Q_SPENT.matchEntire(t)?.let { m ->
            return Cmd.Total("spent", m.groupValues[1].ifEmpty { null }, spanOr(m.groupValues[2], "today"))
        }
        Q_EARNED.matchEntire(t)?.let { m ->
            return Cmd.Total("earned", null, spanOr(m.groupValues[1], "today"))
        }
        Q_NET.matchEntire(t)?.let { m ->
            return Cmd.Total("net", null, spanOr(m.groupValues[1], "this month"))
        }
        Q_RECENT.matchEntire(t)?.let { m ->
            return Cmd.Recent(spanOr(m.groupValues[1], "today"))
        }

        EXP.matchEntire(t)?.let { m ->
            val a = amount(m.groupValues[2]) ?: return@let
            val spent = m.groupValues[1] == "expense" || m.groupValues[1] == "spending"
            return Cmd.Add(spent, a, m.groupValues[3])
        }

        SPENT_A.matchEntire(t)?.let { m ->
            val a = amount(m.groupValues[1]) ?: return@let
            return Cmd.Add(true, a, m.groupValues[2])
        }

        BUY.matchEntire(t)?.let { m ->
            val a = amount(m.groupValues[2]) ?: return@let
            return Cmd.Add(true, a, m.groupValues[1])
        }

        EARN_B.matchEntire(t)?.let { m ->
            val a = amount(m.groupValues[1]) ?: return@let
            return Cmd.Add(false, a, m.groupValues[2])
        }

        EARN.matchEntire(t)?.let { m ->
            val a = amount(m.groupValues[1]) ?: return@let
            return Cmd.Add(false, a, m.groupValues[2])
        }

        return null
    }
}
