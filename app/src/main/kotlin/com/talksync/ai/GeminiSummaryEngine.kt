package com.talksync.ai

// sends transcript text to gemini and asks for the short history summary/question stuff.

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiSummaryEngine {

    private val httpClient = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    fun summarizeConversation(
        transcript: String,
    ): String? {
        if (!GeminiConfig.isConfigured) {
            Log.w(TAG, "summarizeConversation: Gemini not configured")
            return null
        }
        val cleanedTranscript = cleanedTranscript(transcript) ?: return null
        val prompt = buildString {
            appendLine("One-line summary for a mobile history card. Max 10 words. No quotes, no labels, no punctuation at the end. Just the topic.")
            appendLine()
            appendLine("Transcript:")
            append(compactTranscript(cleanedTranscript))
        }

        return generateText(
            prompt = prompt,
            temperature = 0.2,
            maxOutputTokens = 48,
        )?.let(::sanitizeSummary)
    }

    fun translateText(text: String, sourceLanguage: String): String? {
        if (!GeminiConfig.isConfigured) return null
        val cleaned = text.trim().takeIf { it.isNotBlank() } ?: return null
        val prompt = "Translate the following $sourceLanguage text to English. " +
            "Return ONLY the translated text. " +
            "Do not add any explanation, prefix, label, or quotation marks. " +
            "Output nothing except the translation itself.\n\n$cleaned"
        return generateText(
            prompt = prompt,
            temperature = 0.1,
            maxOutputTokens = 256,
        )?.trim()?.takeIf { it.isNotBlank() }
    }

    fun answerQuestionAboutConversation(
        transcript: String,
        question: String,
        summaryHint: String? = null,
        toneHint: String? = null,
    ): String? {
        if (!GeminiConfig.isConfigured) {
            return null
        }

        val cleanedQuestion = question.trim().takeIf { it.isNotBlank() } ?: return null
        val cleanedTranscript = cleanedTranscript(transcript)
            ?: return null

        val prompt = buildString {
            appendLine("Answer the question about this conversation transcript. Be direct and brief.")
            appendLine("Use 2–4 bullet points starting with •. Each bullet max 10 words.")
            appendLine("Only use what's in the transcript. If the transcript doesn't support it, say so in one bullet.")
            summaryHint?.takeIf { it.isNotBlank() }?.let {
                appendLine("Context: $it")
            }
            toneHint?.takeIf { it.isNotBlank() }?.let {
                appendLine("Tone: $it")
            }
            appendLine()
            appendLine("Transcript:")
            appendLine(compactTranscript(cleanedTranscript))
            appendLine()
            appendLine("Question: $cleanedQuestion")
        }

        return generateText(
            prompt = prompt,
            temperature = 0.3,
            maxOutputTokens = 160,
        )?.let(::sanitizeAnswer)
    }

    private fun generateText(
        prompt: String,
        temperature: Double,
        maxOutputTokens: Int,
    ): String? {
        if (!GeminiConfig.isConfigured) {
            return null
        }

        val payload = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put("text", prompt),
                            ),
                        ),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", temperature)
                    .put("maxOutputTokens", maxOutputTokens),
            )

        val request = Request.Builder()
            .url("$GEMINI_URL?key=${GeminiConfig.API_KEY}")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini HTTP ${response.code}: $body")
                return null
            }
            if (body.isBlank()) {
                return null
            }

            val json = JSONObject(body)
            val candidates = json.optJSONArray("candidates") ?: run {
                Log.e(TAG, "Gemini response missing candidates: $body")
                return null
            }
            for (index in 0 until candidates.length()) {
                val candidate = candidates.optJSONObject(index) ?: continue
                val parts = candidate
                    .optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?: continue
                for (partIndex in 0 until parts.length()) {
                    val part = parts.optJSONObject(partIndex) ?: continue
                    val text = part.optString("text")
                    if (text.isNotBlank()) {
                        return text
                    }
                }
            }
        }

        Log.w(TAG, "generateText: no usable text found in Gemini response")
        return null
    }

    private fun cleanedTranscript(
        transcript: String,
    ): String? {
        return transcript
            .lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotBlank() && !line.startsWith("[pause ") }
            .joinToString(separator = "\n")
            .takeIf { it.isNotBlank() }
    }

    private fun compactTranscript(
        transcript: String,
    ): String {
        return if (transcript.length > 6_500) {
            transcript.take(2_500) + "\n...\n" + transcript.takeLast(4_000)
        } else {
            transcript
        }
    }

    private fun sanitizeSummary(
        raw: String,
    ): String {
        return raw
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .trim()
            .trim('"')
            .removePrefix("Summary:")
            .trim()
    }

    private fun sanitizeAnswer(
        raw: String,
    ): String {
        return raw
            .replace("\r\n", "\n")
            .lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotBlank() }
            .joinToString(separator = "\n")
            .trim()
    }

    private companion object {
        const val TAG = "GeminiSummaryEngine"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
    }
}
