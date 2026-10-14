package com.sanya.assistant

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.MediaStore
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.KeyEvent
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.abs
import kotlin.math.floor
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

sealed class Step {
    data class OpenApp(val name: String) : Step()
    data class Call(val contact: String, val video: Boolean, val whatsapp: Boolean) : Step()
    data class Sms(val contact: String, val text: String) : Step()
    data class Timer(val seconds: Int) : Step()
    data class Alarm(val hour: Int, val min: Int) : Step()
    data class Remind(val text: String, val seconds: Int) : Step()
    data class Event(val title: String, val start: Long) : Step()
    data class Toggle(val what: String, val on: Boolean) : Step()
    data class Volume(val up: Boolean) : Step()
    data class Media(val key: String) : Step()
    object Photo : Step()
    data class Search(val q: String, val youtube: Boolean) : Step()
    data class Navigate(val place: String) : Step()
    data class Info(val kind: String) : Step()
    data class AddTodo(val text: String) : Step()
    data class RemoveTodo(val text: String) : Step()
    object ReadTodos : Step()
    object ClearTodos : Step()
    data class Note(val text: String) : Step()
    object ReadNotes : Step()
    object ClearNotes : Step()
    data class Nick(val nicks: List<String>, val real: String) : Step()
    data class Teach(val phrases: List<String>, val cmd: String) : Step()
    data class WordMap(val word: String, val meaning: String) : Step()
    data class Ignore(val word: String) : Step()
    data class Mean(val cmd: String) : Step()
    data class Remember(val key: String, val value: String) : Step()
    data class Recall(val key: String) : Step()
    data class Copy(val key: String) : Step()
    data class Forget(val key: String) : Step()
    data class SetName(val name: String) : Step()
    object Brief : Step()
    object Stats : Step()
    object OpenBrain : Step()
    object Again : Step()
    data class Reply(val text: String) : Step()
    data class Unknown(val raw: String) : Step()
}

object CommandParser {
    private const val VERBS = "open|launch|start|run|call|video|voice|whatsapp|set|turn|search|play|watch|google|find|navigate|message|msg|text|sms|add|put|remove|delete|clear|read|show|what|take|volume|note|remember|remind|copy|forget|switch|ring|dial|tell|nickname|next|previous|pause|resume|skip|schedule|calculate|brief|stop|good|hello|hi|flip|roll|again|ignore|when|check|am"

    // Splits at "and"/"then" only when a new command follows,
    // so "add milk and eggs" stays whole.
    private val splitter =
        Regex("\\b(?:and then|after that|then|and)\\s+(?=(?:$VERBS)\\b)")

    private val builtinFill =
        Regex("\\b(?:please|pls|plz|kindly|just|quickly|i want to|i wanna|i need to|lets|let's|for me)\\b")

    private val nums = listOf(
        "twenty five" to 25,
        "thirty five" to 35,
        "forty five" to 45,
        "one" to 1,
        "two" to 2,
        "three" to 3,
        "four" to 4,
        "five" to 5,
        "six" to 6,
        "seven" to 7,
        "eight" to 8,
        "nine" to 9,
        "ten" to 10,
        "fifteen" to 15,
        "twenty" to 20,
        "thirty" to 30,
        "forty" to 40,
        "fifty" to 50,
        "sixty" to 60
    )

    private val jokes = listOf(
        "Why do programmers prefer dark mode? Because light attracts bugs.",
        "I told my phone I needed space. It opened the gallery.",
        "Why did the developer go broke? He used up all his cache."
    )

    private val talk = listOf(
        Regex("^(?:hello|hi|hey|hai|hii|yo)$") to "Hello{n}! How can I help?",
        Regex("^vanakkam") to "Vanakkam{n}! How can I help?",
        Regex("good (?:evening|afternoon)") to "Good evening{n}!",
        Regex("good night") to "Good night{n}, sleep well",
        Regex("how are you|how r u|how are u|what.?s up|wassup|\\bsup\\b") to "I'm doing great, thanks for asking",
        Regex("\\bthank|\\bthx") to "You're welcome{n}",
        Regex("who are you|your name") to "I'm Sanya, your personal assistant",
        Regex("who am i|what.?s my name|what is my name") to "Your name is {name}",
        Regex("what can you do|^help$") to "I can open apps, call and message, set timers, alarms and reminders, play music, search, navigate, calculate, keep notes and secrets, track your money and study time, run pomodoro focus sessions, and learn your nicknames and slang. Say show my brain to edit what I know.",
        Regex("love you") to "That's sweet. I'm always here to help"
    )

    private fun digits(s: String): String {
        if (!Regex("timer|alarm|remind|minute|hour|second|schedule|event|meeting|appointment|percent|plus|minus|times|divided|multiplied")
                .containsMatchIn(s)
        ) {
            return s
        }

        var r = s
            .replace("half an hour", "30 minutes")
            .replace(Regex("\\b(?:an|a) (hour|minute)")) {
                "1 " + it.groupValues[1]
            }

        for ((w, n) in nums) {
            r = r.replace(
                Regex("\\b$w\\b"),
                n.toString()
            )
        }

        return r
    }

    private fun normalize(raw: String): String {
        var s = raw
            .lowercase()
            .replace(Regex("[,!?]"), " ")
            .trim()

        // Tanglish word order:
        // "youtube open pannu"
        // "ravi ku call pannu"
        // "comedy songs podu"
        s = Regex(
            "^(.+?)\\s+(?:ku\\s+)?(open|call|search|play)\\s+(?:pannu|pannunga|pannungal|seiyu)$"
        ).replace(s) {
            it.groupValues[2] + " " + it.groupValues[1]
        }

        s = Regex(
            "^(.+?)\\s+(?:podu|poduga|poodu)$"
        ).replace(s) {
            "play " + it.groupValues[1]
        }

        s = s.replace(
            Regex("^(?:hello|hi|hey|hai|hii|yo)\\s+"),
            ""
        )

        s = s.replace(
            Regex("^(?:can|could|would|will)\\s+you\\s+"),
            ""
        )

        s = builtinFill.replace(s, " ")

        val extra = Brain.words("filler")

        if (extra.isNotEmpty()) {
            s = Regex(
                "\\b(?:" +
                    extra.joinToString("|") {
                        Regex.escape(it)
                    } +
                    ")\\b"
            ).replace(s, " ")
        }

        for ((k, v) in Brain.map("word")) {
            s = s.replace(
                Regex("\\b" + Regex.escape(k) + "\\b"),
                v
            )
        }

        s = s.replace(
            Regex("\\b(?:switch|put)\\s+(on|off)\\b")
        ) {
            "turn " + it.groupValues[1]
        }

        s = s
            .replace(Regex("\\benable\\b"), "turn on")
            .replace(Regex("\\bdisable\\b"), "turn off")

        return digits(s)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun parse(sentence: String): List<Step> {
        val s = normalize(sentence)

        if (s.isEmpty()) {
            return emptyList()
        }

        whole(s)?.let {
            return listOf(it)
        }

        var wa = false

        return s
            .split(splitter)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { p ->
                val st = one(p, wa)

                if (
                    st is Step.OpenApp &&
                    st.name.contains("whatsapp")
                ) {
                    wa = true
                }

                st
            }
    }

    private fun parts(s: String) =
        s.split(
            Regex("\\s+(?:or|and)\\s+|,")
        )
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun secs(n: Int, u: String) =
        if (u.startsWith("h")) {
            n * 3600
        } else if (u.startsWith("m")) {
            n * 60
        } else {
            n
        }

    private fun fmt(d: Double) =
        if (d == floor(d) && abs(d) < 1e12) {
            d.toLong().toString()
        } else {
            "%.2f".format(d)
        }

    // Sentences that must stay in one piece.
    private fun whole(s: String): Step? {

        Regex(
            "^(?:when i say|if i say|whenever i say)\\s+(.+?)\\s+i mean\\s+(.+)"
        ).find(s)?.let {
            return Step.WordMap(
                it.groupValues[1],
                it.groupValues[2]
            )
        }

        Regex(
            "^(?:when i say|if i say|whenever i say)\\s+(.+?)\\s+(?:then\\s+)?(?:do|run)\\s+(.+)"
        ).find(s)?.let {
            return Step.Teach(
                parts(it.groupValues[1]),
                it.groupValues[2]
            )
        }

        Regex(
            "^(?:that|it|this)\\s+means\\s+(.+)|^i meant\\s+(.+)"
        ).find(s)?.let {
            return Step.Mean(
                it.groupValues[1].ifEmpty {
                    it.groupValues[2]
                }
            )
        }

        Regex(
            "^(?:set\\s+|give\\s+|add\\s+)?(?:another\\s+)?nickname\\s+(.+?)\\s+(?:for|to|of)\\s+(.+)"
        ).find(s)?.let {
            return Step.Nick(
                parts(it.groupValues[1]),
                it.groupValues[2]
            )
        }

        Regex(
            "^(.+?)\\s+is\\s+(?:my\\s+)?nickname\\s+(?:for|of)\\s+(.+)"
        ).find(s)?.let {
            return Step.Nick(
                parts(it.groupValues[1]),
                it.groupValues[2]
            )
        }

        Regex(
            "^(?:call me|my name is|you can call me)\\s+(.+)"
        ).find(s)?.let {
            return Step.SetName(it.groupValues[1])
        }

        Regex(
            "^ignore\\s+(?:the\\s+)?(?:word\\s+)?(.+)"
        ).find(s)?.let {
            return Step.Ignore(it.groupValues[1])
        }

        Regex(
            "^remember\\s+(?:that\\s+)?(.+?)\\s+(?:is|are)\\s+(.+)"
        ).find(s)?.let {
            return Step.Remember(
                it.groupValues[1],
                it.groupValues[2]
            )
        }

        Regex(
            "^remember\\s+(?:to\\s+|that\\s+)?(.+)"
        ).find(s)?.let {
            return Step.Note(it.groupValues[1])
        }

        Regex(
            "^(?:take a |make a |add a |write a |write )?note(?: down)?(?: that)?\\s+(.+)"
        ).find(s)?.let {
            return Step.Note(it.groupValues[1])
        }

        return null
    }

    private fun one(p: String, wa: Boolean): Step {

        if (
            Regex(
                "^(?:again|repeat|do (?:that|it) again|same again|once more)$"
            ).matches(p)
        ) {
            return Step.Again
        }

        if (
            Regex(
                "(?:show|open|edit)\\s+(?:my\\s+|your\\s+)?brain"
            ).containsMatchIn(p)
        ) {
            return Step.OpenBrain
        }

        if (
            Regex(
                "what (?:did|have|do) you (?:learn|know)"
            ).containsMatchIn(p)
        ) {
            return Step.Stats
        }

        if (
            Regex(
                "brief me|briefing|what.?s my day|^good morning"
            ).containsMatchIn(p)
        ) {
            return Step.Brief
        }

        for ((r, reply) in talk) {
            if (r.containsMatchIn(p)) {
                return Step.Reply(reply)
            }
        }

        if (p.contains("joke")) {
            return Step.Reply(jokes.random())
        }

        if (
            Regex("flip a coin|toss a coin").containsMatchIn(p)
        ) {
            return Step.Reply(
                if ((0..1).random() == 0) "Heads" else "Tails"
            )
        }

        if (
            Regex("roll a (?:dice|die)").containsMatchIn(p)
        ) {
            return Step.Reply(
                "You rolled a ${(1..6).random()}"
            )
        }

        // Calculator
        Regex(
            "^(?:calculate|what.?s|what is)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:percent|%)\\s*of\\s*(\\d+(?:\\.\\d+)?)$"
        ).find(p)?.let {
            val a = it.groupValues[1].toDouble()
            val b = it.groupValues[2].toDouble()

            return Step.Reply(
                "${fmt(a)} percent of ${fmt(b)} is ${fmt(a * b / 100)}"
            )
        }

        Regex(
            "^(?:calculate|calc|compute|what.?s|what is|how much is)?\\s*(\\d+(?:\\.\\d+)?)\\s*(plus|minus|times|multiplied by|x|into|divided by|over|\\+|-|\\*|×|/|÷)\\s*(\\d+(?:\\.\\d+)?)$"
        ).find(p)?.let {

            val a = it.groupValues[1].toDouble()
            val b = it.groupValues[3].toDouble()
            val op = it.groupValues[2]

            val plus =
                op == "plus" || op == "+"

            val minus =
                op == "minus" || op == "-"

            val div =
                op in listOf(
                    "divided by",
                    "over",
                    "/",
                    "÷"
                )

            if (div && b == 0.0) {
                return Step.Reply(
                    "I can't divide by zero"
                )
            }

            val r =
                if (plus) {
                    a + b
                } else if (minus) {
                    a - b
                } else if (div) {
                    a / b
                } else {
                    a * b
                }

            val word =
                if (plus) {
                    "plus"
                } else if (minus) {
                    "minus"
                } else if (div) {
                    "divided by"
                } else {
                    "times"
                }

            return Step.Reply(
                "${fmt(a)} $word ${fmt(b)} is ${fmt(r)}"
            )
        }

        // Music
        if (
            Regex(
                "^(?:next|skip)(?: song| track| video)?$"
            ).matches(p)
        ) {
            return Step.Media("next")
        }

        if (
            Regex(
                "^(?:previous|prev|last)(?: song| track)?$"
            ).matches(p)
        ) {
            return Step.Media("prev")
        }

        if (
            Regex(
                "^(?:pause|stop)(?: the)?(?: music| song| video| playing)?$"
            ).matches(p)
        ) {
            return Step.Media("pause")
        }

        if (
            Regex(
                "^(?:resume|continue|play)(?: the)?(?: music| song| video)?$"
            ).matches(p)
        ) {
            return Step.Media("play")
        }

        Regex(
            "^(?:whatsapp\\s+)?(?:(?:make|give|place)\\s+(?:a\\s+)?)?(?:(video|voice)\\s+)?(?:call|ring|dial|phone)\\s+(?:to\\s+)?(?:my\\s+)?(.+?)(?:\\s+(?:on|in|using|via|through)\\s+whatsapp)?$"
        ).find(p)?.let {

            val video =
                it.groupValues[1] == "video"

            return Step.Call(
                it.groupValues[2],
                video,
                wa ||
                    video ||
                    p.contains("whatsapp")
            )
        }

        Regex(
            "^(?:send\\s+)?(?:a\\s+)?(?:sms|text|message|msg)\\s+(?:to\\s+)?(?:my\\s+)?(.+?)\\s+(?:saying|that says|say)\\s+(.+)"
        ).find(p)?.let {
            return Step.Sms(
                it.groupValues[1],
                it.groupValues[2]
            )
        }

        Regex(
            "^(?:open|launch|start|run|load)\\s+(?:the\\s+)?(.+)"
        ).find(p)?.let {
            return Step.OpenApp(it.groupValues[1])
        }

        // Time tools
        Regex(
            "remind me (?:to\\s+)?(.+?)\\s+in\\s+(\\d+)\\s*(second|sec|minute|min|hour|hr)"
        ).find(p)?.let {
            return Step.Remind(
                it.groupValues[1],
                secs(
                    it.groupValues[2].toInt(),
                    it.groupValues[3]
                )
            )
        }

        Regex(
            "remind me in\\s+(\\d+)\\s*(second|sec|minute|min|hour|hr)\\w*\\s+to\\s+(.+)"
        ).find(p)?.let {
            return Step.Remind(
                it.groupValues[3],
                secs(
                    it.groupValues[1].toInt(),
                    it.groupValues[2]
                )
            )
        }

        Regex(
            "(?:timer|countdown).*?(\\d+)\\s*(second|sec|minute|min|hour|hr)"
        ).find(p)?.let {
            return Step.Timer(
                secs(
                    it.groupValues[1].toInt(),
                    it.groupValues[2]
                )
            )
        }

        Regex(
            "(\\d+)\\s*(second|sec|minute|min|hour|hr)\\w*\\s+timer"
        ).find(p)?.let {
            return Step.Timer(
                secs(
                    it.groupValues[1].toInt(),
                    it.groupValues[2]
                )
            )
        }

        Regex(
            "(?:alarm|wake me up)\\s+(?:for\\s+|at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?"
        ).find(p)?.let {

            var h = it.groupValues[1].toInt()

            val m =
                it.groupValues[2].toIntOrNull() ?: 0

            val ap = it.groupValues[3]

            if (ap.startsWith("p") && h < 12) {
                h += 12
            }

            if (ap.startsWith("a") && h == 12) {
                h = 0
            }

            return Step.Alarm(
                h % 24,
                m
            )
        }

        Regex(
            "^(?:schedule|add event|add meeting|create event|add appointment)\\s+(.+?)\\s+(?:on\\s+)?(today|tomorrow)?\\s*at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?$"
        ).find(p)?.let {

            val cal = Calendar.getInstance()

            if (it.groupValues[2] == "tomorrow") {
                cal.add(
                    Calendar.DAY_OF_YEAR,
                    1
                )
            }

            var h =
                it.groupValues[3].toInt()

            val ap = it.groupValues[5]

            if (ap.startsWith("p") && h < 12) {
                h += 12
            }

            if (ap.startsWith("a") && h == 12) {
                h = 0
            }

            cal.set(
                Calendar.HOUR_OF_DAY,
                h % 24
            )

            cal.set(
                Calendar.MINUTE,
                it.groupValues[4].toIntOrNull() ?: 0
            )

            cal.set(
                Calendar.SECOND,
                0
            )

            if (
                it.groupValues[2].isEmpty() &&
                cal.timeInMillis < System.currentTimeMillis()
            ) {
                cal.add(
                    Calendar.DAY_OF_YEAR,
                    1
                )
            }

            return Step.Event(
                it.groupValues[1],
                cal.timeInMillis
            )
        }

        // Phone controls
        val devices =
            "wifi|wi-fi|bluetooth|data|network|airplane(?: mode)?|flight mode|location|gps|nfc|flash\\s?light|torch"

        Regex(
            "turn\\s+(on|off)\\s+(?:the\\s+)?(?:mobile\\s+)?($devices)"
        ).find(p)?.let {
            return Step.Toggle(
                it.groupValues[2],
                it.groupValues[1] == "on"
            )
        }

        Regex(
            "turn\\s+(?:the\\s+)?(?:mobile\\s+)?($devices)\\s+(on|off)"
        ).find(p)?.let {
            return Step.Toggle(
                it.groupValues[1],
                it.groupValues[2] == "on"
            )
        }

        if (
            Regex(
                "\\b(?:volume|louder|quieter)\\b"
            ).containsMatchIn(p)
        ) {
            val up =
                Regex(
                    "\\b(?:up|louder|increase|raise|more|high)\\b"
                ).containsMatchIn(p)

            val down =
                Regex(
                    "\\b(?:down|quieter|decrease|lower|less|low|mute)\\b"
                ).containsMatchIn(p)

            if (up || down) {
                return Step.Volume(up)
            }
        }

        if (
            Regex(
                "take\\s+(?:a\\s+)?(?:photo|picture|pic|selfie)"
            ).containsMatchIn(p)
        ) {
            return Step.Photo
        }

        // Search, maps, info
        Regex(
            "^(?:search|play|watch|find)\\s+(.+?)\\s+on\\s+youtube"
        ).find(p)?.let {
            return Step.Search(
                it.groupValues[1],
                true
            )
        }

        Regex(
            "^(?:play|watch)\\s+(.+)"
        ).find(p)?.let {
            return Step.Search(
                it.groupValues[1],
                true
            )
        }

        Regex(
            "^(?:search|google|look up|find)\\s+(?:for\\s+)?(.+)"
        ).find(p)?.let {
            return Step.Search(
                it.groupValues[1],
                false
            )
        }

        Regex(
            "^(?:navigate to|directions to|take me to|route to|go to)\\s+(.+)"
        ).find(p)?.let {
            return Step.Navigate(
                it.groupValues[1]
            )
        }

        if (
            Regex(
                "am i online|am i offline|do i have internet|internet (?:on|connected|working)|network status"
            ).containsMatchIn(p)
        ) {
            return Step.Info("net")
        }

        if (
            Regex(
                "what.?s the time|what time|time now|current time|the time"
            ).containsMatchIn(p)
        ) {
            return Step.Info("time")
        }

        if (
            Regex(
                "what.?s the date|what day|today.?s date|the date"
            ).containsMatchIn(p)
        ) {
            return Step.Info("date")
        }

        if (p.contains("battery")) {
            return Step.Info("battery")
        }

        // Notes and list
        if (
            Regex(
                "(?:clear|delete|erase)\\s+(?:all\\s+)?(?:my\\s+)?notes"
            ).containsMatchIn(p)
        ) {
            return Step.ClearNotes
        }

        if (
            Regex(
                "(?:read|show|what.?s|what are|list)\\s+(?:my\\s+)?notes"
            ).containsMatchIn(p)
        ) {
            return Step.ReadNotes
        }

        Regex(
            "^(?:remove|delete)\\s+(.+?)\\s+from\\s+(?:my\\s+)?(?:to-?\\s?do\\s+)?list"
        ).find(p)?.let {
            return Step.RemoveTodo(
                it.groupValues[1]
            )
        }

        if (
            Regex(
                "(?:clear|empty|delete)\\s+(?:my\\s+)?(?:to-?\\s?do\\s+)?list"
            ).containsMatchIn(p)
        ) {
            return Step.ClearTodos
        }

        Regex(
            "^(?:add|put)\\s+(.+?)\\s+(?:to|in|on)\\s+(?:my\\s+)?(?:to-?\\s?do\\s+)?list"
        ).find(p)?.let {
            return Step.AddTodo(
                it.groupValues[1]
            )
        }

        if (
            Regex(
                "(?:read|show|what.?s(?:\\s+on)?|list)\\s+(?:my\\s+)?(?:to-?\\s?do\\s+)?list"
            ).containsMatchIn(p)
        ) {
            return Step.ReadTodos
        }

        // Facts and secrets
        Regex(
            "^copy\\s+(my\\s+.+)"
        ).find(p)?.let {
            return Step.Copy(
                it.groupValues[1]
            )
        }

        Regex(
            "^forget\\s+(?:about\\s+)?(.+)"
        ).find(p)?.let {
            return Step.Forget(
                it.groupValues[1]
            )
        }

        Regex(
            "^(?:what.?s|what is|what are|tell me|show me|show|read out|read)\\s+(my\\s+.+)"
        ).find(p)?.let {
            return Step.Recall(
                it.groupValues[1]
            )
        }

        return Step.Unknown(p)
    }
}

class StepRunner(
    private val ctx: Context,
    private val say: (String) -> Unit,
    private val quiet: (String) -> Unit
) {

    private val ui =
        Handler(Looper.getMainLooper())

    private val prefs =
        ctx.getSharedPreferences(
            "sanya",
            Context.MODE_PRIVATE
        )

    private val secretWords =
        Regex(
            "\\b(?:password|passcode|pin|otp|cvv|card|secret|key)\\b"
        )

    private val noSlang =
        Regex(
            "^(?:when i say|if i say|whenever i say|forget|nickname|set nickname|give nickname|add nickname|another nickname|that means|it means|this means|i meant|ignore)"
        )

    private var lastCommand = ""
    private var lastUnknown = ""

    // Most recent general-knowledge exchange. Kept in memory only so a
    // follow-up can be handled conversationally without changing Brain.
    @Volatile private var lastKnowledgeQuestion = ""
    @Volatile private var lastKnowledgeAnswer = ""
    @Volatile private var lastKnowledgeAt = 0L

    // Conversation state: a question Sanya asked and is waiting on.
    //  confirmCmd    "Did you mean X?" / "Shall I do X?"  (waiting for yes/no)
    //  teachPhrase   "What should I do when you say X?"   (waiting for a command)
    private var confirmCmd = ""
    private var confirmPhrase = ""
    private var teachPhrase = ""
    private var confirmSteps: List<Step> = emptyList()   // risky action waiting for "yes"
    private var confirmText = ""                          // what she learns if you say yes
    private var walkItems: List<Routines.Instance> = emptyList()   // going through the remaining activities
    private var walkIdx = 0
    private var correctPending = false                    // "Sorry. What did you say?"
    @Volatile private var lastOriginal = ""               // what she last heard, exactly as heard
    @Volatile private var lastHeardAt = 0L
    private var fixSpoken = ""                            // "I couldn't find X. Did you mean Y?"
    private var fixName = ""
    private var fixStep: Step? = null
    private var pendingAt = 0L

    // Web lookups and local knowledge I/O must never block the UI thread.
    private val knowledgeExecutor =
        Executors.newSingleThreadExecutor()

    private data class Contact(
        val id: Long,
        val name: String,
        val number: String
    )

    init {
        Brain.setup(ctx)

        // Boot the LLM and knowledge base on a background thread so they are
        // ready by the time the user speaks.
        Thread {
            if (LocalLLM.isInstalled(ctx)) {
                LocalLLM.load(ctx)
            }
            if (OfflineKnowledge.isInstalled(ctx)) {
                OfflineKnowledge.open(ctx)
            }
        }.also { it.isDaemon = true }.start()
    }

    // Learned slang first, then normal parsing.
    private fun plan(input: String): List<Step> {

        // "call my mother" -> "call ammu" (people she has learned)
        val raw = Mind.resolveRelations(input)

        val t = Brain.clean(raw)

        val slang = Brain.map("slang")

        val exact =
            if (noSlang.containsMatchIn(t)) {
                null
            } else {
                slang.keys
                    .filter {
                        it.isNotEmpty() &&
                            Regex(
                                "\\b" +
                                    Regex.escape(it) +
                                    "\\b"
                            ).containsMatchIn(t)
                    }
                    .maxByOrNull { it.length }
            }

        // No exact match: tolerate small mishearings ("movy time" -> "movie time").
        val hit =
            exact ?: if (noSlang.containsMatchIn(t)) {
                null
            } else {
                fuzzyHit(t, slang.keys)
            }

        return CommandParser.parse(
            if (hit != null) {
                // A slang command can name a relation too ("call my mother").
                Mind.resolveRelations(slang[hit] ?: raw)
            } else {
                raw
            }
        )
    }

    // Closest learned slang phrase within a small edit distance, or null.
    // Slides a window of the same word count over the spoken sentence, so
    // "movy time please" can still match "movie time".
    private fun fuzzyHit(
        t: String,
        keys: Collection<String>
    ): String? {

        val words = t.split(" ").filter { it.isNotEmpty() }

        var best: String? = null
        var bestDist = Int.MAX_VALUE

        for (k in keys) {

            if (k.length < 4) continue

            val n = k.split(" ").size

            if (words.size < n) continue

            val allowed = if (k.length >= 9) 2 else 1

            for (i in 0..(words.size - n)) {

                val chunk =
                    words.subList(i, i + n).joinToString(" ")

                val d = minOf(
                    lev(chunk, k),
                    lev(
                        chunk.replace(" ", ""),
                        k.replace(" ", "")
                    )
                )

                if (d <= allowed && d < bestDist) {
                    bestDist = d
                    best = k
                }
            }
        }

        return best
    }

    // True if Sanya understands the input: she is waiting for an answer,
    // it is a chat about herself, a follow-up question, a general question,
    // a normal command, or something the offline brain can answer.
    fun understands(input: String): Boolean {

        if (pendingValid()) return true

        // "no, I said preethi" is something she understands too.
        if (correctionFor(input) != null) return true

        val raw = hear(input)

        if (Mind.kind(raw) != null) return true

        if (Routines.parse(raw) != null) return true

        if (Pomodoro.parse(raw) != null) return true

        if (Money.parse(raw) != null) return true

        if (Study.parse(raw) != null) return true

        // Natural-language questions must bypass the broad command parser.
        val effective = followUp(raw) ?: raw

        if (IntentEngine.isGeneralQuestion(effective)) return true

        val p = plan(raw)

        if (p.isNotEmpty() && p.none { it is Step.Unknown }) return true

        return AIBrain.answer(raw) != null
    }

    // What she heard, after her own corrections and her own model have repaired it:
    // the fixes you taught her, then words YOU have said in that place that sound the same.
    private fun hear(input: String): String =
        Learner.repair(ctx, Mind.applyHeard(input))

    // True while Sanya has asked a question and is waiting for the answer.
    // BubbleService keeps the microphone open (no wake word) while this is true.
    fun wantsReply(): Boolean = pendingValid()

    // Returns how many normal command steps will run.
    fun handle(input: String): Int {

        // ---------------------------------------------------------
        // 0. A correction of what she just heard: "no, I said preethi"
        // ---------------------------------------------------------

        val correction = correctionFor(input)
        if (correction != null) return applyCorrection(correction)

        // Fixes she has already learned ("call pretty" -> "call preethi").
        val taught = Mind.applyHeard(input)
        val raw = Learner.repair(ctx, taught)

        if (raw != taught) quiet("Heard '${taught}', understood '${raw}'")

        // ---------------------------------------------------------
        // 1. Answer to a question Sanya just asked
        // ---------------------------------------------------------

        if (pendingValid()) {
            val r = answerPending(raw)
            if (r >= 0) return r
        }

        // ---------------------------------------------------------
        // 2. Conversation about Sanya herself or about the user
        // ---------------------------------------------------------

        val kind = Mind.kind(raw)
        if (kind != null) return converse(kind, raw)

        // ---------------------------------------------------------
        // 2b. Your routines: add, remaining, mark done, show the chart
        // ---------------------------------------------------------

        val routine = Routines.parse(raw)
        if (routine != null) return routineCommand(routine)

        // Remember exactly what she heard, so a correction has something to fix.
        lastOriginal = input
        lastHeardAt = System.currentTimeMillis()

        // ---------------------------------------------------------
        // 2c. Pomodoro, money tracker and study timer. Pomodoro comes first
        //     ("start pomodoro timer" would otherwise start a study timer
        //     called "pomodoro"). All three must come before the
        //     general-question check, or "how much did I spend today"
        //     would go to the web search.
        // ---------------------------------------------------------

        val pomo = Pomodoro.parse(raw)
        if (pomo != null) return pomodoroCommand(pomo)

        val money = Money.parse(raw)
        if (money != null) return moneyCommand(money)

        val study = Study.parse(raw)
        if (study != null) return studyCommand(study)

        // ---------------------------------------------------------
        // 3. Follow-up ("what about japan" after a capital question)
        // ---------------------------------------------------------

        val effective = followUp(raw) ?: raw

        // ---------------------------------------------------------
        // 3b. Explicit LLM tasks — translate, summarise, explain, write, correct
        // ---------------------------------------------------------

        if (LocalLLM.isReady && LocalLLM.looksLikeLLMTask(effective)) {
            Mind.record(ctx, Brain.clean(effective), true)
            handleLLMTask(effective)
            return 1
        }

        // ---------------------------------------------------------
        // 4. General knowledge question
        // ---------------------------------------------------------
        // MUST be checked before CommandParser.parse(). The command parser
        // contains intentionally broad natural-language/teaching patterns.

        if (IntentEngine.isGeneralQuestion(effective)) {
            Mind.record(ctx, Brain.clean(effective), true)
            answerGeneralQuestion(effective)
            return 1
        }

        val steps = plan(raw)

        // ---------------------------------------------------------
        // 5. Normal Sanya command
        // ---------------------------------------------------------

        if (steps.isNotEmpty() && steps.none { it is Step.Unknown }) {

            if (
                steps.none {
                    it is Step.Again ||
                        it is Step.Mean ||
                        it is Step.Teach
                }
            ) {
                lastCommand = raw
            }

            // Calls and bulk deletes need a "yes" first.
            val ask = riskyAsk(steps)

            if (ask != null) {
                clearPending()
                confirmSteps = steps
                confirmText = confirmedText(steps, raw)
                pendingAt = System.currentTimeMillis()
                say(ask)
                return 1
            }

            Mind.record(ctx, Brain.clean(raw), true)
            learnFrom(raw, 1)
            return run(steps)
        }

        // ---------------------------------------------------------
        // 6. Offline AI Brain
        // ---------------------------------------------------------

        val aiAnswer = AIBrain.answer(raw)

        if (aiAnswer != null) {
            Mind.record(ctx, Brain.clean(raw), true)
            say(aiAnswer)
            return 1
        }

        // ---------------------------------------------------------
        // 7. Not understood: use what she has learned, or ask and learn
        // ---------------------------------------------------------

        return learnOrAsk(raw)
    }

    // ---------------------------------------------------------------
    // Her own mind: conversation, learning, curiosity
    // ---------------------------------------------------------------

    private fun run(steps: List<Step>): Int {
        steps.forEachIndexed { i, s ->
            ui.postDelayed({ exec(s) }, i * 2500L)
        }
        return steps.size
    }

    // Runs a stored command directly. Never re-applies slang, so a bad
    // rule can never loop.
    private fun perform(command: String): Int {

        val cmd = Mind.resolveRelations(command)

        val routine = Routines.parse(cmd)
        if (routine != null) return routineCommand(routine)

        val pomo = Pomodoro.parse(cmd)
        if (pomo != null) return pomodoroCommand(pomo)

        val money = Money.parse(cmd)
        if (money != null) return moneyCommand(money)

        val study = Study.parse(cmd)
        if (study != null) return studyCommand(study)

        if (IntentEngine.isGeneralQuestion(cmd)) {
            answerGeneralQuestion(cmd)
            return 1
        }

        val steps = CommandParser.parse(cmd)

        if (steps.isNotEmpty() && steps.none { it is Step.Unknown }) {
            return run(steps)
        }

        val ai = AIBrain.answer(cmd)

        if (ai != null) {
            say(ai)
            return 1
        }

        say("I can't do that one yet.")
        return 0
    }

    private fun pendingValid(): Boolean =
        (
            confirmCmd.isNotEmpty() ||
                teachPhrase.isNotEmpty() ||
                confirmSteps.isNotEmpty() ||
                fixStep != null ||
                correctPending ||
                walkItems.isNotEmpty()
            ) &&
            System.currentTimeMillis() - pendingAt < 20_000L

    private fun clearPending() {
        confirmCmd = ""
        confirmPhrase = ""
        teachPhrase = ""
        confirmSteps = emptyList()
        fixSpoken = ""
        fixName = ""
        fixStep = null
        correctPending = false
        confirmText = ""
        walkItems = emptyList()
        walkIdx = 0
    }

    // Proactive Sanya: say something first and, if you say yes, run [cmd].
    // Called by BubbleService (Proactive.next).
    fun offer(text: String, cmd: String) {
        clearPending()
        confirmCmd = cmd
        pendingAt = System.currentTimeMillis()
        say(text)
    }

    // Actions that deserve a "yes" first. Returns the question, or null if safe.
    // Messages are not listed: they only open a draft and you tap send.
    private fun riskyAsk(steps: List<Step>): String? {

        if (Brain.setting("confirm actions", "on") == "off") return null

        for (s in steps) {
            when (s) {
                is Step.Call -> {
                    // Say the name she matched, not the word she heard ("pretty" -> Preethi Kumar).
                    val who = matchContact(s.contact)?.first?.name ?: return null
                    return if (s.whatsapp) {
                        "WhatsApp ${if (s.video) "video " else ""}call $who? Say yes or no."
                    } else {
                        "Call $who? Say yes or no."
                    }
                }

                Step.ClearTodos ->
                    return "Clear your whole to-do list? Say yes or no."

                Step.ClearNotes ->
                    return "Delete all your notes? Say yes or no."

                else -> {}
            }
        }

        return null
    }

    private val yesRx = Regex(
        """^(?:yes|yeah|yep|yup|ya|ok|okay|sure|correct|right|do it|please do|go ahead|confirm)\b"""
    )

    private val noRx = Regex(
        """^(?:no|nope|nah|never mind|nevermind|cancel|forget it|skip|stop|dont|do not)\b"""
    )

    // Returns the step count if the sentence answered my question,
    // or -1 if it was something else (then it is handled as a fresh command).
    private fun answerPending(raw: String): Int {

        val t = Brain.clean(raw)

        // ---- Going through the remaining activities one by one ----
        if (walkItems.isNotEmpty()) {

            val items = walkItems
            val idx = walkIdx

            if (walkStopRx.containsMatchIn(t)) {
                clearPending()
                say("Okay. " + Routines.todayLine(ctx))
                return 1
            }

            val finished = walkDoneRx.containsMatchIn(t)
            val skipped = walkSkipRx.containsMatchIn(t)

            if (!finished && !skipped) {
                // Something else: handle it as a new command.
                clearPending()
                return -1
            }

            if (finished) Routines.setDone(ctx, items[idx], true)

            val lead = if (finished) "Marked done. " else "Okay. "
            val next = idx + 1

            if (next >= items.size) {
                clearPending()
                say(lead + "That's all. " + Routines.todayLine(ctx))
                return 1
            }

            walkIdx = next
            pendingAt = System.currentTimeMillis()
            say(lead + "Next: " + Routines.spoken(items[next]) + ". Done, not yet, or stop?")
            return 1
        }

        // ---- "Sorry about that. What did you say?" ----
        if (correctPending) {
            clearPending()
            if (noRx.containsMatchIn(t)) {
                say("Okay.")
                return 1
            }
            return applyCorrection(Mind.Correction(raw))
        }

        // ---- "I couldn't find X. Did you mean Y?" ----
        val fix = fixStep

        if (fix != null) {

            val spoken = fixSpoken
            val name = fixName

            if (yesRx.containsMatchIn(t)) {
                clearPending()
                Brain.link("nick", spoken, name)
                quiet("Learned: $spoken = $name")
                ui.post { exec(fix) }
                return 1
            }

            if (noRx.containsMatchIn(t)) {
                clearPending()
                say("Okay. Try saying the name again, slowly.")
                return 1
            }

            clearPending()
            return -1
        }

        // ---- "Call Ravi? Say yes or no." ----
        if (confirmSteps.isNotEmpty()) {

            val steps = confirmSteps

            if (yesRx.containsMatchIn(t)) {
                val learned = confirmText
                clearPending()
                // You said yes to exactly this: it is a safe thing to learn from.
                learnFrom(learned, 2)
                return run(steps)
            }

            if (noRx.containsMatchIn(t)) {
                clearPending()
                say("Okay, cancelled.")
                return 1
            }

            clearPending()
            return -1
        }

        // ---- "Did you mean X?" / "Shall I do X?" ----
        if (confirmCmd.isNotEmpty()) {

            val cmd = confirmCmd
            val phrase = confirmPhrase

            if (yesRx.containsMatchIn(t)) {
                clearPending()

                if (phrase.isNotEmpty()) {
                    Brain.link("slang", phrase, cmd)
                    quiet("Learned: $phrase")
                }

                Mind.record(ctx, if (phrase.isNotEmpty()) phrase else Brain.clean(cmd), true)
                return maxOf(perform(cmd), 1)
            }

            if (noRx.containsMatchIn(t)) {
                clearPending()

                if (phrase.isNotEmpty()) {
                    teachPhrase = phrase
                    pendingAt = System.currentTimeMillis()
                    say("Okay. What should I do when you say $phrase?")
                } else {
                    say("Okay.")
                }
                return 1
            }

            clearPending()
            return -1
        }

        // ---- "What should I do when you say X?" ----
        if (teachPhrase.isNotEmpty()) {

            val phrase = teachPhrase

            if (noRx.containsMatchIn(t)) {
                clearPending()
                say("Okay, I'll forget it.")
                return 1
            }

            val steps = CommandParser.parse(raw)

            clearPending()

            if (steps.isNotEmpty() && steps.none { it is Step.Unknown }) {
                Brain.link("slang", phrase, raw)
                quiet("Learned: $phrase = $raw")
                say("Got it. When you say $phrase, I'll $raw.")
                ui.postDelayed({ perform(raw) }, 2500L)
                return 1
            }

            say("I don't know how to do that either, so I'll skip it for now.")
            return 1
        }

        return -1
    }

    // ---------------------------------------------------------------
    // Routines (Routines.kt)
    // ---------------------------------------------------------------

    private val walkDoneRx = Regex(
        """^(?:done|finished|completed|complete|yes|yeah|yep|ya|ok|okay|did it|i did|i have|sure)\b"""
    )

    private val walkSkipRx = Regex(
        """^(?:not yet|no|nope|nah|not done|later|skip|next|pending|unfinished|not finished|not)\b"""
    )

    private val walkStopRx = Regex(
        """^(?:stop|cancel|enough|thats all|exit|never mind|nevermind)\b"""
    )

    private fun routineCommand(c: Routines.Cmd): Int {

        when (c) {

            is Routines.Cmd.Add -> say(Routines.add(c.name, c.spec))

            is Routines.Cmd.Remove -> say(Routines.remove(c.name))

            is Routines.Cmd.Mark -> say(Routines.mark(ctx, c.name, c.done))

            is Routines.Cmd.Show -> {
                val opened = openScreen(
                    Intent(ctx, RoutineActivity::class.java).putExtra("period", c.period.word)
                )
                if (opened) {
                    say("Here is your ${c.period.word} routine.")
                } else {
                    say(
                        "I couldn't open the routines screen, so here it is in words. " +
                            Routines.todayLine(ctx) +
                            " The app's manifest may be missing the routines screen. Check the start screen for a setup message."
                    )
                }
            }

            is Routines.Cmd.Describe -> say(Routines.describe(c.period))

            is Routines.Cmd.CheckAt -> say(Routines.setCheck(c.minutes))

            is Routines.Cmd.Say -> say(c.text)

            Routines.Cmd.Remaining -> startWalk()

            Routines.Cmd.TodayStatus -> say(Routines.todayLine(ctx))
        }

        // A routine you just changed by voice should show on the home-screen widget.
        SanyaWidget.refresh(ctx)

        return 1
    }

    // ---------------------------------------------------------------
    // Pomodoro (Pomodoro.kt), money tracker (Money.kt), study timer (Study.kt)
    // ---------------------------------------------------------------

    private fun pomodoroCommand(c: Pomodoro.Cmd): Int {

        say(Pomodoro.run(ctx, c))

        return 1
    }

    private fun moneyCommand(c: Money.Cmd): Int {

        when (c) {

            is Money.Cmd.Add -> say(Money.add(ctx, c.spent, c.paise, c.note))

            is Money.Cmd.Total -> say(Money.total(ctx, c.what, c.category, c.span))

            is Money.Cmd.Recent -> say(Money.recent(ctx, c.span))

            is Money.Cmd.Budget -> {
                val p = c.paise
                say(if (p == null) Money.budgetStatus(ctx) else Money.setBudget(p))
            }

            is Money.Cmd.Show ->
                showScreen(
                    MoneyActivity::class.java,
                    "money report",
                    Money.total(ctx, "net", null, "this month")
                )

            Money.Cmd.Undo -> say(Money.undoLast(ctx))
        }

        return 1
    }

    private fun studyCommand(c: Study.Cmd): Int {

        when (c) {

            is Study.Cmd.Log ->
                say(Study.log(ctx, c.task, c.minutes, c.endMs ?: System.currentTimeMillis()))

            is Study.Cmd.Start -> say(Study.start(ctx, c.task))

            is Study.Cmd.Total -> say(Study.total(ctx, c.task, c.span))

            is Study.Cmd.Say -> say(c.text)

            is Study.Cmd.Show ->
                showScreen(TimeActivity::class.java, "study time", Study.status(ctx))

            Study.Cmd.Stop -> say(Study.stop(ctx))

            Study.Cmd.Status -> say(Study.status(ctx))

            Study.Cmd.Undo -> say(Study.undoLast(ctx))
        }

        // A timer or a logged session changes what the home-screen widget shows.
        SanyaWidget.refresh(ctx)

        return 1
    }

    // Opens one of her screens, or reads the answer aloud if the manifest is missing it.
    private fun showScreen(cls: Class<*>, label: String, fallback: String) {

        if (openScreen(Intent(ctx, cls))) {
            say("Here is your $label.")
        } else {
            say(
                "I couldn't open the $label screen, so here it is in words. $fallback " +
                    "The app's manifest may be missing it."
            )
        }
    }

    // "What are the remaining activities": she reads them one at a time.
    private fun startWalk() {

        val remaining = Routines.today(ctx).remaining

        if (remaining.isEmpty()) {
            say(Routines.todayLine(ctx))
            return
        }

        clearPending()
        walkItems = remaining
        walkIdx = 0
        pendingAt = System.currentTimeMillis()

        say(
            "You have ${remaining.size} remaining. First: " +
                Routines.spoken(remaining[0]) + ". Done, not yet, or stop?"
        )
    }

    // ---------------------------------------------------------------
    // Learning from corrections
    // ---------------------------------------------------------------

    // Is this a correction of what she heard in the last two minutes?
    private fun correctionFor(input: String): Mind.Correction? {
        if (lastOriginal.isEmpty()) return null
        if (System.currentTimeMillis() - lastHeardAt > 120_000L) return null
        return Mind.parseCorrection(input, lastOriginal)
    }

    private fun looksLikeCommand(text: String): Boolean {
        if (IntentEngine.isGeneralQuestion(text)) return true
        val steps = CommandParser.parse(text)
        return steps.isNotEmpty() && steps.none { it is Step.Unknown }
    }

    // She misheard. Work out which words, remember the fix, and do what you meant.
    private fun applyCorrection(c: Mind.Correction): Int {

        // "wrong" on its own: ask what the right words were.
        if (c.said.isEmpty()) {
            clearPending()
            correctPending = true
            pendingAt = System.currentTimeMillis()
            say("Sorry about that. What did you say?")
            return 1
        }

        val fix = Mind.diffFix(lastOriginal, c) { looksLikeCommand(it) }

        clearPending()

        if (fix == null) {
            say("I heard it that way too. Try saying it a little slower.")
            return 1
        }

        // That request counts as one she got wrong (it shows in her mood).
        Mind.record(ctx, Brain.clean(lastOriginal), false)

        // The corrected sentence is what you really said: the strongest thing she can learn.
        learnFrom(fix.fixedText, 3)

        val contact = matchContact(fix.intendedSeg)
        val clash = matchContact(fix.heardSeg)?.second ?: 0

        if (contact != null && contact.second >= 60) {

            // A name: save it as an alias, which works with every verb (call, message, ...).
            if (clash >= 90) {
                // She heard another real contact. Remapping it would break calls to that person.
                quiet("Not saved: ${fix.heardSeg} is also one of your contacts")
            } else {
                Brain.link("nick", fix.heardSeg, contact.first.name)
                quiet("Learned: ${fix.heardSeg} = ${contact.first.name}")
            }

        } else {

            // Anything else: remember it with the word before it, so it only fires in the same place.
            Brain.put("heard", fix.key, fix.value)
            quiet("Learned: ${fix.key} = ${fix.value}")
        }

        say("Sorry about that. I'll remember it.")

        // Now do what you actually meant.
        return maxOf(handle(fix.fixedText), 1)
    }

    // ---------------------------------------------------------------
    // Her own model (Learner.kt): learn from what she understood
    // ---------------------------------------------------------------

    // weight: 1 = understood, 2 = you said yes, 3 = you corrected her.
    private fun learnFrom(text: String, weight: Int) {

        if (text.isBlank()) return

        Learner.observe(ctx, text, weight)

        // Only commands she can run directly go into her memory of your commands,
        // never a slang phrase that needs the Brain file to be understood.
        val steps = CommandParser.parse(text)

        if (steps.isNotEmpty() && steps.none { it is Step.Unknown }) {
            Learner.observeCommand(ctx, text)
        }
    }

    // For a call: the words with the contact she matched, not the word she heard.
    private fun confirmedText(steps: List<Step>, raw: String): String {

        for (s in steps) {
            if (s is Step.Call) {
                val who = matchContact(s.contact)?.first?.name ?: return raw
                return (if (s.whatsapp) "whatsapp call " else "call ") + who
            }
        }

        return raw
    }

    // Not understood. If it is close to something she was taught, act on it
    // (or check with you). Otherwise be curious: ask what it means.
    private fun learnOrAsk(raw: String): Int {

        val t = Brain.clean(raw)
        lastUnknown = t

        val slang = Brain.map("slang")
        val best = Mind.best(t, slang.keys)
        val cmd = if (best != null) slang[best.first] ?: "" else ""

        if (best != null && cmd.isNotEmpty()) {

            // Very close: she is sure, so she learns this wording too.
            if (best.second >= 0.80) {
                Brain.link("slang", t, cmd)
                quiet("Learned: $t = ${best.first}")
                Mind.record(ctx, t, true)
                return maxOf(perform(cmd), 1)
            }

            // Somewhat close: check first.
            if (best.second >= 0.45) {
                confirmPhrase = t
                confirmCmd = cmd
                teachPhrase = ""
                pendingAt = System.currentTimeMillis()
                say("Did you mean ${best.first}?")
                return 1
            }
        }

        // Not taught, but close to a command you really run? Check first.
        val near = Learner.nearestCommand(ctx, t)

        if (near != null && near.second >= 0.55) {
            confirmPhrase = t
            confirmCmd = near.first
            teachPhrase = ""
            pendingAt = System.currentTimeMillis()
            say("Did you mean ${near.first}?")
            return 1
        }

        // Nothing close: she doesn't know yet, so she asks.
        Mind.record(ctx, t, false)

        teachPhrase = t
        confirmCmd = ""
        confirmPhrase = ""
        pendingAt = System.currentTimeMillis()

        say("I don't know \"$raw\" yet. What should I do when you say that?")
        return 1
    }

    private fun converse(kind: String, raw: String): Int {

        if (kind == "health_check") {
            say(Mind.reply(ctx, kind, raw, name()))
            openScreen(Intent(ctx, HealthActivity::class.java))
            return 1
        }

        if (kind == "suggest") {
            val h = Mind.habit(ctx)
            if (h != null) {
                confirmCmd = h
                confirmPhrase = ""
                teachPhrase = ""
                pendingAt = System.currentTimeMillis()
                say("Around this time you usually say $h. Shall I do that?")
                return 1
            }
        }

        say(Mind.reply(ctx, kind, raw, name()))
        return 1
    }

    // "what about japan" right after "what is the capital of india"
    // becomes "what is the capital of japan".
    private fun followUp(raw: String): String? {

        if (lastKnowledgeQuestion.isEmpty()) return null
        if (System.currentTimeMillis() - lastKnowledgeAt > 5 * 60_000L) return null

        val t = raw.lowercase()
            .replace(Regex("""[^a-z0-9 ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val subject = Regex("""^(?:and\s+)?(?:what|how)\s+about\s+(.+)$""")
            .find(t)?.groupValues?.get(1)?.trim()
            ?: return null

        if (subject.isEmpty()) return null

        val last = lastKnowledgeQuestion

        val m = Regex("""^(.*\b(?:of|in|for|at)\s+)(.+)$""").find(last)
        if (m != null) return m.groupValues[1] + subject

        if (last.startsWith("what is ")) return "what is $subject"
        if (last.startsWith("who is ")) return "who is $subject"

        return null
    }

    // New pipeline:
    //   taught fact -> KnowledgeStore cache -> offline knowledge DB
    //   -> local LLM (with DB context, or alone) -> AIBrain built-ins -> WebSearch
    private fun answerGeneralQuestion(raw: String) {

        // 1. Something you personally taught her — always wins.
        val taught = Mind.taughtAnswer(raw)
        if (taught != null) {
            rememberKnowledge(IntentEngine.normalizeQuestion(raw), taught)
            say(taught)
            return
        }

        val question = IntentEngine.normalizeQuestion(raw)
        if (question.isEmpty()) { safeSay("I didn't catch the question"); return }

        knowledgeExecutor.execute {
            try {

                // 2. KnowledgeStore: cached answer from a previous web search.
                val local = KnowledgeStore.find(ctx, question)
                if (local != null) {
                    val natural = SanyaResponseEngine.answer(question, local.answer)
                    if (natural != null) {
                        rememberKnowledge(question, natural)
                        safeSay(natural)
                        return@execute
                    }
                }

                // 3. Offline knowledge database (no internet needed).
                if (OfflineKnowledge.isOpen) {
                    val direct = OfflineKnowledge.directAnswer(question)
                    if (direct != null) {
                        val natural = SanyaResponseEngine.answer(question, direct)
                        if (natural != null) {
                            rememberKnowledge(question, natural)
                            safeSay(natural)
                            return@execute
                        }
                    }
                }

                // 4. Local LLM with offline-knowledge context (no internet).
                if (LocalLLM.isReady) {
                    val context = if (OfflineKnowledge.isOpen)
                        OfflineKnowledge.findContext(question) else null

                    val llmAnswer = if (context != null) {
                        // Give the LLM the Wikipedia paragraph as background.
                        LocalLLM.complete(
                            system = "You are Sanya, a concise voice assistant. " +
                                "Use the background knowledge below to answer in 1-2 short sentences. " +
                                "No markdown. If the background doesn't help, answer from your own knowledge.\n\n" +
                                context,
                            user = question,
                            temp = 0.3f
                        )
                    } else {
                        LocalLLM.answerQuestion(question)
                    }

                    if (!llmAnswer.isNullOrBlank()) {
                        val natural = SanyaResponseEngine.answer(question, llmAnswer)
                            ?: llmAnswer  // LLM output is already natural — use it directly
                        KnowledgeStore.save(ctx, question, natural)
                        rememberKnowledge(question, natural)
                        safeSay(natural)
                        return@execute
                    }
                }

                // 5. AIBrain built-ins (offline).
                val builtIn = AIBrain.answer(raw)
                if (builtIn != null) {
                    val natural = SanyaResponseEngine.answer(question, builtIn) ?: builtIn
                    KnowledgeStore.save(ctx, question, natural)
                    rememberKnowledge(question, natural)
                    safeSay(natural)
                    return@execute
                }

                // 6. WebSearch — needs internet.
                if (!online()) {
                    safeSay("I don't know that yet, and I can't search without internet right now.")
                    return@execute
                }

                val webAnswer = WebSearch.answer(question)
                if (webAnswer.isNullOrBlank()) {
                    safeSay("I couldn't find a reliable answer right now.")
                    return@execute
                }

                val natural = SanyaResponseEngine.answer(question, webAnswer)
                if (natural.isNullOrBlank()) {
                    safeSay("I couldn't find a reliable answer right now.")
                    return@execute
                }

                KnowledgeStore.save(ctx, question, natural)
                rememberKnowledge(question, natural)
                safeSay(natural)

            } catch (e: Throwable) {
                ErrorLog.add(ctx, "Answering a question", e)
                safeSay("I couldn't answer that right now.")
            }
        }
    }

    // Explicit LLM tasks — translate, summarise, explain, write, correct
    private fun handleLLMTask(raw: String) {
        knowledgeExecutor.execute {
            try {
                val t = raw.lowercase().trim()

                val result: String? = when {

                    // "translate X to Tamil / Hindi / French …"
                    Regex("""translate (.+) to ([a-z ]+)""").containsMatchIn(t) -> {
                        val m = Regex("""translate (.+) to ([a-z ]+)""").find(t)!!
                        LocalLLM.translate(m.groupValues[1].trim(), m.groupValues[2].trim())
                    }

                    // "translate this / that / it" — use last LLM answer as source
                    t.contains("translate this") || t.contains("translate that") -> {
                        val src = lastKnowledgeAnswer.ifBlank { "nothing to translate" }
                        val lang = Regex("""to ([a-z]+)$""").find(t)?.groupValues?.get(1) ?: "Tamil"
                        LocalLLM.translate(src, lang)
                    }

                    // "summarise / summarize"
                    t.contains("summar") -> {
                        val src = Regex("""summar(?:ise|ize) (.+)""").find(t)
                            ?.groupValues?.get(1)
                            ?: lastKnowledgeAnswer
                        LocalLLM.summarise(src.ifBlank { raw })
                    }

                    // "explain X in simple words"
                    t.contains("explain") -> {
                        val concept = t.removePrefix("explain").removePrefix("what is").trim()
                        LocalLLM.explain(concept.ifBlank { raw })
                    }

                    // "correct this / fix my grammar"
                    t.contains("correct") || t.contains("fix my grammar") || t.contains("fix this") -> {
                        val src = Regex("""(?:correct|fix)\s+(?:this|that|my grammar[:]?\s*)(.*)""")
                            .find(t)?.groupValues?.get(1)?.trim()
                            ?: lastKnowledgeAnswer
                        LocalLLM.correct(src.ifBlank { raw })
                    }

                    // "write a poem / story / joke / essay …"
                    t.contains("write") || t.contains("tell me a story") || t.contains("poem") -> {
                        LocalLLM.create(raw)
                    }

                    // "difference between X and Y"
                    t.contains("difference between") -> LocalLLM.explain(raw)

                    // Everything else: general LLM question
                    else -> LocalLLM.ask(raw)
                }

                if (!result.isNullOrBlank()) {
                    rememberKnowledge(raw, result)
                    safeSay(result)
                } else {
                    safeSay("I couldn't work that out right now.")
                }
            } catch (e: Throwable) {
                ErrorLog.add(ctx, "LLM task", e)
                safeSay("I couldn't do that right now.")
            }
        }
    }

    private fun rememberKnowledge(
        question: String,
        answer: String
    ) {
        lastKnowledgeQuestion = question
        lastKnowledgeAnswer = answer
        lastKnowledgeAt = System.currentTimeMillis()
    }

    private fun safeSay(text: String) {
        ui.post {
            runCatching {
                say(text)
            }
        }
    }

    private fun name() =
        Brain.setting(
            "name",
            ""
        ).let {
            if (it.isEmpty()) {
                ""
            } else {
                " " +
                    it.replaceFirstChar {
                        c -> c.uppercase()
                    }
            }
        }

    private fun now(f: String) =
        SimpleDateFormat(
            f,
            Locale.getDefault()
        ).format(Date())

    private fun fk(x: String) =
        Brain.clean(x)
            .removePrefix("my ")
            .removePrefix("the ")

    private fun online(): Boolean {

        val cm =
            ctx.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        val caps =
            cm.getNetworkCapabilities(
                cm.activeNetwork
            ) ?: return false

        return caps.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        )
    }

    private fun exec(s: Step) {

        when (s) {

            is Step.OpenApp ->
                openApp(s.name)

            is Step.Call ->
                call(s)

            is Step.Sms -> {

                val c =
                    findContact(s.contact)
                        ?: return notFound(s.contact, s)

                go(
                    Intent(
                        Intent.ACTION_SENDTO,
                        Uri.parse(
                            "smsto:" +
                                Uri.encode(c.number)
                        )
                    ).putExtra(
                        "sms_body",
                        s.text
                    )
                )

                say(
                    "Message ready for ${c.name}"
                )
            }

            is Step.Timer -> {

                go(
                    Intent(
                        AlarmClock.ACTION_SET_TIMER
                    )
                        .putExtra(
                            AlarmClock.EXTRA_LENGTH,
                            s.seconds
                        )
                        .putExtra(
                            AlarmClock.EXTRA_SKIP_UI,
                            true
                        )
                )

                say("Timer set")
            }

            is Step.Alarm -> {

                go(
                    Intent(
                        AlarmClock.ACTION_SET_ALARM
                    )
                        .putExtra(
                            AlarmClock.EXTRA_HOUR,
                            s.hour
                        )
                        .putExtra(
                            AlarmClock.EXTRA_MINUTES,
                            s.min
                        )
                        .putExtra(
                            AlarmClock.EXTRA_SKIP_UI,
                            true
                        )
                )

                say(
                    "Alarm set for ${s.hour}:${"%02d".format(s.min)}"
                )
            }

            is Step.Remind -> {

                go(
                    Intent(
                        AlarmClock.ACTION_SET_TIMER
                    )
                        .putExtra(
                            AlarmClock.EXTRA_LENGTH,
                            s.seconds
                        )
                        .putExtra(
                            AlarmClock.EXTRA_MESSAGE,
                            s.text
                        )
                        .putExtra(
                            AlarmClock.EXTRA_SKIP_UI,
                            true
                        )
                )

                say(
                    "Okay, I'll remind you"
                )
            }

            is Step.Event -> {

                go(
                    Intent(
                        Intent.ACTION_INSERT,
                        CalendarContract.Events.CONTENT_URI
                    )
                        .putExtra(
                            CalendarContract.Events.TITLE,
                            s.title
                        )
                        .putExtra(
                            CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                            s.start
                        )
                        .putExtra(
                            CalendarContract.EXTRA_EVENT_END_TIME,
                            s.start + 3600000L
                        )
                )

                say(
                    "Event ready. Tap save."
                )
            }

            is Step.Toggle ->
                toggle(s)

            is Step.Volume -> {

                val am =
                    ctx.getSystemService(
                        Context.AUDIO_SERVICE
                    ) as AudioManager

                repeat(3) {

                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        if (s.up) {
                            AudioManager.ADJUST_RAISE
                        } else {
                            AudioManager.ADJUST_LOWER
                        },
                        AudioManager.FLAG_SHOW_UI
                    )
                }

                say(
                    "Volume ${if (s.up) "up" else "down"}"
                )
            }

            is Step.Media -> {

                val code =
                    when (s.key) {
                        "next" ->
                            KeyEvent.KEYCODE_MEDIA_NEXT

                        "prev" ->
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS

                        "pause" ->
                            KeyEvent.KEYCODE_MEDIA_PAUSE

                        else ->
                            KeyEvent.KEYCODE_MEDIA_PLAY
                    }

                val am =
                    ctx.getSystemService(
                        Context.AUDIO_SERVICE
                    ) as AudioManager

                am.dispatchMediaKeyEvent(
                    KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        code
                    )
                )

                am.dispatchMediaKeyEvent(
                    KeyEvent(
                        KeyEvent.ACTION_UP,
                        code
                    )
                )

                quiet(
                    "Music: ${s.key}"
                )
            }

            Step.Photo ->
                go(
                    Intent(
                        MediaStore.ACTION_IMAGE_CAPTURE
                    )
                )

            is Step.Search -> {

                if (!online()) {
                    return say(
                        "You're offline, so I can't search"
                    )
                }

                val base =
                    if (s.youtube) {
                        "https://www.youtube.com/results?search_query="
                    } else {
                        "https://www.google.com/search?q="
                    }

                go(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(
                            base +
                                Uri.encode(s.q)
                        )
                    )
                )

                say(
                    "Searching ${s.q}"
                )
            }

            is Step.Navigate -> {

                go(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(
                            "google.navigation:q=" +
                                Uri.encode(s.place)
                        )
                    )
                )

                say(
                    "Navigating to ${s.place}"
                )
            }

            is Step.Info -> {

                say(
                    when (s.kind) {

                        "time" ->
                            "It's " +
                                now("h:mm a")

                        "date" ->
                            "Today is " +
                                now("EEEE, d MMMM")

                        "net" ->
                            if (online()) {
                                "You're online"
                            } else {
                                "You're offline. I still work for calls, apps, timers and notes."
                            }

                        else -> {

                            val bm =
                                ctx.getSystemService(
                                    Context.BATTERY_SERVICE
                                ) as BatteryManager

                            "Battery is ${
                                bm.getIntProperty(
                                    BatteryManager.BATTERY_PROPERTY_CAPACITY
                                )
                            } percent"
                        }
                    }
                )
            }

            is Step.AddTodo -> {

                Brain.add(
                    "todo",
                    s.text
                )

                say(
                    "Added ${s.text}"
                )
            }

            is Step.RemoveTodo -> {

                Brain.drop(
                    "todo",
                    s.text
                )

                say(
                    "Removed ${s.text}"
                )
            }

            Step.ClearTodos -> {

                Brain.clear("todo")

                say(
                    "List cleared"
                )
            }

            Step.ReadTodos -> {

                say(
                    Brain.items("todo").let {
                        if (it.isEmpty()) {
                            "Your list is empty"
                        } else {
                            it.joinToString(", ")
                        }
                    }
                )
            }

            is Step.Note -> {

                Brain.add(
                    "note",
                    s.text
                )

                say("Noted")
            }

            Step.ClearNotes -> {

                Brain.clear("note")

                say(
                    "Notes cleared"
                )
            }

            Step.ReadNotes -> {

                say(
                    Brain.items("note").let { l ->

                        if (l.isEmpty()) {
                            "You have no notes"
                        } else {
                            "You have ${l.size} notes. " +
                                l.mapIndexed { i, n ->
                                    "${i + 1}, $n"
                                }.joinToString(". ")
                        }
                    }
                )
            }

            is Step.Nick -> {

                val real =
                    Brain.map("nick")[
                        Brain.clean(s.real)
                    ] ?: s.real

                s.nicks.forEach {
                    Brain.link(
                        "nick",
                        it,
                        real
                    )
                }

                say(
                    "Okay, ${s.nicks.joinToString(" and ")} now means $real"
                )
            }

            is Step.Teach -> {

                s.phrases.forEach {
                    Brain.link(
                        "slang",
                        it,
                        s.cmd
                    )
                }

                say(
                    "Got it. When you say ${s.phrases.first()}, I'll ${s.cmd}"
                )
            }

            is Step.WordMap -> {

                Brain.link(
                    "word",
                    s.word,
                    s.meaning
                )

                say(
                    "Okay, ${s.word} means ${s.meaning}"
                )
            }

            is Step.Ignore -> {

                Brain.add(
                    "filler",
                    s.word
                )

                say(
                    "I'll ignore the word ${s.word}"
                )
            }

            is Step.Mean -> {

                val phrase =
                    lastUnknown

                if (phrase.isEmpty()) {
                    return say(
                        "I have nothing to learn from yet"
                    )
                }

                Brain.link(
                    "slang",
                    phrase,
                    s.cmd
                )

                lastUnknown = ""

                say(
                    "Got it. Next time $phrase will ${s.cmd}"
                )

                val c =
                    s.cmd

                ui.postDelayed(
                    {
                        handle(c)
                    },
                    2000
                )
            }

            is Step.Remember -> {

                val k =
                    fk(s.key)

                if (
                    secretWords.containsMatchIn(k)
                ) {

                    prefs.edit()
                        .putString(
                            "secret:$k",
                            Vault.enc(s.value)
                        )
                        .apply()

                    say(
                        "Saved securely. I won't say it aloud."
                    )

                } else {

                    Brain.link(
                        "fact",
                        k,
                        s.value
                    )

                    say(
                        "Okay, I'll remember that"
                    )
                }
            }

            is Step.Recall -> {

                val k =
                    fk(s.key)

                if (k == "name") {
                    return say(
                        "Your name is ${
                            Brain.setting(
                                "name",
                                "not set yet"
                            )
                        }"
                    )
                }

                Brain.map("fact")[k]?.let {
                    return say(
                        "${s.key} is $it"
                    )
                }

                val sec =
                    prefs.getString(
                        "secret:$k",
                        null
                    ) ?: return say(
                        "I don't remember ${s.key}"
                    )

                val text =
                    runCatching {
                        Vault.dec(sec)
                    }.getOrNull()
                        ?: return say(
                            "I couldn't unlock that"
                        )

                quiet(
                    "${s.key}: $text"
                )
            }

            is Step.Copy -> {

                val k =
                    fk(s.key)

                val sec =
                    prefs.getString(
                        "secret:$k",
                        null
                    )

                val text =
                    if (sec != null) {
                        runCatching {
                            Vault.dec(sec)
                        }.getOrNull()
                    } else {
                        Brain.map("fact")[k]
                    }

                if (text == null) {
                    return say(
                        "I don't have ${s.key}"
                    )
                }

                val clip =
                    ClipData.newPlainText(
                        "sanya",
                        text
                    )

                if (sec != null) {
                    runCatching {

                        clip.description.extras =
                            PersistableBundle().apply {
                                putBoolean(
                                    "android.content.extra.IS_SENSITIVE",
                                    true
                                )
                            }
                    }
                }

                (
                    ctx.getSystemService(
                        Context.CLIPBOARD_SERVICE
                    ) as ClipboardManager
                ).setPrimaryClip(clip)

                say("Copied")
            }

            is Step.Forget -> {

                val k =
                    fk(s.key)

                val removed =
                    Brain.unlink(s.key) ||
                        Brain.unlink(k) ||
                        prefs.contains("secret:$k").also {
                            if (it) {
                                prefs.edit()
                                    .remove("secret:$k")
                                    .apply()
                            }
                        }

                say(
                    if (removed) {
                        "Forgot ${s.key}"
                    } else {
                        "I don't have ${s.key}"
                    }
                )
            }

            is Step.SetName -> {

                Brain.put(
                    "setting",
                    "name",
                    s.name
                )

                say(
                    "Nice to meet you${name()}"
                )
            }

            Step.Brief -> {

                val h =
                    Calendar.getInstance()
                        .get(Calendar.HOUR_OF_DAY)

                val greet =
                    if (h < 12) {
                        "Good morning"
                    } else if (h < 17) {
                        "Good afternoon"
                    } else {
                        "Good evening"
                    }

                val bm =
                    ctx.getSystemService(
                        Context.BATTERY_SERVICE
                    ) as BatteryManager

                val todo =
                    Brain.items("todo")

                val tail =
                    if (todo.isEmpty()) {
                        "Your list is empty."
                    } else {
                        "You have ${todo.size} things on your list: " +
                            todo.joinToString(", ") +
                            "."
                    }

                val dates = Mind.upcomingLine()
                val routineLine = Routines.briefLine(ctx)

                val hint =
                    (if (dates.isEmpty()) "" else " $dates") +
                        (if (routineLine.isEmpty()) "" else " $routineLine") +
                        (Mind.habit(ctx)?.let {
                            " Around now you often say $it."
                        } ?: "")

                say(
                    "$greet${name()}. " +
                        "It's ${now("h:mm a")}, " +
                        "${now("EEEE, d MMMM")}. " +
                        "Battery ${
                            bm.getIntProperty(
                                BatteryManager.BATTERY_PROPERTY_CAPACITY
                            )
                        } percent. " +
                        tail +
                        hint +
                        if (online()) {
                            ""
                        } else {
                            " You're offline."
                        }
                )
            }

            Step.Stats -> {

                say(
                    "I know " +
                        "${Brain.map("nick").size} nicknames, " +
                        "${Brain.map("slang").size} slang phrases, " +
                        "${Brain.map("word").size} word meanings and " +
                        "${Brain.map("fact").size} facts. " +
                        "Say show my brain to edit them."
                )
            }

            Step.OpenBrain -> {

                go(
                    Intent(
                        ctx,
                        MainActivity::class.java
                    )
                )

                say(
                    "Opening my brain"
                )
            }

            Step.Again -> {

                if (lastCommand.isEmpty()) {
                    say(
                        "There's nothing to repeat yet"
                    )
                } else {
                    handle(lastCommand)
                }
            }

            is Step.Reply -> {

                say(
                    s.text
                        .replace(
                            "{n}",
                            name()
                        )
                        .replace(
                            "{name}",
                            Brain.setting(
                                "name",
                                ""
                            ).ifEmpty {
                                "not set yet. Say call me, then your name"
                            }
                        )
                )
            }

            is Step.Unknown -> {

                say(
                    "I didn't get that. Say 'that means' and what I should do, and I'll learn it."
                )
            }
        }
    }

    // Opens one of Sanya's own screens. Returns false (and shows the reason in the
    // caption) instead of failing silently.
    private fun openScreen(i: Intent): Boolean {
        return try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            quiet("Not in AndroidManifest.xml: " + (i.component?.shortClassName ?: "screen"))
            ErrorLog.add(ctx, "Screen missing from AndroidManifest.xml", e)
            false
        } catch (e: Throwable) {
            quiet("Could not open: " + e.javaClass.simpleName + " " + (e.message ?: ""))
            ErrorLog.add(ctx, "Could not open a screen", e)
            false
        }
    }

    private fun go(i: Intent) {

        runCatching {
            ctx.startActivity(
                i.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            )
        }.onFailure {
            ErrorLog.add(ctx, "Could not open an app or screen", it)
            say(
                "That didn't open"
            )
        }
    }

    // ---------- fuzzy matching ----------

    private fun norm(x: String) =
        x.lowercase()
            .replace(
                Regex("[^a-z0-9]"),
                ""
            )

    private fun resolve(x: String) =
        Brain.map("nick")[
            Brain.clean(x)
        ] ?: x

    private fun lev(
        a: String,
        b: String
    ): Int {

        val d =
            Array(a.length + 1) {
                IntArray(b.length + 1)
            }

        for (i in 0..a.length) {
            d[i][0] = i
        }

        for (j in 0..b.length) {
            d[0][j] = j
        }

        for (i in 1..a.length) {
            for (j in 1..b.length) {

                d[i][j] =
                    minOf(
                        d[i - 1][j] + 1,
                        d[i][j - 1] + 1,
                        d[i - 1][j - 1] +
                            if (
                                a[i - 1] ==
                                b[j - 1]
                            ) {
                                0
                            } else {
                                1
                            }
                    )
            }
        }

        return d[a.length][b.length]
    }

    private fun score(
        n: String,
        q: String,
        raw: String,
        sounds: Boolean = false
    ): Int {

        if (q.isEmpty()) {
            return 0
        }

        if (n == q) {
            return 100
        }

        val tokens =
            raw.lowercase()
                .split(" ")
                .map {
                    norm(it)
                }

        if (q in tokens) {
            return 90
        }

        if (n.startsWith(q)) {
            return 80
        }

        if (n.contains(q)) {
            return 70
        }

        val d =
            tokens.minOfOrNull {
                lev(it, q)
            } ?: 9

        return if (
            d <= 1 &&
            q.length > 3
        ) {
            60
        } else if (
            d <= 2 &&
            q.length > 5
        ) {
            50
        } else if (sounds) {
            // Spelling is too far apart ("pretty" vs "preethi"): compare how they sound.
            Mind.soundScore(q, raw)
        } else {
            0
        }
    }

    private fun openApp(
        spoken: String
    ) {

        val q =
            norm(
                resolve(
                    spoken.removeSuffix(
                        " app"
                    )
                )
            )

        val pm =
            ctx.packageManager

        val apps =
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(
                        Intent.CATEGORY_LAUNCHER
                    ),
                0
            )

        val best =
            apps
                .map {
                    it to score(
                        norm(
                            it.loadLabel(pm)
                                .toString()
                        ),
                        q,
                        it.loadLabel(pm)
                            .toString()
                    )
                }
                .filter {
                    it.second > 0
                }
                .maxByOrNull {
                    it.second
                }

        val i =
            best?.let {
                pm.getLaunchIntentForPackage(
                    it.first.activityInfo.packageName
                )
            }

        if (i != null) {
            go(i)
        } else {
            say(
                "I can't find $spoken"
            )
        }
    }

    // ---------- contacts and calls ----------

    private fun allContacts(): List<Contact> {

        val out =
            mutableListOf<Contact>()

        ctx.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(
                Phone.CONTACT_ID,
                Phone.DISPLAY_NAME,
                Phone.NUMBER
            ),
            null,
            null,
            null
        )?.use {

            while (it.moveToNext()) {

                out += Contact(
                    it.getLong(0),
                    it.getString(1) ?: "",
                    it.getString(2) ?: ""
                )
            }
        }

        return out
    }

    // Best contact for what was heard, or null. No side effects.
    private fun matchContact(
        spoken: String
    ): Pair<Contact, Int>? {

        val q =
            norm(
                resolve(spoken)
            )

        return allContacts()
            .map {
                it to score(
                    norm(it.name),
                    q,
                    it.name,
                    true
                )
            }
            .filter {
                it.second > 0
            }
            .maxByOrNull {
                it.second
            }
    }

    private fun findContact(
        spoken: String
    ): Contact? {

        val best = matchContact(spoken) ?: return null

        // A fuzzy or by-sound match: remember it, so next time it is exact.
        if (
            best.second in 50..60
        ) {

            Brain.link(
                "nick",
                spoken,
                best.first.name
            )

            quiet(
                "Learned: $spoken = ${best.first.name}"
            )
        }

        return best.first
    }

    // Nothing matched. Offer the closest-sounding contact, if any, and learn from the answer.
    private fun notFound(
        spoken: String,
        retry: Step
    ) {

        val q = norm(resolve(spoken))

        val guess =
            if (q.length < 3) {
                null
            } else {
                allContacts()
                    .map { it to Mind.soundNear(q, it.name) }
                    .filter { it.second < 99 }
                    .minByOrNull { it.second }
                    ?.first
            }

        if (guess == null) {
            say("I can't find $spoken in your contacts")
            return
        }

        clearPending()
        fixSpoken = spoken
        fixName = guess.name
        fixStep = retry
        pendingAt = System.currentTimeMillis()

        say("I couldn't find $spoken. Did you mean ${guess.name}?")
    }

    private fun whatsappRow(
        contactId: Long,
        mime: String
    ): Long? {

        val c =
            ctx.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data._ID
                ),
                "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(
                    contactId.toString(),
                    mime
                ),
                null
            )

        return c?.use {
            if (it.moveToFirst()) {
                it.getLong(0)
            } else {
                null
            }
        }
    }

    private fun call(
        s: Step.Call
    ) {

        val c =
            findContact(
                s.contact
            )
                ?: return notFound(s.contact, s)

        if (s.whatsapp) {

            val mime =
                if (s.video) {
                    "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
                } else {
                    "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
                }

            val id =
                whatsappRow(
                    c.id,
                    mime
                )

            if (id == null) {
                say(
                    "${c.name} isn't on WhatsApp, or WhatsApp contacts aren't synced"
                )
                return
            }

            go(
                Intent(
                    Intent.ACTION_VIEW
                )
                    .setDataAndType(
                        Uri.parse(
                            "content://com.android.contacts/data/$id"
                        ),
                        mime
                    )
                    .setPackage(
                        "com.whatsapp"
                    )
            )

            say(
                if (s.video) {
                    "Video calling ${c.name}"
                } else {
                    "WhatsApp calling ${c.name}"
                }
            )

            return
        }

        val uri =
            Uri.fromParts(
                "tel",
                c.number,
                null
            )

        if (
            ctx.checkSelfPermission(
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            go(
                Intent(
                    Intent.ACTION_CALL,
                    uri
                )
            )

            say(
                "Calling ${c.name}"
            )

        } else {

            go(
                Intent(
                    Intent.ACTION_DIAL,
                    uri
                )
            )

            say(
                "Allow phone permission so I can call directly"
            )
        }
    }

    private fun toggle(
        s: Step.Toggle
    ) {

        if (
            s.what.startsWith("flash") ||
            s.what == "torch"
        ) {

            runCatching {

                val cm =
                    ctx.getSystemService(
                        Context.CAMERA_SERVICE
                    ) as CameraManager

                cm.setTorchMode(
                    cm.cameraIdList[0],
                    s.on
                )

            }.onSuccess {

                say(
                    "Flashlight ${if (s.on) "on" else "off"}"
                )

            }.onFailure {

                say(
                    "Flashlight failed"
                )
            }

            return
        }

        // Android blocks direct switching of these.
        // For now this opens the correct settings page.
        if (
            directToggle(
                ctx,
                s,
                say
            )
        ) {
            return
        }

        go(
            Intent(
                when {

                    s.what.contains(
                        "bluetooth"
                    ) ->
                        Settings.ACTION_BLUETOOTH_SETTINGS

                    s.what.contains(
                        "airplane"
                    ) ||
                        s.what.contains(
                            "flight"
                        ) ->
                        Settings.ACTION_AIRPLANE_MODE_SETTINGS

                    s.what.contains(
                        "location"
                    ) ||
                        s.what == "gps" ->
                        Settings.ACTION_LOCATION_SOURCE_SETTINGS

                    s.what == "nfc" ->
                        Settings.ACTION_NFC_SETTINGS

                    s.what == "data" ||
                        s.what == "network" ->
                        Settings.ACTION_WIRELESS_SETTINGS

                    else ->
                        Settings.ACTION_WIFI_SETTINGS
                }
            )
        )

        say(
            "Opening ${s.what} settings"
        )
    }
}

/**
 * Small local knowledge cache for general questions.
 *
 * SharedPreferences is used deliberately here because Sanya already uses
 * SharedPreferences for its existing lightweight memory. No new database or
 * dependency is introduced for this feature.
 */
object KnowledgeStore {

    private const val PREFS = "sanya_knowledge_v3"
    private const val Q_PREFIX = "q:"
    private const val A_PREFIX = "a:"
    private const val T_PREFIX = "t:"

    data class Entry(
        val question: String,
        val answer: String,
        val timestamp: Long,
        internal val id: String
    )

    fun find(
        ctx: Context,
        question: String
    ): Entry? {

        return runCatching {
            val prefs =
                ctx.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )

            val normalized =
                IntentEngine.normalizeQuestion(question)

            if (normalized.isEmpty()) {
                return@runCatching null
            }

            // Fast path: exact normalized question.
            val exactId = idFor(normalized)
            val exactQuestion =
                prefs.getString(Q_PREFIX + exactId, null)

            if (exactQuestion != null) {
                val answer =
                    prefs.getString(A_PREFIX + exactId, null)

                val timestamp =
                    prefs.getLong(
                        T_PREFIX + exactId,
                        0L
                    )

                if (
                    !answer.isNullOrBlank() &&
                    isFresh(normalized, timestamp) &&
                    WebSearch.isUsableAnswer(normalized, answer)
                ) {
                    return@runCatching Entry(
                        exactQuestion,
                        answer,
                        timestamp,
                        exactId
                    )
                }
            }

            // Practical fuzzy match for small wording changes.
            val targetTokens = importantTokens(normalized)

            if (targetTokens.size < 2) {
                return@runCatching null
            }

            var best: Entry? = null
            var bestScore = 0.0

            for ((key, value) in prefs.all) {
                if (!key.startsWith(Q_PREFIX)) continue

                val id = key.removePrefix(Q_PREFIX)
                val storedQuestion = value as? String ?: continue
                val answer =
                    prefs.getString(A_PREFIX + id, null)
                        ?: continue

                val timestamp =
                    prefs.getLong(
                        T_PREFIX + id,
                        0L
                    )

                if (
                    answer.isBlank() ||
                    !isFresh(normalized, timestamp) ||
                    !WebSearch.isUsableAnswer(normalized, answer)
                ) continue

                val storedTokens =
                    importantTokens(storedQuestion)

                if (storedTokens.isEmpty()) continue

                val common =
                    targetTokens.intersect(storedTokens).size

                val minSize =
                    minOf(
                        targetTokens.size,
                        storedTokens.size
                    )

                val maxSize =
                    maxOf(
                        targetTokens.size,
                        storedTokens.size
                    )

                val score =
                    if (minSize == 0) {
                        0.0
                    } else {
                        common.toDouble() / minSize.toDouble()
                    }

                val coverage =
                    if (maxSize == 0) {
                        0.0
                    } else {
                        common.toDouble() / maxSize.toDouble()
                    }

                // Require the shorter question to be fully represented and
                // at least two-thirds token coverage in total. This allows
                // practical variants such as "what city is the capital of
                // America" to reuse "what is the capital of America".
                if (score >= 1.0 && coverage >= 0.66 && score > bestScore) {
                    bestScore = score
                    best = Entry(
                        storedQuestion,
                        answer,
                        timestamp,
                        id
                    )
                }
            }

            best
        }.getOrNull()
    }

    fun save(
        ctx: Context,
        question: String,
        answer: String
    ) {

        runCatching {
            val normalized =
                IntentEngine.normalizeQuestion(question)

            val cleanedAnswer =
                WebSearch.cleanAnswer(answer)

            if (
                normalized.isEmpty() ||
                cleanedAnswer.isBlank()
            ) {
                return@runCatching
            }

            val prefs =
                ctx.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )

            // Reuse an existing exact/fuzzy record so we don't create
            // duplicate answers for small wording variations.
            val existing =
                find(ctx, normalized)

            val id =
                existing?.id ?: idFor(normalized)

            prefs.edit()
                .putString(
                    Q_PREFIX + id,
                    normalized
                )
                .putString(
                    A_PREFIX + id,
                    cleanedAnswer
                )
                .putLong(
                    T_PREFIX + id,
                    System.currentTimeMillis()
                )
                .apply()
        }
    }

    private fun isFresh(
        question: String,
        timestamp: Long
    ): Boolean {

        if (timestamp <= 0L) {
            return false
        }

        val q =
            question.lowercase(Locale.getDefault())

        // Current/serving questions should be refreshed frequently.
        // The decision is delegated to IntentEngine so historical questions
        // such as "former president" are not treated as current by accident.
        val dynamic =
            IntentEngine.isTimeSensitiveQuestion(q)

        val maxAge =
            if (dynamic) {
                12L * 60L * 60L * 1000L
            } else {
                30L * 24L * 60L * 60L * 1000L
            }

        return System.currentTimeMillis() - timestamp <= maxAge
    }

    private fun idFor(value: String): String {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(
                    value.toByteArray(Charsets.UTF_8)
                )

        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or
                Base64.NO_WRAP or
                Base64.NO_PADDING
        )
    }

    private fun importantTokens(
        question: String
    ): Set<String> {

        val stopWords = setOf(
            "what", "what's", "whats", "is", "are", "was", "were",
            "who", "whom", "when", "where", "why", "how",
            "can", "could", "would", "will", "should", "shall",
            "do", "does", "did", "has", "have", "had", "the",
            "a", "an", "of", "to", "for", "me", "please", "tell",
            "about", "you", "know", "give"
        )

        return question
            .lowercase(Locale.getDefault())
            .split(Regex("\\s+"))
            .map {
                it.trim(
                    ' ', '.', ',', '?', '!', ':', ';', '"', '\''
                )
                    .replace(Regex("[’']s$"), "")
            }
            .filter {
                it.isNotEmpty() &&
                    it !in stopWords
            }
            .toSet()
    }
}

/**
 * Free/no-key web lookup using DuckDuckGo's public Instant Answer API,
 * with a lightweight HTML-search fallback when no Instant Answer exists.
 */
object WebSearch {

    private const val TIMEOUT_MS = 8000
    private const val WIKIDATA_ENDPOINT = "https://query.wikidata.org/sparql"

    /**
     * Dynamic knowledge pipeline:
     *
     * 1. Wikidata for current office holders and structured facts.
     * 2. Wikipedia for entity descriptions and historical questions.
     * 3. DuckDuckGo Instant Answer.
     * 4. DuckDuckGo HTML search as the last fallback.
     *
     * No person, president, prime minister, CEO, or other office holder
     * is hard-coded in this file.
     */
    fun answer(question: String): String? {

        val normalized =
            IntentEngine.normalizeQuestion(question)

        if (normalized.isBlank()) {
            return null
        }

        // The most important bug fix:
        // current office-holder questions must use structured data first.
        // This prevents noisy search snippets such as "Name of the ..." from
        // being spoken as though they were the actual answer.
        if (IntentEngine.isTimeSensitiveQuestion(normalized)) {

            currentOfficeHolder(normalized)?.let {
                return it
            }

            wikipediaAnswer(normalized)?.let {
                return it
            }

            duckDuckGoInstant(normalized)?.let {
                return it
            }

            duckDuckGoHtml(normalized)?.let {
                return it
            }

            return null
        }

        // Stable questions can use Wikipedia first.
        wikipediaAnswer(normalized)?.let {
            return it
        }

        duckDuckGoInstant(normalized)?.let {
            return it
        }

        return duckDuckGoHtml(normalized)
    }

    /**
     * Generic current office-holder resolver.
     *
     * Examples it can resolve dynamically:
     *   who is the president of India
     *   who is the prime minister of India
     *   who is the chief minister of Tamil Nadu
     *   who is the governor of Tamil Nadu
     *
     * The answer comes from Wikidata's structured P39 (position held)
     * statements rather than a search-result snippet.
     */
    private fun currentOfficeHolder(question: String): String? {

        val match =
            Regex(
                """(?i)^who\s+(?:is|are)\s+(?:(?:the|current|present|serving|incumbent)\s+)*""" +
                    """(president|prime\s+minister|chief\s+minister|governor|mayor|chancellor|pope|king|queen|chief\s+justice|chairman|chairperson)\s+of\s+(.+?)\s*(?:now|currently|at\s+present|as\s+of\s+now)?$"""
            ).find(question.trim())
            ?: return null

        val role =
            match.groupValues[1]
                .replace(Regex("\\s+"), " ")
                .trim()

        val place =
            match.groupValues[2]
                .replace(Regex("\\s+"), " ")
                .trim()

        if (role.isBlank() || place.isBlank()) {
            return null
        }

        // Wikidata labels are case-sensitive ("President of India"), but the
        // question arrives lower-cased, so rebuild the proper-case label.
        val displayRole = titleCase(role)
        val displayPlace = titleCase(place)
        val positionLabel = "$displayRole of $displayPlace"
        val literal = sparqlLiteral(positionLabel)

        val query =
            """
            SELECT ?person ?personLabel ?start ?end WHERE {
              ?position rdfs:label $literal@en.
              ?person p:P39 ?statement.
              ?statement ps:P39 ?position.
              OPTIONAL { ?statement pq:P580 ?start. }
              OPTIONAL { ?statement pq:P582 ?end. }
              FILTER(!BOUND(?end) || ?end >= NOW())
              SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
            }
            ORDER BY DESC(?start)
            LIMIT 3
            """.trimIndent()

        val encoded =
            URLEncoder.encode(query, "UTF-8")

        val url =
            "$WIKIDATA_ENDPOINT?query=$encoded&format=json"

        val raw =
            get(url)
                ?: return null

        val json =
            runCatching { JSONObject(raw) }.getOrNull()
                ?: return null

        val bindings =
            json.optJSONObject("results")
                ?.optJSONArray("bindings")
                ?: return null

        for (i in 0 until bindings.length()) {
            val row =
                bindings.optJSONObject(i)
                    ?: continue

            val label =
                row.optJSONObject("personLabel")
                    ?.optString("value")
                    ?.trim()
                    .orEmpty()

            if (label.isBlank()) {
                continue
            }

            val lowerLabel =
                label.lowercase(Locale.getDefault())

            // Avoid returning a raw organization/position label when the
            // structured response did not resolve to a person name.
            if (
                lowerLabel == positionLabel.lowercase(Locale.getDefault()) ||
                lowerLabel == "unknown value" ||
                lowerLabel == "no value"
            ) {
                continue
            }

            return "The current $role of $displayPlace is $label."
        }

        return null
    }

    private fun titleCase(value: String): String {
        val small = setOf("of", "the", "and", "for", "in")
        return value
            .trim()
            .split(Regex("\\s+"))
            .mapIndexed { i, w ->
                if (i > 0 && w.lowercase(Locale.ROOT) in small) {
                    w.lowercase(Locale.ROOT)
                } else {
                    w.replaceFirstChar { c -> c.uppercase() }
                }
            }
            .joinToString(" ")
    }

    private fun sparqlLiteral(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
            .let { "\"$it\"" }
    }

    private fun duckDuckGoInstant(
        normalized: String
    ): String? {

        return runCatching {
            val encoded =
                URLEncoder.encode(
                    normalized,
                    "UTF-8"
                )

            val url =
                "https://api.duckduckgo.com/?q=$encoded" +
                    "&format=json&no_html=1&skip_disambig=1"

            val raw =
                get(url)
                    ?: return@runCatching null

            val json =
                JSONObject(raw)

            val directCandidates = listOf(
                json.optString("Answer"),
                json.optString("Definition"),
                json.optString("AbstractText")
            )

            for (candidate in directCandidates) {
                val cleaned =
                    cleanAnswer(candidate, normalized)

                if (isUsableAnswer(normalized, cleaned)) {
                    return@runCatching cleaned
                }
            }

            val related =
                json.optJSONArray("RelatedTopics")

            val relatedText =
                findRelatedText(related)

            val cleanedRelated =
                cleanAnswer(
                    relatedText,
                    normalized
                )

            if (isUsableAnswer(normalized, cleanedRelated)) {
                return@runCatching cleanedRelated
            }

            null
        }.getOrNull()
    }

    private fun duckDuckGoHtml(
        normalized: String
    ): String? {

        return runCatching {
            val queries = linkedSetOf(normalized)

            if (IntentEngine.isTimeSensitiveQuestion(normalized)) {
                queries.add("current $normalized")
            }

            for (query in queries) {
                val encoded =
                    URLEncoder.encode(
                        query,
                        "UTF-8"
                    )

                val url =
                    "https://html.duckduckgo.com/html/?q=$encoded"

                val html =
                    get(url)
                        ?: continue

                val candidates =
                    extractSnippets(
                        html,
                        normalized
                    )

                for (candidate in candidates) {
                    if (isUsableAnswer(normalized, candidate)) {
                        return@runCatching candidate
                    }
                }
            }

            null
        }.getOrNull()
    }

    fun isUsableAnswer(
        question: String,
        answer: String?
    ): Boolean {

        if (answer.isNullOrBlank()) {
            return false
        }

        val cleaned =
            cleanAnswer(
                answer,
                question
            )

        if (cleaned.isBlank() || cleaned.length < 3) {
            return false
        }

        val q =
            IntentEngine.normalizeQuestion(question)
                .lowercase(Locale.getDefault())

        val a =
            cleaned.lowercase(Locale.getDefault())

        // Reject an answer that is basically the spoken question again.
        val qWords =
            q.split(Regex("\\s+"))
                .filter { it.length > 2 }
                .toSet()

        val aWords =
            a.split(Regex("\\s+"))
                .map {
                    it.trim(
                        ' ', '.', ',', '?', '!', ':', ';', '"', '\''
                    )
                }
                .filter { it.length > 2 }
                .toSet()

        if (qWords.isNotEmpty()) {
            val overlap =
                qWords.intersect(aWords).size.toDouble() /
                    qWords.size.toDouble()

            if (overlap >= 1.0 && a.length <= q.length + 35) {
                return false
            }
        }

        // Search snippets with repeated navigation wording are usually not
        // the requested answer.
        if (
            Regex("(?i)\\bname of the\\b")
                .containsMatchIn(a) &&
            !Regex("(?i)\\bis\\s+[A-Z][a-z]")
                .containsMatchIn(cleaned)
        ) {
            return false
        }

        // A current office question must not accept a fragment that merely
        // repeats the role. The structured provider should normally answer
        // these questions; this guard protects the fallbacks.
        if (IntentEngine.isTimeSensitiveQuestion(question)) {
            val currentOfficePattern =
                Regex(
                    "(?i)^(?:the\\s+)?(?:current|present|serving|incumbent)?\\s*" +
                        "(?:president|prime\\s+minister|chief\\s+minister|governor|mayor|chancellor|pope|king|queen|chief\\s+justice)" +
                        "(?:\\s+of\\s+[^.?!]+)?[.?!]?$"
                )

            if (currentOfficePattern.matches(cleaned.trim())) {
                return false
            }
        }

        return true
    }

    fun cleanAnswer(
        text: String?,
        question: String? = null
    ): String {

        if (text.isNullOrBlank()) {
            return ""
        }

        var cleaned =
            text
                .replace(Regex("<script.*?</script>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<style.*?</style>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&#x27;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace(Regex("\\s+"), " ")
                .trim()

        if (cleaned.isEmpty()) {
            return ""
        }

        // Remove exact query echo at the beginning of an HTML snippet.
        if (!question.isNullOrBlank()) {
            val q =
                IntentEngine.normalizeQuestion(question)

            if (q.isNotBlank()) {
                cleaned =
                    cleaned.replace(
                        Regex(
                            "(?i)^\\s*${Regex.escape(q)}[\\s:,-]*"
                        ),
                        ""
                    )
            }
        }

        // Collapse directly repeated phrases introduced by snippets.
        repeat(3) {
            cleaned =
                cleaned.replace(
                    Regex("(?i)\\b([^.!?]{2,80})\\s+\\1\\b"),
                    "$1"
                )
        }

        val sentences =
            Regex("(?<=[.!?])\\s+")
                .split(cleaned)
                .filter { it.isNotBlank() }

        if (sentences.isNotEmpty()) {
            cleaned =
                sentences
                    .take(2)
                    .joinToString(" ")
                    .trim()
        }

        if (cleaned.length > 400) {
            cleaned =
                cleaned
                    .take(397)
                    .trimEnd()
                    .plus("...")
        }

        return cleaned
    }

    private fun wikipediaAnswer(
        question: String
    ): String? {

        val query =
            wikipediaQuery(question)

        if (query.isBlank()) {
            return null
        }

        val queries = linkedSetOf(query)

        if (
            Regex(
                "(?i)\\b(?:former|ex|previous|past|last|historical|in\\s+\\d{4})\\b"
            ).containsMatchIn(query)
        ) {
            val subject =
                query
                    .replace(
                        Regex("(?i)^(?:who|what)\\s+(?:is|was)\\s+"),
                        ""
                    )
                    .trim()

            if (subject.isNotBlank()) {
                queries.add("list of $subject")
            }
        }

        for (searchQuery in queries) {
            val encoded =
                URLEncoder.encode(searchQuery, "UTF-8")

            val searchUrl =
                "https://en.wikipedia.org/w/api.php" +
                    "?action=query&list=search" +
                    "&srsearch=$encoded&format=json&utf8=1&srlimit=5"

            val raw =
                get(searchUrl)
                    ?: continue

            val json =
                runCatching { JSONObject(raw) }.getOrNull()
                    ?: continue

            val search =
                json.optJSONObject("query")
                    ?.optJSONArray("search")
                    ?: continue

            for (i in 0 until search.length()) {
                val hit =
                    search.optJSONObject(i)
                        ?: continue

                val title =
                    hit.optString("title").trim()

                if (title.isBlank()) {
                    continue
                }

                val slug =
                    URLEncoder.encode(
                        title.replace(' ', '_'),
                        "UTF-8"
                    )

                val pageUrl =
                    "https://en.wikipedia.org/api/rest_v1/page/summary/$slug"

                val pageRaw =
                    get(pageUrl)
                        ?: continue

                val page =
                    runCatching { JSONObject(pageRaw) }.getOrNull()
                        ?: continue

                val extract =
                    page.optString("extract").trim()

                if (extract.isBlank()) {
                    continue
                }

                val answer =
                    answerFromWikipedia(
                        question,
                        extract
                    )

                if (isUsableAnswer(question, answer)) {
                    return answer
                }
            }
        }

        return null
    }

    private fun wikipediaQuery(
        question: String
    ): String {
        return IntentEngine.normalizeQuestion(question)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun answerFromWikipedia(
        question: String,
        extract: String
    ): String {

        val cleaned =
            cleanAnswer(extract, question)

        if (cleaned.isBlank()) {
            return ""
        }

        val sentences =
            Regex("(?<=[.!?])\\s+")
                .split(cleaned)
                .filter { it.isNotBlank() }

        if (sentences.isEmpty()) {
            return cleaned
        }

        val q =
            question.lowercase(Locale.getDefault())

        if (q.startsWith("who ") || q.startsWith("what ")) {
            val definition =
                sentences.firstOrNull { sentence ->
                    Regex("(?i)\\b(?:is|was|are|were)\\b")
                        .containsMatchIn(sentence)
                }

            if (!definition.isNullOrBlank()) {
                return definition.trim()
            }
        }

        return sentences.take(2).joinToString(" ").trim()
    }

    private fun get(urlString: String): String? {

        val connection =
            URL(urlString)
                .openConnection() as? HttpURLConnection
                ?: return null

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android) Sanya/1.0"
            )
            connection.setRequestProperty(
                "Accept",
                "application/sparql-results+json,application/json,text/html;q=0.9,*/*;q=0.8"
            )

            if (connection.responseCode !in 200..299) {
                return null
            }

            connection.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

        } finally {
            connection.disconnect()
        }
    }

    private fun findRelatedText(
        array: JSONArray?
    ): String? {

        if (array == null) {
            return null
        }

        for (i in 0 until array.length()) {
            val item =
                array.opt(i)

            if (item !is JSONObject) {
                continue
            }

            val text =
                item.optString("Text")

            if (text.isNotBlank()) {
                return text
            }

            val nested =
                item.optJSONArray("Topics")

            val nestedText =
                findRelatedText(nested)

            if (!nestedText.isNullOrBlank()) {
                return nestedText
            }
        }

        return null
    }

    private fun extractSnippets(
        html: String,
        question: String
    ): List<String> {

        val patterns = listOf(
            Regex(
                """(?is)<(?:a|div)[^>]*class=[\"'][^\"']*result__snippet[^\"']*[\"'][^>]*>(.*?)</(?:a|div)>"""
            ),
            Regex(
                """(?is)<(?:a|div)[^>]*class=[\"'][^\"']*result__a[^\"']*[\"'][^>]*>(.*?)</(?:a|div)>"""
            )
        )

        val results = mutableListOf<String>()

        for (pattern in patterns) {
            for (match in pattern.findAll(html).take(8)) {
                val cleaned =
                    cleanAnswer(
                        match.groupValues[1],
                        question
                    )

                if (cleaned.isNotBlank() && cleaned !in results) {
                    results.add(cleaned)
                }
            }
        }

        return results
    }
}
// Encrypts secrets with a key that never leaves the phone's Keystore.
object Vault {

    private fun key(): SecretKey {

        val ks =
            KeyStore.getInstance(
                "AndroidKeyStore"
            ).apply {
                load(null)
            }

        (
            ks.getKey(
                "sanya_vault",
                null
            ) as? SecretKey
        )?.let {
            return it
        }

        val kg =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )

        kg.init(
            KeyGenParameterSpec.Builder(
                "sanya_vault",
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(
                    KeyProperties.BLOCK_MODE_GCM
                )
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .build()
        )

        return kg.generateKey()
    }

    fun enc(s: String): String {

        val c =
            Cipher.getInstance(
                "AES/GCM/NoPadding"
            )

        c.init(
            Cipher.ENCRYPT_MODE,
            key()
        )

        return Base64.encodeToString(
            c.iv +
                c.doFinal(
                    s.toByteArray()
                ),
            Base64.NO_WRAP
        )
    }

    fun dec(s: String): String {

        val b =
            Base64.decode(
                s,
                Base64.NO_WRAP
            )

        val c =
            Cipher.getInstance(
                "AES/GCM/NoPadding"
            )

        c.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(
                128,
                b.copyOfRange(
                    0,
                    12
                )
            )
        )

        return String(
            c.doFinal(
                b,
                12,
                b.size - 12
            )
        )
    }
}
