package com.sanya.assistant

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

// Offline voice model: downloaded once, stored inside the app.
object ModelStore {
    const val DEFAULT_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    // Trained on Indian English speakers (36 MB); usually better for Indian accents.
    const val INDIAN_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"

    // The voice chosen in the brain file ("setting | voice model url = ...").
    fun currentUrl(): String = Brain.setting("voice model url", DEFAULT_URL)

    // Every voice lives in its own folder, so switching never deletes the other one
    // and never needs a second download.
    private fun idOf(url: String): String =
        url.substringAfterLast('/')
            .removeSuffix(".zip")
            .filter { it.isLetterOrDigit() || it == '-' || it == '.' }
            .ifEmpty { "custom" }

    private fun dirFor(c: Context, url: String) = File(c.filesDir, "voice-" + idOf(url))

    fun label(url: String): String = when (url) {
        DEFAULT_URL -> "US English"
        INDIAN_URL -> "Indian English"
        else -> idOf(url)
    }

    fun isInstalled(c: Context, url: String): Boolean {
        migrate(c)
        return File(dirFor(c, url), "am/final.mdl").exists()
    }

    fun dir(c: Context) = dirFor(c, currentUrl())
    fun ready(c: Context) = isInstalled(c, currentUrl())

    // Older versions kept a single model in "vosk-model". Move it into its own
    // folder once, so nobody has to download it again.
    private fun migrate(c: Context) {
        val old = File(c.filesDir, "vosk-model")
        if (!File(old, "am/final.mdl").exists()) return
        val marker = File(c.filesDir, "vosk-model.url")
        val url = try {
            if (marker.exists()) marker.readText().trim().ifEmpty { DEFAULT_URL } else DEFAULT_URL
        } catch (e: Exception) {
            DEFAULT_URL
        }
        val target = dirFor(c, url)
        if (File(target, "am/final.mdl").exists()) {
            old.deleteRecursively()
        } else {
            target.deleteRecursively()
            old.renameTo(target)
        }
        marker.delete()
    }

    // Returns null on success, or a short error message.
    fun download(c: Context, onProgress: (Int) -> Unit): String? {
        return try {
            val url = currentUrl()
            val tmp = File(c.cacheDir, "voice-model.zip")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(65536)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
            val dest = File(c.filesDir, "vosk-model-new")
            dest.deleteRecursively()
            dest.mkdirs()
            ZipInputStream(tmp.inputStream().buffered()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val rel = e.name.substringAfter('/')
                    if (rel.isNotEmpty()) {
                        val f = File(dest, rel)
                        if (!f.canonicalPath.startsWith(dest.canonicalPath)) throw Exception("bad zip")
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                    }
                    e = z.nextEntry
                }
            }
            tmp.delete()
            if (!File(dest, "am/final.mdl").exists()) {
                dest.deleteRecursively()
                "model files missing after unzip (your other voice is untouched)"
            } else {
                val live = dirFor(c, url)
                live.deleteRecursively()
                if (dest.renameTo(live)) {
                    null
                } else {
                    "could not install the new voice"
                }
            }
        } catch (e: Exception) {
            e.message ?: "download failed"
        }
    }
}

// Microphone loop: feeds Vosk and reports every finished sentence. No beeps, no internet.
class Ears(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onProblem: (String) -> Unit
) {
    @Volatile var paused = false          // true while Sanya is speaking, so she doesn't hear herself
    @Volatile private var running = false
    @Volatile private var gen = 0
    private var model: Model? = null
    private var thread: Thread? = null

    fun load(): Boolean {
        if (model != null) return true
        if (!ModelStore.ready(ctx)) return false
        return try { model = Model(ModelStore.dir(ctx).absolutePath); true } catch (e: Throwable) { false }
    }

    fun start() {
        if (running) return
        val m = model ?: return
        try { if (thread?.isAlive == true) thread?.join(400) } catch (e: Throwable) {}
        val my = ++gen
        running = true
        thread = Thread {
            var rec: AudioRecord? = null
            var rcg: Recognizer? = null
            try {
                val rate = 16000
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val a = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096) * 4)
                rec = a
                if (a.state != AudioRecord.STATE_INITIALIZED) { onProblem("Microphone is busy or not allowed"); return@Thread }
                val z = Recognizer(m, rate.toFloat())
                rcg = z
                a.startRecording()
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                val buf = ShortArray(2048)
                var idleBefore = true
                var saver = false
                var checkAt = 0L
                while (running && my == gen) {
                    val n = a.read(buf, 0, buf.size)
                    if (n < 0) { onProblem("Microphone error $n"); break }
                    if (n == 0) continue
                    val now = System.currentTimeMillis()
                    if (now > checkAt) { saver = Brain.setting("screen off listening", "on") == "off"; checkAt = now + 3000 }
                    val idle = paused || (saver && !pm.isInteractive)
                    if (idle) { idleBefore = true; continue }
                    if (idleBefore) { z.reset(); idleBefore = false }
                    if (z.acceptWaveForm(buf, n)) {
                        val t = JSONObject(z.result).optString("text")
                        if (t.isNotBlank()) onFinal(t)
                    } else {
                        val p = JSONObject(z.partialResult).optString("partial")
                        if (p.isNotEmpty()) onPartial(p)
                    }
                }
            } catch (e: Throwable) {
                onProblem("Ears stopped: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                try { rec?.stop() } catch (e: Throwable) {}
                try { rec?.release() } catch (e: Throwable) {}
                try { (rcg as? java.lang.AutoCloseable)?.close() } catch (e: Throwable) {}
                if (my == gen) running = false
            }
        }.also { it.start() }
    }

    fun stop() { running = false; gen++ }

    fun close() {
        stop()
        try { thread?.join(1500) } catch (e: Throwable) {}
        try { (model as? java.lang.AutoCloseable)?.close() } catch (e: Throwable) {}
        model = null
    }
}

// Direct Wi-Fi / Bluetooth switching. Works on Android 10 because Sanya targets API 28.
// Returns false if the phone refuses, and then the settings page opens instead.
fun directToggle(ctx: Context, s: Step.Toggle, say: (String) -> Unit): Boolean = try {
    when {
        s.what.contains("bluetooth") -> {
            val b = BluetoothAdapter.getDefaultAdapter()
            val ok = b != null && (if (s.on) b.enable() else b.disable())
            if (ok) say("Bluetooth ${if (s.on) "on" else "off"}")
            ok
        }
        s.what.contains("wi") -> {
            val w = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ok = w.setWifiEnabled(s.on)
            if (ok) say("Wi-Fi ${if (s.on) "on" else "off"}")
            ok
        }
        else -> false
    }
} catch (e: Throwable) { false }

// Brings Sanya back after the phone restarts (Xiaomi also needs Autostart switched on).
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val on = c.getSharedPreferences("sanya", Context.MODE_PRIVATE).getBoolean("autostart", false)
        if (i.action == Intent.ACTION_BOOT_COMPLETED && on && Settings.canDrawOverlays(c)) {
            try { c.startForegroundService(Intent(c, BubbleService::class.java)) } catch (e: Throwable) {}
        }
    }
}
