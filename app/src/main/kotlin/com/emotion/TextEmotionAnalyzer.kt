package com.talksync.emotion

// backup emotion guesser from text only, for when the fancy service is not around.

object TextEmotionAnalyzer {

    data class TextSignal(
        val label: ToneLabel,
        val confidence: Float,
        val evidence: String,
    )

    private val joyfulWords = setOf(
        "amazing", "awesome", "excited", "fun", "glad", "great", "happy", "joy", "love",
        "nice", "perfect", "sweet", "wonderful", "yay", "yes",
    )
    private val warmWords = setOf(
        "appreciate", "care", "dear", "gentle", "grateful", "hello", "hope", "kind", "please",
        "sorry", "thanks", "thank", "welcome",
    )
    private val frustratedWords = setOf(
        "angry", "annoying", "awful", "bad", "damn", "frustrated", "hate", "hurry",
        "ridiculous", "seriously", "shut", "stop", "terrible", "ugh", "upset",
    )
    private val sadWords = setOf(
        "alone", "cry", "disappointed", "down", "grief", "hurt", "miss", "sad", "sorry",
        "tired", "unhappy", "wish",
    )
    private val fearfulWords = setOf(
        "afraid", "anxious", "careful", "danger", "nervous", "panic", "scared", "stress",
        "stressed", "worried",
    )
    private val surprisedWords = setOf(
        "actually", "incredible", "no way", "really", "seriously", "suddenly", "surprised",
        "unexpected", "unreal", "wait", "whoa", "wow",
    )
    private val confusedWords = setOf(
        "again", "confused", "don't understand", "explain", "huh", "maybe", "not sure",
        "repeat", "unclear", "unsure", "what", "why",
    )

    fun analyze(text: String): TextSignal? {
        val normalized = text.trim().lowercase()
        if (normalized.isBlank()) {
            return null
        }

        val tokens = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size < 2) {
            return null
        }

        val matches = listOfNotNull(
            scoreLabel(normalized, tokens, ToneLabel.JOYFUL, joyfulWords),
            scoreLabel(normalized, tokens, ToneLabel.WARM, warmWords),
            scoreLabel(normalized, tokens, ToneLabel.FRUSTRATED, frustratedWords),
            scoreLabel(normalized, tokens, ToneLabel.SAD, sadWords),
            scoreLabel(normalized, tokens, ToneLabel.FEARFUL, fearfulWords),
            scoreLabel(normalized, tokens, ToneLabel.SURPRISED, surprisedWords),
            scoreLabel(normalized, tokens, ToneLabel.CONFUSED, confusedWords),
        )

        val winner = matches.maxByOrNull { it.confidence } ?: return null

        val punctuationBoost = when {
            winner.label == ToneLabel.SURPRISED && normalized.contains("!") -> 0.08f
            winner.label == ToneLabel.CONFUSED && normalized.contains("?") -> 0.08f
            winner.label == ToneLabel.FRUSTRATED && normalized.contains("!") -> 0.05f
            else -> 0f
        }

        val finalConfidence = (winner.confidence + punctuationBoost).coerceIn(0f, 1f)
        return if (finalConfidence >= 0.16f) {
            winner.copy(confidence = finalConfidence)
        } else {
            null
        }
    }

    private fun scoreLabel(
        normalizedText: String,
        tokens: List<String>,
        label: ToneLabel,
        phrases: Set<String>,
    ): TextSignal? {
        val cleanedTokens = tokens.map { token -> token.trim { !it.isLetterOrDigit() && it != '\'' } }
        val matches = phrases.filter { phrase ->
            if (phrase.contains(' ')) {
                normalizedText.contains(phrase)
            } else {
                cleanedTokens.any { it == phrase }
            }
        }

        if (matches.isEmpty()) {
            return null
        }

        val baseConfidence = when (matches.size) {
            1 -> 0.22f
            2 -> 0.36f
            else -> 0.48f
        }

        return TextSignal(
            label = label,
            confidence = baseConfidence,
            evidence = matches.first(),
        )
    }
}
