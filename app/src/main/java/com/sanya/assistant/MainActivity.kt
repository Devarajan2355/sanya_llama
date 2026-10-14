package com.sanya.assistant

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var dl: Button
    private var loaded = ""

    private fun same(a: String, b: String) = a.trimEnd() == b.trimEnd()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ErrorLog.install(this)
        Brain.setup(this)
        val pad = (12 * resources.displayMetrics.density).toInt()
        fun btn(label: String, w: Float, click: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
        }
        fun wide(label: String, click: () -> Unit) = btn(label, 0f, click).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        loaded = Brain.text()
        editor = EditText(this).apply {
            setText(loaded)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        status = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, pad / 2) }
        dl = wide("Download offline voice (about 40 MB)") { download() }
        val start = wide("Start Sanya") { go() }.apply { textSize = 20f }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Save", 1f) { saveNow(); toast("Saved"); refreshModel() })
            addView(btn("Share / backup", 1f) { share() })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(start)
            addView(row)
            addView(status)
            addView(dl)
            addView(wide("Choose voice: US / Indian English") { switchVoice() })
            addView(wide("Hearing: offline / Google (better for Indian names)") { switchHearing() })
            addView(wide("Health check (what is wrong, and how to fix it)") { openScreen(HealthActivity::class.java, "HealthActivity") })
            addView(wide("My routines (chart and checklist)") { openScreen(RoutineActivity::class.java, "RoutineActivity") })
            addView(wide("My money (spent, earned, budget)") { openScreen(MoneyActivity::class.java, "MoneyActivity") })
            addView(wide("Study time (timer and log)") { openScreen(TimeActivity::class.java, "TimeActivity") })
            addView(wide("Edit rules (organised: nicknames, slang, facts...)") { openScreen(BrainActivity::class.java, "BrainActivity") })
            addView(wide("Keep Sanya alive (Xiaomi settings)") { keepAlive() })
            addView(wide("Download AI brain — reasoning, explanation, translation (~200 MB)") { downloadLLM() })
            addView(wide("Download offline knowledge base — Wikipedia facts (~100 MB)") { downloadKnowledge() })
            addView(wide("Download natural voice — Piper TTS, sounds more human (~60 MB)") { downloadVoice() })
            addView(editor)
        })
        refreshModel()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun refreshModel() {
        val url = ModelStore.currentUrl()
        val ok = ModelStore.ready(this)
        val have = listOf(ModelStore.DEFAULT_URL, ModelStore.INDIAN_URL)
            .filter { ModelStore.isInstalled(this, it) }
            .joinToString(", ") { ModelStore.label(it) }
            .ifEmpty { "none" }
        val google = Brain.setting("hearing", "offline") == "google"
        val ears = if (google) " Hearing: Google (online or offline pack)." else ""
        status.text = if (ok) {
            "Voice: ${ModelStore.label(url)} (ready). Downloaded: $have. Tap Start Sanya.$ears"
        } else {
            "Voice: ${ModelStore.label(url)} is not downloaded yet (one time, needs internet). Downloaded: $have.$ears"
        }
        dl.text = "Download ${ModelStore.label(url)} voice (about 40 MB)"
        dl.visibility = if (ok) View.GONE else View.VISIBLE

        // ---- LLM / knowledge / natural voice status ----
        val llmReady = LocalLLM.isInstalled(this)
        val wikiReady = OfflineKnowledge.isInstalled(this)
        val piperReady = NaturalVoice.isInstalled(this)

        val extras = buildString {
            if (!llmReady) append("\n• AI brain not downloaded yet (adds reasoning, translation, explanation).")
            if (!wikiReady) append("\n• Offline knowledge base not downloaded yet (adds Wikipedia facts offline).")
            if (!piperReady) append("\n• Natural voice not downloaded yet (sounds more human).")
        }
        if (extras.isNotEmpty()) {
            status.text = status.text.toString() + extras
        }

        val missing = missingScreens()
        if (missing.isNotEmpty()) {
            status.text = status.text.toString() +
                "\n\nSETUP PROBLEM: AndroidManifest.xml is missing " + missing.joinToString(" and ") +
                ". Add the line shown in FEATURES.md, then rebuild."
        }
    }

    // Screens that exist in the code but are not declared in AndroidManifest.xml cannot open.
    private fun missingScreens(): List<String> {

        val out = ArrayList<String>()

        val screens = listOf<Pair<Class<*>, String>>(
            Pair(BrainActivity::class.java, "BrainActivity"),
            Pair(RoutineActivity::class.java, "RoutineActivity"),
            Pair(HealthActivity::class.java, "HealthActivity"),
            Pair(MoneyActivity::class.java, "MoneyActivity"),
            Pair(TimeActivity::class.java, "TimeActivity")
        )

        for (s in screens) {
            try {
                packageManager.getActivityInfo(ComponentName(this, s.first), 0)
            } catch (e: Exception) {
                out.add(s.second)
            }
        }

        return out
    }

    private fun openScreen(cls: Class<*>, name: String) {
        try {
            startActivity(Intent(this, cls))
        } catch (e: Exception) {
            toast("Can't open $name. It is missing from AndroidManifest.xml. Add it and rebuild.")
        }
    }

    private fun download() {
        saveNow()
        dl.isEnabled = false
        Thread {
            val err = ModelStore.download(this) { p -> runOnUiThread { status.text = "Downloading… $p%" } }
            runOnUiThread {
                dl.isEnabled = true
                if (err == null) {
                    // The running service still holds the old model in memory; restart it on the next Start.
                    stopService(Intent(this, BubbleService::class.java))
                    refreshModel()
                } else status.text = "Download failed: $err"
            }
        }.start()
    }

    private fun downloadLLM() {
        if (LocalLLM.isInstalled(this)) { toast("AI brain already installed"); return }
        status.text = "Downloading AI brain…"
        Thread {
            val err = LocalLLM.download(this) { p ->
                runOnUiThread { status.text = "Downloading AI brain… $p%" }
            }
            runOnUiThread {
                if (err == null) {
                    status.text = "AI brain downloaded. Tap Start Sanya."
                    // Reload the running service so it picks up the new model.
                    stopService(Intent(this, BubbleService::class.java))
                    refreshModel()
                } else {
                    status.text = "AI brain download failed: $err"
                }
            }
        }.start()
    }

    private fun downloadKnowledge() {
        if (OfflineKnowledge.isInstalled(this)) { toast("Knowledge base already installed"); return }
        status.text = "Downloading offline knowledge base…"
        Thread {
            val err = OfflineKnowledge.download(this) { p ->
                runOnUiThread { status.text = "Downloading knowledge base… $p%" }
            }
            runOnUiThread {
                if (err == null) {
                    // Open the DB immediately so the running service can use it.
                    OfflineKnowledge.open(this)
                    status.text = "Knowledge base ready."
                    refreshModel()
                } else {
                    status.text = "Knowledge base download failed: $err"
                }
            }
        }.start()
    }

    private fun downloadVoice() {
        if (NaturalVoice.isInstalled(this)) { toast("Natural voice already installed"); return }
        status.text = "Downloading natural voice…"
        Thread {
            val err = NaturalVoice.download(this) { p ->
                runOnUiThread { status.text = "Downloading natural voice… $p%" }
            }
            runOnUiThread {
                if (err == null) {
                    stopService(Intent(this, BubbleService::class.java))
                    status.text = "Natural voice downloaded. Tap Start Sanya."
                    refreshModel()
                } else {
                    status.text = "Natural voice download failed: $err"
                }
            }
        }.start()
    }

    // Flips the "voice model url" line between the US and Indian English voices.
    // A voice that is already downloaded switches instantly (no re-download).
    private fun switchVoice() {
        saveNow()
        val next = if (ModelStore.currentUrl() == ModelStore.INDIAN_URL) ModelStore.DEFAULT_URL else ModelStore.INDIAN_URL
        Brain.put("setting", "voice model url", next)
        loaded = Brain.text()
        editor.setText(loaded)
        refreshModel()
        if (ModelStore.isInstalled(this, next)) {
            // Restart the service so the next Start loads this voice.
            stopService(Intent(this, BubbleService::class.java))
            toast("Switched to ${ModelStore.label(next)}. Tap Start Sanya.")
        } else {
            toast("${ModelStore.label(next)} is not downloaded yet. Tap the download button once.")
        }
    }

    // Flips "setting | hearing" between my own offline ears and Google's recogniser.
    // Tap Start Sanya afterwards so she picks it up.
    private fun switchHearing() {
        saveNow()
        val google = Brain.setting("hearing", "offline") == "google"
        Brain.put("setting", "hearing", if (google) "offline" else "google")
        loaded = Brain.text()
        editor.setText(loaded)
        toast(if (google) "Offline hearing. Tap Start Sanya." else "Google hearing (needs internet or the offline English-India pack). Tap Start Sanya.")
        refreshModel()
    }

    private fun keepAlive() {
        Toast.makeText(this, "Turn on Autostart. Set Battery saver to No restrictions. Allow 'Display pop-up windows while running in the background'.", Toast.LENGTH_LONG).show()
        val tries = listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        for (i in tries) { if (runCatching { startActivity(i) }.isSuccess) return }
    }

    private fun saveNow() {
        Brain.save(editor.text.toString())
        loaded = Brain.text()
    }

    private fun share() {
        saveNow()
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, Brain.text()), "Back up Sanya's brain"))
    }

    override fun onPause() {
        super.onPause()
        if (!same(editor.text.toString(), loaded)) saveNow()
    }

    override fun onResume() {
        super.onResume()
        refreshModel()
        val cur = Brain.text()
        if (same(editor.text.toString(), loaded) && !same(cur, loaded)) { editor.setText(cur); loaded = cur }
    }

    private fun go() {
        saveNow()
        if (!Settings.canDrawOverlays(this)) {
            // Turn on "Display over other apps" for Sanya, then come back and tap Start again
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val need = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1) else start()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, g: IntArray) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
    }

    private fun start() {
        getSharedPreferences("sanya", MODE_PRIVATE).edit().putBoolean("autostart", true).apply()
        startForegroundService(Intent(this, BubbleService::class.java))
        finish()
    }
}
