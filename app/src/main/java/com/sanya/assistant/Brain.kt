package com.sanya.assistant

import android.content.Context
import java.io.File

// Sanya's editable memory: one plain-text file, edited by hand (in the app) and by Sanya herself.
object Brain {
    private var file: File? = null

    private val STARTER = """
# SANYA BRAIN - edit freely. One rule per line. Lines starting with # are ignored.
# Sanya also edits this file herself when you teach her by voice.
#
#   nick    | name1, name2 = real contact or app name     e.g. ammu, amma = revathi
#   slang   | phrase1, phrase2 = what to do               e.g. movie time = open youtube and search comedy
#   word    | your word = my word                         e.g. thira = open
#   filler  | words to ignore                             e.g. da, bro, machan
#   fact    | what = answer                               e.g. favorite color = blue
#   setting | wake words / language / name = value
#   todo    | text        note | text
setting | wake words = sanya, saniya, sunya, sonya, sonia
setting | language = en-IN
filler | da, bro, machan, macha, dude, buddy
nick | yt = youtube
nick | insta = instagram
nick | gpay = google pay
""".trimIndent() + "\n"

    fun setup(c: Context) {
        if (file == null) file = File(c.filesDir, "brain.txt")
        if (!file!!.exists()) file!!.writeText(STARTER)
    }

    private fun f() = file!!
    fun text(): String = f().readText()
    fun save(t: String) { f().writeText(if (t.endsWith("\n")) t else t + "\n") }

    fun clean(x: String) = x.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()

    private data class Row(val type: String, val left: String, val right: String)

    private fun parse(line: String): Row? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return null
        val bar = t.indexOf('|')
        if (bar < 0) return null
        val type = t.substring(0, bar).trim().lowercase()
        val rest = t.substring(bar + 1)
        val eq = rest.indexOf('=')
        return if (eq < 0) Row(type, rest.trim(), "") else Row(type, rest.substring(0, eq).trim(), rest.substring(eq + 1).trim())
    }

    private fun rows(type: String) = f().readLines().mapNotNull { parse(it) }.filter { it.type == type }
    private fun aliases(s: String) = s.split(",").map { clean(it) }.filter { it.isNotEmpty() }
    private fun write(lines: List<String>) { f().writeText(lines.joinToString("\n") + "\n") }

    // alias -> value, for nick / slang / word / fact
    fun map(type: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (r in rows(type)) for (a in aliases(r.left)) m[a] = r.right
        return m
    }
    fun words(type: String): List<String> = rows(type).flatMap { aliases(it.left) }
    fun items(type: String): List<String> = rows(type).map { if (it.right.isEmpty()) it.left else it.left + " = " + it.right }
    fun setting(key: String, def: String): String =
        rows("setting").firstOrNull { clean(it.left) == key }?.right?.takeIf { it.isNotBlank() } ?: def

    // alias -> value. Aliases with the same value share one line. An alias lives on only one line.
    fun link(type: String, alias: String, value: String) {
        val a = clean(alias)
        if (a.isEmpty()) return
        val out = mutableListOf<String>()
        for (ln in f().readLines()) {
            val r = parse(ln)
            if (r != null && r.type == type && r.right.isNotEmpty()) {
                val keep = r.left.split(",").map { it.trim() }.filter { it.isNotEmpty() && clean(it) != a }
                if (keep.isNotEmpty()) out += "$type | ${keep.joinToString(", ")} = ${r.right}"
            } else out += ln
        }
        val i = out.indexOfFirst { val r = parse(it); r != null && r.type == type && r.right.equals(value, true) }
        if (i >= 0) {
            val r = parse(out[i])!!
            out[i] = "$type | ${r.left}, $a = ${r.right}"
        } else out += "$type | $a = $value"
        write(out)
    }

    // one value per key (settings)
    fun put(type: String, key: String, value: String) {
        val k = clean(key)
        val out = f().readLines().filterNot { val r = parse(it); r != null && r.type == type && clean(r.left) == k }.toMutableList()
        out += "$type | $k = $value"
        write(out)
    }

    fun unlink(alias: String): Boolean {
        val a = clean(alias)
        var found = false
        val out = mutableListOf<String>()
        for (ln in f().readLines()) {
            val r = parse(ln)
            if (r != null && r.right.isNotEmpty() && r.type in listOf("nick", "slang", "word", "fact", "heard") && aliases(r.left).contains(a)) {
                found = true
                val keep = r.left.split(",").map { it.trim() }.filter { it.isNotEmpty() && clean(it) != a }
                if (keep.isNotEmpty()) out += "${r.type} | ${keep.joinToString(", ")} = ${r.right}"
            } else out += ln
        }
        if (found) write(out)
        return found
    }

    fun add(type: String, text: String) {
        val t = f().readText()
        f().writeText((if (t.isEmpty() || t.endsWith("\n")) t else t + "\n") + "$type | $text\n")
    }
    fun drop(type: String, contains: String) {
        write(f().readLines().filterNot { val r = parse(it); r != null && r.type == type && r.left.contains(contains, true) })
    }
    fun clear(type: String) { write(f().readLines().filterNot { parse(it)?.type == type }) }

    // ------------------------------------------------------------------
    // For the Brain editor screen (BrainActivity): row-level reading and editing.
    //
    // Every change below re-reads the file and touches exactly ONE line, so the
    // editor can never overwrite something Sanya learned while the screen was open.
    // ------------------------------------------------------------------

    /** One rule: its parts, plus the exact line it came from (used to find it again). */
    data class Item(val type: String, val left: String, val right: String, val line: String)

    // Types whose whole text is one field (no "left = right").
    private val SINGLE = setOf("filler", "todo", "note")

    fun isSingle(type: String): Boolean = type in SINGLE

    fun format(type: String, left: String, right: String): String =
        if (right.isEmpty()) "$type | $left" else "$type | $left = $right"

    /** Every rule of [type], in file order. */
    fun entries(type: String): List<Item> {

        val out = ArrayList<Item>()

        for (ln in f().readLines()) {

            val r = parse(ln) ?: continue
            if (r.type != type) continue

            if (type in SINGLE) {
                out.add(Item(type, ln.substring(ln.indexOf('|') + 1).trim(), "", ln))
            } else {
                out.add(Item(type, r.left, r.right, ln))
            }
        }

        return out
    }

    /** Lines that are not a known rule type (typos, damaged lines), so nothing is invisible. */
    fun stray(known: Set<String>): List<Item> {

        val out = ArrayList<Item>()

        for (ln in f().readLines()) {

            val t = ln.trim()
            if (t.isEmpty() || t.startsWith("#")) continue

            val r = parse(ln)
            if (r == null || r.type !in known) out.add(Item("other", t, "", ln))
        }

        return out
    }

    /**
     * Replace (or, with null, delete) one exact line.
     * Returns false if that line is gone because the file changed in the meantime.
     */
    fun replaceLine(old: String, new: String?): Boolean {

        val lines = f().readLines().toMutableList()
        val i = lines.indexOf(old)

        if (i < 0) return false

        if (new == null) lines.removeAt(i) else lines[i] = new

        write(lines)

        return true
    }

    /** Add one raw line at the end. */
    fun appendLine(line: String) {
        val t = f().readText()
        f().writeText((if (t.isEmpty() || t.endsWith("\n")) t else t + "\n") + line + "\n")
    }

    // One-step undo for "Restore": the brain as it was just before.
    private fun safetyFile() = File(f().parentFile, "brain.before-restore.txt")

    fun keepSafetyCopy() { safetyFile().writeText(f().readText()) }

    fun hasSafetyCopy(): Boolean = safetyFile().exists()

    fun undoRestore(): Boolean {
        val b = safetyFile()
        if (!b.exists()) return false
        f().writeText(b.readText())
        b.delete()
        return true
    }

    // Words the speech recognizer should expect (wake words, nicknames, slang)
    fun hints(): ArrayList<String> = ArrayList<String>().apply {
        addAll(setting("wake words", "sanya").split(",").map { it.trim() }.filter { it.isNotEmpty() })
        addAll(map("nick").keys.take(40))
        addAll(map("slang").keys.take(40))
    }
}
