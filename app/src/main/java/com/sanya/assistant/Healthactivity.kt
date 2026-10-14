package com.sanya.assistant

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Health check: is Sanya set up properly, and what should be fixed?
 *
 *   - every check shows a green tick, a warning, or a red cross, with a plain explanation
 *   - a Fix button where one tap can help (allow a permission, open the right settings page)
 *   - the last errors she recorded, with Copy / Share so you can send the exact failure
 *
 * Open it from the start screen, or say "run a health check".
 */
class HealthActivity : Activity() {

    private lateinit var box: LinearLayout

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Brain.setup(this)
        ErrorLog.install(this)

        val pad = dp(12)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "Health check"
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

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(tool("Check again") { render() })
        tools.addView(tool("Copy report") { copyReport() })
        tools.addView(tool("Share report") { shareReport() })
        root.addView(tools)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(box)
        root.addView(scroll)

        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()      // come back from a settings page and see the result
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        render()
    }

    private fun tool(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    // ---------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------

    private fun render() {

        box.removeAllViews()

        val checks = Health.run(this)

        val bad = checks.count { it.level == Health.Level.BAD }
        val warn = checks.count { it.level == Health.Level.WARN }

        box.addView(TextView(this).apply {
            text = when {
                bad > 0 -> "$bad problem(s) to fix" + (if (warn > 0) " and $warn warning(s)" else "")
                warn > 0 -> "$warn warning(s). Nothing is broken."
                else -> "Everything looks fine"
            }
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        })

        var category = ""

        for (c in checks) {

            if (c.category != category) {
                category = c.category
                box.addView(TextView(this).apply {
                    text = category.uppercase()
                    textSize = 12f
                    alpha = 0.6f
                    setPadding(0, dp(14), 0, dp(2))
                })
            }

            box.addView(row(c))
        }

        errors()
    }

    private fun row(c: Health.Check): View {

        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }

        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        words.addView(TextView(this).apply {
            text = c.level.icon + "  " + c.title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })

        words.addView(TextView(this).apply {
            text = c.detail
            textSize = 13f
            alpha = 0.8f
        })

        line.addView(words)

        val fix = c.fix

        if (fix != null) {
            line.addView(Button(this).apply {
                text = "Fix"
                isAllCaps = false
                setOnClickListener { fixIt(fix) }
            })
        }

        return line
    }

    private fun errors() {

        box.addView(TextView(this).apply {
            text = "LAST ERRORS"
            textSize = 12f
            alpha = 0.6f
            setPadding(0, dp(18), 0, dp(2))
        })

        val list = ErrorLog.recent(this, 10)

        if (list.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "None recorded."
                textSize = 13f
            })
            return
        }

        for (e in list) {
            box.addView(TextView(this).apply {
                text = ErrorLog.stamp(e.time) + "  " + e.where + "\n" + e.message
                textSize = 12f
                setPadding(0, dp(4), 0, dp(4))
            })
        }

        box.addView(Button(this).apply {
            text = "Clear errors"
            isAllCaps = false
            setOnClickListener {
                ErrorLog.clear(this@HealthActivity)
                render()
            }
        })
    }

    // ---------------------------------------------------------------
    // Fix buttons
    // ---------------------------------------------------------------

    private fun launch(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            ErrorLog.add(this, "Health fix", e)
            toast("That settings page did not open on this phone.")
        }
    }

    private fun fixIt(fix: String) {

        when {

            fix.startsWith("perm:") ->
                requestPermissions(arrayOf(fix.removePrefix("perm:")), 21)

            fix == "overlay" ->
                launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))

            fix == "battery" ->
                launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

            fix == "appsettings" ->
                launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

            fix == "startscreen" ->
                launch(Intent(this, MainActivity::class.java))
        }
    }

    // ---------------------------------------------------------------
    // Report
    // ---------------------------------------------------------------

    private fun copyReport() {

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        cm.setPrimaryClip(ClipData.newPlainText("Sanya health report", Health.report(this)))

        toast("Report copied")
    }

    private fun shareReport() {

        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, Health.report(this)),
                "Share Sanya health report"
            )
        )
    }
}
