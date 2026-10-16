package com.sanya.assistant

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * LocalLLM: on-device language model via llama.cpp JNI.
 *
 * Default model: SmolLM2-360M-Instruct Q4_K_M (about 250 MB on disk).
 * Downloaded once into files/llm-model.gguf, just like the Vosk voice model.
 *
 * LAZY LOADING (important on a 4 GB phone):
 *   The model is NOT loaded when Sanya starts. The first request that needs it calls
 *   ensureLoaded() (a few seconds, from a background thread). BubbleService calls
 *   unloadIfIdle() once a minute, and the model is freed after 5 idle minutes.
 *   Use isInstalled(ctx) to ask "is it available?", and isReady for "is it in memory now?".
 *
 * What this adds that the rule-based brain cannot do:
 *  - explanation, summarisation, basic translation, short creative writing,
 *    rephrasing / correcting text, and last-resort answers
 *
 * Prompt format: ChatML (used by SmolLM2, Qwen, many others).
 * Runs at roughly 5-15 tokens/sec on a mid-range phone. Uses 4 CPU threads, no GPU.
 */
object LocalLLM {

    // Swap the URL to use a different GGUF model.
    // SmolLM2-135M  ~100 MB  very fast, basic     (use this if you must stay well under 500 MB)
    // SmolLM2-360M  ~250 MB  fast, decent quality
    // Qwen2-0.5B    ~400 MB  slightly better reasoning
    const val MODEL_URL =
        "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/main/" +
            "SmolLM2-360M-Instruct-Q4_K_M.gguf"

    private const val MODEL_FILE  = "llm-model.gguf"
    private const val MAX_TOKENS  = 300
    private const val CTX_SIZE    = 2048
    private const val N_THREADS   = 4

    @Volatile private var handle  = 0L
    @Volatile private var loaded  = false
    @Volatile private var lastUsed = 0L        // SystemClock.elapsedRealtime()

    // Held while the model loads, runs or is freed, so it can never be freed mid-answer.
    private val gate = ReentrantLock()

    // Rolling conversation history: at most 3 turns so the context never overflows.
    private val history = ArrayDeque<Pair<String, String>>() // (user, assistant)
    private const val MAX_HISTORY = 3

    // ---------------------------------------------------------------
    // Native interface  (implemented in app/src/main/cpp/llm_jni.cpp)
    // ---------------------------------------------------------------

    private external fun nativeLoad(path: String, ctxSize: Int, nThreads: Int): Long
    private external fun nativeInfer(handle: Long, prompt: String, maxTokens: Int, temp: Float): String
    private external fun nativeFree(handle: Long)

    init {
        runCatching { System.loadLibrary("llama_android") }
        // If the .so is missing, loading simply fails and ensureLoaded() returns false.
    }

    // ---------------------------------------------------------------
    // Model lifecycle
    // ---------------------------------------------------------------

    fun modelFile(ctx: Context) = File(ctx.filesDir, MODEL_FILE)

    fun isInstalled(ctx: Context) =
        modelFile(ctx).let { it.exists() && it.length() > 50_000_000L }

    /** Download the GGUF model file. Progress 0-100. Returns null on success. */
    fun download(ctx: Context, onProgress: (Int) -> Unit): String? {

        val tmp = File(ctx.cacheDir, "llm.tmp")

        return try {
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout    = 30_000
            conn.instanceFollowRedirects = true

            if (conn.responseCode !in 200..299) {
                tmp.delete()
                return "Server said ${conn.responseCode}"
            }

            val total = conn.contentLengthLong

            conn.inputStream.use { inp ->
                tmp.outputStream().use { out ->
                    val buf  = ByteArray(65_536)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = inp.read(buf)
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

            unload()
            modelFile(ctx).delete()

            if (tmp.renameTo(modelFile(ctx))) {
                null
            } else {
                tmp.copyTo(modelFile(ctx), overwrite = true)
                tmp.delete()
                null
            }
        } catch (e: Exception) {
            tmp.delete()
            e.message ?: "Download failed"
        }
    }

    /**
     * Load the model into memory. Slow (seconds): call from a background thread.
     * Safe to call many times; returns true at once if it is already loaded.
     */
    fun load(ctx: Context): Boolean {

        if (loaded && handle != 0L) {
            touch()
            return true
        }

        if (!isInstalled(ctx)) return false

        gate.lock()

        try {
            if (loaded && handle != 0L) {
                touch()
                return true
            }

            val h = try {
                nativeLoad(modelFile(ctx).absolutePath, CTX_SIZE, N_THREADS)
            } catch (e: Throwable) {
                ErrorLog.add(ctx, "LLM load", e)
                0L
            }

            if (h == 0L) return false

            handle = h
            loaded = true
            touch()
            return true

        } finally {
            gate.unlock()
        }
    }

    /** Same as load(): the name to use before a request. Call from a background thread. */
    fun ensureLoaded(ctx: Context): Boolean = load(ctx)

    /** Frees the model now. Waits if an answer is being generated. */
    fun unload() {

        gate.lock()

        try {
            freeLocked()
        } finally {
            gate.unlock()
        }
    }

    /**
     * Frees the model when nobody has used it for [minutes]. Never blocks:
     * if an answer is being generated right now it simply does nothing.
     * Called once a minute by BubbleService.
     */
    fun unloadIfIdle(minutes: Int) {

        if (!loaded) return

        if (SystemClock.elapsedRealtime() - lastUsed < TimeUnit.MINUTES.toMillis(minutes.toLong())) return

        if (!gate.tryLock()) return

        try {
            freeLocked()
        } finally {
            gate.unlock()
        }
    }

    private fun freeLocked() {

        val h = handle

        handle = 0L
        loaded = false
        history.clear()

        if (h != 0L) runCatching { nativeFree(h) }
    }

    private fun touch() {
        lastUsed = SystemClock.elapsedRealtime()
    }

    /** True while the model is in memory right now. */
    val isReady get() = loaded && handle != 0L

    // ---------------------------------------------------------------
    // High-level Sanya API
    // (all of these need ensureLoaded() to have succeeded; they return null otherwise)
    // ---------------------------------------------------------------

    /** Answer a general question with conversation history. A last resort: it can be wrong. */
    fun answerQuestion(question: String): String? =
        ask(question, temp = 0.3f)

    /** Summarise [text] into one or two spoken sentences. */
    fun summarise(text: String): String? =
        complete(
            system = "Summarise in 1-2 short spoken sentences. No bullet points or markdown.",
            user   = text,
            temp   = 0.3f
        )

    /** Translate [text] to [language] and return only the translation. */
    fun translate(text: String, language: String): String? =
        complete(
            system = "Translate the following to $language. Reply with only the translation.",
            user   = text,
            temp   = 0.2f
        )

    /** Explain a concept in simple terms. */
    fun explain(concept: String): String? =
        complete(
            system = "Explain in 2 short sentences, as if to a smart 12-year-old. No markdown.",
            user   = concept,
            temp   = 0.4f
        )

    /** Write a short creative piece (poem, story, etc.). */
    fun create(prompt: String): String? =
        complete(
            system = "You are a creative assistant. Keep the reply under 80 words. No markdown.",
            user   = prompt,
            temp   = 0.8f
        )

    /** Correct grammar and spelling in [text]. */
    fun correct(text: String): String? =
        complete(
            system = "Fix grammar and spelling. Return only the corrected text, nothing else.",
            user   = text,
            temp   = 0.1f
        )

    // Only sentences that START with one of these go to the LLM. Matching anywhere in the
    // sentence used to steal normal commands ("write a note buy milk", "play songs in tamil").
    private val LLM_START = Regex(
        """^(?:explain|summari[sz]e|translate|define|describe|rephrase|rewrite|""" +
            """correct this|fix this|fix my grammar|tell me a story|""" +
            """write (?:me )?(?:a|an) (?:short |small |little )?(?:poem|story|song|essay|paragraph|letter|email)|""" +
            """difference between|what is the difference between|what's the difference between|compare)\b"""
    )

    /** Does [text] start like a task for the LLM (and not like a phone command)? */
    fun looksLikeLLMTask(text: String): Boolean =
        LLM_START.containsMatchIn(text.lowercase().trim())

    // ---------------------------------------------------------------
    // Core inference
    // ---------------------------------------------------------------

    // Runs one generation while holding the gate, so unload() cannot free the model under it.
    private fun infer(prompt: String, temp: Float): String? {

        gate.lock()

        try {
            val h = handle
            if (!loaded || h == 0L) return null

            val raw = runCatching { nativeInfer(h, prompt, MAX_TOKENS, temp) }.getOrNull()

            touch()

            return raw
        } finally {
            gate.unlock()
        }
    }

    /**
     * Stateful: includes the last [MAX_HISTORY] conversation turns.
     * Use for interactive questions where follow-up context matters.
     */
    fun ask(input: String, temp: Float = 0.7f): String? {

        val prompt = buildChatPrompt(input)
        val raw    = infer(prompt, temp) ?: return null
        val out    = clean(raw)

        if (out.isNotBlank()) {
            synchronized(history) {
                history.addLast(Pair(input, out))
                if (history.size > MAX_HISTORY) history.removeFirst()
            }
        }

        return out.ifBlank { null }
    }

    /**
     * Stateless single-shot completion.
     * Use for tasks like translation and summarisation where history is irrelevant.
     */
    fun complete(system: String, user: String, temp: Float = 0.3f): String? {

        val prompt = buildSystemPrompt(system, user)
        val raw    = infer(prompt, temp) ?: return null

        return clean(raw).ifBlank { null }
    }

    fun clearHistory() {
        synchronized(history) { history.clear() }
    }

    // ---------------------------------------------------------------
    // Prompt building  (ChatML: compatible with SmolLM2, Qwen, Mistral)
    // ---------------------------------------------------------------

    private val SYSTEM_PROMPT =
        "You are Sanya, a helpful Android voice assistant. " +
        "Keep answers short (1-3 sentences), clear, and spoken-friendly. " +
        "No bullet points, no markdown, no emoji."

    private fun buildChatPrompt(input: String): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n$SYSTEM_PROMPT<|im_end|>\n")
        synchronized(history) {
            for ((u, a) in history) {
                sb.append("<|im_start|>user\n$u<|im_end|>\n")
                sb.append("<|im_start|>assistant\n$a<|im_end|>\n")
            }
        }
        sb.append("<|im_start|>user\n$input<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun buildSystemPrompt(system: String, user: String): String =
        "<|im_start|>system\n$system<|im_end|>\n" +
        "<|im_start|>user\n$user<|im_end|>\n" +
        "<|im_start|>assistant\n"

    // Strip ChatML tokens, markdown and extra whitespace so the reply is speakable.
    private fun clean(raw: String): String =
        raw.substringBefore("<|im_end|>")
           .substringBefore("<|im_start|>")
           .replace(Regex("""[*_#`]+"""), "")
           .replace(Regex("""\n{2,}"""), " ")
           .replace("\n", " ")
           .replace(Regex("""\s{2,}"""), " ")
           .trim()
}
