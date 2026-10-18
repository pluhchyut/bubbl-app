package com.talksync.translation

// glues language guessing, model readiness, and translation into live caption turns.

import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Manages ML Kit on-device translation.
 *
 * Default target language is English. Translation is shown in the bubble
 * and the user can toggle to see the original via a button in the UI.
 *
 * Language models download automatically on first use (~30MB each).
 * No internet required after download.
 */
class LanguageTranslationManager {

    private val TAG = "LanguageTranslationManager"

    // Cache translators keyed by "source->target" e.g. "es->en"
    private val translatorCache = mutableMapOf<String, Translator>()
    private val readyModelKeys = ConcurrentHashMap.newKeySet<String>()

    // Always translating into English for now
    // Easy to make configurable later if needed
    private val targetLanguageCode = "en"

    /**
     * Pre-download translation models for common languages so they're ready
     * when speech is first heard. Call this in the background at session start.
     */
    suspend fun warmUpCommonLanguages() {
        val common = listOf("es", "fr", "de", "pt", "zh", "ja", "ko", "ar", "hi")
        for (code in common) {
            val mlKitCode = toMlKitCode(code) ?: continue
            val cacheKey = "$mlKitCode->$targetLanguageCode"
            val translator = translatorCache.getOrPut(cacheKey) {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(mlKitCode)
                    .setTargetLanguage(TranslateLanguage.ENGLISH)
                    .build()
                Translation.getClient(options)
            }
            try {
                ensureModelDownloaded(translator, cacheKey)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Translate text from the detected source language into English.
     * Returns null if:
     *   - source is already English
     *   - source language is unsupported by ML Kit
     *   - translation fails for any reason
     *
     * @param text           Raw transcript text from Deepgram
     * @param sourceLanguage BCP-47 code returned by Deepgram e.g. "es", "fr", "zh"
     */
    suspend fun translate(text: String, sourceLanguage: String): String? {
        val sourceLower = sourceLanguage.lowercase().take(2)

        if (sourceLower == targetLanguageCode) return null
        if (text.isBlank()) return null

        val mlKitSource = toMlKitCode(sourceLower) ?: run {
            Log.w(TAG, "Unsupported source language for translation: $sourceLower")
            return null
        }

        val cacheKey = "$mlKitSource->$targetLanguageCode"
        val translator = translatorCache.getOrPut(cacheKey) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(mlKitSource)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            Translation.getClient(options)
        }

        return try {
            ensureModelDownloaded(translator, cacheKey)
            suspendCancellableCoroutine { continuation ->
                translator.translate(text)
                    .addOnSuccessListener { result -> continuation.resume(result) }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Translation failed", e)
                        continuation.resume(null)
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Translation error", e)
            null
        }
    }

    /**
     * Returns true if this language is English and no translation is needed.
     */
    fun isEnglish(bcp47Code: String): Boolean {
        return bcp47Code.lowercase().take(2) == "en"
    }

    fun release() {
        translatorCache.values.forEach { it.close() }
        translatorCache.clear()
        readyModelKeys.clear()
    }

    private suspend fun ensureModelDownloaded(
        translator: Translator,
        cacheKey: String,
    ) {
        if (cacheKey in readyModelKeys) {
            return
        }
        suspendCancellableCoroutine { continuation ->
            translator.downloadModelIfNeeded()
                .addOnSuccessListener {
                    readyModelKeys.add(cacheKey)
                    continuation.resume(Unit)
                }
                .addOnFailureListener { continuation.resume(Unit) }
        }
    }

    private fun toMlKitCode(code: String): String? = when (code) {
        "af" -> TranslateLanguage.AFRIKAANS
        "ar" -> TranslateLanguage.ARABIC
        "be" -> TranslateLanguage.BELARUSIAN
        "bg" -> TranslateLanguage.BULGARIAN
        "bn" -> TranslateLanguage.BENGALI
        "ca" -> TranslateLanguage.CATALAN
        "cs" -> TranslateLanguage.CZECH
        "cy" -> TranslateLanguage.WELSH
        "da" -> TranslateLanguage.DANISH
        "de" -> TranslateLanguage.GERMAN
        "el" -> TranslateLanguage.GREEK
        "en" -> TranslateLanguage.ENGLISH
        "eo" -> TranslateLanguage.ESPERANTO
        "es" -> TranslateLanguage.SPANISH
        "et" -> TranslateLanguage.ESTONIAN
        "fa" -> TranslateLanguage.PERSIAN
        "fi" -> TranslateLanguage.FINNISH
        "fr" -> TranslateLanguage.FRENCH
        "ga" -> TranslateLanguage.IRISH
        "gl" -> TranslateLanguage.GALICIAN
        "gu" -> TranslateLanguage.GUJARATI
        "he" -> TranslateLanguage.HEBREW
        "hi" -> TranslateLanguage.HINDI
        "hr" -> TranslateLanguage.CROATIAN
        "ht" -> TranslateLanguage.HAITIAN_CREOLE
        "hu" -> TranslateLanguage.HUNGARIAN
        "id" -> TranslateLanguage.INDONESIAN
        "is" -> TranslateLanguage.ICELANDIC
        "it" -> TranslateLanguage.ITALIAN
        "ja" -> TranslateLanguage.JAPANESE
        "ka" -> TranslateLanguage.GEORGIAN
        "kn" -> TranslateLanguage.KANNADA
        "ko" -> TranslateLanguage.KOREAN
        "lt" -> TranslateLanguage.LITHUANIAN
        "lv" -> TranslateLanguage.LATVIAN
        "mk" -> TranslateLanguage.MACEDONIAN
        "mr" -> TranslateLanguage.MARATHI
        "ms" -> TranslateLanguage.MALAY
        "mt" -> TranslateLanguage.MALTESE
        "nl" -> TranslateLanguage.DUTCH
        "no" -> TranslateLanguage.NORWEGIAN
        "pl" -> TranslateLanguage.POLISH
        "pt" -> TranslateLanguage.PORTUGUESE
        "ro" -> TranslateLanguage.ROMANIAN
        "ru" -> TranslateLanguage.RUSSIAN
        "sk" -> TranslateLanguage.SLOVAK
        "sl" -> TranslateLanguage.SLOVENIAN
        "sq" -> TranslateLanguage.ALBANIAN
        "sv" -> TranslateLanguage.SWEDISH
        "sw" -> TranslateLanguage.SWAHILI
        "ta" -> TranslateLanguage.TAMIL
        "te" -> TranslateLanguage.TELUGU
        "th" -> TranslateLanguage.THAI
        "tl" -> TranslateLanguage.TAGALOG
        "tr" -> TranslateLanguage.TURKISH
        "uk" -> TranslateLanguage.UKRAINIAN
        "ur" -> TranslateLanguage.URDU
        "vi" -> TranslateLanguage.VIETNAMESE
        "zh" -> TranslateLanguage.CHINESE
        else -> null
    }
}
