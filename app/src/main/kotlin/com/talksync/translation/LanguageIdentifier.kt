package com.talksync.translation

// quick guess for what language the caption text is before translation starts.

import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Identifies the language of finalized transcript text using ML Kit.
 *
 * This runs on-device so it works with Deepgram's streaming `language=multi`
 * mode, which does not return a detected language for each finalized result.
 */
object LanguageIdentifier {
    private const val TAG = "LanguageIdentifier"
    private const val MIN_CONFIDENCE = 0.50f  // loosened: short phrases have lower confidence
    private const val MIN_TEXT_LENGTH = 3      // catch "Sí", "Hola", "Oui" etc.

    @Volatile
    private var identifier: com.google.mlkit.nl.languageid.LanguageIdentifier? = createClient()

    private fun createClient() = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(MIN_CONFIDENCE)
            .build(),
    )

    private fun client() = identifier ?: synchronized(this) {
        identifier ?: createClient().also { identifier = it }
    }

    suspend fun identify(text: String): String? {
        if (text.trim().length < MIN_TEXT_LENGTH) {
            return null
        }

        return suspendCancellableCoroutine { continuation ->
            client().identifyLanguage(text)
                .addOnSuccessListener { languageCode ->
                    val result = if (
                        languageCode.isNullOrBlank() ||
                        languageCode == "und"
                    ) {
                        null
                    } else {
                        languageCode.take(2).lowercase()
                    }
                    Log.d(TAG, "Identified language: $result for text: ${text.take(30)}")
                    continuation.resume(result)
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Language identification failed", error)
                    continuation.resume(null)
                }
        }
    }

    suspend fun warmUp() {
        try {
            identify("hello this is a warmup phrase")
        } catch (_: Exception) {
        }
    }

    fun release() {
        synchronized(this) {
            identifier?.close()
            identifier = null
        }
    }
}
