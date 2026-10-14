package com.sanya.assistant

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.SpeechRecognizer
import java.io.File

/**
 * Health: one place that checks whether Sanya is set up properly and says what to fix.
 * Used by the Health check screen and by the voice command "run a health check".
 *
 *   OK    working
 *   WARN  works, but something is limited or worth fixing
 *   BAD   something she needs is missing, so a feature will not work
 *   INFO  just information
 *
 * A check can carry a fix id that HealthActivity turns into a button:
 *   perm:<ANDROID PERMISSION>   ask for that permission
 *   overlay                     "Display over other apps" settings
 *   battery                     battery optimisation settings
 *   appsettings                 this app's settings page
 *   startscreen                 the start screen (download a voice, tap Start)
 */
object Health {

    enum class Level(val icon: String) {
        OK("✅"),
        WARN("⚠️"),
        BAD("❌"),
        INFO("ℹ️")
    }

    data class Check(
        val category: String,
        val title: String,
        val detail: String,
        val level: Level,
        val fix: String? = null
    )

    private val KNOWN_TYPES = setOf(
        "nick", "slang", "word", "filler", "fact", "heard", "person",
        "date", "routine", "setting", "todo", "note", "category", "other"
    )

    private const val DAY_MS = 86_400_000L

    // ---------------------------------------------------------------
    // The checks
    // ---------------------------------------------------------------

    @Suppress("DEPRECATION")
    fun run(ctx: Context): List<Check> {

        val out = ArrayList<Check>()
        val pm = ctx.packageManager

        // ---- Setup ----

        try {
            val info = pm.getPackageInfo(ctx.packageName, 0)
            out.add(
                Check(
                    "Setup", "App",
                    "Sanya " + (info.versionName ?: "?") + " (build " + info.versionCode + "), Android " +
                        Build.VERSION.RELEASE + ", " + Build.MANUFACTURER + " " + Build.MODEL,
                    Level.INFO
                )
            )
        } catch (e: Exception) {
        }

        val missing = ArrayList<String>()

        val screens = listOf<Pair<Class<*>, String>>(
            Pair(MainActivity::class.java, "MainActivity"),
            Pair(BrainActivity::class.java, "BrainActivity"),
            Pair(RoutineActivity::class.java, "RoutineActivity"),
            Pair(HealthActivity::class.java, "HealthActivity"),
            Pair(MoneyActivity::class.java, "MoneyActivity"),
            Pair(TimeActivity::class.java, "TimeActivity")
        )

        for (s in screens) {
            try {
                pm.getActivityInfo(ComponentName(ctx, s.first), 0)
            } catch (e: Exception) {
                missing.add(s.second)
            }
        }

        try {
            pm.getServiceInfo(ComponentName(ctx, BubbleService::class.java), 0)
        } catch (e: Exception) {
            missing.add("BubbleService")
        }

        try {
            pm.getReceiverInfo(ComponentName(ctx, BootReceiver::class.java), 0)
        } catch (e: Exception) {
            missing.add("BootReceiver")
        }

        if (missing.isEmpty()) {
            out.add(Check("Setup", "Screens and service", "All screens and services are declared in AndroidManifest.xml.", Level.OK))
        } else {
            out.add(
                Check(
                    "Setup", "AndroidManifest.xml",
                    "Missing: " + missing.joinToString(", ") +
                        ". Add them inside <application> (see FEATURES.md, section 15b) and rebuild.",
                    Level.BAD
                )
            )
        }

        // ---- Permissions ----

        val mic = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        out.add(
            if (mic) {
                Check("Permissions", "Microphone", "Allowed.", Level.OK)
            } else {
                Check("Permissions", "Microphone", "Not allowed, so she cannot hear you.", Level.BAD, "perm:" + Manifest.permission.RECORD_AUDIO)
            }
        )

        val overlay = Settings.canDrawOverlays(ctx)

        out.add(
            if (overlay) {
                Check("Permissions", "Display over other apps", "Allowed. The bubble can show.", Level.OK)
            } else {
                Check("Permissions", "Display over other apps", "Not allowed, so the bubble cannot show.", Level.BAD, "overlay")
            }
        )

        val optional = ArrayList<Triple<String, String, String>>()
        optional.add(Triple(Manifest.permission.READ_CONTACTS, "Contacts", "needed to find people by name"))
        optional.add(Triple(Manifest.permission.CALL_PHONE, "Phone calls", "needed to place calls"))
        if (Build.VERSION.SDK_INT >= 33) {
            optional.add(Triple(Manifest.permission.POST_NOTIFICATIONS, "Notifications", "needed to show her status notification"))
        }

        for (o in optional) {
            val ok = ctx.checkSelfPermission(o.first) == PackageManager.PERMISSION_GRANTED
            out.add(
                if (ok) {
                    Check("Permissions", o.second, "Allowed.", Level.OK)
                } else {
                    Check("Permissions", o.second, "Not allowed (" + o.third + ").", Level.WARN, "perm:" + o.first)
                }
            )
        }

        // ---- Running ----

        val running = try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getRunningServices(100).any { it.service.className == BubbleService::class.java.name }
        } catch (e: Exception) {
            false
        }

        out.add(
            if (running) {
                Check("Running", "Sanya", "Running. The bubble is active.", Level.OK)
            } else {
                Check("Running", "Sanya", "Not running. Open the start screen and tap Start Sanya.", Level.WARN, "startscreen")
            }
        )

        val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager

        out.add(
            if (power.isIgnoringBatteryOptimizations(ctx.packageName)) {
                Check("Running", "Battery", "Not restricted, so Android is unlikely to stop her.", Level.OK)
            } else {
                Check(
                    "Running", "Battery",
                    "Android may stop her in the background to save battery. Set Sanya to \"No restrictions\" or \"Don't optimise\". On Xiaomi also switch on Autostart (the Keep Sanya alive button on the start screen).",
                    Level.WARN, "battery"
                )
            }
        )

        // ---- Hearing ----

        val google = Brain.setting("hearing", "offline") == "google"

        if (google) {
            val available = SpeechRecognizer.isRecognitionAvailable(ctx)
            out.add(
                Check(
                    "Hearing", "Google hearing",
                    if (available) {
                        "On. Needs internet unless the offline English (India) pack is installed."
                    } else {
                        "On, but Google's speech service is not available on this phone."
                    },
                    if (available) Level.INFO else Level.BAD
                )
            )
        } else {
            val ready = ModelStore.ready(ctx)
            out.add(
                if (ready) {
                    Check("Hearing", "Offline voice", ModelStore.label(ModelStore.currentUrl()) + " is installed and chosen.", Level.OK)
                } else {
                    Check(
                        "Hearing", "Offline voice",
                        ModelStore.label(ModelStore.currentUrl()) + " is not downloaded yet, so she cannot hear offline.",
                        Level.BAD, "startscreen"
                    )
                }
            )
        }

        out.add(
            if (online(ctx)) {
                Check("Hearing", "Internet", "Connected (used for answers from the web and for Google hearing).", Level.OK)
            } else {
                Check("Hearing", "Internet", "Not connected. Offline commands still work.", Level.INFO)
            }
        )

        // ---- AI brain (LocalLLM) ----

        out.add(
            if (LocalLLM.isInstalled(ctx)) {
                Check(
                    "AI brain", "On-device LLM",
                    "SmolLM2-360M installed (${LocalLLM.modelFile(ctx).length() / 1_048_576} MB). " +
                        if (LocalLLM.isReady) "Loaded and ready." else "Not yet loaded — will load on next Start.",
                    Level.OK
                )
            } else {
                Check(
                    "AI brain", "On-device LLM",
                    "Not downloaded. Open the start screen and tap Download AI brain (~200 MB). " +
                        "Without it, hard questions go to the internet or stay unanswered offline.",
                    Level.INFO,
                    "startscreen"
                )
            }
        )

        // ---- Offline knowledge base (OfflineKnowledge) ----

        out.add(
            if (OfflineKnowledge.isInstalled(ctx)) {
                Check(
                    "AI brain", "Offline knowledge base",
                    "Wikipedia knowledge base installed (${OfflineKnowledge.dbFile(ctx).length() / 1_048_576} MB). " +
                        if (OfflineKnowledge.isOpen) "Open and ready." else "Installed but not open — will open on next Start.",
                    Level.OK
                )
            } else {
                Check(
                    "AI brain", "Offline knowledge base",
                    "Not downloaded. Open the start screen and tap Download offline knowledge base (~100 MB). " +
                        "Without it, fact questions need internet.",
                    Level.INFO,
                    "startscreen"
                )
            }
        )

        // ---- Natural voice (NaturalVoice / Piper TTS) ----

        out.add(
            if (NaturalVoice.isInstalled(ctx)) {
                Check(
                    "Voice", "Natural TTS (Piper)",
                    "Piper voice installed (${NaturalVoice.modelFile(ctx).length() / 1_048_576} MB). " +
                        if (NaturalVoice.isReady) "Active — using natural voice." else "Installed, loads on next Start.",
                    Level.OK
                )
            } else {
                Check(
                    "Voice", "Natural TTS (Piper)",
                    "Not downloaded. Using Android's built-in TTS. " +
                        "Tap Download natural voice on the start screen (~60 MB) for a much more natural sound.",
                    Level.INFO,
                    "startscreen"
                )
            }
        )

        // ---- Her mind ----

        val counts = listOf("nick", "slang", "word", "fact", "heard", "person", "date", "routine")
            .map { it to Brain.entries(it).size }
            .filter { it.second > 0 }
            .joinToString(", ") { it.second.toString() + " " + it.first }

        val brainBytes = try {
            File(ctx.filesDir, "brain.txt").length()
        } catch (e: Exception) {
            0L
        }

        out.add(
            Check(
                "Her mind", "Brain file",
                (if (counts.isEmpty()) "No rules yet." else "Rules: $counts.") + " Size " + (brainBytes / 1024) + " KB.",
                Level.INFO
            )
        )

        val stray = Brain.stray(KNOWN_TYPES).size

        if (stray > 0) {
            out.add(
                Check(
                    "Her mind", "Unrecognised lines",
                    "$stray line(s) in the Brain file are not a known rule. Open Edit rules, then Other, to fix or delete them.",
                    Level.WARN
                )
            )
        }

        val badRoutines = Brain.entries("routine").size - Routines.tasks().size

        if (badRoutines > 0) {
            out.add(
                Check(
                    "Her mind", "Routine schedules",
                    "$badRoutines routine(s) have a schedule she cannot read, so they are ignored. Open Edit rules, then Routines.",
                    Level.WARN
                )
            )
        }

        out.add(Check("Her mind", "Her own model", Learner.stats(ctx), Level.INFO))

        val talked = ctx.getSharedPreferences("sanya_mind", Context.MODE_PRIVATE).getInt("total", 0)

        out.add(Check("Her mind", "Requests remembered", "$talked so far.", Level.INFO))

        // ---- Settings she is using ----

        out.add(
            Check(
                "Settings", "Proactive",
                "Speaking first: " + Brain.setting("proactive", "on") +
                    ". Quiet hours: " + Brain.setting("quiet hours", "22:00-07:00") +
                    ". Morning brief: " + Brain.setting("morning brief", "off") +
                    ". Routine check: " + Brain.setting("routine check", "20:00") + ".",
                Level.INFO
            )
        )

        // ---- Storage ----

        val freeMb = try {
            ctx.filesDir.usableSpace / (1024 * 1024)
        } catch (e: Exception) {
            -1L
        }

        if (freeMb >= 0 && freeMb < 200) {
            out.add(Check("Setup", "Storage", "Only $freeMb MB free. Voices and her memory need some space.", Level.WARN))
        }

        // ---- Recent errors ----

        val day = System.currentTimeMillis() - DAY_MS
        val problems = ErrorLog.countSince(ctx, day)
        val crashes = ErrorLog.crashesSince(ctx, day)

        out.add(
            when {
                crashes > 0 ->
                    Check("Errors", "Crashes", "$crashes crash(es) in the last 24 hours. See the list below and send it to get it fixed.", Level.BAD)
                problems > 0 ->
                    Check("Errors", "Problems", "$problems problem(s) logged in the last 24 hours. See the list below.", Level.WARN)
                else ->
                    Check("Errors", "Errors", "None in the last 24 hours.", Level.OK)
            }
        )

        return out
    }

    private fun online(ctx: Context): Boolean {

        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------
    // Saying it and sharing it
    // ---------------------------------------------------------------

    /** What she says aloud for "run a health check". */
    fun summary(ctx: Context): String {

        val checks = run(ctx)
        val bad = checks.filter { it.level == Level.BAD }
        val warn = checks.filter { it.level == Level.WARN }

        if (bad.isEmpty() && warn.isEmpty()) {
            return "Everything looks fine. All checks passed."
        }

        val first = bad.firstOrNull() ?: warn.first()

        return "Health check: ${bad.size} problem" + (if (bad.size == 1) "" else "s") +
            " and ${warn.size} warning" + (if (warn.size == 1) "" else "s") +
            ". First: " + first.title + ". " + first.detail.substringBefore(". ").trimEnd('.') + "."
    }

    /** A plain-text report you can paste to someone who is helping. No personal content. */
    fun report(ctx: Context): String {

        val sb = StringBuilder()

        sb.append("Sanya health report\n")
        sb.append(ErrorLog.stamp(System.currentTimeMillis())).append("\n\n")

        var category = ""

        for (c in run(ctx)) {
            if (c.category != category) {
                category = c.category
                sb.append("\n[").append(category).append("]\n")
            }
            sb.append(c.level.icon).append(" ").append(c.title).append(": ").append(c.detail).append("\n")
        }

        val errors = ErrorLog.recent(ctx, 15)

        sb.append("\n[Last errors]\n")

        if (errors.isEmpty()) {
            sb.append("none\n")
        } else {
            for (e in errors) {
                sb.append(ErrorLog.stamp(e.time)).append("  ").append(e.where).append("\n    ").append(e.message).append("\n")
            }
        }

        return sb.toString()
    }
}
