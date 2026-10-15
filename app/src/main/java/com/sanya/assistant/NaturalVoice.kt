package com.sanya.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * NaturalVoice: Piper neural text-to-speech through sherpa-onnx. Fully offline.
 *
 * Uses sherpa-onnx's own packaged Piper voice (it already contains the pieces Piper needs:
 * the .onnx model, tokens.txt and the espeak-ng-data folder), about 67 MB to download once.
 * It is unpacked into files/piper-voice/.
 *
 * Dependencies (app/build.gradle):
 *   implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")   // JitPack
 *   implementation("org.apache.commons:commons-compress:1.26.1")          // reads .tar.bz2
 *
 * BubbleService uses it when isReady is true and falls back to Android's TTS otherwise.
 *   NaturalVoice.init(ctx)    background thread, once
 *   NaturalVoice.speak(text)  blocks until finished (call from a background thread)
 *   NaturalVoice.stop()       interrupts what is playing and drops anything queued
 */
object NaturalVoice {

    // Other voices from the same release: swap PACKAGE and FOLDER and the model file name.
    private const val PACKAGE = "vits-piper-en_US-lessac-medium"
    private const val URL_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
    private const val MODEL_NAME = "en_US-lessac-medium.onnx"

    private const val DIR = "piper-voice"
    private const val MARKER = ".installed"

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var generation = 0          // bumped by stop(): queued speech is dropped
    private val speakLock = Any()                  // one utterance at a time, in order

    val isReady: Boolean get() = tts != null

    // ---------------------------------------------------------------
    // Files
    // ---------------------------------------------------------------

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR)

    /** The voice model file (used by the Health check to show a size). */
    fun modelFile(ctx: Context) = File(dir(ctx), MODEL_NAME)

    fun isInstalled(ctx: Context): Boolean {
        val d = dir(ctx)
        return File(d, MARKER).exists() &&
            modelFile(ctx).exists() &&
            File(d, "tokens.txt").exists() &&
            File(d, "espeak-ng-data").isDirectory
    }

    // ---------------------------------------------------------------
    // Download and unpack. Returns null on success, or a short error.
    // Progress: 0-90 while downloading, 90-100 while unpacking.
    // ---------------------------------------------------------------

    fun download(ctx: Context, onProgress: (Int) -> Unit): String? {

        val tmp = File(ctx.cacheDir, "piper-voice.tar.bz2")
        val fresh = File(ctx.filesDir, "$DIR-new")

        return try {
            val conn = URL(URL_BASE + PACKAGE + ".tar.bz2").openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true

            if (conn.responseCode !in 200..299) return "Server said ${conn.responseCode}"

            val total = conn.contentLengthLong

            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(65_536)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 90 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }

            fresh.deleteRecursively()
            fresh.mkdirs()
            unpack(tmp, fresh, onProgress)
            tmp.delete()

            if (!File(fresh, MODEL_NAME).exists() ||
                !File(fresh, "tokens.txt").exists() ||
                !File(fresh, "espeak-ng-data").isDirectory
            ) {
                fresh.deleteRecursively()
                return "Voice files were missing after unpacking"
            }

            File(fresh, MARKER).writeText("ok")

            release()                              // free the old engine before replacing its files
            dir(ctx).deleteRecursively()

            if (fresh.renameTo(dir(ctx))) {
                onProgress(100)
                null
            } else {
                "Could not install the voice"
            }
        } catch (e: Exception) {
            tmp.delete()
            fresh.deleteRecursively()
            e.message ?: "Download failed"
        }
    }

    // Counts compressed bytes read so unpacking can show progress.
    private class Counting(input: InputStream, val total: Long, val report: (Int) -> Unit) : FilterInputStream(input) {
        private var read = 0L
        private var last = -1
        private fun add(n: Int) {
            if (n <= 0 || total <= 0) return
            read += n
            val p = 90 + (read * 10 / total).toInt().coerceAtMost(9)
            if (p != last) { last = p; report(p) }
        }
        override fun read(): Int { val b = super.read(); if (b >= 0) add(1); return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); add(n); return n }
    }

    // tar.bz2 -> folder, dropping the top-level folder name and refusing paths that escape.
    private fun unpack(archive: File, into: File, onProgress: (Int) -> Unit) {

        val root = into.canonicalPath + File.separator

        Counting(archive.inputStream().buffered(), archive.length(), onProgress).use { counted ->
            TarArchiveInputStream(BZip2CompressorInputStream(counted)).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val rel = entry.name.substringAfter('/', "")
                    if (rel.isEmpty()) continue

                    val target = File(into, rel)
                    if (!target.canonicalPath.startsWith(root)) throw Exception("Unsafe path in voice package")

                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { tar.copyTo(it) }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Engine
    // ---------------------------------------------------------------

    /** Load the voice. Call from a background thread. Returns true if ready. */
    @Synchronized
    fun init(ctx: Context): Boolean {

        if (tts != null) return true
        if (!isInstalled(ctx)) return false

        return try {
            val d = dir(ctx)

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = File(d, MODEL_NAME).absolutePath,
                        tokens = File(d, "tokens.txt").absolutePath,
                        dataDir = File(d, "espeak-ng-data").absolutePath
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
            )

            tts = OfflineTts(null, config)      // null = read from files, not from app assets
            true
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Natural voice", e)
            tts = null
            false
        }
    }

    @Synchronized
    fun release() {
        stop()
        val engine = tts
        tts = null
        try { engine?.release() } catch (e: Throwable) { }
    }

    // ---------------------------------------------------------------
    // Speaking (blocks until finished or stopped)
    // ---------------------------------------------------------------

    fun speak(text: String) {

        if (text.isBlank()) return

        val mine = generation

        synchronized(speakLock) {

            if (mine != generation) return          // stop() was called while this was queued

            val engine = tts ?: return

            try {
                val audio = engine.generate(text, 0, 1.0f)       // text, speaker id, speed
                if (mine != generation) return
                play(audio.samples, audio.sampleRate, mine)
            } catch (e: Throwable) {
                // Android TTS is the fallback; a failed sentence is just skipped.
            }
        }
    }

    /** Interrupts what is playing and cancels anything waiting to be spoken. */
    fun stop() {
        generation++
        val t = track
        track = null
        try { t?.pause(); t?.flush(); t?.stop() } catch (e: Throwable) { }
        try { t?.release() } catch (e: Throwable) { }
    }

    private fun play(samples: FloatArray, sampleRate: Int, mine: Int) {

        if (samples.isEmpty()) return

        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
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
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBytes, 16_384))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track = at

        try {
            at.play()

            var offset = 0
            while (offset < samples.size && mine == generation) {
                val n = at.write(samples, offset, minOf(4096, samples.size - offset), AudioTrack.WRITE_BLOCKING)
                if (n <= 0) break
                offset += n
            }

            // Everything is queued; wait for it to actually finish playing.
            val deadline = System.currentTimeMillis() + samples.size * 1000L / sampleRate + 1500L
            while (mine == generation &&
                at.playbackHeadPosition < samples.size &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(40)
            }
        } finally {
            if (track === at) track = null
            try { at.stop() } catch (e: Throwable) { }
            try { at.release() } catch (e: Throwable) { }
        }
    }
}
