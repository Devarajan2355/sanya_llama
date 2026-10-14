package com.sanya.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The Brain editor: Sanya's rules, organised.
 *
 * Same single file underneath (brain.txt), so Backup / Share still works in one tap.
 * What changes is how you see and edit it:
 *   - one section per kind of rule (Nicknames, Slang, Corrections, Facts, ...)
 *   - one card per rule: tap to edit, X to delete, + Add for a new one
 *   - search
 *   - every change touches ONE line (Brain.replaceLine / appendLine), re-reading the
 *     file first, so it can never overwrite something Sanya learned meanwhile
 *   - "Other" shows lines that are not a known rule, so a typo is never invisible
 */
class BrainActivity : Activity() {

    private class Section(
        val type: String,
        val title: String,
        val left: String,          // label of the first box
        val right: String?,        // label of the second box, or null if there is only one box
        val example: String
    )

    private val sections = listOf(
        Section("nick", "Nicknames", "Names (separate with commas)", "Real contact or app", "ammu, amma   →   revathi"),
        Section("slang", "Slang", "Phrases (separate with commas)", "What she should do", "movie time   →   open youtube and search comedy"),
        Section("word", "Word swaps", "Your words (separate with commas)", "Means", "thira   →   open"),
        Section("filler", "Fillers", "Words to ignore (separate with commas)", null, "da, bro, machan"),
        Section("fact", "Facts", "Subject", "Answer", "chief minister of tamil nadu   →   Joseph Vijay"),
        Section("heard", "Corrections", "What she heard", "What you really said", "call pretty   →   call preethi"),
        Section("person", "People", "Name", "Relation", "ammu   →   mother"),
        Section("date", "Dates", "Who and what", "Date", "ammu birthday   →   12 march"),
        Section(
            "routine", "Routines", "Activity",
            "When: daily / weekly mon wed / monthly 1 / yearly 12 march. Add a time like 06:30 for a reminder, and 'for 2h' for a study target",
            "japanese   →   daily 19:00 for 2h"
        ),
        Section("category", "Money categories", "Words (separate with commas)", "Category", "tea, chai, snacks   →   food"),
        Section("setting", "Settings", "Setting", "Value", "hearing   →   google"),
        Section("todo", "To-do", "Item", null, "buy milk"),
        Section("note", "Notes", "Note", null, "buy charger"),
        Section("other", "Other", "Whole line", null, "Lines she does not recognise show up here")
    )

    private val known = sections.map { it.type }.toSet()

    private var current = sections[0]
    private var filter = ""

    private lateinit var chipRow: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var exampleView: TextView
    private lateinit var undoBtn: Button

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun smallBtn(label: String, w: Float, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Brain.setup(this)

        val wanted = intent?.getStringExtra("section")
        val start = sections.firstOrNull { it.type == wanted }
        if (start != null) current = start

        val pad = dp(12)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        // Title and Done
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "Sanya's brain"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(Button(this).apply {
            text = "Done"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        root.addView(top)

        // Backup, Restore, Undo restore
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(smallBtn("Backup", 1f) { backup() })
        tools.addView(smallBtn("Restore", 1f) { restore() })
        undoBtn = smallBtn("Undo restore", 1f) { undoRestore() }
        tools.addView(undoBtn)
        root.addView(tools)

        // Section chips
        val scroller = HorizontalScrollView(this)
        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scroller.addView(chipRow)
        root.addView(scroller)

        // Search
        searchBox = EditText(this).apply {
            hint = "Search this section"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    filter = s?.toString()?.trim()?.lowercase() ?: ""
                    renderList()
                }
            })
        }
        root.addView(searchBox)

        exampleView = TextView(this).apply {
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(exampleView)

        // The list of rules
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox)
        root.addView(scroll)

        root.addView(Button(this).apply {
            text = "+ Add"
            isAllCaps = false
            setOnClickListener { edit(null) }
        })

        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()      // pick up anything Sanya learned while this screen was in the background
    }

    // ---------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------

    private fun entriesOf(s: Section): List<Brain.Item> =
        if (s.type == "other") Brain.stray(known) else Brain.entries(s.type)

    private fun render() {
        renderChips()
        renderList()
        undoBtn.visibility = if (Brain.hasSafetyCopy()) View.VISIBLE else View.GONE
    }

    private fun renderChips() {

        chipRow.removeAllViews()

        for (s in sections) {

            val n = entriesOf(s).size
            val selected = s.type == current.type

            chipRow.addView(Button(this).apply {
                text = s.title + " (" + n + ")"
                isAllCaps = false
                alpha = if (selected) 1f else 0.55f
                if (selected) setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    current = s
                    filter = ""
                    searchBox.setText("")
                    render()
                }
            })
        }
    }

    private fun renderList() {

        exampleView.text = "Example: " + current.example

        listBox.removeAllViews()

        val all = entriesOf(current)
        val shown = all.filter { filter.isEmpty() || it.line.lowercase().contains(filter) }

        if (shown.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = if (all.isEmpty()) "Nothing here yet. Tap + Add." else "No match."
                setPadding(0, dp(16), 0, 0)
            })
            return
        }

        for (e in shown) listBox.addView(row(e))
    }

    private fun pretty(e: Brain.Item): String =
        if (e.right.isEmpty()) e.left else e.left + "   →   " + e.right

    private fun row(e: Brain.Item): View {

        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        r.addView(TextView(this).apply {
            text = pretty(e)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { edit(e) }
        })

        r.addView(Button(this).apply {
            text = "✕"
            isAllCaps = false
            setOnClickListener { confirmDelete(e) }
        })

        return r
    }

    // ---------------------------------------------------------------
    // Add, edit, delete
    // ---------------------------------------------------------------

    private fun field(hintText: String, value: String) = EditText(this).apply {
        hint = hintText
        setText(value)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    private fun edit(existing: Brain.Item?) {

        val s = current

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val first = field(s.left, existing?.left ?: "")
        box.addView(first)

        val second: EditText? =
            if (s.right != null) field(s.right, existing?.right ?: "").also { box.addView(it) } else null

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add to " + s.title else "Edit")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                save(existing, first.text.toString(), second?.text?.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save(existing: Brain.Item?, leftIn: String, rightIn: String?) {

        val s = current
        val left = leftIn.trim()
        val right = (rightIn ?: "").trim()

        if (left.isEmpty()) return toast("Nothing to save")

        val line: String

        if (s.type == "other") {
            line = left
        } else {
            if (left.contains('|') || right.contains('|')) return toast("Please don't use the | character")
            if (s.right != null && right.isEmpty()) return toast("Please fill in both boxes")
            if (s.right != null && left.contains('=')) return toast("Please don't use = in the first box")
            if (s.type == "routine" && Routines.parseSpec(right) == null) {
                return toast("I can't read that schedule. Try: daily 06:30, weekly mon wed, monthly 1, yearly 12 march, or add 'for 2h' for a study target")
            }
            line = Brain.format(s.type, left, right)
        }

        val ok =
            if (existing == null) {
                Brain.appendLine(line)
                true
            } else {
                Brain.replaceLine(existing.line, line)
            }

        render()

        if (!ok) {
            toast("That rule changed while you were editing, so nothing was saved. Try again.")
            return
        }

        // A slang rule is only useful if she can run its command.
        if (s.type == "slang") {
            val steps = CommandParser.parse(right)
            val understood = steps.isNotEmpty() && steps.none { it is Step.Unknown }
            if (!understood && !IntentEngine.isGeneralQuestion(right)) {
                toast("Saved. But I may not understand \"" + right + "\" yet.")
            }
        }
    }

    private fun confirmDelete(e: Brain.Item) {

        AlertDialog.Builder(this)
            .setTitle("Delete this rule?")
            .setMessage(pretty(e))
            .setPositiveButton("Delete") { _, _ ->
                if (!Brain.replaceLine(e.line, null)) toast("That rule had already changed.")
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------
    // Backup and restore (the whole brain, as plain text)
    // ---------------------------------------------------------------

    private fun backup() {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, Brain.text()),
                "Back up Sanya's brain"
            )
        )
    }

    private fun clipboardText(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(this)?.toString() ?: ""
    }

    private fun restore() {

        val text = clipboardText()
        val looksLikeBrain = Regex("""(?m)^\s*[a-z]+\s*\|""").containsMatchIn(text)

        if (!looksLikeBrain) {
            toast("First copy your backup text, then tap Restore.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Replace everything?")
            .setMessage("Sanya's whole brain will be replaced by the copied backup. You can undo this once.")
            .setPositiveButton("Restore") { _, _ ->
                Brain.keepSafetyCopy()
                Brain.save(text)
                render()
                toast("Restored")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun undoRestore() {
        if (Brain.undoRestore()) toast("Brought back the brain from before the restore") else toast("Nothing to undo")
        render()
    }
}
