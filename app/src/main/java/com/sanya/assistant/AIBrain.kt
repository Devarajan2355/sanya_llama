package com.sanya.assistant

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Sanya Offline AI Brain
 *
 * Works without:
 * - API key
 * - Internet
 * - Gemini
 * - external AI service
 *
 * This is the fallback brain for questions that the normal
 * command parser does not understand.
 */
object AIBrain {

    /**
     * Main entry point.
     *
     * Returns an answer if Sanya can handle the question offline.
     * Returns null when the question needs a real AI/Internet brain.
     */
    fun answer(input: String): String? {

        var text = input
            .lowercase(Locale.getDefault())
            .trim()

        if (text.isEmpty()) {
            return null
        }

        // Remove common wake words.
        text = text.replace(
            Regex("""\b(?:hey\s+)?(?:sanya|sania|saniya|sonya|sonia|sunya)\b"""),
            " "
        )

        text = text
            .replace(Regex("""[,.!?;:]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (text.isEmpty()) {
            return "Yes, I'm listening."
        }

        // ---------------------------------------------------------
        // GREETINGS
        // ---------------------------------------------------------

        if (Regex(
                """^(?:hi|hello|hey|good morning|good afternoon|good evening)$"""
            ).matches(text)
        ) {
            return "Hello. How can I help you?"
        }

        // ---------------------------------------------------------
        // IDENTITY
        // ---------------------------------------------------------

        if (
            text.contains("who are you") ||
            text.contains("what are you") ||
            text == "your name"
        ) {
            return "I am Sanya, your Android voice assistant."
        }

        if (
            text.contains("who made you") ||
            text.contains("who created you")
        ) {
            return "I am Sanya, a custom Android voice assistant."
        }

        // ---------------------------------------------------------
        // TIME
        // ---------------------------------------------------------

        if (
            text == "time" ||
            text.contains("what time is it") ||
            text.contains("tell me the time") ||
            text.contains("current time")
        ) {
            val formatter = SimpleDateFormat(
                "hh:mm a",
                Locale.getDefault()
            )

            return "The current time is ${formatter.format(Calendar.getInstance().time)}."
        }

        // ---------------------------------------------------------
        // DATE
        // ---------------------------------------------------------

        if (
            text == "date" ||
            text.contains("what is the date") ||
            text.contains("what's the date") ||
            text.contains("today's date") ||
            text.contains("today date")
        ) {
            val formatter = SimpleDateFormat(
                "EEEE, dd MMMM yyyy",
                Locale.getDefault()
            )

            return "Today is ${formatter.format(Calendar.getInstance().time)}."
        }

        // ---------------------------------------------------------
        // DAY
        // ---------------------------------------------------------

        if (
            text.contains("what day is it") ||
            text.contains("which day is today") ||
            text == "what day"
        ) {
            val formatter = SimpleDateFormat(
                "EEEE",
                Locale.getDefault()
            )

            return "Today is ${formatter.format(Calendar.getInstance().time)}."
        }

        // ---------------------------------------------------------
        // MONTH
        // ---------------------------------------------------------

        if (
            text.contains("what month is it") ||
            text.contains("current month")
        ) {
            val formatter = SimpleDateFormat(
                "MMMM",
                Locale.getDefault()
            )

            return "The current month is ${formatter.format(Calendar.getInstance().time)}."
        }

        // ---------------------------------------------------------
        // YEAR
        // ---------------------------------------------------------

        if (
            text.contains("what year is it") ||
            text.contains("current year")
        ) {
            val formatter = SimpleDateFormat(
                "yyyy",
                Locale.getDefault()
            )

            return "The current year is ${formatter.format(Calendar.getInstance().time)}."
        }

        // ---------------------------------------------------------
        // BASIC PERSONALITY QUESTIONS
        // ---------------------------------------------------------

        if (
            text.contains("how are you") ||
            text.contains("are you okay")
        ) {
            return "I'm working perfectly and ready to help."
        }

        if (
            text == "thank you" ||
            text == "thanks" ||
            text.contains("thanks sanya")
        ) {
            return "You're welcome."
        }

        if (
            text == "good job" ||
            text == "well done"
        ) {
            return "Thank you. I'm glad I could help."
        }

        // ---------------------------------------------------------
        // HELP
        // ---------------------------------------------------------

        if (
            text == "help" ||
            text == "what can you do" ||
            text == "what can you do for me"
        ) {
            return """
                I can open apps, make calls, send messages, search, 
                navigate, control some phone functions, set timers 
                and reminders, take notes, and answer basic questions.
            """.trimIndent()
        }

        // ---------------------------------------------------------
        // SIMPLE MATH
        // ---------------------------------------------------------

        val mathAnswer = calculate(text)

        if (mathAnswer != null) {
            return mathAnswer
        }

        // ---------------------------------------------------------
        // SIMPLE GENERAL KNOWLEDGE
        // ---------------------------------------------------------

        if (
            text.contains("capital of india")
        ) {
            return "The capital of India is New Delhi."
        }

        if (
            text.contains("capital of tamil nadu")
        ) {
            return "The capital of Tamil Nadu is Chennai."
        }

        if (
            text.contains("largest planet")
        ) {
            return "Jupiter is the largest planet in our solar system."
        }

        if (
            text.contains("red planet")
        ) {
            return "Mars is known as the Red Planet."
        }

        if (
            text.contains("nearest planet to the sun")
        ) {
            return "Mercury is the nearest planet to the Sun."
        }

        if (
            text.contains("how many planets")
        ) {
            return "There are eight planets in our solar system."
        }

        if (
            text.contains("speed of light")
        ) {
            return "The speed of light in vacuum is approximately 300,000 kilometres per second."
        }

        // Word boundaries: plain contains("what is ai") also matched
        // "what is air", "what is aim", "what is aid", ...
        if (
            Regex("""\b(?:meaning of ai|what is ai|what does ai mean)\b""")
                .containsMatchIn(text)
        ) {
            return "AI stands for Artificial Intelligence. It is technology that enables computers to perform tasks that normally require human intelligence."
        }

        if (
            text.contains("meaning of android") ||
            text == "what is android"
        ) {
            return "Android is a mobile operating system developed primarily for smartphones and other devices."
        }

        // ---------------------------------------------------------
        // FALLBACK
        // ---------------------------------------------------------

        return null
    }

    /**
     * Basic offline calculator.
     *
     * Supports:
     * 2 + 3
     * 10 - 4
     * 5 * 6
     * 20 / 4
     * 10 plus 5
     * 20 divided by 4
     */
    private fun calculate(input: String): String? {

        var text = input
            .replace("plus", "+")
            .replace("minus", "-")
            .replace("times", "*")
            .replace("multiplied by", "*")
            .replace("divided by", "/")
            .replace("divide", "/")
            .trim()

        text = text.replace(
            Regex("""^(?:calculate|what is|what's)\s+"""),
            ""
        ).trim()

        val match = Regex(
            """^(-?\d+(?:\.\d+)?)\s*([+\-*/])\s*(-?\d+(?:\.\d+)?)$"""
        ).find(text) ?: return null

        val first = match.groupValues[1].toDoubleOrNull()
            ?: return null

        val operator = match.groupValues[2]

        val second = match.groupValues[3].toDoubleOrNull()
            ?: return null

        val result = when (operator) {

            "+" -> first + second

            "-" -> first - second

            "*" -> first * second

            "/" -> {
                if (second == 0.0) {
                    return "I cannot divide by zero."
                }

                first / second
            }

            else -> return null
        }

        return "The answer is ${formatNumber(result)}."
    }

    /**
     * Makes calculator output easier to speak.
     *
     * 5.0 -> 5
     * 2.5 -> 2.5
     */
    private fun formatNumber(value: Double): String {

        return if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            String.format(
                Locale.US,
                "%.4f",
                value
            ).trimEnd('0')
                .trimEnd('.')
        }
    }
}
