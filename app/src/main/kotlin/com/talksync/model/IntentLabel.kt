package com.talksync.model

// tiny rule-based labeler for questions, jokes, important stuff, etc. fast and testable.

enum class IntentLabel(val displayText: String) {
    QUESTION("question"),
    INSTRUCTION("instruction"),
    JOKE("joke"),
    IMPORTANT("important"),
}

object IntentLabeler {
    private val questionStarters = setOf(
        "what",
        "why",
        "how",
        "who",
        "where",
        "when",
        "which",
        "can",
        "could",
        "would",
        "should",
        "do",
        "does",
        "did",
        "is",
        "are",
        "was",
        "were",
        "will",
        "have",
        "has",
    )

    private val instructionPhrases = listOf(
        "please",
        "make sure",
        "remember to",
        "don't",
        "do not",
        "need you to",
        "you need to",
        "you should",
        "let's",
        "send",
        "call",
        "check",
        "look",
        "turn",
        "open",
        "close",
        "bring",
        "go",
        "stop",
        "start",
        "wait",
        "try",
    )

    private val jokePhrases = listOf(
        "lol",
        "haha",
        "lmao",
        "jk",
        "kidding",
        "joke",
        "funny",
        "hilarious",
        "just messing",
        "knock knock",
    )

    private val importantPhrases = listOf(
        "important",
        "urgent",
        "asap",
        "critical",
        "deadline",
        "careful",
        "remember",
        "don't forget",
        "do not forget",
        "must",
        "have to",
        "need to",
        "emergency",
        "seriously",
    )

    fun labelsFor(text: String): List<IntentLabel> {
        val clean = text.trim()
        if (clean.isBlank()) {
            return emptyList()
        }

        val lower = clean.lowercase()
        val labels = mutableListOf<IntentLabel>()
        if (isQuestion(clean, lower)) labels += IntentLabel.QUESTION
        if (containsPhrase(lower, instructionPhrases)) labels += IntentLabel.INSTRUCTION
        if (containsPhrase(lower, jokePhrases)) labels += IntentLabel.JOKE
        if (containsPhrase(lower, importantPhrases)) labels += IntentLabel.IMPORTANT
        return labels.distinct()
    }

    private fun isQuestion(clean: String, lower: String): Boolean {
        if (clean.endsWith("?")) {
            return true
        }
        val firstWord = lower
            .takeWhile { character -> character.isLetter() || character == '\'' }
            .trim('\'')
        return firstWord in questionStarters
    }

    private fun containsPhrase(
        lower: String,
        phrases: List<String>,
    ): Boolean {
        return phrases.any { phrase ->
            Regex("(^|\\s|[,.!?;:])${Regex.escape(phrase)}($|\\s|[,.!?;:])")
                .containsMatchIn(lower)
        }
    }
}
