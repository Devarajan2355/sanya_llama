package com.sanya.assistant

/**
 * SanyaResponseEngine
 *
 * Validates retrieved knowledge and turns it into a clean, speakable reply.
 *
 * Used by StepRunner (SanyaCommands.kt):
 *   SanyaResponseEngine.answer(question, retrievedText)  -> String?
 *
 * Returns null when the retrieved text is empty or clearly unrelated to the
 * question, so the caller can say "I couldn't find a reliable answer".
 *
 * Separate from:
 * - IntentEngine.kt   -> decides whether the input is a general question
 * - SanyaCommands.kt  -> retrieves knowledge (cache, AIBrain, WebSearch)
 * - AIBrain.kt        -> built-in offline facts
 *
 * Contains no hard-coded names of presidents, ministers, celebrities, etc.
 *
 * IMPORTANT: answer() must be idempotent. StepRunner re-runs cached answers
 * through it, so running it on its own output must not change the text.
 */
object SanyaResponseEngine {

    private const val NO_ANSWER = "I couldn't find a reliable answer to that."

    /**
     * Main entry point used by SanyaCommands.
     *
     * @param question  the user's question (any case)
     * @param retrieved raw text from the knowledge system
     * @return a natural reply that keeps the original capitalisation,
     *         or null if the text is empty / unrelated.
     */
    fun answer(question: String, retrieved: String): String? {

        val q = lower(question)
        val text = removeNoise(retrieved)

        if (text.isEmpty()) return null

        // Reject bad retrievals such as
        //   "What is the capital of Japan?" -> "Capital punishment is ..."
        if (looksUnrelated(q, text.lowercase())) return null

        return makeNaturalResponse(q, text)
    }

    /**
     * Convenience wrapper that always returns speakable text.
     */
    fun createResponse(question: String, retrieved: String): String =
        answer(question, retrieved) ?: NO_ANSWER

    // -------------------------------------------------------------
    // Natural response
    // -------------------------------------------------------------

    private fun makeNaturalResponse(question: String, retrieved: String): String {

        val text = sentence(retrieved)

        // Already a full sentence ("The capital of India is New Delhi.")
        // or already wrapped by an earlier pass: leave it alone.
        if (alreadyConversational(text)) return text

        // A bare name or phrase in answer to "who ..." ("Droupadi Murmu").
        val isWho = question.startsWith("who ") || question.startsWith("whom ")
        val wordCount = text.trimEnd('.', '!', '?').split(" ").size

        return if (isWho && wordCount <= 5) {
            "That would be $text"
        } else {
            text
        }
    }

    private val CONVERSATIONAL = Regex(
        """(?i)\b(?:is|are|was|were|has|had|have|means|refers|born|known|called|located|founded|became|serves?|served|would\s+be)\b"""
    )

    private fun alreadyConversational(text: String): Boolean =
        CONVERSATIONAL.containsMatchIn(text)

    // -------------------------------------------------------------
    // Text cleaning
    // -------------------------------------------------------------

    private fun removeNoise(input: String): String {

        var text = input.trim()

        // Common search/API prefixes.
        text = text.replace(
            Regex("""(?i)^(?:answer|result|definition|description)\s*:\s*"""),
            ""
        )

        // Repeated whitespace.
        text = text.replace(Regex("""\s+"""), " ")

        // Repeated sentence punctuation (but keep "..." from truncation).
        text = text.replace(Regex("""([!?])\1+"""), "$1")

        return text.trim()
    }

    private fun sentence(input: String): String {

        val text = input.trim().replace(Regex("""\s+"""), " ")

        if (text.isEmpty()) return ""

        return if (text.endsWith(".") || text.endsWith("?") || text.endsWith("!")) {
            text
        } else {
            "$text."
        }
    }

    private fun lower(input: String): String =
        input.lowercase().replace(Regex("""\s+"""), " ").trim()

    // -------------------------------------------------------------
    // Retrieval validation (all lowercase inputs)
    // -------------------------------------------------------------

    /**
     * Detects obvious cases where the retrieved text does not match
     * the question. Deliberately generic.
     */
    private fun looksUnrelated(question: String, answer: String): Boolean {

        if (question.isEmpty() || answer.isEmpty()) return true

        // ---------------------------------------------------------
        // Capital-city question answered with "capital punishment"
        // ---------------------------------------------------------

        if (
            Regex("""^(?:what\s+is\s+)?the\s+capital(?:\s+city)?\s+of\s+.+""")
                .matches(question)
        ) {
            if (
                answer.contains("capital punishment") ||
                answer.contains("legal penalty") ||
                answer.contains("death penalty")
            ) {
                return true
            }
        }

        // ---------------------------------------------------------
        // Keyword overlap: if none of the question's important words
        // appear in the answer, the result is suspicious.
        // ---------------------------------------------------------

        val normalizedQuestion = squash(question)
        val normalizedAnswer = squash(answer)

        if (normalizedQuestion.isEmpty()) return false

        val words = normalizedQuestion
            .split(" ")
            .filter { it.length >= 4 && it !in QUESTION_WORDS }

        if (words.size >= 3) {
            val matching = words.count {
                normalizedAnswer.contains(Regex("""\b${Regex.escape(it)}\b"""))
            }
            if (matching == 0) return true
        }

        return false
    }

    private fun squash(s: String): String =
        s.replace(Regex("""[^a-z0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private val QUESTION_WORDS = setOf(
        "what", "when", "where", "who", "whom", "whose", "which", "why", "how",
        "is", "are", "was", "were", "the", "a", "an", "of", "to", "in", "on", "for",
        "does", "did", "do", "can", "could", "would", "will",
        "please", "tell", "about", "give", "me"
    )
}
