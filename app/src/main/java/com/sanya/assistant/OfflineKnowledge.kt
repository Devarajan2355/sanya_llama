package com.sanya.assistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * OfflineKnowledge: a local SQLite FTS5 database of Simple English Wikipedia
 * article summaries (~100 MB, downloaded once like the Vosk voice model).
 *
 * Schema (built by tools/build_knowledge_db.py):
 *
 *   CREATE VIRTUAL TABLE articles USING fts5(
 *       title,
 *       summary,      -- first 3 sentences of each article
 *       categories,   -- pipe-separated category names
 *       content=''    -- contentless FTS for smaller file size
 *   );
 *   CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
 *
 * What it provides:
 *  - instant offline answers to: what is X, who is X, where is X
 *  - context paragraphs that LocalLLM can reason over
 *  - ~200 000 article summaries covering science, history, geography,
 *    people, places, concepts — all without internet
 *
 * Pipeline:
 *   question → OfflineKnowledge.search() → best article summary
 *            → LocalLLM.complete(system, summary+question)  (optional)
 *            → spoken answer
 */
object OfflineKnowledge {

    // Host this pre-built file yourself (see tools/build_knowledge_db.py).
    // A ~100 MB SQLite file with FTS5 index covering Simple English Wikipedia.
    const val DB_URL  = "https://YOUR_CDN/sanya-wiki-v1.db"
    private const val DB_FILE = "offline-knowledge.db"

    private var db: SQLiteDatabase? = null

    // ---------------------------------------------------------------
    // Data model
    // ---------------------------------------------------------------

    data class Article(
        val title:      String,
        val summary:    String,
        val categories: String
    )

    // ---------------------------------------------------------------
    // Installation
    // ---------------------------------------------------------------

    fun dbFile(ctx: Context) = File(ctx.filesDir, DB_FILE)

    fun isInstalled(ctx: Context) =
        dbFile(ctx).let { it.exists() && it.length() > 10_000_000L }

    /** Download the database. Progress 0-100. Returns null on success. */
    fun download(ctx: Context, onProgress: (Int) -> Unit): String? {
        return try {
            val tmp  = File(ctx.cacheDir, "wiki.tmp")
            val conn = URL(DB_URL).openConnection() as HttpURLConnection
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
            if (tmp.renameTo(dbFile(ctx))) null else "Could not install knowledge base"
        } catch (e: Exception) { e.message ?: "Download failed" }
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
        } catch (e: Exception) { false }
    }

    @Synchronized
    fun close() { db?.close(); db = null }

    val isOpen get() = db != null

    // ---------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------

    /**
     * FTS5 full-text search.  Returns up to [limit] articles ranked by
     * relevance (FTS5 BM25 rank).
     *
     * Falls back to a LIKE title search when FTS returns nothing,
     * so single-word lookups ("Python", "Gandhi") always work.
     */
    fun search(query: String, limit: Int = 5): List<Article> {
        val d = db ?: return emptyList()
        if (query.isBlank()) return emptyList()

        val safe = query
            .replace('"', ' ')
            .replace("'", "''")   // SQLite escaping
            .replace("*", " ")
            .trim()

        // ---- FTS5 phrase match ----
        val fts = runCatching {
            d.rawQuery(
                "SELECT title, summary, categories " +
                "FROM articles WHERE articles MATCH ? " +
                "ORDER BY rank LIMIT ?",
                arrayOf("\"$safe\"", limit.toString())
            ).use { cur ->
                buildList {
                    while (cur.moveToNext())
                        add(Article(cur.getString(0), cur.getString(1), cur.getString(2)))
                }
            }
        }.getOrDefault(emptyList())

        if (fts.isNotEmpty()) return fts

        // ---- Fallback: FTS5 prefix on individual tokens ----
        val tokens = safe.split(" ")
            .filter { it.length >= 3 }
            .joinToString(" ") { "$it*" }

        val prefix = if (tokens.isNotEmpty()) runCatching {
            d.rawQuery(
                "SELECT title, summary, categories " +
                "FROM articles WHERE articles MATCH ? " +
                "ORDER BY rank LIMIT ?",
                arrayOf(tokens, limit.toString())
            ).use { cur ->
                buildList {
                    while (cur.moveToNext())
                        add(Article(cur.getString(0), cur.getString(1), cur.getString(2)))
                }
            }
        }.getOrDefault(emptyList()) else emptyList()

        if (prefix.isNotEmpty()) return prefix

        // ---- Final fallback: title LIKE search ----
        return runCatching {
            d.rawQuery(
                "SELECT title, summary, categories FROM articles " +
                "WHERE title LIKE ? COLLATE NOCASE LIMIT ?",
                arrayOf("%$safe%", limit.toString())
            ).use { cur ->
                buildList {
                    while (cur.moveToNext())
                        add(Article(cur.getString(0), cur.getString(1), cur.getString(2)))
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Exact title lookup (case-insensitive). */
    fun lookup(title: String): Article? {
        val d = db ?: return null
        return runCatching {
            d.rawQuery(
                "SELECT title, summary, categories FROM articles " +
                "WHERE title = ? COLLATE NOCASE LIMIT 1",
                arrayOf(title)
            ).use { cur ->
                if (cur.moveToFirst())
                    Article(cur.getString(0), cur.getString(1), cur.getString(2))
                else null
            }
        }.getOrNull()
    }

    /**
     * Returns the best context paragraph for a question, suitable for
     * feeding into LocalLLM.complete() as background knowledge.
     *
     * Example:
     *   val ctx = findContext("who invented the telephone")
     *   // "According to Wikipedia — Alexander Graham Bell:
     *   //  Alexander Graham Bell was a Scottish-American inventor..."
     */
    fun findContext(question: String): String? {
        val articles = search(stripQuestionWords(question), limit = 3)
        val best = articles.firstOrNull() ?: return null
        return "According to Wikipedia — ${best.title}: ${best.summary.take(400)}"
    }

    /**
     * Try to answer [question] directly from the knowledge base,
     * without the LLM.  Works well for "what is X", "who is X", "where is X".
     * Returns null when no good match is found.
     */
    fun directAnswer(question: String): String? {
        val subject = stripQuestionWords(question)
        if (subject.length < 3) return null

        // Exact title match — highest confidence
        val exact = lookup(subject)
        if (exact != null) {
            val first = exact.summary.substringBefore(". ").trimEnd('.') + "."
            return "${exact.title}: $first"
        }

        // FTS match — accept only when the title closely matches the subject
        val hits = search(subject, limit = 3)
        val good = hits.firstOrNull { hit ->
            val t = Brain.clean(hit.title)
            val s = Brain.clean(subject)
            t.contains(s) || s.contains(t) || Mind.similarity(t, s) >= 0.60
        } ?: return null

        val first = good.summary.substringBefore(". ").trimEnd('.') + "."
        return "${good.title}: $first"
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private val QUESTION_LEADS = listOf(
        "what is ", "what are ", "who is ", "who are ", "where is ",
        "where are ", "when is ", "when was ", "how is ", "define ",
        "tell me about ", "what does ", "explain "
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
