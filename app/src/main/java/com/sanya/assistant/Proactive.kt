package com.sanya.assistant

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import java.util.Calendar

/**
 * Proactive: decides when Sanya should speak first.
 *
 * BubbleService calls Proactive.next(ctx) once a minute while the phone is in
 * use. It returns at most ONE nudge, or null to stay quiet.
 *
 * Rules (all on-device):
 *  - Battery warning        15% (8% during quiet hours), not charging, every 2 h at most
 *  - Morning brief          at the time you set, once a day, within 90 minutes of it
 *  - Birthdays / dates      today and tomorrow, once each day
 *  - Habit offer            something you usually say around now, once a day, 3 h apart
 *  - Quiet hours            22:00-07:00 by default: only battery below 8% and the brief
 *  - Daily limit            4 nudges a day (battery and brief do not count against it)
 *  - Study break            once per running study timer, after "study break" minutes
 *
 * Settings (Brain file, or by voice):
 *   setting | proactive = on / off          "stop suggesting" / "proactive on"
 *   setting | morning brief = 07:30 / off   "morning brief at 7 30"
 *   setting | quiet hours = 22:00-07:00
 */
object Proactive {

    /**
     * text  = just say it
     * offer = say text, then do this command if the answer is yes
     * run   = run this command now (like the morning brief)
     */
    data class Nudge(
        val text: String = "",
        val offer: String = "",
        val run: String = ""
    )

    private const val PREFS = "sanya_proactive"
    private const val MAX_PER_DAY = 4
    private const val HOUR_MS = 3_600_000L

    // Never suggest these on her own: people, money/secrets, or anything destructive.
    private val NO_SUGGEST = Regex(
        """\b(?:call|message|text|sms|whatsapp|send|pin|password|otp|secret|delete|clear|remove|forget)\b"""
    )

    fun next(ctx: Context): Nudge? {

        if (Brain.setting("proactive", "on") == "off") return null

        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val now = System.currentTimeMillis()

        if (p.getInt("day", 0) != today) {
            p.edit().putInt("day", today).putInt("count", 0).apply()
        }

        val count = p.getInt("count", 0)
        val quiet = inQuietHours(minutes)

        fun counted(n: Nudge): Nudge {
            p.edit().putInt("count", count + 1).putLong("last", now).apply()
            return n
        }

        // ---------------------------------------------------------
        // 1. Battery
        // ---------------------------------------------------------

        val bat = battery(ctx)

        if (bat != null && !bat.second) {
            val limit = if (quiet) 8 else 15
            if (bat.first <= limit && now - p.getLong("bat", 0L) > 2 * HOUR_MS) {
                p.edit().putLong("bat", now).apply()
                return Nudge(
                    text = "Your battery is at ${bat.first} percent. You might want to charge."
                )
            }
        }

        // ---------------------------------------------------------
        // 2. Morning brief (you asked for it, so it ignores quiet hours)
        // ---------------------------------------------------------

        val at = briefMinutes()

        if (
            at != null &&
            p.getInt("brief", 0) != today &&
            minutes >= at &&
            minutes < at + 90
        ) {
            p.edit().putInt("brief", today).putLong("last", now).apply()
            return Nudge(run = "good morning")
        }

        // ---------------------------------------------------------
        // 2b. Routines: an activity's time has come, or the evening check.
        //     You set these up yourself, so they ignore quiet hours.
        // ---------------------------------------------------------

        val due = Routines.reminder(ctx, minutes)

        if (due != null) {
            Routines.markReminded(ctx, due)
            p.edit().putLong("last", now).apply()
            return Nudge(text = "Time for ${due.task.name}.")
        }

        if (p.getInt("routine", 0) != today) {
            val evening = Routines.eveningDue(ctx, minutes)
            if (evening != null) {
                p.edit().putInt("routine", today).putLong("last", now).apply()
                return Nudge(text = evening)
            }
        }

        // ---------------------------------------------------------
        // 2c. Study break: you started the timer yourself, so this
        //     ignores quiet hours and the daily limit. Once per timer.
        // ---------------------------------------------------------

        val breakNudge = Study.breakDue(ctx)

        if (breakNudge != null) {
            p.edit().putLong("last", now).apply()
            return Nudge(text = breakNudge)
        }

        if (quiet || count >= MAX_PER_DAY) return null

        // ---------------------------------------------------------
        // 3. Birthdays and other dates (today and tomorrow)
        // ---------------------------------------------------------

        val dated = Mind.dueDates()

        if (dated.isNotEmpty()) {
            val done = doneToday(p, "dates", today)
            for ((label, days) in dated) {
                if (label in done) continue
                markDone(p, "dates", today, done + label)
                return counted(Nudge(text = Mind.dateSentence(label, days)))
            }
        }

        // ---------------------------------------------------------
        // 4. A habit: something you usually say around now
        // ---------------------------------------------------------

        if (now - p.getLong("last", 0L) >= 3 * HOUR_MS) {

            val h = Mind.habit(ctx)

            if (
                h != null &&
                !NO_SUGGEST.containsMatchIn(h) &&
                !Mind.didRecently(ctx, h, 4 * HOUR_MS)
            ) {
                val done = doneToday(p, "habits", today)
                if (h !in done) {
                    markDone(p, "habits", today, done + h)
                    return counted(
                        Nudge(
                            text = "Around now you usually say $h. Shall I do that?",
                            offer = h
                        )
                    )
                }
            }
        }

        return null
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private fun doneToday(
        p: SharedPreferences,
        key: String,
        today: Int
    ): List<String> {
        val raw = p.getString(key, "") ?: ""
        return if (raw.startsWith("$today|")) {
            raw.substringAfter('|').split(";").filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
    }

    private fun markDone(
        p: SharedPreferences,
        key: String,
        today: Int,
        items: List<String>
    ) {
        p.edit().putString(key, "$today|" + items.joinToString(";")).apply()
    }

    /** (percent, isCharging) or null if the phone does not say. */
    private fun battery(ctx: Context): Pair<Int, Boolean>? {

        val i = ctx.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return null

        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

        if (level < 0 || scale <= 0) return null

        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return Pair(level * 100 / scale, charging)
    }

    private fun inQuietHours(minutes: Int): Boolean {

        val raw = Brain.setting("quiet hours", "22:00-07:00").trim()

        val m = Regex("""^(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})$""").find(raw)
            ?: return minutes >= 22 * 60 || minutes < 7 * 60

        val start = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        val end = m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()

        return if (start <= end) {
            minutes in start until end
        } else {
            minutes >= start || minutes < end
        }
    }

    private fun briefMinutes(): Int? {

        val raw = Brain.setting("morning brief", "off").trim()

        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(raw) ?: return null

        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }
}
