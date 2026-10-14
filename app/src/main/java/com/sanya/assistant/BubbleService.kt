package com.sanya.assistant

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

class BubbleService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var bubble: TextView
    private lateinit var caption: TextView
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var runner: StepRunner
    private val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(IDLE) }
    private val am by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val prefs by lazy { getSharedPreferences("sanya", MODE_PRIVATE) }
    private val muted = mutableListOf<Int>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var sr: SpeechRecognizer? = null        // Google voice (backup)
    private var ears: Ears? = null                  // offline Vosk voice
    private var voskReady = false
    private var hearingMode = ""                   // "google" or "offline", as last applied

    // Which ears are in charge. "setting | hearing = google" makes Google's recogniser
    // do all the listening (much better with Indian names and accents).
    private fun googleHearing() = Brain.setting("hearing", "offline") == "google"
    private fun offlineEars() = voskReady && !googleHearing()

    // Called after any command, and on Start: switch the ears if the setting changed.
    private fun applyHearing() {
        val now = if (googleHearing()) "google" else "offline"
        if (now == hearingMode) return
        hearingMode = now
        if (now == "google") {
            ears?.stop()
            releaseLock()
            if (handsFree) ui.postDelayed({ resume() }, 2500)    // after she says "Okay…"
        } else {
            sr?.destroy(); sr = null; listening = false; unmuteBeep()
            if (handsFree && voskReady) { takeLock(); ears?.paused = false; ears?.start() }
        }
    }
    private var loading = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var pulse: ObjectAnimator? = null
    private var handsFree = false   // always listening for "Sanya" (long-press the bubble)
    private var awaiting = false    // the next sentence is a command, no wake word needed
    private var listening = false   // Google voice is active
    private var pending = 0         // spoken sentences still queued
    private var size = 0
    private var wakeRx = Regex("sanya")
    private val hideCaption = Runnable { caption.visibility = View.GONE }
    private val giveUp = Runnable {
        awaiting = false
        if (!handsFree) ears?.stop()
        look(if (handsFree) HANDS_FREE else IDLE, false)
    }

    companion object {
        const val IDLE = 0xFF6C5CE7.toInt()
        const val LISTENING = 0xFF00B894.toInt()
        const val HANDS_FREE = 0xFFE17055.toInt()

        // True while the service is running, so a pomodoro alarm knows it can ask her to speak.
        @Volatile var alive = false
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun notif() = Notification.Builder(this, "sanya")
        .setContentTitle("Sanya is ready")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()

    override fun onCreate() {
        ErrorLog.install(this)
        super.onCreate()
        alive = true
        Brain.setup(this)
        wakeRx = wake()
        hearingMode = if (googleHearing()) "google" else "offline"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("sanya", "Sanya", NotificationManager.IMPORTANCE_MIN))
        startForeground(1, notif())
        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                ttsReady = true
                val r = tts?.setLanguage(Locale.forLanguageTag(Brain.setting("language", "en-IN")))
                if (r == null || r < 0) tts?.setLanguage(Locale.getDefault())
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { ui.post { pending = maxOf(0, pending - 1); resume() } }
                    override fun onError(id: String?) { ui.post { pending = maxOf(0, pending - 1); resume() } }
                })
            }
        }

        // Boot Piper TTS on a background thread.
        Thread {
            if (NaturalVoice.isInstalled(this)) {
                val ok = NaturalVoice.init(this)
                if (ok) ui.post { show("Natural voice ready") }
            }
        }.also { it.isDaemon = true }.start()

        runner = StepRunner(this, { say(it) }, { show(it, 8000L) })
        ears = Ears(this,
            onPartial = { p -> ui.post { if (awaiting || wakeRx.containsMatchIn(p)) show("🎤 $p") } },
            onFinal = { t -> ui.post { heard(listOf(t)) } },
            onProblem = { m ->
                ErrorLog.note(this, "Hearing", m)
                ui.post { stopEars(m) }
            })
        showBubble()
        makeCaption()
        loadEars()
        ui.postDelayed(tick, 45_000L)
    }

    // ---------- Proactive Sanya: speaks first when something matters ----------
    private val tick = object : Runnable {
        override fun run() {
            try { proactiveCheck() } catch (e: Throwable) { ErrorLog.add(this@BubbleService, "Proactive check", e) }
            try { keepWidgetFresh() } catch (e: Throwable) { ErrorLog.add(this@BubbleService, "Widget refresh", e) }
            ui.postDelayed(this, 60_000L)
        }
    }

    // The home-screen widget ticks its own clock, but routines you mark by voice and
    // pomodoro phase changes need a redraw. Only while the screen is on, to save battery.
    private fun keepWidgetFresh() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isInteractive) SanyaWidget.refresh(this)
    }

    private fun proactiveCheck() {
        // Never interrupt: not while she is talking or listening, not on a call.
        if (pending > 0 || listening || awaiting || runner.wantsReply()) return
        if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) return

        // A pomodoro cue is something you asked for, so it is spoken even with the screen off
        // or music playing (you study with the phone down). It also logs finished focus sessions.
        val cue = Pomodoro.due(this)
        if (cue != null) {
            say(cue)
            Pomodoro.notify(this, cue)
            SanyaWidget.refresh(this)
            return
        }

        // Everything else only speaks over silence, and only while you are using the phone.
        if (am.isMusicActive) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return

        val n = Proactive.next(this) ?: return

        when {
            n.run.isNotEmpty() -> execute(n.run)
            n.offer.isNotEmpty() -> {
                runner.offer(n.text, n.offer)
                awaiting = true          // listen for yes / no, no wake word needed
            }
            n.text.isNotEmpty() -> say(n.text)
        }
    }

    // Called again each time you tap Start in the app, so a freshly downloaded voice gets loaded.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notif())
        loadEars()
        applyHearing()

        // Retry Piper init if it was not ready when the service started
        // (e.g. user just downloaded the voice model from the settings screen).
        if (!NaturalVoice.isReady && NaturalVoice.isInstalled(this)) {
            Thread {
                NaturalVoice.init(this)
            }.also { it.isDaemon = true }.start()
        }

        // A pomodoro alarm asks her to say the cue out loud.
        intent?.getStringExtra("say")?.let { text -> ui.post { say(text) } }
        return START_STICKY
    }

    private fun loadEars() {
        val e = ears ?: return
        if (voskReady || loading || !ModelStore.ready(this)) return
        loading = true
        Thread {
            val ok = e.load()
            ui.post {
                loading = false
                voskReady = ok
                if (ok) {
                    show("Offline voice ready")
                    if (prefs.getBoolean("handsfree", false)) setHandsFree(true, false)
                } else show("Offline voice failed to load")
            }
        }.start()
    }

    private fun stopEars(msg: String) {
        handsFree = false; awaiting = false
        prefs.edit().putBoolean("handsfree", false).apply()
        ears?.stop(); releaseLock()
        look(IDLE, false)
        say(msg)
    }

    // Wake words come from the brain file, so you can add the ways the phone mishears "Sanya".
    private fun wake(): Regex {
        val w = (Brain.setting("wake words", "sanya").split(",").map { it.trim().lowercase() } +
            listOf("sanya", "saniya", "sania", "sunya", "sonya", "sonia", "sanja", "suniya", "shanya")).filter { it.isNotEmpty() }.distinct()
        return Regex("^\\s*(?:(?:hey|hi|ok|okay|uh|um|so)\\s+)?(?:" + w.joinToString("|") { Regex.escape(it) } + ")\\b[ ,.]*")
    }

    private fun takeLock() {
        if (Brain.setting("screen off listening", "on") == "off") return
        val w = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sanya:ears").also { wakeLock = it }
        if (!w.isHeld) w.acquire()
    }

    private fun releaseLock() { wakeLock?.let { if (it.isHeld) it.release() } }

    // ---------- Bubble ----------
    private fun showBubble() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        size = (56 * resources.displayMetrics.density).toInt()
        bubble = TextView(this).apply {
            text = "S"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); background = circle
        }
        lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 400 }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
            var moved = false; var longPressed = false
            val longPress = Runnable { longPressed = true; buzz(); setHandsFree(!handsFree) }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY
                        moved = false; longPressed = false
                        ui.postDelayed(longPress, 700)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - tx; val dy = e.rawY - ty
                        if (abs(dx) > 12 || abs(dy) > 12) { moved = true; ui.removeCallbacks(longPress) }
                        if (moved) { lp.x = sx + dx.toInt(); lp.y = sy + dy.toInt(); wm.updateViewLayout(bubble, lp) }
                    }
                    MotionEvent.ACTION_UP -> {
                        ui.removeCallbacks(longPress)
                        if (moved) snap() else if (!longPressed) tap()
                    }
                    MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(longPress)
                }
                return true
            }
        })
        wm.addView(bubble, lp)
    }

    private fun snap() {
        val w = resources.displayMetrics.widthPixels
        lp.x = if (lp.x + size / 2 < w / 2) 0 else w - size
        wm.updateViewLayout(bubble, lp)
    }

    private fun buzz() { bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }

    private fun look(color: Int, pulseOn: Boolean) {
        circle.setColor(color); bubble.invalidate()
        pulse?.cancel(); bubble.alpha = 1f
        if (pulseOn) {
            pulse = ObjectAnimator.ofFloat(bubble, "alpha", 1f, 0.45f).apply {
                duration = 500; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start()
            }
        }
    }

    // ---------- Caption pill (top of screen) ----------
    private fun makeCaption() {
        caption = TextView(this).apply {
            textSize = 16f; setTextColor(Color.WHITE); setPadding(40, 20, 40, 20)
            background = GradientDrawable().apply { cornerRadius = 60f; setColor(0xE6222222.toInt()) }
            visibility = View.GONE
        }
        val cp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 140 }
        wm.addView(caption, cp)
    }

    private fun show(text: String, ms: Long = 3500L) {
        caption.text = text; caption.visibility = View.VISIBLE
        ui.removeCallbacks(hideCaption); ui.postDelayed(hideCaption, ms)
    }

    // ---------- Beep control (only needed for the Google voice backup) ----------
    private fun muteBeep() {
        for (st in intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM)) {
            if (st == AudioManager.STREAM_MUSIC && am.isMusicActive) continue   // never cut your video audio
            if (muted.contains(st) || am.isStreamMute(st)) continue
            runCatching {
                am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0)
                if (am.isStreamMute(st)) muted.add(st)
            }
        }
    }

    private fun unmuteBeep() {
        for (st in muted) runCatching { am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0) }
        muted.clear()
    }

    // ---------- Speaking ----------
    private fun say(s: String) {
        show(s)
        unmuteBeep()

        // Stop Google voice listener — never listen to our own speech.
        sr?.let { if (listening) { it.cancel(); finish(it) } }

        // Pause offline ears too.
        ears?.paused = true
        pending++

        if (NaturalVoice.isReady) {
            // Piper TTS — runs on its own thread, calls back when done.
            Thread {
                NaturalVoice.speak(s)
                ui.post {
                    pending = maxOf(0, pending - 1)
                    resume()
                }
            }.also { it.isDaemon = true }.start()
            return
        }

        // ---- Original Android TTS fallback (unchanged) ----
        val t = tts ?: run { pending = maxOf(0, pending - 1); return }
        if (!ttsReady) { pending = maxOf(0, pending - 1); return }

        if (t.speak(s, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime()) == TextToSpeech.ERROR) {
            pending = maxOf(0, pending - 1)
            resume()
        }
    }

    // After she finishes talking, go back to listening if hands-free (or waiting for a command)
    private fun resume() {
        if (runner.wantsReply()) awaiting = true   // she asked something (even later, from a step): listen for the answer
        if (offlineEars()) {
            ui.postDelayed({
                if (pending > 0) return@postDelayed
                if (handsFree || awaiting) {
                    ears?.paused = false
                    ears?.start()
                    if (awaiting) {
                        look(LISTENING, true)
                        ui.removeCallbacks(giveUp); ui.postDelayed(giveUp, 8000)
                    }
                } else ears?.stop()
            }, 450)
            return
        }
        if ((handsFree || awaiting) && !listening && pending == 0) {
            ui.postDelayed({ if ((handsFree || awaiting) && !listening && pending == 0) listen() }, 500)
        }
    }

    // ---------- Listening ----------
    private fun tap() {
        buzz()
        if (pending > 0) { tts?.stop(); pending = 0 }   // tap = stop talking
        awaiting = true                                  // a tap means: no wake word needed
        if (offlineEars() && Brain.setting("tap voice", "offline") != "google") {
            ears?.paused = false
            ears?.start()
            look(LISTENING, true)
            show("Listening…")
            ui.removeCallbacks(giveUp); ui.postDelayed(giveUp, 8000)
        } else {
            ears?.stop()                      // free the microphone for Google's recogniser
            if (listening) sr?.stopListening() else listen()
        }
    }

    // Google voice backup (online first, offline pack if there is no internet)
    private fun listen(offline: Boolean = false) {
        if (listening || pending > 0) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { say("No speech service on this phone"); return }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        sr = r
        listening = true
        look(LISTENING, true)
        if (handsFree) muteBeep()
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(res: Bundle?) {
                finish(r)
                val list = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (list.isNullOrEmpty()) resume() else heard(list)
            }
            override fun onError(code: Int) {
                finish(r)
                if (!offline && (code == 1 || code == 2 || code == 11)) {   // no internet: try the offline pack
                    show("Offline mode")
                    ui.post { listen(true) }
                    return
                }
                awaiting = false
                if (handsFree && (code == 6 || code == 7 || code == 8)) {   // silence or busy: keep waiting
                    ui.postDelayed({ resume() }, if (code == 8) 1500L else 300L); return
                }
                if (handsFree) handsFree = false                              // real error: stop looping
                look(IDLE, false)
                say(explain(code))
            }
            override fun onPartialResults(p: Bundle?) {
                p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { show("🎤 $it") }
            }
            override fun onReadyForSpeech(p: Bundle?) { if (!handsFree) show(if (offline) "Listening (offline)…" else "Listening…") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Brain.setting("language", "en-IN"))
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, Brain.hints())
        if (offline) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        r.startListening(i)
    }

    private fun finish(r: SpeechRecognizer) {
        listening = false
        unmuteBeep()
        look(if (handsFree) HANDS_FREE else IDLE, false)
        if (sr === r) sr = null
        ui.post { r.destroy() }
    }

    private fun explain(c: Int) = when (c) {
        1, 2 -> "No internet and no offline speech pack. Check your connection."
        3 -> "Microphone problem"
        6 -> "I didn't hear anything"
        7 -> "I didn't catch that"
        8 -> "Speech service is busy, try again"
        9 -> "Microphone permission is missing"
        12, 13 -> "Your language isn't supported for speech here"
        else -> "Speech error $c"
    }

    // ---------- Understanding ----------
    // Google gives up to 5 guesses, Vosk gives 1. Pick the one Sanya actually understands.
    private fun heard(all: List<String>) {
        val lows = all.map { it.lowercase() }
        val pool = if (handsFree && !awaiting) lows.filter { wakeRx.containsMatchIn(it) } else lows
        if (pool.isEmpty()) { if (!offlineEars()) resume(); return }   // not meant for me, keep waiting
        ears?.paused = true
        ui.removeCallbacks(giveUp)
        look(if (handsFree) HANDS_FREE else IDLE, false)
        // Of the guesses she understands, prefer the one that sounds most like YOU (her own model).
        val understood = pool.filter { val c = wakeRx.replace(it, "").trim(); c.isNotEmpty() && runner.understands(c) }
        val pick = when {
            understood.size > 1 -> {
                val stripped = understood.map { wakeRx.replace(it, "").trim() }
                understood[stripped.indexOf(Learner.choose(this, stripped)).coerceAtLeast(0)]
            }
            understood.size == 1 -> understood[0]
            else -> pool.first()
        }
        show("“$pick”")
        val cmd = wakeRx.replace(pick, "").trim()
        awaiting = false
        if (cmd.isEmpty()) { awaiting = true; buzz(); say("Yes?") } else execute(cmd)
    }

    private fun execute(c: String) {
        when {
            Regex("^(?:exit|quit|close|shut down)$").containsMatchIn(c) -> {
                prefs.edit().putBoolean("autostart", false).putBoolean("handsfree", false).apply()
                say("Bye"); ui.postDelayed({ stopSelf() }, 1500)
            }
            Regex("stop listening|hands.?free off").containsMatchIn(c) -> setHandsFree(false)
            Regex("hands.?free on|hands.?free mode|start listening").containsMatchIn(c) -> setHandsFree(true)
            else -> {
                val n = runner.handle(c)
                if (runner.wantsReply()) awaiting = true   // she asked a question: keep listening, no wake word
                wakeRx = wake()
                applyHearing()
                ui.postDelayed({ resume() }, n * 2500L + 1500L)
            }
        }
    }

    private fun setHandsFree(on: Boolean, announce: Boolean = true) {
        handsFree = on; awaiting = false
        prefs.edit().putBoolean("handsfree", on).apply()
        if (!on) { sr?.destroy(); sr = null; listening = false; unmuteBeep(); ears?.stop(); releaseLock() }
        look(if (on) HANDS_FREE else IDLE, false)
        if (on && offlineEars()) { takeLock(); ears?.paused = false; ears?.start() }
        if (announce) {
            say(if (on) {
                if (offlineEars()) "Hands free on. Just say Sanya, then your command."
                else if (googleHearing()) "Hands free on, using Google voice."
                else "Hands free on, using Google voice. For no beeps, download offline voice in the Sanya app."
            } else "Hands free off")
        } else show(if (on) "Hands free is on" else "Hands free off")
        if (on && !offlineEars()) resume()
    }

    override fun onDestroy() {
        alive = false
        Learner.flush(this)
        ui.removeCallbacksAndMessages(null)
        unmuteBeep()
        releaseLock()
        pulse?.cancel()
        if (::bubble.isInitialized) wm.removeView(bubble)
        if (::caption.isInitialized) wm.removeView(caption)
        sr?.destroy(); tts?.shutdown()
        NaturalVoice.stop()
        NaturalVoice.release()
        val e = ears
        ears = null
        e?.stop()
        Thread { e?.close() }.start()
        super.onDestroy()
    }
}
