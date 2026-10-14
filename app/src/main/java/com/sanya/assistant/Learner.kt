package com.sanya.assistant

import android.content.Context
import java.io.File
import kotlin.math.ln

/**
 * Learner: Sanya's own small model. Trained only on you, on this phone.
 *
 * Nothing is downloaded and nothing is sent anywhere. It is a small statistical
 * model, not a neural network, and it learns from three things:
 *
 *   1. YOUR WORDS       which words you say, and which word follows which
 *                       ("call" then "preethi").  This is a personal language model.
 *   2. YOUR COMMANDS    the commands you really run, and how often.
 *   3. YOUR CORRECTIONS what you told her she got wrong (counted extra).
 *
 * What it does with that:
 *   - repair()         fixes a misheard word when YOU have said a different word that
 *                      SOUNDS THE SAME in exactly this place before.
 *                      "call pretty" -> "call preethi", "open you tube" -> "open youtube".
 *                      It never touches a word you use yourself, or a place you have
 *                      never said that pair, so "that was pretty good" is left alone.
 *   - choose()         when the recogniser offers several guesses, prefer the one
 *                      that sounds most like you.
 *   - nearestCommand() for a phrase she does not know, find the command you usually
 *                      run that is most like it, and ask "Did you mean ...?".
 *
 * It learns from the times she heard you right (for example with Google hearing)
 * and uses that to repair the times the offline ears get it wrong.
 *
 * Storage: files/learner.txt (private). Erase it with: "forget how i speak".
 */
object Learner {

    private const val FILE = "learner.txt"
    private const val START = "<s>"
    private const val SAVE_EVERY = 12
    private const val KEEP_WORDS = 1500
    private const val KEEP_PAIRS = 2500
    private const val KEEP_CMDS = 400
    private const val MIN_EXPERIENCE = 30      // words heard before she trusts her own statistics

    private val words = HashMap<String, Int>()     // word -> count   ("<s>" = number of sentences)
    private val pairs = HashMap<String, Int>()     // "previous word" -> count
    private val cmds = HashMap<String, Int>()      // command you ran -> count
    private val keys = HashMap<String, String>()   // word -> how it sounds (cache)

    private var totalWords = 0
    private var repairs = 0
    private var loaded = false
    private var dirty = 0

    // ---------------------------------------------------------------
    // Storage
    // ---------------------------------------------------------------

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun load(ctx: Context) {

        if (loaded) return
        loaded = true

        try {
            val f = file(ctx)
            if (f.exists()) {
                for (line in f.readLines()) {
                    val p = line.split("\t")
                    if (p.size < 3) continue
                    val n = p[2].toIntOrNull() ?: continue
                    when (p[0]) {
                        "w" -> words[p[1]] = n
                        "p" -> pairs[p[1]] = n
                        "c" -> cmds[p[1]] = n
                        "r" -> repairs = n
                    }
                }
            }
        } catch (e: Exception) {
            // A damaged file just means she starts learning again.
        }

        recount()
    }

    private fun recount() {
        var t = 0
        for ((w, n) in words) if (w != START) t += n
        totalWords = t
    }

    private fun save(ctx: Context) {

        try {
            val sb = StringBuilder()
            for ((k, v) in words) sb.append("w\t").append(k).append('\t').append(v).append('\n')
            for ((k, v) in pairs) sb.append("p\t").append(k).append('\t').append(v).append('\n')
            for ((k, v) in cmds) sb.append("c\t").append(k).append('\t').append(v).append('\n')
            sb.append("r\t-\t").append(repairs).append('\n')
            file(ctx).writeText(sb.toString())
        } catch (e: Exception) {
        }

        dirty = 0
    }

    private fun prune(m: HashMap<String, Int>, keep: Int) {
        if (m.size <= keep) return
        val drop = m.entries.sortedBy { it.value }.take(m.size - keep).map { it.key }
        for (k in drop) m.remove(k)
    }

    private fun touch(ctx: Context) {

        dirty++
        if (dirty < SAVE_EVERY) return

        prune(words, KEEP_WORDS)
        prune(pairs, KEEP_PAIRS)
        prune(cmds, KEEP_CMDS)
        if (keys.size > 6000) keys.clear()
        recount()
        save(ctx)
    }

    /** Write everything to disk now (called when the service stops). */
    @Synchronized
    fun flush(ctx: Context) {
        if (!loaded || dirty == 0) return
        save(ctx)
    }

    // ---------------------------------------------------------------
    // Learning
    // ---------------------------------------------------------------

    private fun tokens(text: String): List<String> =
        Brain.clean(text).split(" ").filter { it.isNotEmpty() }

    /**
     * Learn from something she understood.
     * weight: 1 = a normal command, 2 = you said yes to it, 3 = you corrected her.
     */
    @Synchronized
    fun observe(ctx: Context, text: String, weight: Int = 1) {

        load(ctx)

        val t = tokens(text)
        if (t.isEmpty() || t.size > 14) return

        words[START] = (words[START] ?: 0) + weight

        var prev = START

        for (w in t) {
            words[w] = (words[w] ?: 0) + weight
            val pair = "$prev $w"
            pairs[pair] = (pairs[pair] ?: 0) + weight
            totalWords += weight
            prev = w
        }

        touch(ctx)
    }

    /** Remember a command you ran. */
    @Synchronized
    fun observeCommand(ctx: Context, command: String) {

        load(ctx)

        val c = Brain.clean(command)
        if (c.length < 4 || c.length > 70) return

        cmds[c] = (cmds[c] ?: 0) + 1
        touch(ctx)
    }

    // ---------------------------------------------------------------
    // How much a sentence sounds like you
    // ---------------------------------------------------------------

    private fun logp(prev: String, w: String): Double {

        val uni = (words[w] ?: 0).toDouble()
        val pUni = (uni + 0.1) / (totalWords + 0.1 * (words.size + 1))

        val prevCount = (words[prev] ?: 0).toDouble()
        val pBi =
            if (prevCount > 0) (pairs["$prev $w"] ?: 0) / prevCount else 0.0

        return ln(0.7 * pBi + 0.3 * pUni)
    }

    private fun fluency(text: String): Double {

        val t = tokens(text)
        if (t.isEmpty()) return -99.0

        var prev = START
        var sum = 0.0

        for (w in t) {
            sum += logp(prev, w)
            prev = w
        }

        return sum / t.size
    }

    /**
     * Several guesses from the recogniser: pick the one that sounds most like you.
     * The first (most likely) guess keeps its place unless another is clearly better.
     */
    @Synchronized
    fun choose(ctx: Context, candidates: List<String>): String {

        if (candidates.isEmpty()) return ""
        if (candidates.size == 1) return candidates[0]

        load(ctx)

        if (totalWords < MIN_EXPERIENCE) return candidates[0]

        val first = fluency(candidates[0])

        var best = candidates[0]
        var bestScore = first + 0.8

        for (i in 1 until candidates.size) {
            val s = fluency(candidates[i]) - 0.1 * i
            if (s > bestScore) {
                bestScore = s
                best = candidates[i]
            }
        }

        return best
    }

    // ---------------------------------------------------------------
    // Repairing mishearings
    // ---------------------------------------------------------------

    private fun keyOf(w: String): String = keys.getOrPut(w) { Mind.soundKey(w) }

    // The word you have said most often after [prev] that sounds like [key].
    private fun soundsLike(prev: String, key: String, skip: String): String? {

        if (key.length < 3) return null

        var best: String? = null
        var bestSupport = 1

        for ((c, n) in words) {
            if (c == START || c == skip || n < 2) continue
            val support = pairs["$prev $c"] ?: 0
            if (support <= bestSupport) continue
            if (keyOf(c) == key) {
                best = c
                bestSupport = support
            }
        }

        return best
    }

    // (replacement, how many heard words it replaces), or null to leave the word alone.
    private fun fixFor(prev: String, w: String, next: String?): Pair<String, Int>? {

        if (w.length < 3) return null

        // You have said this word here before, or you use it a lot: it is yours. Leave it.
        if ((pairs["$prev $w"] ?: 0) >= 1) return null
        if ((words[w] ?: 0) >= 3) return null

        // Two heard words that are really one of your words: "you tube" -> youtube.
        if (next != null) {
            val joined = w + next
            if ((words[joined] ?: 0) >= 2 && (pairs["$prev $joined"] ?: 0) >= 1) {
                return Pair(joined, 2)
            }
        }

        // A word you say in this place that sounds the same: "pretty" -> preethi.
        val one = soundsLike(prev, keyOf(w), w)
        if (one != null) return Pair(one, 1)

        // Two heard words that sound like one of yours: "pre thee" -> preethi.
        if (next != null) {
            val two = soundsLike(prev, keyOf(w + next), w)
            if (two != null) return Pair(two, 2)
        }

        return null
    }

    /**
     * Fix words she probably misheard, using only things YOU have said before.
     * Returns [text] unchanged unless a repair was made.
     */
    @Synchronized
    fun repair(ctx: Context, text: String): String {

        load(ctx)

        if (totalWords < MIN_EXPERIENCE) return text

        val t = tokens(text)
        if (t.size < 2 || t.size > 14) return text

        val out = ArrayList<String>()
        var changed = false
        var i = 0

        while (i < t.size) {

            val prev = if (out.isEmpty()) START else out[out.size - 1]
            val next = if (i + 1 < t.size) t[i + 1] else null
            val fix = fixFor(prev, t[i], next)

            if (fix != null) {
                out.add(fix.first)
                i += fix.second
                changed = true
            } else {
                out.add(t[i])
                i += 1
            }
        }

        if (!changed) return text

        repairs++
        dirty++

        return out.joinToString(" ")
    }

    // ---------------------------------------------------------------
    // Your usual commands
    // ---------------------------------------------------------------

    /**
     * The command you have run that is most like [phrase], and how alike (0.0 to 1.0).
     * Commands you run often win ties.
     */
    @Synchronized
    fun nearestCommand(ctx: Context, phrase: String): Pair<String, Double>? {

        load(ctx)

        val p = Brain.clean(phrase)
        if (p.isEmpty() || cmds.isEmpty()) return null

        var best: String? = null
        var bestScore = 0.0

        for ((c, n) in cmds) {
            val s = Mind.similarity(p, c) + 0.02 * minOf(ln(1.0 + n), 3.0)
            if (s > bestScore) {
                bestScore = s
                best = c
            }
        }

        val b = best ?: return null

        return Pair(b, Mind.similarity(p, b))
    }

    // ---------------------------------------------------------------
    // About the model
    // ---------------------------------------------------------------

    @Synchronized
    fun stats(ctx: Context): String {

        load(ctx)

        if (totalWords < 10) {
            return "I've only just started learning how you speak. Keep talking to me."
        }

        val vocab = words.size - (if (words.containsKey(START)) 1 else 0)

        return "I know $vocab of your words, ${pairs.size} word pairs and ${cmds.size} commands you use. " +
            "I've repaired $repairs mishearings so far. " +
            if (totalWords < MIN_EXPERIENCE) {
                "I need a little more practice before I trust it."
            } else {
                "That's all learned from you, and it stays on this phone."
            }
    }

    @Synchronized
    fun reset(ctx: Context) {

        words.clear()
        pairs.clear()
        cmds.clear()
        keys.clear()
        totalWords = 0
        repairs = 0
        dirty = 0
        loaded = true

        try {
            file(ctx).delete()
        } catch (e: Exception) {
        }
    }
}
