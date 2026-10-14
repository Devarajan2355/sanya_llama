package com.sanya.assistant

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * LocalLLM: on-device language model via llama.cpp JNI.
 *
 * Default model: SmolLM2-360M-Instruct Q4_K_M (~200 MB).
 * Downloaded once into files/llm-model.gguf, just like the Vosk voice model.
 *
 * What this adds that the rule-based brain cannot do:
 *  - free-form reasoning and multi-sentence explanation
 *  - summarisation of long text
 *  - basic translation ("translate this to Tamil")
 *  - creative writing, stories, poems
 *  - multi-turn conversation with context
 *  - any question not handled by WebSearch or AIBrain
 *  - rephrasing / correcting text
 *  - simple code explanation
 *
 * Prompt format: ChatML (used by SmolLM2, Qwen, many others).
 * Temperature 0.3 for facts, 0.7 for creative tasks.
 *
 * Runs at ~5-15 tokens/sec on a mid-range Android phone (Snapdragon 7xx+).
 * Uses 4 CPU threads, no GPU required.
 */
object LocalLLM {

    // Swap the URL to use a different GGUF model. Anything under ~300 MB at Q4_K_M works.
    // SmolLM2-360M  ~200 MB  fast, decent quality
    // Qwen2-0.5B    ~270 MB  slightly better reasoning
    // Qwen2-1.5B    ~830 MB  good but needs 1 GB+ RAM
    const val MODEL_URL =
        "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/main/" +
            "SmolLM2-360M-Instruct-Q4_K_M.gguf"

    private const val MODEL_FILE  = "llm-model.gguf"
    private const val MAX_TOKENS  = 300
    private const val CTX_SIZE    = 2048
    private const val N_THREADS   = 4

    @Volatile private var handle  = 0L
    @Volatile private var loaded  = false
    @Volatile private var loading = false

    // Rolling conversation history — at most 3 turns so the context never overflows.
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
        // If the .so is missing the JNI calls return empty strings gracefully.
    }

    // ---------------------------------------------------------------
    // Model lifecycle
    // ---------------------------------------------------------------

    fun modelFile(ctx: Context) = File(ctx.filesDir, MODEL_FILE)

    fun isInstalled(ctx: Context) =
        modelFile(ctx).let { it.exists() && it.length() > 50_000_000L }

    /** Download the GGUF model file. Progress 0-100. Returns null on success. */
    fun download(ctx: Context, onProgress: (Int) -> Unit): String? {
        return try {
            val tmp  = File(ctx.cacheDir, "llm.tmp")
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout    = 30_000
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
            if (tmp.renameTo(modelFile(ctx))) null else "Could not install model file"
        } catch (e: Exception) { e.message ?: "Download failed" }
    }

    /**
     * Load the model into memory. Call once from a background thread.
     * Safe to call multiple times — no-ops if already loaded.
     */
    @Synchronized
    fun load(ctx: Context): Boolean {
        if (loaded)  return true
        if (loading) return false
        if (!isInstalled(ctx)) return false
        loading = true
        return try {
            val h = nativeLoad(modelFile(ctx).absolutePath, CTX_SIZE, N_THREADS)
            if (h != 0L) { handle = h; loaded = true; true } else false
        } catch (e: Throwable) { false }
        finally { loading = false }
    }

    fun unload() {
        val h = handle; if (h == 0L) return
        handle = 0L; loaded = false
        runCatching { nativeFree(h) }
    }

    val isReady get() = loaded && handle != 0L

    // ---------------------------------------------------------------
    // High-level Sanya API
    // ---------------------------------------------------------------

    /**
     * Answer a general question with conversation history.
     * Use this as the last resort after AIBrain and WebSearch both fail.
     */
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

    /** Check whether [text] looks like a task for the LLM (not a phone command). */
    fun looksLikeLLMTask(text: String): Boolean {
        val t = text.lowercase()
        return LLM_TRIGGERS.any { t.contains(it) }
    }

    private val LLM_TRIGGERS = listOf(
        "explain", "summarise", "summarize", "translate", "write a", "write me",
        "tell me a story", "poem", "what does", "what is the meaning",
        "difference between", "compare", "why does", "how does", "define",
        "describe", "correct this", "fix this", "rephrase", "rewrite",
        "in simple words", "in tamil", "in hindi", "in telugu", "in malayalam"
    )

    // ---------------------------------------------------------------
    // Core inference
    // ---------------------------------------------------------------

    /**
     * Stateful: includes the last [MAX_HISTORY] conversation turns.
     * Use for interactive questions where follow-up context matters.
     */
    fun ask(input: String, temp: Float = 0.7f): String? {
        if (!isReady) return null
        val prompt = buildChatPrompt(input)
        val raw    = runCatching { nativeInfer(handle, prompt, MAX_TOKENS, temp) }.getOrNull()
        val out    = clean(raw ?: return null)
        if (out.isNotBlank()) {
            history.addLast(Pair(input, out))
            if (history.size > MAX_HISTORY) history.removeFirst()
        }
        return out.ifBlank { null }
    }

    /**
     * Stateless single-shot completion.
     * Use for tasks like translation and summarisation where history is irrelevant.
     */
    fun complete(system: String, user: String, temp: Float = 0.3f): String? {
        if (!isReady) return null
        val prompt = buildSystemPrompt(system, user)
        val raw    = runCatching { nativeInfer(handle, prompt, MAX_TOKENS, temp) }.getOrNull()
        return clean(raw ?: return null).ifBlank { null }
    }

    fun clearHistory() { history.clear() }

    // ---------------------------------------------------------------
    // Prompt building  (ChatML — compatible with SmolLM2, Qwen, Mistral)
    // ---------------------------------------------------------------

    private val SYSTEM_PROMPT =
        "You are Sanya, a helpful Android voice assistant. " +
        "Keep answers short (1-3 sentences), clear, and spoken-friendly. " +
        "No bullet points, no markdown, no emoji."

    private fun buildChatPrompt(input: String): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n$SYSTEM_PROMPT<|im_end|>\n")
        for ((u, a) in history) {
            sb.append("<|im_start|>user\n$u<|im_end|>\n")
            sb.append("<|im_start|>assistant\n$a<|im_end|>\n")
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
