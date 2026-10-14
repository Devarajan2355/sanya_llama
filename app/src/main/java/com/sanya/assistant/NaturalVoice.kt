package com.sanya.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * NaturalVoice: Piper neural TTS via the sherpa-onnx Android AAR.
 *
 * Piper sounds vastly more natural than Android's built-in TTS engine.
 * It runs fully offline, produces audio in ~200 ms on mid-range phones,
 * and uses ~60 MB for one voice model.
 *
 * Dependency (add to app/build.gradle):
 *   implementation 'com.github.k2-fsa:sherpa-onnx-android:1.10.22'
 *   // Maven repo: https://jitpack.io
 *
 * Voice model (downloaded once, ~60 MB):
 *   en_US-lessac-medium.onnx   — clear US-accented English
 *   en_US-lessac-medium.onnx.json  — config file alongside the model
 *
 * Alternate voices (swap the URL pair):
 *   en_IN-southern_english_female-low.onnx  ~28 MB  Indian accent
 *   en_GB-cori-medium.onnx                  ~63 MB  British English
 *
 * Usage:
 *   NaturalVoice.init(ctx)           // call once from a background thread
 *   NaturalVoice.speak("Hello")      // blocking — call from background thread
 *   NaturalVoice.stop()              // interrupt current speech
 *   NaturalVoice.isReady             // false until init() finishes
 *
 * BubbleService uses this when isReady == true, falls back to Android TTS otherwise.
 */
object NaturalVoice {

    // ---- model URLs (two files required by Piper / sherpa-onnx) ----
    private const val MODEL_URL =
        "https://huggingface.co/rhasspy/piper-voices/resolve/main/" +
        "en/en_US/lessac/medium/en_US-lessac-medium.onnx"
    private const val CONFIG_URL =
        "https://huggingface.co/rhasspy/piper-voices/resolve/main/" +
        "en/en_US/lessac/medium/en_US-lessac-medium.onnx.json"

    private const val MODEL_FILE  = "piper-voice.onnx"
    private const val CONFIG_FILE = "piper-voice.onnx.json"

    private const val SAMPLE_RATE = 22050   // Piper outputs 22 050 Hz mono PCM16

    @Volatile private var tts: Any?  = null   // k2fsa.sherpa_onnx.OfflineTts (reflection)
    @Volatile private var playing    = false
    @Volatile private var track: AudioTrack? = null

    val isReady get() = tts != null

    // ---------------------------------------------------------------
    // Installation
    // ---------------------------------------------------------------

    fun modelFile(ctx:  Context) = File(ctx.filesDir, MODEL_FILE)
    fun configFile(ctx: Context) = File(ctx.filesDir, CONFIG_FILE)

    fun isInstalled(ctx: Context) =
        modelFile(ctx).exists()  && modelFile(ctx).length()  > 1_000_000L &&
        configFile(ctx).exists() && configFile(ctx).length() > 100L

    /** Download both model files. Returns null on success. */
    fun download(ctx: Context, onProgress: (Int) -> Unit): String? {
        val files = listOf(MODEL_URL to MODEL_FILE, CONFIG_URL to CONFIG_FILE)
        for ((url, name) in files) {
            val err = downloadFile(ctx, url, name) { p ->
                // Model is ~60 MB, config is tiny — report model progress as 0-95, config as 95-100
                val adj = if (name == MODEL_FILE) (p * 95) / 100 else 95 + (p * 5) / 100
                onProgress(adj)
            }
            if (err != null) return err
        }
        onProgress(100)
        return null
    }

    private fun downloadFile(
        ctx:        Context,
        url:        String,
        filename:   String,
        onProgress: (Int) -> Unit
    ): String? {
        return try {
            val tmp  = File(ctx.cacheDir, "$filename.tmp")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout    = 60_000
            val total = conn.contentLengthLong
            conn.inputStream.use { inp ->
                tmp.outputStream().use { out ->
                    val buf  = ByteArray(65_536)
                    var done = 0L; var last = -1
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
            val dest = File(ctx.filesDir, filename)
            if (tmp.renameTo(dest)) null else "Could not install $filename"
        } catch (e: Exception) { e.message ?: "Download failed: $filename" }
    }

    // ---------------------------------------------------------------
    // Lifecycle  (call from a background thread)
    // ---------------------------------------------------------------

    /**
     * Initialise the Piper TTS engine.
     * Uses reflection so the code compiles even if the sherpa-onnx AAR
     * is not yet added — isReady will just stay false.
     */
    fun init(ctx: Context): Boolean {
        if (tts != null) return true
        if (!isInstalled(ctx)) return false
        return try {
            // Equivalent to:
            //   val config = OfflineTtsConfig(
            //       model = OfflineTtsModelConfig(
            //           vits = OfflineTtsVitsModelConfig(
            //               model  = modelFile(ctx).absolutePath,
            //               lexicon = "",
            //               tokens = configFile(ctx).absolutePath
            //           )
            //       )
            //   )
            //   tts = OfflineTts(ctx, config)
            //
            // We use reflection so the app still builds without the AAR
            // and NaturalVoice.isReady simply returns false.

            val vitsClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig")
            val vitsConfig = vitsClass.getConstructor(String::class.java, String::class.java, String::class.java)
                .newInstance(modelFile(ctx).absolutePath, "", configFile(ctx).absolutePath)

            val modelCfgClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsModelConfig")
            val modelCfg = modelCfgClass.getConstructor(vitsClass)
                .newInstance(vitsConfig)

            val cfgClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsConfig")
            val cfg = cfgClass.getConstructor(modelCfgClass)
                .newInstance(modelCfg)

            val ttsClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
            tts = ttsClass.getConstructor(Context::class.java, cfgClass)
                .newInstance(ctx, cfg)

            true
        } catch (e: Throwable) {
            false
        }
    }

    fun release() {
        stop()
        runCatching { tts?.javaClass?.getMethod("release")?.invoke(tts) }
        tts = null
    }

    // ---------------------------------------------------------------
    // Speaking  (call from a background thread — blocks until done)
    // ---------------------------------------------------------------

    /**
     * Synthesise [text] and play it immediately.
     * Returns when the audio is finished (or stopped).
     */
    fun speak(text: String) {
        val engine = tts ?: return
        if (text.isBlank()) return

        stop()  // interrupt anything playing

        try {
            // Call OfflineTts.generate(text, speed, sid) → GeneratedAudio
            val genMethod = engine.javaClass.getMethod(
                "generate", String::class.java, Float::class.java, Int::class.java
            )
            val audio = genMethod.invoke(engine, text, 1.0f, 0) ?: return

            // GeneratedAudio has a .samples: FloatArray and .sampleRate: Int
            val samplesField = audio.javaClass.getField("samples")
            val samples = samplesField.get(audio) as? FloatArray ?: return

            playPcmFloat(samples)
        } catch (e: Throwable) {
            // Silently fall back to Android TTS (handled by BubbleService)
        }
    }

    /** Interrupt any ongoing speech immediately. */
    fun stop() {
        playing = false
        runCatching { track?.stop(); track?.release() }
        track = null
    }

    // ---------------------------------------------------------------
    // PCM playback
    // ---------------------------------------------------------------

    private fun playPcmFloat(samples: FloatArray) {
        if (samples.isEmpty()) return

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, samples.size * 4))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track   = at
        playing = true

        at.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        at.play()

        // Wait for playback to finish or be stopped.
        val durationMs = (samples.size.toLong() * 1000L) / SAMPLE_RATE
        val deadline   = System.currentTimeMillis() + durationMs + 500L

        while (playing && at.playState == AudioTrack.PLAYSTATE_PLAYING
               && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        at.stop()
        at.release()
        if (track === at) track = null
        playing = false
    }
}
