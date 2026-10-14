package com.sanya.assistant

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Num: numbers and durations the way you say them, shared by the money tracker, the study
 * timer and routine targets.
 *
 *   Num.parse("one hundred and twenty")   -> 120.0
 *   Num.parse("two thousand five hundred") -> 2500.0
 *   Num.parse("1,200") / ("1.5k") / ("five lakh") -> 1200.0 / 1500.0 / 500000.0
 *   Num.parseDuration("an hour and a half") -> 90   (minutes)
 *   Num.parseDuration("two hours thirty minutes") -> 150
 *   Num.parseDuration("45 mins") / ("1h30m") -> 45 / 90
 */
object Num {

    private val UNITS: Map<String, Int> = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19
    )

    private val TENS: Map<String, Int> = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )

    private val BIG: Map<String, Double> = mapOf(
        "thousand" to 1_000.0, "lakh" to 100_000.0, "lakhs" to 100_000.0,
        "lac" to 100_000.0, "lacs" to 100_000.0, "crore" to 10_000_000.0,
        "crores" to 10_000_000.0, "million" to 1_000_000.0
    )

    private val DIGITS = Regex("""^\d+(?:\.\d+)?$""")
    private val KILO = Regex("""^(\d+(?:\.\d+)?)k$""")

    private fun tokens(text: String): List<String> =
        text.lowercase().replace(",", "").replace("-", " ").split(" ").filter { it.isNotEmpty() }

    /** A number from words or digits, or null if anything in [text] is not part of a number. */
    fun parse(text: String): Double? {

        val toks = tokens(text)
        if (toks.isEmpty()) return null

        var total = 0.0
        var current = 0.0
        var seen = false
        var i = 0

        while (i < toks.size) {

            val t = toks[i]

            when {

                // "and", "a", "an" are just glue: "one hundred and twenty", "a hundred", "one and a half"
                t == "and" || t == "a" || t == "an" -> {}

                DIGITS.matches(t) -> {
                    current += t.toDouble()
                    seen = true
                }

                KILO.matches(t) -> {
                    current += KILO.matchEntire(t)!!.groupValues[1].toDouble() * 1000.0
                    seen = true
                }

                UNITS.containsKey(t) -> {
                    current += UNITS.getValue(t)
                    seen = true
                }

                TENS.containsKey(t) -> {
                    current += TENS.getValue(t)
                    seen = true
                }

                t == "hundred" -> {
                    current = (if (current == 0.0) 1.0 else current) * 100.0
                    seen = true
                }

                BIG.containsKey(t) -> {
                    total += (if (current == 0.0) 1.0 else current) * BIG.getValue(t)
                    current = 0.0
                    seen = true
                }

                t == "half" -> {
                    current += 0.5
                    seen = true
                }

                // "two point five" -> 2.5
                t == "point" -> {
                    var frac = 0.0
                    var scale = 0.1
                    var j = i + 1
                    if (j >= toks.size) return null
                    while (j < toks.size) {
                        val d = toks[j]
                        val v = UNITS[d] ?: (if (DIGITS.matches(d) && d.length == 1) d.toInt() else return null)
                        if (v > 9) return null
                        frac += v * scale
                        scale /= 10.0
                        j++
                    }
                    return if (seen) total + current + frac else null
                }

                else -> return null
            }

            i++
        }

        return if (seen) total + current else null
    }

    // ---------------------------------------------------------------
    // Durations
    // ---------------------------------------------------------------

    private val HOUR_WORDS = setOf("hour", "hours", "hr", "hrs", "h")
    private val MIN_WORDS = setOf("minute", "minutes", "min", "mins", "m")

    private val COMPACT_H = Regex("""^(\d+(?:\.\d+)?)(?:h|hr|hrs|hour|hours)(?:(\d+)(?:m|min|mins)?)?$""")
    private val COMPACT_M = Regex("""^(\d+)(?:m|min|mins|minute|minutes)$""")

    private fun valueOf(p: List<String>): Double? {
        if (p.isEmpty()) return null
        val words = p.filter { it != "and" }
        if (words.isNotEmpty() && words.all { it == "a" || it == "an" }) return 1.0
        return parse(p.joinToString(" "))
    }

    /** Minutes, or null if [text] is not a length of time. Needs a unit (hours / minutes). */
    fun parseDuration(text: String): Int? {

        val toks = tokens(text)
        if (toks.isEmpty()) return null

        var minutes = 0.0
        var any = false
        var lastUnit = 0                      // 0 none, 1 hours, 2 minutes
        val phrase = ArrayList<String>()

        for (t in toks) {

            val ch = COMPACT_H.matchEntire(t)
            val cm = COMPACT_M.matchEntire(t)

            if (ch != null) {
                if (phrase.isNotEmpty()) return null
                minutes += ch.groupValues[1].toDouble() * 60.0
                if (ch.groupValues[2].isNotEmpty()) minutes += ch.groupValues[2].toDouble()
                any = true
                lastUnit = 1
                continue
            }

            if (cm != null) {
                if (phrase.isNotEmpty()) return null
                minutes += cm.groupValues[1].toDouble()
                any = true
                lastUnit = 2
                continue
            }

            when {
                t in HOUR_WORDS -> {
                    val v = valueOf(phrase) ?: return null
                    minutes += v * 60.0
                    phrase.clear()
                    any = true
                    lastUnit = 1
                }
                t in MIN_WORDS -> {
                    val v = valueOf(phrase) ?: return null
                    minutes += v
                    phrase.clear()
                    any = true
                    lastUnit = 2
                }
                else -> phrase.add(t)
            }
        }

        // "an hour and a half": a number with no unit after a unit counts in that unit.
        if (phrase.isNotEmpty()) {
            if (!any || lastUnit == 0) return null
            val v = valueOf(phrase) ?: return null
            minutes += if (lastUnit == 1) v * 60.0 else v
        }

        val m = Math.round(minutes).toInt()

        return if (any && m in 1..1440) m else null
    }

    // ---------------------------------------------------------------
    // Saying and showing amounts of time
    // ---------------------------------------------------------------

    /** "1 hour 30 minutes", "45 minutes", "2 hours" (for speech). */
    fun durText(minutes: Int): String {

        val h = minutes / 60
        val m = minutes % 60

        val hp = if (h == 0) "" else if (h == 1) "1 hour" else "$h hours"
        val mp = if (m == 0) "" else if (m == 1) "1 minute" else "$m minutes"

        return when {
            hp.isEmpty() && mp.isEmpty() -> "0 minutes"
            hp.isEmpty() -> mp
            mp.isEmpty() -> hp
            else -> "$hp $mp"
        }
    }

    /** "1h 30m", "45m", "2h" (for screens). */
    fun short(minutes: Int): String {

        val h = minutes / 60
        val m = minutes % 60

        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    // ---------------------------------------------------------------
    // Money formatting (Indian grouping: 1,25,000)
    // ---------------------------------------------------------------

    fun grouped(rupees: Long): String {

        val neg = rupees < 0
        val s = Math.abs(rupees).toString()

        if (s.length <= 3) return (if (neg) "-" else "") + s

        val last3 = s.takeLast(3)
        var rest = s.dropLast(3)
        val parts = ArrayList<String>()

        while (rest.length > 2) {
            parts.add(0, rest.takeLast(2))
            rest = rest.dropLast(2)
        }

        if (rest.isNotEmpty()) parts.add(0, rest)

        return (if (neg) "-" else "") + parts.joinToString(",") + "," + last3
    }

    /** Paise as "1,250" or "1,250.50" (no currency symbol). */
    fun money(paise: Long): String {

        val whole = paise / 100
        val frac = Math.abs(paise % 100)

        return grouped(whole) + (if (frac == 0L) "" else "." + frac.toString().padStart(2, '0'))
    }

    /** For speech: "1250 rupees" or "1250.50 rupees". */
    fun spokenMoney(paise: Long, word: String): String {

        val whole = paise / 100
        val frac = Math.abs(paise % 100)

        return whole.toString() + (if (frac == 0L) "" else "." + frac.toString().padStart(2, '0')) + " " + word
    }
}

/**
 * Span: the stretch of days a phrase like "today", "this week" or "last month" means.
 */
object Span {

    val WORDS = "today|yesterday|this week|last week|this month|last month|this year"

    fun range(word: String, today: LocalDate): Pair<LocalDate, LocalDate> = when (word) {
        "yesterday" -> Pair(today.minusDays(1), today.minusDays(1))
        "this week" -> Pair(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today)
        "last week" -> {
            val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
            Pair(start, start.plusDays(6))
        }
        "this month" -> Pair(today.withDayOfMonth(1), today)
        "last month" -> {
            val first = today.withDayOfMonth(1).minusMonths(1)
            Pair(first, first.withDayOfMonth(first.lengthOfMonth()))
        }
        "this year" -> Pair(LocalDate.of(today.year, 1, 1), today)
        else -> Pair(today, today)
    }
}
