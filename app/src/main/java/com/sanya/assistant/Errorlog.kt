package com.sanya.assistant

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ErrorLog: a private list of the last things that went wrong, so you can send the exact
 * failure instead of describing it. Shown on the Health check screen.
 *
 *   - Crashes are recorded automatically (install() sets an uncaught-exception hook and
 *     then lets Android carry on as normal).
 *   - Failures that used to be swallowed (a screen that would not open, a web lookup that
 *     failed, a microphone problem) are recorded with add() / note().
 *
 * Stored in files/errors.log, one entry per line:  time <tab> where <tab> message
 * Only the last 60 are kept. It never contains your Brain file, contacts or secrets.
 */
object ErrorLog {

    private const val FILE = "errors.log"
    private const val KEEP = 60

    @Volatile private var installed = false

    data class Entry(val time: Long, val where: String, val message: String) {
        val crash: Boolean get() = where.startsWith("CRASH")
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun oneLine(s: String): String =
        s.replace('\n', ' ').replace('\t', ' ').replace('\r', ' ').trim().take(400)

    // "NullPointerException: text  @ Class.method:12 < Class.method:40"
    private fun describe(e: Throwable): String {

        val ours = e.stackTrace.filter { it.className.startsWith("com.sanya") }
        val frames = (if (ours.isNotEmpty()) ours else e.stackTrace.toList()).take(4)

        val where = frames.joinToString(" < ") {
            it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber
        }

        val cause = e.cause?.let { " (cause: " + it.javaClass.simpleName + ")" } ?: ""

        return e.javaClass.simpleName + ": " + (e.message ?: "") + cause + "  @ " + where
    }

    @Synchronized
    private fun write(ctx: Context, where: String, message: String) {

        try {
            val f = file(ctx)

            f.appendText(System.currentTimeMillis().toString() + "\t" + oneLine(where) + "\t" + oneLine(message) + "\n")

            val lines = f.readLines()
            if (lines.size > KEEP + 40) {
                f.writeText(lines.takeLast(KEEP).joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
            // Logging must never cause a problem of its own.
        }
    }

    /** Record a caught exception. */
    fun add(ctx: Context, where: String, e: Throwable?) {
        write(ctx, where, if (e == null) "(no details)" else describe(e))
    }

    /** Record a plain message (for example a microphone problem). */
    fun note(ctx: Context, where: String, message: String) {
        write(ctx, where, message)
    }

    /** Newest first. */
    @Synchronized
    fun recent(ctx: Context, max: Int = 20): List<Entry> {

        return try {
            val f = file(ctx)

            if (!f.exists()) {
                emptyList<Entry>()
            } else {
                f.readLines()
                    .mapNotNull { line ->
                        val p = line.split("\t")
                        val t = if (p.size >= 3) p[0].toLongOrNull() else null
                        if (t == null) null else Entry(t, p[1], p.drop(2).joinToString(" "))
                    }
                    .asReversed()
                    .take(max)
            }
        } catch (e: Exception) {
            emptyList<Entry>()
        }
    }

    fun countSince(ctx: Context, sinceMs: Long): Int =
        recent(ctx, KEEP).count { it.time >= sinceMs }

    fun crashesSince(ctx: Context, sinceMs: Long): Int =
        recent(ctx, KEEP).count { it.time >= sinceMs && it.crash }

    fun stamp(t: Long): String =
        SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(t))

    @Synchronized
    fun clear(ctx: Context) {
        try {
            file(ctx).delete()
        } catch (e: Exception) {
        }
    }

    /** Record every crash, then let Android handle it exactly as before. */
    fun install(ctx: Context) {

        if (installed) return
        installed = true

        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, "CRASH in " + thread.name, describe(error))
            } catch (x: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
