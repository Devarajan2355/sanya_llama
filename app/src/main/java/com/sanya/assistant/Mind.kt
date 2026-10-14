package com.sanya.assistant

import android.content.Context
import java.io.File
import java.util.Calendar
import kotlin.math.abs

/**
 * Mind: Sanya's own memory and character. 100% on-device, no external AI.
 *
 * What it gives her:
 *  - Episodic memory   every request is remembered (what, when, understood or not)
 *  - Habits            she notices what you usually ask at what time of day
 *  - Mood              derived from how well she has understood you lately
 *  - Familiarity       she acts differently with a stranger than with someone she knows
 *  - Similarity sense  she can recognise a phrase she was never taught, if it is
 *                      close to something she was
 *  - Self-model        she can answer questions about herself from her real state
 *
 * Storage (private to the app):
 *  - files/mind.log           one event per line:  timestamp|hour|ok|text
 *  - prefs "sanya_mind"       total, first, last interaction times
 *
 * Used by StepRunner (SanyaCommands.kt). Deleting the data: Mind.wipe(ctx).
 */
object Mind {

    private const val LOG = "mind.log"
    private const val PREFS = "sanya_mind"
    private const val KEEP = 400
    private const val DAY_MS = 86_400_000L

    private data class Event(
        val ts: Long,
        val hour: Int,
        val ok: Boolean,
        val text: String
    )

    // ---------------------------------------------------------------
    // Memory
    // ---------------------------------------------------------------

    private fun logFile(ctx: Context) = File(ctx.filesDir, LOG)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun parseEvent(line: String): Event? {
        val p = line.split("|", limit = 4)
        if (p.size < 4) return null
        val ts = p[0].toLongOrNull() ?: return null
        val hour = p[1].toIntOrNull() ?: return null
        return Event(ts, hour, p[2] == "1", p[3])
    }

    private fun events(ctx: Context): List<Event> {
        return try {
            val f = logFile(ctx)
            if (!f.exists()) {
                emptyList<Event>()
            } else {
                f.readLines().mapNotNull { parseEvent(it) }
            }
        } catch (e: Exception) {
            emptyList<Event>()
        }
    }

    /** Remember one request. ok = she understood it. */
    fun record(ctx: Context, text: String, ok: Boolean) {

        val clean = text.replace('|', ' ').replace('\n', ' ').trim()
        if (clean.isEmpty()) return

        try {
            val now = System.currentTimeMillis()
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val flag = if (ok) 1 else 0
            val f = logFile(ctx)

            f.appendText("$now|$hour|$flag|$clean\n")

            val p = prefs(ctx)
            val total = p.getInt("total", 0) + 1
            val ed = p.edit()

            if (p.getLong("first", 0L) == 0L) ed.putLong("first", now)

            ed.putInt("total", total)
                .putLong("last", now)
                .apply()

            // Keep the log small.
            if (total % 50 == 0) {
                val lines = f.readLines()
                if (lines.size > KEEP + 100) {
                    f.writeText(lines.takeLast(KEEP).joinToString("\n") + "\n")
                }
            }
        } catch (e: Exception) {
            // Memory is a bonus; never break a command because of it.
        }
    }

    /** Erase everything she remembers about your history and habits. */
    fun wipe(ctx: Context) {
        try {
            logFile(ctx).delete()
            prefs(ctx).edit().clear().apply()
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------
    // Habits
    // ---------------------------------------------------------------

    private val BORING = Regex(
        """^(?:again|hello|hi|hey|hai|thanks|thank you|yes|no|ok|okay|stop|exit|good morning|good afternoon|good evening|good night|yes\s?\w*)$"""
    )

    private fun meaningful(text: String): Boolean =
        text.length >= 4 && !BORING.matches(text)

    /**
     * What you usually say around this time of day (within one hour),
     * if you have said it at least 3 times. Otherwise null.
     */
    fun habit(ctx: Context): String? {

        val now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val counts = HashMap<String, Int>()

        for (e in events(ctx)) {
            if (!e.ok || !meaningful(e.text)) continue
            val d = abs(e.hour - now)
            val gap = minOf(d, 24 - d)
            if (gap <= 1) counts[e.text] = (counts[e.text] ?: 0) + 1
        }

        val best = counts.maxByOrNull { it.value } ?: return null
        return if (best.value >= 3) best.key else null
    }

    private fun topCommands(ctx: Context, n: Int): List<String> {

        val counts = HashMap<String, Int>()

        for (e in events(ctx)) {
            if (!e.ok || !meaningful(e.text)) continue
            counts[e.text] = (counts[e.text] ?: 0) + 1
        }

        return counts.entries
            .filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .take(n)
            .map { it.key }
    }

    /** (understood, total) over her last 20 requests, or null if too few. */
    private fun recent(ctx: Context): Pair<Int, Int>? {
        val last = events(ctx).takeLast(20)
        if (last.size < 5) return null
        return Pair(last.count { it.ok }, last.size)
    }

    // ---------------------------------------------------------------
    // Similarity: recognising phrases she was never taught exactly
    // ---------------------------------------------------------------

    private fun trigrams(s: String): Set<String> {
        val p = "  $s "
        val out = HashSet<String>()
        for (i in 0..(p.length - 3)) out.add(p.substring(i, i + 3))
        return out
    }

    private fun stem(w: String): String = when {
        w.length > 4 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 3 && w.endsWith("s") -> w.dropLast(1)
        else -> w
    }

    private fun wordSet(s: String): Set<String> =
        s.split(" ").filter { it.length > 1 }.map { stem(it) }.toSet()

    /** 0.0 (unrelated) to 1.0 (identical). */
    fun similarity(a: String, b: String): Double {

        val x = a.lowercase().trim()
        val y = b.lowercase().trim()

        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0

        val ta = trigrams(x)
        val tb = trigrams(y)
        val dice = 2.0 * ta.intersect(tb).size / (ta.size + tb.size)

        val wa = wordSet(x)
        val wb = wordSet(y)
        val union = wa.union(wb).size
        val jaccard =
            if (union == 0) 0.0 else wa.intersect(wb).size.toDouble() / union

        return 0.5 * dice + 0.5 * jaccard
    }

    /** The closest candidate and its score, or null if there are none. */
    fun best(phrase: String, candidates: Collection<String>): Pair<String, Double>? {

        var bestKey: String? = null
        var bestScore = 0.0

        for (c in candidates) {
            val s = similarity(phrase, c)
            if (s > bestScore) {
                bestScore = s
                bestKey = c
            }
        }

        val k = bestKey ?: return null
        return Pair(k, bestScore)
    }

    // ---------------------------------------------------------------
    // Talking about herself and about you
    // ---------------------------------------------------------------

    private val GREET =
        Regex("""^(?:hello|hi|hey|hai|hii|yo|vanakkam)$""")

    private val MOOD =
        Regex("""^(?:how are you|how r u|how are u|how are you feeling|how do you feel|how is it going|how you doing)$""")

    private val ABOUT_ME =
        Regex("""^(?:what do you know about me|tell me about myself|what have you noticed|what are my habits|what do you remember about me|how well do you know me)$""")

    private val SUGGEST =
        Regex("""^(?:what should i do|suggest something|any suggestions?|what do i usually do now)$""")

    private val LAST =
        Regex("""^(?:what did i (?:ask|say|tell you) (?:last|before|earlier)|what was my last (?:command|request)|what did i just (?:ask|say))$""")

    private val SELF =
        Regex("""^(?:are you (?:alive|real|conscious|human|sentient)|do you have (?:a )?(?:mind|feelings|brain))$""")

    private val WIPE =
        Regex("""^(?:forget my habits|forget my history|clear my history|clear my habits|reset your memory)$""")

    // ---- settings by voice ----
    private val PROACTIVE_OFF = Regex(
        """^(?:stop (?:suggesting|being proactive|talking first)|proactive off|turn off proactive|be quiet|don't talk first)$"""
    )
    private val PROACTIVE_ON = Regex(
        """^(?:proactive on|turn on proactive|start suggesting|be proactive)$"""
    )
    private val BRIEF_OFF = Regex(
        """^(?:stop|cancel|turn off) (?:my )?morning brief$"""
    )
    private val BRIEF_SET = Regex(
        """^(?:set )?(?:my )?morning brief (?:at|for|to) (.+)$"""
    )
    private val CONFIRM_OFF = Regex(
        """^(?:stop asking (?:me )?(?:before(?: calling| deleting| clearing)?|to confirm|for confirmation)|don't ask (?:me )?before calling|confirm off|no confirmation)$"""
    )
    private val TAP_GOOGLE = Regex(
        """^(?:use google (?:voice|ears|hearing) when i tap|tap google)$"""
    )
    private val TAP_OFFLINE = Regex(
        """^(?:use offline (?:voice|ears|hearing) when i tap|tap offline)$"""
    )
    private val HEAR_GOOGLE = Regex(
        """^(?:use google (?:voice|ears|hearing)|google hearing on|google voice on)$"""
    )
    private val HEAR_OFFLINE = Regex(
        """^(?:use offline (?:voice|ears|hearing)|google hearing off|google voice off)$"""
    )
    private val FIXES_LIST = Regex(
        """^(?:what have i corrected|show my corrections|list my corrections|what mistakes did you learn)$"""
    )
    private val FIXES_CLEAR = Regex(
        """^(?:forget my corrections|clear my corrections)$"""
    )
    private val HEALTH = Regex(
        """^(?:run (?:a )?health check|health check|check yourself|is everything working|what is wrong with you|run diagnostics)$"""
    )
    private val MODEL_STATS = Regex(
        """^(?:how much have you learned|how well do you know my voice|show my model|how well do you know how i speak)$"""
    )
    private val MODEL_RESET = Regex(
        """^(?:forget how i speak|reset your model|forget my speech)$"""
    )
    private val CONFIRM_ON = Regex(
        """^(?:ask (?:me )?before calling|confirm on|ask for confirmation)$"""
    )

    // ---- people and dates ----
    private val REL_GROUPS: List<List<String>> = listOf(
        listOf("mother", "mom", "mum", "mummy", "amma"),
        listOf("father", "dad", "daddy", "appa"),
        listOf("brother", "anna", "thambi"),
        listOf("sister", "akka", "thangai", "sis"),
        listOf("grandmother", "grandma", "paati"),
        listOf("grandfather", "grandpa", "thatha"),
        listOf("friend"), listOf("wife"), listOf("husband"),
        listOf("son"), listOf("daughter"), listOf("uncle"), listOf("aunt"),
        listOf("boss"), listOf("teacher"), listOf("guide"), listOf("cousin"),
        listOf("boyfriend"), listOf("girlfriend"), listOf("partner"),
        listOf("colleague"), listOf("manager"), listOf("neighbour", "neighbor")
    )

    private val REL_WORDS: String = REL_GROUPS.flatten().joinToString("|")

    private val NOT_NAMES = setOf(
        "who", "what", "where", "which", "how", "when", "why",
        "that", "this", "it", "there", "here", "he", "she", "they"
    )

    private val PERSON_A = Regex("""^([a-z][a-z ]{1,25}?) is my ($REL_WORDS)$""")
    private val PERSON_B = Regex("""^my ($REL_WORDS) is (?:called |named )?([a-z][a-z ]{1,25})$""")
    private val DATE_SET = Regex(
        """^(my|[a-z][a-z ]{0,24}?)(?:'s| s)? (birthday|anniversary) is (?:on )?(.+)$"""
    )
    private val DATE_QUERY = Regex(
        """^when is (my|[a-z][a-z ]{0,24}?)(?:'s| s)? (birthday|anniversary)$"""
    )
    private val WHO_REL = Regex("""^who is my ($REL_WORDS)$""")
    private val WHO_IS = Regex("""^who is ([a-z][a-z ]{1,25})$""")
    private val WORLD_LIST = Regex(
        """^(?:what dates do you know|list my dates|who do you know|who are my people|list my people|show my people)$"""
    )
    private val WORLD_FORGET = Regex("""^forget (?:person|contact|date) ([a-z][a-z ]{1,30})$""")

    private fun norm(raw: String): String =
        raw.lowercase()
            .replace(Regex("""[^a-z0-9' ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /**
     * Which kind of conversation this is, or null if it is not one.
     * Kinds: greet, mood, about, suggest, last, self, wipe
     */
    fun kind(raw: String): String? {
        val t = norm(raw)
        return when {
            t.isEmpty() -> null
            GREET.matches(t) -> "greet"
            MOOD.matches(t) -> "mood"
            ABOUT_ME.matches(t) -> "about"
            SUGGEST.matches(t) -> "suggest"
            LAST.matches(t) -> "last"
            SELF.matches(t) -> "self"
            WIPE.matches(t) -> "wipe"
            PROACTIVE_OFF.matches(t) -> "proactive_off"
            PROACTIVE_ON.matches(t) -> "proactive_on"
            BRIEF_OFF.matches(t) -> "brief_off"
            BRIEF_SET.matches(t) -> "brief_set"
            CONFIRM_OFF.matches(t) -> "confirm_off"
            CONFIRM_ON.matches(t) -> "confirm_on"
            TAP_GOOGLE.matches(t) -> "tap_google"
            TAP_OFFLINE.matches(t) -> "tap_offline"
            HEALTH.matches(t) -> "health_check"
            MODEL_STATS.matches(t) -> "model_stats"
            MODEL_RESET.matches(t) -> "model_reset"
            FIXES_LIST.matches(t) -> "fixes_list"
            FIXES_CLEAR.matches(t) -> "fixes_clear"
            HEAR_GOOGLE.matches(t) -> "hear_google"
            HEAR_OFFLINE.matches(t) -> "hear_offline"
            DATE_SET.matches(t) -> "date_set"
            DATE_QUERY.matches(t) -> "date_query"
            isPersonSet(t) -> "person_set"
            isPersonSetB(t) -> "person_set"
            WHO_REL.matches(t) -> "who_rel"
            WORLD_LIST.matches(t) -> "world_list"
            WORLD_FORGET.matches(t) -> "world_forget"
            isFactSet(t) -> "fact_set"
            FACTS_LIST.matches(t) -> "facts_list"
            isKnownPerson(t) -> "who_is"
            else -> null
        }
    }

    /**
     * Her reply, built from her real state (history, habits, mood).
     * @param name the user's name with a leading space (" Dean") or "".
     */
    fun reply(ctx: Context, kind: String, raw: String, name: String): String =
        when (kind) {
            "greet" -> greeting(ctx, norm(raw), name)
            "mood" -> moodLine(ctx)
            "about" -> aboutMe(ctx)
            "suggest" -> noHabitLine(ctx)
            "last" -> lastLine(ctx)
            "self" -> selfLine(ctx)
            "wipe" -> {
                wipe(ctx)
                "Done. I've cleared my memory of our history and your habits."
            }
            "proactive_off" -> {
                Brain.put("setting", "proactive", "off")
                "Okay. I won't speak first anymore. Say proactive on to bring it back."
            }
            "proactive_on" -> {
                Brain.put("setting", "proactive", "on")
                "Okay. I'll speak up when something matters, like a birthday, low battery or a habit."
            }
            "brief_off" -> {
                Brain.put("setting", "morning brief", "off")
                "Morning brief is off."
            }
            "brief_set" -> briefSet(raw)
            "confirm_off" -> {
                Brain.put("setting", "confirm actions", "off")
                "Okay. I won't ask before calling or clearing your lists."
            }
            "confirm_on" -> {
                Brain.put("setting", "confirm actions", "on")
                "Okay. I'll check with you before calling or clearing your lists."
            }
            "tap_google" -> {
                Brain.put("setting", "tap voice", "google")
                "Okay. When you tap the bubble I'll listen with Google's voice, which is much better with Indian names. " +
                    "It needs internet and sends your voice to Google. Hands-free stays offline. " +
                    "Say use offline voice to switch back."
            }
            "health_check" -> Health.summary(ctx)
            "model_stats" -> Learner.stats(ctx)
            "model_reset" -> {
                Learner.reset(ctx)
                "Okay. I've forgotten how you speak, and I'll start learning it again."
            }
            "fixes_list" -> {
                val m = Brain.map("heard")
                if (m.isEmpty()) {
                    "Nobody has corrected me yet. If I mishear you, say: no, I said, and then the right words."
                } else {
                    "I've learned ${m.size} corrections: " +
                        m.entries.take(8).joinToString(", ") { it.key + " means " + it.value } + "."
                }
            }
            "fixes_clear" -> {
                Brain.clear("heard")
                "Okay. I've forgotten all my corrections."
            }
            "hear_google" -> {
                Brain.put("setting", "hearing", "google")
                "Okay. I'll listen with Google's voice from now on, which is much better with Indian names and accents. " +
                    "It sends your voice to Google when you're online. Say use offline hearing to go back."
            }
            "hear_offline" -> {
                Brain.put("setting", "hearing", "offline")
                Brain.put("setting", "tap voice", "offline")
                "Okay. I'm back to my own offline hearing."
            }
            "tap_offline" -> {
                Brain.put("setting", "tap voice", "offline")
                "Okay. Tapping the bubble uses my own offline hearing again."
            }
            "date_set" -> dateSet(norm(raw))
            "date_query" -> dateQuery(norm(raw))
            "person_set" -> personSet(norm(raw))
            "who_rel" -> whoRel(norm(raw))
            "world_list" -> worldList()
            "world_forget" -> worldForget(norm(raw))
            "fact_set" -> factSet(norm(raw))
            "facts_list" -> factsList()
            "who_is" -> whoIs(norm(raw))
            else -> "I'm here."
        }

    private fun daypart(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            h < 12 -> "morning"
            h < 17 -> "afternoon"
            h < 21 -> "evening"
            else -> "night"
        }
    }

    private fun greeting(ctx: Context, t: String, name: String): String {

        val p = prefs(ctx)
        val total = p.getInt("total", 0)
        val last = p.getLong("last", 0L)
        val days =
            if (last == 0L) 0L else (System.currentTimeMillis() - last) / DAY_MS
        val part = daypart()
        val hello =
            if (t.startsWith("vanakkam")) {
                "Vanakkam"
            } else {
                listOf("Hello", "Hey", "Hi").random()
            }

        return when {
            total < 3 ->
                "$hello$name! I'm Sanya. I'm still new, so teach me how you talk."

            days >= 3 ->
                "Welcome back$name. It's been $days days."

            else -> listOf(
                "$hello$name! Good $part.",
                "$hello$name. What's next?",
                "$hello$name, good $part. Ready when you are."
            ).random()
        }
    }

    private fun moodLine(ctx: Context): String {

        val r = recent(ctx)
            ?: return "I'm good, and still learning how you talk."

        val ok = r.first
        val n = r.second

        return when {
            ok * 2 < n ->
                "A little lost lately. I understood only $ok of your last $n requests. Teach me and I'll improve."

            ok * 10 >= n * 9 ->
                "Sharp today. I understood $ok of your last $n requests."

            else ->
                "Doing fine. I understood $ok of your last $n requests."
        }
    }

    private fun aboutMe(ctx: Context): String {

        val p = prefs(ctx)
        val total = p.getInt("total", 0)

        if (total < 5) {
            return "I don't know you well yet. We've only talked $total times. Keep talking and teaching me."
        }

        val first = p.getLong("first", 0L)
        val days =
            maxOf(1L, (System.currentTimeMillis() - first) / DAY_MS)

        val parts = mutableListOf("We've talked $total times over $days days.")

        val top = topCommands(ctx, 3)
        if (top.isNotEmpty()) {
            parts.add("You most often say: " + top.joinToString(", ") + ".")
        }

        habit(ctx)?.let {
            parts.add("Around this hour you usually say $it.")
        }

        parts.add(
            "I know ${Brain.map("nick").size} nicknames, " +
                "${Brain.map("slang").size} slang phrases and " +
                "${Brain.map("fact").size} facts about your world."
        )

        return parts.joinToString(" ")
    }

    private fun noHabitLine(ctx: Context): String {
        val total = prefs(ctx).getInt("total", 0)
        return if (total < 20) {
            "I haven't seen a pattern yet. Give me a few more days with you."
        } else {
            "Nothing stands out around this time. Tell me what you'd like."
        }
    }

    private fun lastLine(ctx: Context): String {
        val last = events(ctx).lastOrNull { it.ok && meaningful(it.text) }
            ?: return "You haven't asked me anything yet."
        return "Your last request was: ${last.text}."
    }

    private fun selfLine(ctx: Context): String {
        val total = prefs(ctx).getInt("total", 0)
        return "I'm not alive, and I won't pretend to be. But I do have a memory that is only mine. " +
            "I've heard $total of your requests, and I change how I answer as I learn from you."
    }

    /** Did she already hear this exact request within the last withinMs? */
    fun didRecently(ctx: Context, text: String, withinMs: Long): Boolean {
        val cutoff = System.currentTimeMillis() - withinMs
        return events(ctx).any { it.text == text && it.ts >= cutoff }
    }

    // ---------------------------------------------------------------
    // Her knowledge of your world: people and dates
    // Stored in the Brain file, so you can read and edit it:
    //   person | ammu = mother
    //   date   | ammu birthday = 12 march
    // ---------------------------------------------------------------

    private fun cap(s: String): String =
        s.split(" ").joinToString(" ") { w ->
            w.replaceFirstChar { c -> c.uppercase() }
        }

    private fun canonRel(word: String): String =
        REL_GROUPS.firstOrNull { word in it }?.first() ?: word

    private fun relSynonyms(canon: String): List<String> =
        REL_GROUPS.firstOrNull { it.first() == canon } ?: listOf(canon)

    private fun two(n: Int): String = n.toString().padStart(2, '0')

    // Words that are clearly not a person's name ("my friend is late", "my friend is calling me").
    private val NON_NAME_WORDS = setOf(
        "here", "there", "late", "busy", "fine", "good", "sick", "home", "outside",
        "ready", "back", "okay", "well", "angry", "happy", "sad", "tired", "me", "you"
    )

    private fun looksLikeName(s: String): Boolean {
        val w = s.trim().split(" ").filter { it.isNotEmpty() }
        return w.isNotEmpty() &&
            w.size <= 2 &&
            w.none { it.endsWith("ing") || it in NOT_NAMES || it in NON_NAME_WORDS }
    }

    private fun isPersonSet(t: String): Boolean {
        val m = PERSON_A.matchEntire(t) ?: return false
        return looksLikeName(m.groupValues[1])
    }

    private fun isPersonSetB(t: String): Boolean {
        val m = PERSON_B.matchEntire(t) ?: return false
        return looksLikeName(m.groupValues[2])
    }

    private fun isKnownPerson(t: String): Boolean {
        val m = WHO_IS.matchEntire(t) ?: return false
        val name = Brain.clean(m.groupValues[1])
        return Brain.map("person").containsKey(name)
    }

    private fun possessive(who: String): String =
        if (who == "my") "Your" else cap(who) + "'s"

    // ---- numbers spoken as words ("twelve", "twenty first", "seven thirty") ----

    private val UNITS: Map<String, Int> = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19,
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5,
        "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10,
        "eleventh" to 11, "twelfth" to 12, "thirteenth" to 13, "fourteenth" to 14,
        "fifteenth" to 15, "sixteenth" to 16, "seventeenth" to 17,
        "eighteenth" to 18, "nineteenth" to 19
    )

    private val TENS: Map<String, Int> = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "twentieth" to 20, "thirtieth" to 30
    )

    /** "twenty first of march" -> "21 of march" */
    fun numWords(s: String): String {

        val toks = s.split(" ").filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0

        while (i < toks.size) {

            val w = toks[i]
            val tens = TENS[w]
            val unit = UNITS[w]

            if (tens != null) {
                val next = if (i + 1 < toks.size) UNITS[toks[i + 1]] else null
                if (next != null && next in 1..9) {
                    out.add((tens + next).toString())
                    i += 2
                } else {
                    out.add(tens.toString())
                    i += 1
                }
            } else if (unit != null) {
                out.add(unit.toString())
                i += 1
            } else {
                out.add(w)
                i += 1
            }
        }

        return out.joinToString(" ")
    }

    // ---- clock times ("7 30", "seven thirty pm") -> "HH:MM" ----

    fun parseClock(text: String): String? {

        val t = numWords(text.lowercase())
            .replace(Regex("""\b([ap])\.? ?m\.?\b"""), "$1m")
            .replace(Regex("""[^a-z0-9: ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val m = Regex("""^(\d{1,2})(?:[: ](\d{2}))?(?: ?(am|pm))?$""")
            .find(t) ?: return null

        var h = m.groupValues[1].toInt()
        val min = if (m.groupValues[2].isEmpty()) 0 else m.groupValues[2].toInt()
        val ap = m.groupValues[3]

        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0

        if (h > 23 || min > 59) return null

        return two(h) + ":" + two(min)
    }

    private fun briefSet(raw: String): String {

        val m = BRIEF_SET.matchEntire(norm(raw))
            ?: return "I didn't catch the time."

        val clock = parseClock(m.groupValues[1])
            ?: return "I didn't catch the time. Say it like: morning brief at 7 30."

        Brain.put("setting", "morning brief", clock)
        Brain.put("setting", "proactive", "on")

        return "Okay. I'll give you a morning brief at $clock when you're using your phone."
    }

    // ---- dates ----

    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december"
    )

    private val MAX_DAYS = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    /** "12 march", "12th of march", "march 12", "twelve march" -> (12, 3) */
    fun parseDM(text: String): Pair<Int, Int>? {

        val t = numWords(text.lowercase())
            .replace(Regex("""[^a-z0-9 ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .removePrefix("the ")

        val a = Regex("""^(\d{1,2})(?:st|nd|rd|th)?(?: of)? ([a-z]+)$""").find(t)
        val b = Regex("""^([a-z]+) (\d{1,2})(?:st|nd|rd|th)?$""").find(t)

        val parts: Pair<String, String> = when {
            a != null -> Pair(a.groupValues[1], a.groupValues[2])
            b != null -> Pair(b.groupValues[2], b.groupValues[1])
            else -> return null
        }

        val day = parts.first.toIntOrNull() ?: return null
        val name = parts.second

        val idx = MONTHS.indexOfFirst {
            it == name || (name.length >= 3 && it.startsWith(name))
        }

        if (idx < 0) return null
        if (day < 1 || day > MAX_DAYS[idx]) return null

        return Pair(day, idx + 1)
    }

    private fun daysUntil(day: Int, month: Int): Int {

        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        fun at(y: Int): Calendar =
            Calendar.getInstance().apply {
                clear()
                set(y, month - 1, day, 0, 0, 0)
            }

        val year = today.get(Calendar.YEAR)
        var target = at(year)
        if (target.before(today)) target = at(year + 1)

        val diff = target.timeInMillis - today.timeInMillis
        return Math.round(diff / DAY_MS.toDouble()).toInt()
    }

    private fun dateLabel(key: String): String {
        val parts = key.split(" ")
        val event = parts.last()
        val who = parts.dropLast(1).joinToString(" ")
        return if (who == "my" || who.isEmpty()) "your $event" else cap(who) + "'s " + event
    }

    fun dateSentence(label: String, days: Int): String {
        val text = if (days == 0) "Today is $label." else "Tomorrow is $label."
        return if (days == 0 && label.startsWith("your birthday")) {
            "$text Happy birthday!"
        } else {
            text
        }
    }

    /** Saved dates that fall today or tomorrow: (label, days). */
    fun dueDates(): List<Pair<String, Int>> {

        val out = ArrayList<Pair<String, Int>>()

        for ((key, value) in Brain.map("date")) {
            val dm = parseDM(value) ?: continue
            val d = daysUntil(dm.first, dm.second)
            if (d <= 1) out.add(Pair(dateLabel(key), d))
        }

        return out
    }

    /** One sentence for the morning brief, or "". */
    fun upcomingLine(): String =
        dueDates().joinToString(" ") { (label, d) -> dateSentence(label, d) }

    // ---- people and relations ----

    /** "call my mother" -> "call ammu", using the people she knows. */
    fun resolveRelations(raw: String): String {

        val people = Brain.map("person")
        if (people.isEmpty()) return raw

        var out = raw

        for ((name, rel) in people) {
            val alts = relSynonyms(rel).joinToString("|") { Regex.escape(it) }
            val rx = Regex("""\bmy\s+(?:$alts)\b""", RegexOption.IGNORE_CASE)
            out = rx.replace(out, name)
        }

        return out
    }

    private fun personSet(t: String): String {

        val a = PERSON_A.matchEntire(t)
        val b = PERSON_B.matchEntire(t)

        val parts: Pair<String, String> = when {
            a != null -> Pair(a.groupValues[1], a.groupValues[2])
            b != null -> Pair(b.groupValues[2], b.groupValues[1])
            else -> return "I didn't catch that."
        }

        val name = Brain.clean(parts.first)
        val rel = canonRel(parts.second)

        if (name.isEmpty()) return "I didn't catch the name."

        Brain.put("person", name, rel)

        return "Got it. ${cap(name)} is your $rel. You can now say, call my $rel."
    }

    private fun whoRel(t: String): String {

        val m = WHO_REL.matchEntire(t) ?: return "I didn't catch that."
        val rel = canonRel(m.groupValues[1])

        val names = Brain.map("person")
            .filter { it.value == rel }
            .keys
            .map { cap(it) }

        return if (names.isEmpty()) {
            "I don't know who your $rel is yet. Tell me, like: ammu is my $rel."
        } else {
            "Your $rel is ${names.joinToString(" and ")}."
        }
    }

    private fun whoIs(t: String): String {

        val m = WHO_IS.matchEntire(t) ?: return "I didn't catch that."
        val name = Brain.clean(m.groupValues[1])
        val rel = Brain.map("person")[name] ?: return "I don't know ${cap(name)} yet."

        return "${cap(name)} is your $rel."
    }

    private fun dateSet(t: String): String {

        val m = DATE_SET.matchEntire(t) ?: return "I didn't catch that."

        val who = Brain.clean(m.groupValues[1])
        val event = m.groupValues[2]

        val dm = parseDM(m.groupValues[3])
            ?: return "I didn't catch the date. Say it like: twelve March."

        val month = MONTHS[dm.second - 1]

        Brain.put("date", "$who $event", "${dm.first} $month")

        return "Got it. ${possessive(who)} $event is on ${dm.first} ${cap(month)}. " +
            "I'll remind you the day before."
    }

    private fun dateQuery(t: String): String {

        val m = DATE_QUERY.matchEntire(t) ?: return "I didn't catch that."

        val who = Brain.clean(m.groupValues[1])
        val event = m.groupValues[2]

        val value = Brain.map("date")["$who $event"]
            ?: return "I don't know ${possessive(who).lowercase()} $event yet. " +
                "Tell me, like: ${if (who == "my") "my" else who + "'s"} $event is 12 March."

        val dm = parseDM(value)
            ?: return "I have ${possessive(who).lowercase()} $event saved as $value, but I can't read that date."

        val d = daysUntil(dm.first, dm.second)
        val when_ = when (d) {
            0 -> "today"
            1 -> "tomorrow"
            else -> "in $d days"
        }

        return "${possessive(who)} $event is on ${dm.first} ${cap(MONTHS[dm.second - 1])}, $when_."
    }

    private fun worldList(): String {

        val people = Brain.map("person")
        val dates = Brain.map("date")

        if (people.isEmpty() && dates.isEmpty()) {
            return "I don't know your people or dates yet. Tell me, like: ammu is my mother, " +
                "or: my birthday is 12 March."
        }

        val parts = ArrayList<String>()

        if (people.isNotEmpty()) {
            parts.add(
                "People: " + people.entries.joinToString(", ") {
                    cap(it.key) + " (" + it.value + ")"
                } + "."
            )
        }

        if (dates.isNotEmpty()) {
            parts.add(
                "Dates: " + dates.entries.joinToString(", ") {
                    dateLabel(it.key) + " on " + it.value
                } + "."
            )
        }

        return parts.joinToString(" ")
    }

    private fun worldForget(t: String): String {

        val m = WORLD_FORGET.matchEntire(t) ?: return "I didn't catch that."
        val name = Brain.clean(m.groupValues[1])

        Brain.drop("person", name)
        Brain.drop("date", name)

        return "Okay, I've forgotten $name."
    }

    // ---------------------------------------------------------------
    // Hearing names by SOUND
    //
    // The offline voice only knows English words, so a name like "Preethi"
    // arrives as "pretty". Spelling distance cannot connect those two, but
    // their sound can: both boil down to the same skeleton (P-R-T).
    // ---------------------------------------------------------------

    private val SOUND_CLASSES: Map<Char, Char> = mapOf(
        'p' to 'P', 'b' to 'P', 'f' to 'P', 'v' to 'P',
        't' to 'T', 'd' to 'T',
        'k' to 'K', 'g' to 'K', 'q' to 'K',
        's' to 'S',
        'l' to 'L', 'r' to 'R', 'm' to 'M', 'n' to 'N'
    )

    private val SOUND_SWAPS: List<Pair<String, String>> = listOf(
        "ph" to "f", "th" to "t", "dh" to "d", "kh" to "k", "gh" to "g",
        "bh" to "b", "sh" to "s", "ch" to "s", "ck" to "k", "qu" to "kw",
        "x" to "ks", "w" to "v", "z" to "s", "j" to "s"
    )

    /**
     * A rough "how it sounds" code, tuned for Indian names heard by an
     * English recogniser.  preethi, pretty, priti -> PRT.  karthik, kartik -> KRTK.
     */
    fun soundKey(word: String): String {

        var s = word.lowercase().filter { it in 'a'..'z' }
        if (s.isEmpty()) return ""

        for ((a, b) in SOUND_SWAPS) s = s.replace(a, b)

        s = s.replace(Regex("c(?=[eiy])"), "s").replace("c", "k")

        val out = StringBuilder()
        if (s[0] in "aeiou") out.append('A')

        var last = ' '

        for (ch in s) {
            val c = SOUND_CLASSES[ch] ?: ' '
            if (c != ' ' && c != last) out.append(c)
            last = c
        }

        return out.toString()
    }

    private fun editDistance(a: String, b: String): Int {

        val d = Array(a.length + 1) { IntArray(b.length + 1) }

        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(
                    d[i - 1][j] + 1,
                    d[i][j - 1] + 1,
                    d[i - 1][j - 1] + cost
                )
            }
        }

        return d[a.length][b.length]
    }

    // The whole phrase joined ("pre thee" -> "prethee") plus each word on its own.
    private fun soundParts(x: String): List<String> {

        val t = x.lowercase().filter { it in 'a'..'z' || it == ' ' }.trim()
        if (t.isEmpty()) return emptyList()

        return listOf(t.replace(" ", "")) + t.split(" ").filter { it.length >= 2 }
    }

    private fun soundPair(ka: String, kb: String): Int {

        if (ka.length < 2 || kb.length < 2) return 0

        if (ka == kb) {
            return if (ka.length >= 3 || ka[0] == kb[0]) 55 else 0
        }

        if (ka.length >= 4 && kb.length >= 4 && editDistance(ka, kb) == 1) return 50

        return 0
    }

    /**
     * How well [heard] sounds like [name]:
     * 55 = same sound, 50 = one sound slipped, 0 = no.
     */
    fun soundScore(heard: String, name: String): Int {

        var best = 0

        for (a in soundParts(heard)) {
            for (b in soundParts(name)) {
                best = maxOf(best, soundPair(soundKey(a), soundKey(b)))
            }
        }

        return best
    }

    /**
     * For "Did you mean ...?" guesses: a small number means it sounds close,
     * 99 means not close at all.
     */
    fun soundNear(heard: String, name: String): Int {

        var best = 99

        for (a in soundParts(heard)) {
            for (b in soundParts(name)) {

                val ka = soundKey(a)
                val kb = soundKey(b)

                if (ka.length < 2 || kb.length < 2) continue

                val d = editDistance(ka, kb)

                if (d <= (maxOf(ka.length, kb.length) + 2) / 3) {
                    best = minOf(best, d)
                }
            }
        }

        return best
    }

    // ---------------------------------------------------------------
    // Learning from corrections
    //
    // She hears "call pretty" and calls the wrong person. You say
    // "no, I said preethi". She works out WHICH words she misheard and
    // remembers the fix together with the word before it
    // ("call pretty" -> "call preethi"), so it only fires in the same place:
    // "that was pretty good" is never touched.
    // ---------------------------------------------------------------

    /** said = the right words ("" means: you only told her she was wrong). */
    data class Correction(val said: String, val wrong: String? = null)

    /** A mishearing pinned down to words: heardSeg should have been intendedSeg. */
    data class Fix(
        val heardSeg: String,
        val intendedSeg: String,
        val fixedText: String,
        val key: String,
        val value: String
    )

    private val CORRECT_ASK = Regex(
        """^(?:no |nope )?(?:that is wrong|that's wrong|thats wrong|wrong|you misheard|you heard wrong|that's not what i said|thats not what i said|not what i said)$"""
    )
    private val CORRECT_SAID = Regex(
        """^(?:(?:no|nope|wrong) )?(?:i said|i meant|i was saying) (.+)$"""
    )
    private val CORRECT_NOT = Regex("""^(?:no |nope )?not (.+)$""")

    // "i said that ...", "i meant to ..." are ordinary sentences, not corrections.
    private val NOT_A_FIX = setOf("to", "that", "it", "this", "about", "if", "you", "we", "they", "he", "she")
    private val CONNECTORS = setOf("but", "it's", "its", "it", "is")

    private fun okSaid(said: String): Boolean {
        val w = said.split(" ").filter { it.isNotEmpty() }
        return w.isNotEmpty() && w.size <= 8 && w[0] !in NOT_A_FIX
    }

    private fun wordsOf(x: String): List<String> =
        norm(x).split(" ").filter { it.isNotEmpty() }

    private fun indexOfSub(h: List<String>, w: List<String>): Int {
        if (w.isEmpty() || w.size > h.size) return -1
        for (i in 0..(h.size - w.size)) {
            if (h.subList(i, i + w.size) == w) return i
        }
        return -1
    }

    /**
     * Is [raw] a correction of what she heard last ([heard])?
     *   "no i said preethi"   "i meant call preethi"   "not pretty preethi"   "wrong"
     * Returns null for anything else.
     */
    fun parseCorrection(raw: String, heard: String): Correction? {

        val t = norm(raw)
        if (t.isEmpty() || heard.isBlank()) return null

        if (CORRECT_ASK.matches(t)) return Correction("")

        val said = CORRECT_SAID.matchEntire(t)
        if (said != null) {
            val text = said.groupValues[1].trim()
            return if (okSaid(text)) Correction(text) else null
        }

        val negated = CORRECT_NOT.matchEntire(t)
        if (negated != null) {

            val toks = negated.groupValues[1].split(" ").filter { it.isNotEmpty() }
            val h = " " + wordsOf(heard).joinToString(" ") + " "

            // Find the longest start of the sentence that she actually heard.
            for (k in (toks.size - 1) downTo 1) {

                val wrong = toks.subList(0, k).joinToString(" ")
                if (!h.contains(" $wrong ")) continue

                var rest = toks.subList(k, toks.size)
                while (rest.isNotEmpty() && rest[0] in CONNECTORS) rest = rest.subList(1, rest.size)
                if (rest.size >= 2 && rest[0] == "i" && (rest[1] == "said" || rest[1] == "meant")) {
                    rest = rest.subList(2, rest.size)
                }
                if (rest.isEmpty()) continue

                val right = rest.joinToString(" ")
                return if (okSaid(right)) Correction(right, wrong) else null
            }
        }

        return null
    }

    /**
     * Which of the heard words were wrong, and what should they have been?
     * [isCommand] tells whether a phrase is a complete command or question on its own.
     */
    fun diffFix(heard: String, c: Correction, isCommand: (String) -> Boolean): Fix? {

        val h = wordsOf(heard)
        val s = wordsOf(c.said)

        if (h.isEmpty() || s.isEmpty() || h == s) return null

        val seg = locate(h, s, c.wrong, isCommand)

        return build(h, seg.first, seg.second, seg.third)
    }

    // (start, how many heard words, replacement words)
    private fun locate(
        h: List<String>,
        s: List<String>,
        wrong: String?,
        isCommand: (String) -> Boolean
    ): Triple<Int, Int, List<String>> {

        // 1. They named the wrong words: "not pretty, preethi".
        if (wrong != null) {
            val w = wrong.split(" ").filter { it.isNotEmpty() }
            val at = indexOfSub(h, w)
            if (at >= 0) return Triple(at, w.size, s)
        }

        // 2. They repeated the whole command: peel off what both versions share.
        var pre = 0
        while (pre < h.size && pre < s.size && h[pre] == s[pre]) pre++

        var suf = 0
        while (
            suf < h.size - pre &&
            suf < s.size - pre &&
            h[h.size - 1 - suf] == s[s.size - 1 - suf]
        ) suf++

        val whole = s.joinToString(" ")

        if (s.size >= 2 && (pre >= 1 || (suf >= 1 && isCommand(whole)))) {
            val heardLen = h.size - pre - suf
            val mid = s.subList(pre, s.size - suf)
            if (heardLen > 0 && mid.isNotEmpty()) return Triple(pre, heardLen, mid)
            return Triple(0, h.size, s)
        }

        // 3. A brand new command with nothing in common.
        if (s.size >= 2 && isCommand(whole)) return Triple(0, h.size, s)

        // 4. Just the fixed words ("preethi"): replace the heard words that sound most like them.
        val target = s.joinToString("")
        val targetKey = soundKey(target)

        var bestScore = -1.0
        var bestRaw = 0.0
        var bestStart = 0
        var bestLen = 1

        for (len in 1..minOf(3, h.size)) {
            for (st in 0..(h.size - len)) {

                val seg = h.subList(st, st + len).joinToString("")

                var raw = similarity(seg, target)
                if (targetKey.length >= 2 && soundKey(seg) == targetKey) raw += 0.5

                val adj = raw - 0.02 * len

                if (adj >= bestScore) {
                    bestScore = adj
                    bestRaw = raw
                    bestStart = st
                    bestLen = len
                }
            }
        }

        if (bestRaw >= 0.3) return Triple(bestStart, bestLen, s)

        // Nothing sounds alike: names and objects come last, so replace the tail.
        val n = minOf(s.size, h.size)
        return Triple(h.size - n, n, s)
    }

    private fun build(h: List<String>, start: Int, len: Int, rep: List<String>): Fix? {

        val heardSeg = h.subList(start, start + len).joinToString(" ")
        val intendedSeg = rep.joinToString(" ")

        if (heardSeg == intendedSeg) return null

        val fixed = h.subList(0, start) + rep + h.subList(start + len, h.size)

        val whole = start == 0 && len == h.size

        // Keep one neighbouring word as context, so the fix only fires in the same place.
        val key: String
        val value: String

        if (!whole && start > 0) {
            key = h[start - 1] + " " + heardSeg
            value = h[start - 1] + " " + intendedSeg
        } else if (!whole && start + len < h.size) {
            key = heardSeg + " " + h[start + len]
            value = intendedSeg + " " + h[start + len]
        } else {
            key = heardSeg
            value = intendedSeg
        }

        return Fix(heardSeg, intendedSeg, fixed.joinToString(" "), key, value)
    }

    /** Applies everything she has learned from corrections to what she just heard. */
    fun applyHeard(raw: String): String {

        val map = Brain.map("heard")
        if (map.isEmpty()) return raw

        var out = raw

        for ((k, v) in map.entries.sortedByDescending { it.key.length }) {
            if (k.isEmpty() || k == v) continue
            val rx = Regex("""\b""" + Regex.escape(k) + """\b""", RegexOption.IGNORE_CASE)
            if (rx.containsMatchIn(out)) out = rx.replace(out, Regex.escapeReplacement(v))
        }

        return out
    }

    // ---------------------------------------------------------------
    // Facts you teach her: "chief minister of tamil nadu is joseph vijay"
    //
    // Saved in the Brain file as  fact | chief minister of tamil nadu = Joseph Vijay
    // When you later ask "who is the chief minister of tamil nadu", what you
    // taught her is used FIRST, before the internet.
    // ---------------------------------------------------------------

    private val ROLE_WORDS =
        "president|vice president|prime minister|chief minister|deputy chief minister|minister|" +
            "governor|mayor|ceo|chairman|director|principal|captain|coach|leader|founder|" +
            "speaker|collector|commissioner|capital|owner|head|winner"

    // "remember that X is Y", "learn that X is Y", "teach you X is Y"
    private val FACT_EXPLICIT = Regex(
        """^(?:remember|learn|note|know|teach you|i teach you|i am teaching you)(?: that)? (.+?) is (.+)$"""
    )

    // Without "remember", for facts that start with a role: "chief minister of tamil nadu is ..."
    private val FACT_ROLE = Regex(
        """^((?:the )?(?:current )?(?:$ROLE_WORDS) of .+?) is (.+)$"""
    )

    private val FACTS_LIST = Regex(
        """^(?:what facts do you know|list my facts|what have i taught you|show my facts)$"""
    )

    private val SECRET_WORDS = Regex(
        """\b(?:password|passcode|pin|otp|cvv|card|secret|key)\b"""
    )

    private val WH_WORDS = setOf("who", "what", "which", "where", "when", "why", "how")

    private val ASK_LEAD = setOf(
        "tell", "me", "give", "about", "who", "whos", "what", "whats", "which", "where",
        "when", "is", "are", "was", "were", "the", "name", "of", "current", "present"
    )

    private val ASK_TRAIL = setOf("now", "today", "currently", "right", "please")

    private val SMALL_WORDS = setOf("of", "the", "and", "in", "for", "on", "at")

    // "the current chief minister of tamil nadu" -> "chief minister of tamil nadu"
    private fun factSubject(raw: String): String {

        var s = Brain.clean(raw)

        while (true) {
            val next = s.removePrefix("the ").removePrefix("current ").removePrefix("present ")
            if (next == s) break
            s = next
        }

        return s.trim()
    }

    // (subject, answer) if this is a fact she should learn, else null.
    private fun factParts(t: String): Pair<String, String>? {

        val m = FACT_EXPLICIT.matchEntire(t) ?: FACT_ROLE.matchEntire(t) ?: return null

        val subject = factSubject(m.groupValues[1])
        val answer = m.groupValues[2].trim()

        if (subject.isEmpty() || answer.isEmpty()) return null

        // Personal facts and secrets keep using "remember my ..." (encrypted when secret).
        if (subject == "my" || subject.startsWith("my ")) return null
        if (SECRET_WORDS.containsMatchIn(subject)) return null

        // "ravi is my friend" belongs to the people feature.
        if (answer.startsWith("my ") || answer.startsWith("your ")) return null

        // "president of india is who" is a question, not a fact.
        if (answer.split(" ")[0] in WH_WORDS) return null

        if (subject.split(" ").size > 9 || answer.split(" ").size > 14) return null

        return Pair(subject, answer)
    }

    private fun isFactSet(t: String): Boolean = factParts(t) != null

    // Spoken text arrives in lower case: "joseph vijay" -> "Joseph Vijay".
    private fun nameCase(v: String): String {

        val t = v.trim()
        if (t.any { it.isUpperCase() }) return t

        val w = t.split(" ").filter { it.isNotEmpty() }
        if (w.isEmpty() || w.size > 4) return t

        return w.joinToString(" ") { x -> x.replaceFirstChar { c -> c.uppercase() } }
    }

    // "chief minister of tamil nadu" -> "Chief Minister of Tamil Nadu"
    private fun subjectCase(s: String): String =
        s.split(" ")
            .filter { it.isNotEmpty() }
            .mapIndexed { i, w ->
                if (i > 0 && w in SMALL_WORDS) w else w.replaceFirstChar { c -> c.uppercase() }
            }
            .joinToString(" ")

    private fun factSet(t: String): String {

        val p = factParts(t) ?: return "I didn't catch that."

        val answer = nameCase(p.second)

        Brain.link("fact", p.first, answer)

        return "Got it. ${subjectCase(p.first)} is $answer. " +
            "I'll answer that from now on, even if the internet says something else."
    }

    private fun factsList(): String {

        val f = Brain.map("fact")

        if (f.isEmpty()) {
            return "You haven't taught me any facts yet. Say, for example: " +
                "remember that the capital of india is new delhi."
        }

        return "I know ${f.size} things you taught me: " +
            f.entries.take(6).joinToString("; ") {
                subjectCase(it.key) + " is " + nameCase(it.value)
            } + "."
    }

    // Same words, or words that SOUND the same ("nado" and "nadu"): a misheard
    // place name still finds your fact, but "karnataka" never matches "kerala".
    private fun sameWords(a: List<String>, b: List<String>): Boolean {

        if (a.size != b.size) return false

        var differing = 0

        for (i in a.indices) {
            if (a[i] == b[i]) continue
            val ka = soundKey(a[i])
            if (ka.length < 2 || ka != soundKey(b[i])) return false
            differing++
        }

        return differing <= 2
    }

    /**
     * What YOU taught her about this question, or null.
     * "who is the chief minister of tamil nadu now" -> "Chief Minister of Tamil Nadu is Joseph Vijay."
     */
    fun taughtAnswer(question: String): String? {

        val facts = Brain.map("fact")
        if (facts.isEmpty()) return null

        val toks = Brain.clean(question).split(" ").filter { it.isNotEmpty() }.toMutableList()

        while (toks.isNotEmpty() && toks[0] in ASK_LEAD) toks.removeAt(0)
        while (toks.isNotEmpty() && toks[toks.size - 1] in ASK_TRAIL) toks.removeAt(toks.size - 1)

        if (toks.isEmpty()) return null

        val subject = toks.joinToString(" ")

        val exact = facts[subject]
        if (exact != null) return subjectCase(subject) + " is " + nameCase(exact) + "."

        // Longer subjects only: tolerate a misheard word that sounds the same.
        if (toks.size < 3) return null

        for ((k, v) in facts) {
            if (sameWords(toks, k.split(" "))) {
                return subjectCase(k) + " is " + nameCase(v) + "."
            }
        }

        return null
    }
}
