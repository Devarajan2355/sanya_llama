package com.sanya.assistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * OfflineKnowledge: a local SQLite FTS4 database of Simple English Wikipedia
 * article summaries (about 50-80 MB, downloaded once like the Vosk voice model).
 *
 * FTS4 is used because Android's built-in SQLite always has it, while FTS5 is not
 * guaranteed. FTS4 has no "rank" column, so results are ordered by short title first.
 *
 * Schema (built by tools/build_knowledge_db.py):
 *
 *   CREATE VIRTUAL TABLE articles USING fts4(title, summary, tokenize=porter);
 *   CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
 *
 * What it provides:
 *  - instant offline answers to: what is X, who is X, where is X
 *  - context paragraphs that LocalLLM can reason over
 *
 * The database is a snapshot. It must NOT be used for questions whose answer changes
 * over time (office holders, "current", "latest"); StepRunner sends those to the web.
 *
 * Pipeline:
 *   question -> OfflineKnowledge.directAnswer()  -> spoken answer
 *   question -> OfflineKnowledge.findContext()   -> LocalLLM.complete(system, context + question)
 */
object OfflineKnowledge {

    // Upload sanya-wiki-v1.db as an asset of a GitHub Release and paste its URL here, e.g.
    // https://github.com/YOUR_USER/Sanya_Finalize/releases/download/kb-v1/sanya-wiki-v1.db
    const val DB_URL = "https://github.com/YOUR_USER/Sanya_Finalize/releases/download/kb-v1/sanya-wiki-v1.db"
    private const val DB_FILE = "offline-knowledge.db"

    private var db: SQLiteDatabase? = null

    // ---------------------------------------------------------------
    // Data model
    // ---------------------------------------------------------------

    data class Article(
        val title: String,
        val summary: String,
        val categories: String = ""
    )

    // ---------------------------------------------------------------
    // Installation
    // ---------------------------------------------------------------

    fun dbFile(ctx: Context) = File(ctx.filesDir, DB_FILE)

    fun isInstalled(ctx: Context) =
        dbFile(ctx).let { it.exists() && it.length() > 10_000_000L }

    /** Download the database. Progress 0-100. Returns null on success. */
    fun download(ctx: Context, onProgress: (Int) -> Unit): String? {

        if (DB_URL.contains("YOUR_USER")) {
            return "Set DB_URL in OfflineKnowledge.kt to your release file first."
        }

        val tmp = File(ctx.cacheDir, "wiki.tmp")

        return try {
            val conn = URL(DB_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true

            if (conn.responseCode !in 200..299) {
                tmp.delete()
                return "Server said ${conn.responseCode}"
            }

            val total = conn.contentLengthLong

            conn.inputStream.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(65_536)
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

            if (tmp.length() < 10_000_000L) {
                tmp.delete()
                return "The downloaded file is too small. Check DB_URL."
            }

            close()
            dbFile(ctx).delete()

            if (tmp.renameTo(dbFile(ctx))) {
                null
            } else {
                // renameTo can fail across storage areas; copy instead.
                tmp.copyTo(dbFile(ctx), overwrite = true)
                tmp.delete()
                null
            }
        } catch (e: Exception) {
            tmp.delete()
            e.message ?: "Download failed"
        }
    }

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    @Synchronized
    fun open(ctx: Context): Boolean {
        if (db != null) return true
        if (!isInstalled(ctx)) return false
        return try {
            db = SQLiteDatabase.openDatabase(
                dbFile(ctx).absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            true
        } catch (e: Exception) {
            ErrorLog.add(ctx, "Offline knowledge open", e)
            false
        }
    }

    @Synchronized
    fun close() {
        try { db?.close() } catch (e: Exception) { }
        db = null
    }

    val isOpen get() = db != null

    // ---------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------

    // Letters, digits and spaces only, so nothing can break the FTS query syntax.
    private fun clean(q: String): String =
        q.replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun run(sql: String, vararg args: String): List<Article> {
        val d = db ?: return emptyList()
        return runCatching {
            d.rawQuery(sql, arrayOf(*args)).use { cur ->
                val out = ArrayList<Article>()
                while (cur.moveToNext()) {
                    out.add(Article(cur.getString(0) ?: "", cur.getString(1) ?: ""))
                }
                out
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Full-text search. Returns up to [limit] articles, shorter titles first
     * (so "Gandhi" comes before "Gandhi family").
     *
     * Order tried: exact phrase, then every word as a prefix, then a title LIKE.
     */
    fun search(query: String, limit: Int = 5): List<Article> {
        if (db == null) return emptyList()

        val safe = clean(query)
        if (safe.isEmpty()) return emptyList()

        val n = limit.coerceIn(1, 20)

        // ---- exact phrase ----
        val phrase = run(
            "SELECT title, summary FROM articles WHERE articles MATCH ? ORDER BY length(title) LIMIT $n",
            "\"$safe\""
        )
        if (phrase.isNotEmpty()) return phrase

        // ---- every word as a prefix (3+ letters) ----
        val tokens = safe.split(" ")
            .filter { it.length >= 3 }
            .joinToString(" ") { "$it*" }

        if (tokens.isNotEmpty()) {
            val prefix = run(
                "SELECT title, summary FROM articles WHERE articles MATCH ? ORDER BY length(title) LIMIT $n",
                tokens
            )
            if (prefix.isNotEmpty()) return prefix
        }

        // ---- last resort: title contains the text ----
        return run(
            "SELECT title, summary FROM articles WHERE title LIKE ? ORDER BY length(title) LIMIT $n",
            "%$safe%"
        )
    }

    /** Exact title lookup (case-insensitive). */
    fun lookup(title: String): Article? {
        if (db == null) return null

        val safe = clean(title)
        if (safe.isEmpty()) return null

        return run(
            "SELECT title, summary FROM articles WHERE articles MATCH ? LIMIT 20",
            "title:\"$safe\""
        ).firstOrNull { it.title.equals(title.trim(), ignoreCase = true) }
    }

    /**
     * Returns the best context paragraph for a question, suitable for
     * feeding into LocalLLM.complete() as background knowledge.
     * Only returns something when the title of the best article really fits the question.
     */
    fun findContext(question: String): String? {

        val subject = stripQuestionWords(question)
        if (subject.length < 3) return null

        val best = pickGood(subject, search(subject, limit = 3)) ?: return null

        return "According to Wikipedia - ${best.title}: ${best.summary.take(400)}"
    }

    /**
     * Try to answer [question] directly from the knowledge base, without the LLM.
     * Works well for "what is X", "who is X", "where is X".
     * Returns null when no good match is found.
     */
    fun directAnswer(question: String): String? {

        val subject = stripQuestionWords(question)
        if (subject.length < 3) return null

        // Exact title match: highest confidence
        val exact = lookup(subject)
        if (exact != null) return firstSentence(exact)

        val good = pickGood(subject, search(subject, limit = 3)) ?: return null

        return firstSentence(good)
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private fun firstSentence(a: Article): String =
        "${a.title}: " + a.summary.substringBefore(". ").trimEnd('.') + "."

    // Accept a hit only when its title closely matches the subject.
    private fun pickGood(subject: String, hits: List<Article>): Article? {
        val s = Brain.clean(subject)
        return hits.firstOrNull { hit ->
            val t = Brain.clean(hit.title)
            t.isNotEmpty() && (t.contains(s) || s.contains(t) || Mind.similarity(t, s) >= 0.60)
        }
    }

    private val QUESTION_LEADS = listOf(
        "what is ", "what are ", "what was ", "who is ", "who are ", "who was ",
        "where is ", "where are ", "when is ", "when was ", "how is ", "define ",
        "tell me about ", "what does ", "explain ", "about "
    )

    private fun stripQuestionWords(q: String): String {
        var s = q.lowercase().trim().trimEnd('?', '.', '!')
        for (lead in QUESTION_LEADS) {
            if (s.startsWith(lead)) { s = s.removePrefix(lead); break }
        }
        return s.trim()
            .removePrefix("the ")
            .removePrefix("a ")
            .removePrefix("an ")
            .trim()
    }
}
