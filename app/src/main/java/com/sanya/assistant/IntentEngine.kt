package com.sanya.assistant

/**
 * Sanya Natural Language Intent Engine.
 *
 * Converts natural spoken sentences into the simpler command
 * vocabulary understood by CommandParser.
 *
 * Examples:
 *
 * "Hey Sanya, can you please open YouTube for me?"
 *      -> "open youtube"
 *
 * "Sanya, could you call Ravi?"
 *      -> "call ravi"
 *
 * "Please search for Tamil songs"
 *      -> "search tamil songs"
 *
 * "Can you turn the flashlight on?"
 *      -> "turn on flashlight"
 */
object IntentEngine {

    fun process(input: String): String {

        var text = input
            .lowercase()
            .trim()

        if (text.isEmpty()) return ""

        // ---------------------------------------------------------
        // WAKE WORDS
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """\b(?:hey\s+)?(?:sanya|sania|saniya|sonya|sonia|sunya)\b"""
            ),
            " "
        )

        // ---------------------------------------------------------
        // PUNCTUATION
        // ---------------------------------------------------------

        text = text.replace(
            Regex("""[,.!?;:]+"""),
            " "
        )

        // ---------------------------------------------------------
        // POLITE / CONVERSATIONAL PREFIXES
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:can|could|would|will)\s+you\s+(?:please\s+)?"""
            ),
            ""
        )

        text = text.replace(
            Regex(
                """^(?:can|could|would|will)\s+"""
            ),
            ""
        )

        text = text.replace(
            Regex(
                """^(?:please|pls|plz|kindly)\s+"""
            ),
            ""
        )

        text = text.replace(
            Regex(
                """^(?:i\s+want\s+you\s+to|i\s+want\s+to|i'd\s+like\s+you\s+to|i'd\s+like\s+to|i\s+would\s+like\s+you\s+to|i\s+would\s+like\s+to)\s+"""
            ),
            ""
        )

        // ---------------------------------------------------------
        // COMMON CONTRACTIONS
        // ---------------------------------------------------------

        text = text.replace("what's", "what is")
        text = text.replace("whats", "what is")
        text = text.replace("i'm", "i am")
        text = text.replace("you're", "you are")
        text = text.replace("can't", "cannot")
        text = text.replace("don't", "do not")
        text = text.replace("it's", "it is")

        // ---------------------------------------------------------
        // OPEN APPLICATIONS
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:open|launch|start|run|load)\s+"""
            ),
            "open "
        )

        text = text.replace(
            Regex(
                """^(?:take\s+me\s+to|go\s+to)\s+(youtube|whatsapp|instagram|facebook|google|chrome|gmail|spotify|camera|settings)\s*$"""
            ),
            "open $1"
        )

        // ---------------------------------------------------------
        // CALL
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:please\s+)?(?:make|give|place)\s+(?:a\s+)?(?:phone\s+)?call\s+(?:to\s+)?"""
            ),
            "call "
        )

        text = text.replace(
            Regex(
                """^(?:phone|ring|dial)\s+(?:to\s+)?"""
            ),
            "call "
        )

        text = text.replace(
            Regex(
                """^(?:call)\s+(?:up\s+)?"""
            ),
            "call "
        )

        // ---------------------------------------------------------
        // WHATSAPP CALL
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:make|give|place)\s+(?:a\s+)?(?:whatsapp\s+)?(?:video\s+)?call\s+(?:to\s+)?"""
            ),
            "whatsapp call "
        )

        text = text.replace(
            Regex(
                """^(?:whatsapp)\s+(?:video\s+)?call\s+(?:to\s+)?"""
            ),
            "whatsapp call "
        )

        // ---------------------------------------------------------
        // MESSAGE / SMS
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:send|write)\s+(?:a\s+)?(?:text|message|sms|msg)\s+(?:to\s+)?"""
            ),
            "message "
        )

        text = text.replace(
            Regex(
                """^(?:text|message|sms|msg)\s+(?:to\s+)?"""
            ),
            "message "
        )

        // ---------------------------------------------------------
        // SEARCH
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:look\s+up|look\s+for|find)\s+"""
            ),
            "search "
        )

        text = text.replace(
            Regex(
                """^(?:search)\s+(?:for)\s+"""
            ),
            "search "
        )

        text = text.replace(
            Regex(
                """^(?:google)\s+(?:for)?\s*"""
            ),
            "search "
        )

        // ---------------------------------------------------------
        // YOUTUBE
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:watch|find)\s+(?:a\s+)?(?:video|videos)\s+(?:of|about)\s+"""
            ),
            "youtube "
        )

        text = text.replace(
            Regex(
                """^(?:watch)\s+"""
            ),
            "play "
        )

        // ---------------------------------------------------------
        // PLAY MUSIC / VIDEO
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:put\s+on)\s+"""
            ),
            "play "
        )

        text = text.replace(
            Regex(
                """^(?:play)\s+"""
            ),
            "play "
        )

        // ---------------------------------------------------------
        // TIMER
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:set\s+)?(?:a\s+)?timer\s+(?:for\s+)?"""
            ),
            "set timer "
        )

        // "set a timer for 5 minutes"
        // becomes "set timer 5 minutes"
        text = text.replace(
            Regex(
                """^timer\s+(?:for\s+)?"""
            ),
            "set timer "
        )

        // ---------------------------------------------------------
        // REMINDER
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:please\s+)?remind\s+me\s+(?:to\s+)?"""
            ),
            "remind me "
        )

        // ---------------------------------------------------------
        // ALARM
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:set\s+)?(?:an?\s+)?alarm\s+(?:for|at)\s+"""
            ),
            "alarm "
        )

        // ---------------------------------------------------------
        // NOTES
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:please\s+)?(?:write|make|create|take)\s+(?:a\s+)?note\s+(?:saying\s+|that\s+says\s+|that\s+)?"""
            ),
            "note "
        )

        // ---------------------------------------------------------
        // TODO
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:please\s+)?(?:add|put)\s+(.+?)\s+(?:to|in|on)\s+(?:my\s+)?(?:todo|to-do|to do)\s+list"""
            )
        ) {
            "add ${it.groupValues[1]} to my todo list"
        }

        // ---------------------------------------------------------
        // VOLUME
        // ---------------------------------------------------------

        if (
            Regex(
                """^(?:make\s+the\s+volume\s+)?(?:turn\s+the\s+volume\s+)?(?:volume\s+)?(?:louder|higher|up)$"""
            ).matches(text)
        ) {
            text = "volume up"
        }

        if (
            Regex(
                """^(?:make\s+the\s+volume\s+)?(?:turn\s+the\s+volume\s+)?(?:volume\s+)?(?:quieter|lower|down)$"""
            ).matches(text)
        ) {
            text = "volume down"
        }

        // ---------------------------------------------------------
        // FLASHLIGHT
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:switch|turn|turning)\s+(?:the\s+)?flashlight\s+on$"""
            ),
            "turn on flashlight"
        )

        text = text.replace(
            Regex(
                """^(?:switch|turn|turning)\s+(?:the\s+)?flashlight\s+off$"""
            ),
            "turn off flashlight"
        )

        text = text.replace(
            Regex(
                """^(?:switch|turn)\s+(?:the\s+)?torch\s+on$"""
            ),
            "turn on torch"
        )

        text = text.replace(
            Regex(
                """^(?:switch|turn)\s+(?:the\s+)?torch\s+off$"""
            ),
            "turn off torch"
        )

        // ---------------------------------------------------------
        // WIFI
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:switch|turn)\s+(?:the\s+)?wifi\s+on$"""
            ),
            "turn on wifi"
        )

        text = text.replace(
            Regex(
                """^(?:switch|turn)\s+(?:the\s+)?wifi\s+off$"""
            ),
            "turn off wifi"
        )

        // ---------------------------------------------------------
        // BLUETOOTH
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:switch|turn)\s+(?:the\s+)?bluetooth\s+on$"""
            ),
            "turn on bluetooth"
        )

        text = text.replace(
            Regex(
                """^(?:switch|turn)\s+(?:the\s+)?bluetooth\s+off$"""
            ),
            "turn off bluetooth"
        )

        // ---------------------------------------------------------
        // MOBILE DATA
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:switch|turn)\s+(?:the\s+)?(?:mobile\s+)?data\s+on$"""
            ),
            "turn on data"
        )

        text = text.replace(
            Regex(
                """^(?:switch|turn)\s+(?:the\s+)?(?:mobile\s+)?data\s+off$"""
            ),
            "turn off data"
        )

        // ---------------------------------------------------------
        // NAVIGATION
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:please\s+)?(?:take|navigate)\s+me\s+to\s+"""
            ),
            "navigate to "
        )

        text = text.replace(
            Regex(
                """^(?:how\s+do\s+i\s+get\s+to)\s+"""
            ),
            "navigate to "
        )

        text = text.replace(
            Regex(
                """^(?:give\s+me\s+directions\s+to)\s+"""
            ),
            "navigate to "
        )

        text = text.replace(
            Regex(
                """^(?:directions\s+to)\s+"""
            ),
            "navigate to "
        )

        // ---------------------------------------------------------
        // INFORMATION
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:tell\s+me)\s+(?:the\s+)?time$"""
            ),
            "what time is it"
        )

        text = text.replace(
            Regex(
                """^(?:what\s+time\s+is\s+it)$"""
            ),
            "what time is it"
        )

        text = text.replace(
            Regex(
                """^(?:tell\s+me)\s+(?:today'?s\s+)?date$"""
            ),
            "what is the date"
        )

        text = text.replace(
            Regex(
                """^(?:what\s+is\s+today'?s\s+date)$"""
            ),
            "what is the date"
        )

        text = text.replace(
            Regex(
                """^(?:how much\s+battery\s+do\s+i\s+have|what\s+is\s+my\s+battery|how much\s+battery)$"""
            ),
            "battery"
        )

        // ---------------------------------------------------------
        // MEDIA CONTROLS
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """^(?:skip|go)\s+(?:to\s+)?(?:the\s+)?next\s+(?:song|track|video)$"""
            ),
            "next"
        )

        text = text.replace(
            Regex(
                """^(?:go\s+)?(?:back\s+to\s+)?(?:the\s+)?previous\s+(?:song|track|video)$"""
            ),
            "previous"
        )

        text = text.replace(
            Regex(
                """^(?:stop|pause)\s+(?:the\s+)?(?:music|song|video|playing)$"""
            ),
            "pause"
        )

        text = text.replace(
            Regex(
                """^(?:continue|resume)\s+(?:the\s+)?(?:music|song|video|playing)$"""
            ),
            "resume"
        )

        // ---------------------------------------------------------
        // CONVERSATIONAL FILLERS
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """\b(?:actually|basically|simply|really|maybe|just|kindly)\b"""
            ),
            " "
        )

        // ---------------------------------------------------------
        // SPOKEN FORMS
        // ---------------------------------------------------------

        text = text.replace(
            Regex("""\bopen\s+up\s+"""),
            "open "
        )

        text = text.replace(
            Regex("""\bturn\s+on\s+the\s+"""),
            "turn on "
        )

        text = text.replace(
            Regex("""\bturn\s+off\s+the\s+"""),
            "turn off "
        )

        // ---------------------------------------------------------
        // REMOVE POLITE ENDINGS
        // ---------------------------------------------------------

        text = text.replace(
            Regex(
                """\s+(?:please|pls|plz|for\s+me|for\s+me\s+please)$"""
            ),
            ""
        )

        // ---------------------------------------------------------
        // CLEAN SPACES
        // ---------------------------------------------------------

        text = text
            .replace(Regex("""\s+"""), " ")
            .trim()

        return text
    }

    /**
     * Normalizes a knowledge question without applying command-specific
     * rewrites. This deliberately preserves words that carry meaning for
     * knowledge retrieval, especially current/former/previous and years.
     */
    fun normalizeQuestion(input: String): String {

        var text = input
            .lowercase()
            .trim()

        if (text.isEmpty()) return ""

        text = text.replace(
            Regex(
                """\b(?:hey\s+)?(?:sanya|sania|saniya|sonya|sonia|sunya)\b"""
            ),
            " "
        )

        text = text
            .replace("what's", "what is")
            .replace("whats", "what is")
            .replace("who's", "who is")
            .replace("whos", "who is")
            .replace("where's", "where is")
            .replace("wheres", "where is")
            .replace("when's", "when is")
            .replace("whens", "when is")
            .replace("i'm", "i am")
            .replace("you're", "you are")
            .replace("can't", "cannot")
            .replace("don't", "do not")
            .replace("it's", "it is")
            .replace("there's", "there is")

        text = text.replace(
            Regex("""[,.!?;:]+"""),
            " "
        )

        // Natural information-request wrappers. Do not remove meaningful
        // temporal/historical words such as current, now, former, ex,
        // previous, or a year.
        text = text.replace(
            Regex(
                """^(?:can|could|would|will)\s+you\s+(?:please\s+)?(?:tell\s+me|explain|describe|give\s+me|say|answer|identify)\s+"""
            ),
            ""
        )

        text = text.replace(
            Regex(
                """^(?:tell\s+me|give\s+me)\s+about\s+"""
            ),
            "what is "
        )

        text = text.replace(
            Regex(
                """^(?:give\s+me\s+)?(?:information|info|details)\s+(?:about|on)\s+"""
            ),
            "what is "
        )

        text = text.replace(
            Regex(
                """^(?:tell\s+me|give\s+me)\s+"""
            ),
            ""
        )

        text = text.replace(
            Regex(
                """^(?:do\s+you\s+know)\s+"""
            ),
            ""
        )

        text = text.replace(
            Regex(
                """^(?:i\s+want\s+to\s+know|i\s+would\s+like\s+to\s+know|i'd\s+like\s+to\s+know)\s+"""
            ),
            ""
        )

        text = text.replace(
            Regex("""^please\s+"""),
            ""
        )

        text = text.replace(
            Regex("""\s+(?:please|pls|plz)$"""),
            ""
        )

        return text
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * True when the input is a general/world-knowledge question rather than
     * one of Sanya's local commands, memory questions, or teaching phrases.
     */
    fun isGeneralQuestion(input: String): Boolean {

        val raw = normalizeQuestionBase(input)

        if (raw.isEmpty()) return false

        if (isProtectedLocalQuestion(raw)) {
            return false
        }

        if (isCommandRequest(raw)) {
            return false
        }

        val question = normalizeQuestion(raw)

        if (question.isEmpty()) return false

        if (
            Regex(
                """^(?:what|who|whom|when|where|why|how|which|whose)\b"""
            ).containsMatchIn(question)
        ) {
            return true
        }

        return Regex(
            """^(?:about\s+|explain|describe|define|identify|information\s+about|info\s+about|details\s+about)\b"""
        ).containsMatchIn(question)
    }

    /**
     * Detects questions whose answer can change over time. No person or
     * office-holder names are embedded here; only generic language patterns.
     */
    fun isTimeSensitiveQuestion(input: String): Boolean {

        val question = normalizeQuestion(input)

        if (question.isEmpty()) return false

        // Historical wording explicitly asks for a past state and should not
        // be treated as a request for the current value.
        val historical = Regex(
            """\b(?:former|ex|previous|past|historical|in\s+\d{4}|during\s+\d{4}|before\s+\d{4}|after\s+\d{4})\b"""
        ).containsMatchIn(question)

        if (historical) return false

        val explicitCurrent = Regex(
            """\b(?:current|currently|latest|today|now|present|presently|serving|incumbent|at\s+present|as\s+of\s+now)\b"""
        ).containsMatchIn(question)

        if (explicitCurrent) return true

        // By default, an office-holder question asks for the current office
        // holder unless a historical qualifier is supplied.
        return Regex(
            """^(?:who\s+is|who's|who)\b.*\b(?:president|prime\s+minister|chief\s+minister|governor|chancellor|king|queen|pope|chief\s+justice|mayor|ceo|chairman)\b"""
        ).containsMatchIn(question)
    }

    private fun normalizeQuestionBase(input: String): String {

        var text = input
            .lowercase()
            .trim()

        if (text.isEmpty()) return ""

        text = text.replace(
            Regex(
                """\b(?:hey\s+)?(?:sanya|sania|saniya|sonya|sonia|sunya)\b"""
            ),
            " "
        )

        return text
            .replace(Regex("""[,.!?;:]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun isProtectedLocalQuestion(
        raw: String
    ): Boolean {

        val q = normalizeQuestion(raw)

        if (q.isEmpty()) return false

        // Teaching phrases ("when i say X i mean Y") start with "when" but are
        // Sanya commands handled by CommandParser, not world-knowledge questions.
        if (
            Regex("""^when\s+i\s+say\b""").containsMatchIn(q)
        ) return true

        // Local clock/calendar questions only ("what time is it", "what is the
        // date", "what year is it"). Real knowledge questions that merely contain
        // one of these words ("what year did India become independent") must
        // still reach the knowledge pipeline.
        if (
            Regex(
                """^(?:what\s+(?:is|are)\s+(?:the\s+|today'?s\s+|today\s+|current\s+)*(?:time|date|day|month|year)(?:\s+(?:now|today|right\s+now))?|what\s+(?:time|day|month|year)\s+is\s+(?:it|today)(?:\s+now)?|which\s+day\s+is\s+(?:it|today))$"""
            ).matches(q)
        ) return true

        // "how much battery do i have", "what is my battery"
        if (
            Regex("""^(?:what|how)\b.*\bbattery\b""").containsMatchIn(q)
        ) return true

        // "what is your name" is answered locally by CommandParser.
        if (
            Regex("""\byour\s+name\b""").containsMatchIn(q)
        ) return true

        if (
            Regex(
                """^(?:what|who|how)\b.*\b(?:my|mine)\b"""
            ).containsMatchIn(q)
        ) return true

        if (
            Regex(
                """^(?:who\s+am\s+i|what\s+is\s+my\s+name|what\s+is\s+my\s+favourite|what\s+is\s+my\s+favorite)\b"""
            ).containsMatchIn(q)
        ) return true

        if (
            Regex(
                """^(?:how\s+are\s+you|what\s+can\s+you\s+do|help)$"""
            ).matches(q)
        ) return true

        if (
            Regex(
                """\b(?:my\s+notes?|my\s+todo|my\s+to-do|my\s+list|brain|what\s+did\s+you\s+(?:learn|know))\b"""
            ).containsMatchIn(q)
        ) return true

        if (
            Regex(
                """^(?:what\s+(?:is|are)|calculate|compute|how\s+much\s+is)\b.*(?:\d|plus|minus|times|divided|multiplied|percent)"""
            ).containsMatchIn(q)
        ) return true

        return false
    }

    private fun isCommandRequest(
        raw: String
    ): Boolean {

        val command = Regex(
            """^(?:(?:can|could|would|will)\s+you\s+(?:please\s+)?)?(?:open|launch|start|run|load|call|phone|ring|dial|message|text|sms|send|whatsapp|play|watch|search|google|find|look\s+up|navigate|go\s+to|take\s+me\s+to|set|turn|switch|enable|disable|add|put|remove|delete|clear|read|show\s+my|remember|remind|schedule|create|write|take\s+(?:a\s+)?(?:photo|picture|selfie)|calculate|compute|repeat|again)\b"""
        )

        return command.containsMatchIn(raw)
    }

    /**
     * Returns possible interpretations.
     */
    fun alternatives(input: String): List<String> {

        val primary = process(input)

        if (primary.isEmpty()) {
            return emptyList()
        }

        val results = mutableListOf(primary)

        if (primary.startsWith("watch ")) {
            results.add(
                "play " + primary.removePrefix("watch ").trim()
            )
        }

        if (primary.startsWith("find ")) {
            results.add(
                "search " + primary.removePrefix("find ").trim()
            )
        }

        if (primary.startsWith("look up ")) {
            results.add(
                "search " + primary.removePrefix("look up ").trim()
            )
        }

        return results
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
