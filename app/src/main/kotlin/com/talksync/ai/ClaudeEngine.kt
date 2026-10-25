package com.talksync.ai

// Replaces GeminiSummaryEngine. Uses claude-haiku-4-5 for fast translation,
// conversation summaries, and Q&A. Raw OkHttp — no extra SDK dep.

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.pow

data class ForeignTextTranslation(
    val translatedText: String,
    val detectedLanguage: String? = null,
)

class ClaudeEngine {

    private val httpClient = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    // ─── Public API (same signatures as GeminiSummaryEngine) ─────────────────

    fun summarizeConversation(transcript: String): String? {
        if (!ClaudeConfig.isConfigured) {
            Log.w(TAG, "summarizeConversation: Claude not configured")
            return null
        }
        val cleanedTranscript = cleanedTranscript(transcript) ?: return null
        val prompt = buildString {
            appendLine("One-line summary for a mobile history card. Max 10 words. No quotes, no labels, no punctuation at the end. Just the topic.")
            appendLine()
            appendLine("Transcript:")
            append(compactTranscript(cleanedTranscript))
        }
        return callClaude(
            prompt = prompt,
            maxTokens = 48,
        )?.let(::sanitizeSummary)
    }

    fun translateText(text: String, sourceLanguage: String, targetLanguage: String = ENGLISH): String? {
        if (!ClaudeConfig.isConfigured) return null
        val cleaned = text.trim().takeIf { it.isNotBlank() } ?: return null
        val prompt = "Translate this $sourceLanguage text to $targetLanguage:\n\n$cleaned"
        return callClaude(
            prompt = prompt,
            maxTokens = 256,
            system = translationSystem(targetLanguage),
        )?.let(::sanitizeTranslation)?.takeIf { it.isNotBlank() }
    }

    fun translateForeignTextIfPresent(text: String, targetLanguage: String = ENGLISH): ForeignTextTranslation? {
        if (!ClaudeConfig.isConfigured) return null
        val cleaned = text.trim().takeIf { it.isNotBlank() } ?: return null
        val prompt = if (targetLanguage != ENGLISH) translateIntoPrompt(cleaned, targetLanguage) else buildString {
            appendLine("Detect whether this transcript contains ANY non-English text, including one word, phrase, or code-switching.")
            appendLine("If every meaningful word is English, return exactly:")
            appendLine("""{"has_foreign_text":false}""")
            appendLine()
            appendLine("If there is any non-English text, translate only the non-English parts to English while preserving ALL surrounding English words/context.")
            appendLine("The translated_text value MUST be the entire transcript rewritten in English, not just the translated phrase.")
            appendLine("Example input: I will meet you at la biblioteca after work")
            appendLine("""Example output: {"has_foreign_text":true,"translated_text":"I will meet you at the library after work","detected_language":"es"}""")
            appendLine("Example input: that was très good")
            appendLine("""Example output: {"has_foreign_text":true,"translated_text":"that was very good","detected_language":"fr"}""")
            appendLine("Return ONLY compact JSON with these keys:")
            appendLine("""{"has_foreign_text":true,"translated_text":"...","detected_language":"..."}""")
            appendLine("Use a short ISO-like language code when you know it, otherwise use \"unknown\".")
            appendLine("Do not add markdown, explanation, labels, or quotes outside the JSON.")
            appendLine()
            appendLine("Transcript:")
            append(cleaned)
        }
        val raw = callClaude(
            prompt = prompt,
            maxTokens = 320,
        ) ?: return null
        val parsed = parseForeignTextTranslation(raw, cleaned) ?: return null
        return if (looksLikePartialTranslation(originalText = cleaned, translatedText = parsed.translatedText)) {
            repairContextualTranslation(
                originalText = cleaned,
                partialTranslation = parsed.translatedText,
                targetLanguage = targetLanguage,
            )
        } else {
            parsed
        }
    }

    fun answerQuestionAboutConversation(
        transcript: String,
        question: String,
        summaryHint: String? = null,
        toneHint: String? = null,
    ): String? {
        if (!ClaudeConfig.isConfigured) return null
        val cleanedQuestion = question.trim().takeIf { it.isNotBlank() } ?: return null
        val cleanedTranscript = cleanedTranscript(transcript) ?: return null
        val prompt = buildString {
            appendLine("Answer the question about this conversation transcript. Be direct and brief.")
            appendLine("Use 2–4 bullet points starting with •. Each bullet max 10 words.")
            appendLine("Only use what's in the transcript. If the transcript doesn't support it, say so in one bullet.")
            summaryHint?.takeIf { it.isNotBlank() }?.let { appendLine("Context: $it") }
            toneHint?.takeIf { it.isNotBlank() }?.let { appendLine("Tone: $it") }
            appendLine()
            appendLine("Transcript:")
            appendLine(compactTranscript(cleanedTranscript))
            appendLine()
            appendLine("Question: $cleanedQuestion")
        }
        return callClaude(
            prompt = prompt,
            maxTokens = 160,
        )?.let(::sanitizeAnswer)
    }

    // ─── Core HTTP call (with retry) ─────────────────────────────────────────

    /**
     * Calls Claude Haiku and retries up to [MAX_RETRIES] times on transient
     * network errors or non-5xx failures, with exponential backoff.
     * 429 (rate limit) and 5xx are retried; 4xx auth errors are not.
     */
    private fun callClaude(prompt: String, maxTokens: Int, system: String? = null): String? {
        if (!ClaudeConfig.isConfigured) return null

        val payload = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", maxTokens)
            .apply { if (!system.isNullOrBlank()) put("system", system) }
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", prompt),
                ),
            )
            .toString()

        repeat(MAX_RETRIES) { attempt ->
            val result = tryClaude(payload, maxTokens)
            if (result != null) return result
            if (attempt < MAX_RETRIES - 1) {
                val delayMs = (RETRY_BASE_MS * 2.0.pow(attempt)).toLong().coerceAtMost(3_000L)
                Thread.sleep(delayMs)
            }
        }
        Log.w(TAG, "callClaude: all $MAX_RETRIES attempts failed")
        return null
    }

    private fun tryClaude(payloadJson: String, maxTokens: Int): String? {
        val request = Request.Builder()
            .url(API_URL)
            .header("x-api-key", ClaudeConfig.API_KEY)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("content-type", "application/json")
            .post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code in 400..499 && response.code != 429 -> {
                        // Auth or bad request — no point retrying
                        Log.e(TAG, "Claude ${response.code} (non-retryable): $body")
                        return null.also { /* signal caller to stop */ }
                    }
                    !response.isSuccessful -> {
                        Log.w(TAG, "Claude HTTP ${response.code}: $body")
                        return null
                    }
                }
                if (body.isBlank()) return null
                val content = JSONObject(body).optJSONArray("content") ?: return null
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    if (block.optString("type") == "text") {
                        val text = block.optString("text")
                        if (text.isNotBlank()) return text
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "tryClaude: network error (attempt)", e)
            null
        }
    }

    // ─── Helpers (identical to GeminiSummaryEngine) ──────────────────────────

    private fun cleanedTranscript(transcript: String): String? =
        transcript
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("[pause ") }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    private fun compactTranscript(transcript: String): String =
        if (transcript.length > 6_500) {
            transcript.take(2_500) + "\n...\n" + transcript.takeLast(4_000)
        } else {
            transcript
        }

    private fun sanitizeSummary(raw: String): String =
        raw.lineSequence()
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .trim()
            .trim('"')
            .removePrefix("Summary:")
            .trim()

    private fun sanitizeAnswer(raw: String): String =
        raw.replace("\r\n", "\n")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()

    /**
     * Strips any conversational preamble Haiku occasionally adds ("Here is the
     * translation:", "Sure!", "Translation:") and surrounding quotes, leaving
     * only the translated text. Conservative: a preamble must be followed by a
     * colon/dash, so a translation that legitimately starts with one of these
     * words isn't clipped.
     */
    private fun sanitizeTranslation(raw: String): String {
        var text = raw.trim()
        text = TRANSLATION_PREAMBLE.replace(text, "").trim()
        // Drop wrapping quotes only if the whole string is quoted.
        text = text.trim('"', '“', '”', '‘', '’', '`').trim()
        return text
    }

    private fun parseForeignTextTranslation(raw: String, originalText: String): ForeignTextTranslation? {
        val jsonText = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
            .let { cleaned ->
                val start = cleaned.indexOf('{')
                val end = cleaned.lastIndexOf('}')
                if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
            }
        return runCatching {
            val json = JSONObject(jsonText)
            if (!json.optBoolean("has_foreign_text", false)) return@runCatching null
            val translated = json.optString("translated_text")
                .trim()
                .trim('"')
                .takeIf { it.isNotBlank() }
                ?: return@runCatching null
            if (translated.equals(originalText.trim(), ignoreCase = true)) return@runCatching null
            val language = json.optString("detected_language")
                .trim()
                .lowercase()
                .takeIf { it.isNotBlank() && it != "unknown" }
            ForeignTextTranslation(
                translatedText = translated,
                detectedLanguage = language,
            )
        }.getOrNull()
    }

    // Used when the target is not English: translate the whole transcript, and say nothing is to be left as it was.
    private fun translateIntoPrompt(transcript: String, targetLanguage: String): String = buildString {
        appendLine("Translate this transcript into $targetLanguage so a reader who only reads $targetLanguage can follow it.")
        appendLine("If every meaningful word is already $targetLanguage, return exactly:")
        appendLine("""{"has_foreign_text":false}""")
        appendLine()
        appendLine("Otherwise translate the ENTIRE transcript into $targetLanguage, keeping the meaning, tone, and names.")
        appendLine("Return ONLY compact JSON with these keys:")
        appendLine("""{"has_foreign_text":true,"translated_text":"...","detected_language":"..."}""")
        appendLine("detected_language is the language the transcript was spoken in, as a short ISO-like code such as \"en\", or \"unknown\".")
        appendLine("Do not add markdown, explanation, labels, or quotes outside the JSON.")
        appendLine()
        appendLine("Transcript:")
        append(transcript)
    }

    // Same system prompt as always for English; for any other target only the language name changes.
    private fun translationSystem(targetLanguage: String): String =
        if (targetLanguage == ENGLISH) TRANSLATION_SYSTEM else TRANSLATION_SYSTEM.replace(ENGLISH, targetLanguage)

    private fun repairContextualTranslation(
        originalText: String,
        partialTranslation: String,
        targetLanguage: String = ENGLISH,
    ): ForeignTextTranslation? {
        val prompt = buildString {
            appendLine("The previous translation only translated a fragment.")
            appendLine(
                if (targetLanguage == ENGLISH) {
                    "Return the ENTIRE original transcript in English, preserving all English context and replacing only non-English text with English."
                } else {
                    "Return the ENTIRE original transcript translated into $targetLanguage."
                },
            )
            appendLine("Return ONLY compact JSON:")
            appendLine("""{"has_foreign_text":true,"translated_text":"...","detected_language":"..."}""")
            appendLine()
            appendLine("Original transcript:")
            appendLine(originalText)
            appendLine()
            appendLine("Fragment translation:")
            append(partialTranslation)
        }
        val raw = callClaude(prompt = prompt, maxTokens = 320) ?: return null
        return parseForeignTextTranslation(raw, originalText)
            ?.takeUnless { looksLikePartialTranslation(originalText, it.translatedText) }
    }

    private fun looksLikePartialTranslation(
        originalText: String,
        translatedText: String,
    ): Boolean {
        val originalTokens = meaningfulTokens(originalText)
        val translatedTokens = meaningfulTokens(translatedText)
        if (originalTokens.size < 4 || translatedTokens.isEmpty()) return false
        if (translatedTokens.size > (originalTokens.size / 2).coerceAtLeast(2)) return false
        val sharedTokenCount = translatedTokens.count { it in originalTokens }
        return sharedTokenCount == 0
    }

    private fun meaningfulTokens(text: String): Set<String> {
        return text
            .lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in CONTEXT_STOP_WORDS }
            .toSet()
    }

    private companion object {
        const val TAG = "ClaudeEngine"
        const val ENGLISH = "English"
        const val MODEL = "claude-haiku-4-5"
        const val API_URL = "https://api.anthropic.com/v1/messages"
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MAX_RETRIES = 3
        const val RETRY_BASE_MS = 400L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // Forces Haiku to behave as a pure translation engine — no chatter.
        const val TRANSLATION_SYSTEM =
            "You are a translation engine. Output ONLY the English translation of the " +
                "user's text — nothing else. Never add preamble, explanations, notes, " +
                "labels, or quotation marks. Do not say things like \"Here is the " +
                "translation\". If the text is already English, output it unchanged. " +
                "Return only the translated sentence."

        // Leading conversational preamble that ends in a colon or dash (safe to drop).
        val TRANSLATION_PREAMBLE = Regex(
            "^\\s*(sure|okay|ok|certainly|here(?:'s| is)(?: the)?(?: english)?" +
                "(?: translation)?|the (?:english )?translation(?: is)?|translation|" +
                "translated(?: text)?|in english)\\s*[:\\-—]\\s*",
            RegexOption.IGNORE_CASE,
        )
        val CONTEXT_STOP_WORDS = setOf(
            "the",
            "and",
            "for",
            "you",
            "that",
            "this",
            "with",
            "was",
            "are",
            "but",
            "not",
        )
    }
}
