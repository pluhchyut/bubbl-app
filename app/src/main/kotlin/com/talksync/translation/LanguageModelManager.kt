package com.talksync.translation

// handles ml kit model downloads so the ui can just ask "is this pair ready".

import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Manages downloading and deletion of ML Kit translation language models.
 *
 * All 45 ML Kit supported languages are listed here with their display names,
 * native names, and flag emojis for the settings UI.
 *
 * Each language model is ~30MB and downloads once, then cached permanently
 * on device storage until explicitly deleted.
 *
 * Download state per language is tracked in downloadStates StateFlow
 * so the UI can show progress indicators reactively.
 */
object LanguageModelManager {
    private val TAG = "LanguageModelManager"

    enum class DownloadState {
        NOT_DOWNLOADED,
        DOWNLOADING,
        DOWNLOADED,
        FAILED,
    }

    data class LanguageInfo(
        val code: String,           // BCP-47 2-letter code
        val mlKitCode: String,      // ML Kit TranslateLanguage constant
        val displayName: String,    // English name
        val nativeName: String,     // Name in that language
        val flag: String,           // Flag emoji
    )

    // All 45 ML Kit supported translation languages
    val allLanguages: List<LanguageInfo> = listOf(
        LanguageInfo("af", TranslateLanguage.AFRIKAANS,    "Afrikaans",   "Afrikaans",        "🇿🇦"),
        LanguageInfo("ar", TranslateLanguage.ARABIC,       "Arabic",      "العربية",           "🇸🇦"),
        LanguageInfo("be", TranslateLanguage.BELARUSIAN,   "Belarusian",  "Беларуская",        "🇧🇾"),
        LanguageInfo("bg", TranslateLanguage.BULGARIAN,    "Bulgarian",   "Български",         "🇧🇬"),
        LanguageInfo("bn", TranslateLanguage.BENGALI,      "Bengali",     "বাংলা",             "🇧🇩"),
        LanguageInfo("ca", TranslateLanguage.CATALAN,      "Catalan",     "Català",            "🏴󠁥󠁳󠁣󠁴󠁿"),
        LanguageInfo("cs", TranslateLanguage.CZECH,        "Czech",       "Čeština",           "🇨🇿"),
        LanguageInfo("cy", TranslateLanguage.WELSH,        "Welsh",       "Cymraeg",           "🏴󠁧󠁢󠁷󠁬󠁳󠁿"),
        LanguageInfo("da", TranslateLanguage.DANISH,       "Danish",      "Dansk",             "🇩🇰"),
        LanguageInfo("de", TranslateLanguage.GERMAN,       "German",      "Deutsch",           "🇩🇪"),
        LanguageInfo("el", TranslateLanguage.GREEK,        "Greek",       "Ελληνικά",          "🇬🇷"),
        LanguageInfo("eo", TranslateLanguage.ESPERANTO,    "Esperanto",   "Esperanto",         "🌍"),
        LanguageInfo("es", TranslateLanguage.SPANISH,      "Spanish",     "Español",           "🇪🇸"),
        LanguageInfo("et", TranslateLanguage.ESTONIAN,     "Estonian",    "Eesti",             "🇪🇪"),
        LanguageInfo("fa", TranslateLanguage.PERSIAN,      "Persian",     "فارسی",             "🇮🇷"),
        LanguageInfo("fi", TranslateLanguage.FINNISH,      "Finnish",     "Suomi",             "🇫🇮"),
        LanguageInfo("fr", TranslateLanguage.FRENCH,       "French",      "Français",          "🇫🇷"),
        LanguageInfo("ga", TranslateLanguage.IRISH,        "Irish",       "Gaeilge",           "🇮🇪"),
        LanguageInfo("gl", TranslateLanguage.GALICIAN,     "Galician",    "Galego",            "🇪🇸"),
        LanguageInfo("gu", TranslateLanguage.GUJARATI,     "Gujarati",    "ગુજરાતી",           "🇮🇳"),
        LanguageInfo("he", TranslateLanguage.HEBREW,       "Hebrew",      "עברית",             "🇮🇱"),
        LanguageInfo("hi", TranslateLanguage.HINDI,        "Hindi",       "हिन्दी",            "🇮🇳"),
        LanguageInfo("hr", TranslateLanguage.CROATIAN,     "Croatian",    "Hrvatski",          "🇭🇷"),
        LanguageInfo("ht", TranslateLanguage.HAITIAN_CREOLE, "Haitian Creole", "Kreyòl ayisyen", "🇭🇹"),
        LanguageInfo("hu", TranslateLanguage.HUNGARIAN,    "Hungarian",   "Magyar",            "🇭🇺"),
        LanguageInfo("id", TranslateLanguage.INDONESIAN,   "Indonesian",  "Bahasa Indonesia",  "🇮🇩"),
        LanguageInfo("is", TranslateLanguage.ICELANDIC,    "Icelandic",   "Íslenska",          "🇮🇸"),
        LanguageInfo("it", TranslateLanguage.ITALIAN,      "Italian",     "Italiano",          "🇮🇹"),
        LanguageInfo("ja", TranslateLanguage.JAPANESE,     "Japanese",    "日本語",             "🇯🇵"),
        LanguageInfo("ka", TranslateLanguage.GEORGIAN,     "Georgian",    "ქართული",           "🇬🇪"),
        LanguageInfo("kn", TranslateLanguage.KANNADA,      "Kannada",     "ಕನ್ನಡ",             "🇮🇳"),
        LanguageInfo("ko", TranslateLanguage.KOREAN,       "Korean",      "한국어",             "🇰🇷"),
        LanguageInfo("lt", TranslateLanguage.LITHUANIAN,   "Lithuanian",  "Lietuvių",          "🇱🇹"),
        LanguageInfo("lv", TranslateLanguage.LATVIAN,      "Latvian",     "Latviešu",          "🇱🇻"),
        LanguageInfo("mk", TranslateLanguage.MACEDONIAN,   "Macedonian",  "Македонски",        "🇲🇰"),
        LanguageInfo("mr", TranslateLanguage.MARATHI,      "Marathi",     "मराठी",             "🇮🇳"),
        LanguageInfo("ms", TranslateLanguage.MALAY,        "Malay",       "Bahasa Melayu",     "🇲🇾"),
        LanguageInfo("mt", TranslateLanguage.MALTESE,      "Maltese",     "Malti",             "🇲🇹"),
        LanguageInfo("nl", TranslateLanguage.DUTCH,        "Dutch",       "Nederlands",        "🇳🇱"),
        LanguageInfo("no", TranslateLanguage.NORWEGIAN,    "Norwegian",   "Norsk",             "🇳🇴"),
        LanguageInfo("pl", TranslateLanguage.POLISH,       "Polish",      "Polski",            "🇵🇱"),
        LanguageInfo("pt", TranslateLanguage.PORTUGUESE,   "Portuguese",  "Português",         "🇵🇹"),
        LanguageInfo("ro", TranslateLanguage.ROMANIAN,     "Romanian",    "Română",            "🇷🇴"),
        LanguageInfo("ru", TranslateLanguage.RUSSIAN,      "Russian",     "Русский",           "🇷🇺"),
        LanguageInfo("sk", TranslateLanguage.SLOVAK,       "Slovak",      "Slovenčina",        "🇸🇰"),
        LanguageInfo("sl", TranslateLanguage.SLOVENIAN,    "Slovenian",   "Slovenščina",       "🇸🇮"),
        LanguageInfo("sq", TranslateLanguage.ALBANIAN,     "Albanian",    "Shqip",             "🇦🇱"),
        LanguageInfo("sv", TranslateLanguage.SWEDISH,      "Swedish",     "Svenska",           "🇸🇪"),
        LanguageInfo("sw", TranslateLanguage.SWAHILI,      "Swahili",     "Kiswahili",         "🇰🇪"),
        LanguageInfo("ta", TranslateLanguage.TAMIL,        "Tamil",       "தமிழ்",             "🇮🇳"),
        LanguageInfo("te", TranslateLanguage.TELUGU,       "Telugu",      "తెలుగు",            "🇮🇳"),
        LanguageInfo("th", TranslateLanguage.THAI,         "Thai",        "ภาษาไทย",           "🇹🇭"),
        LanguageInfo("tl", TranslateLanguage.TAGALOG,      "Tagalog",     "Tagalog",           "🇵🇭"),
        LanguageInfo("tr", TranslateLanguage.TURKISH,      "Turkish",     "Türkçe",            "🇹🇷"),
        LanguageInfo("uk", TranslateLanguage.UKRAINIAN,    "Ukrainian",   "Українська",        "🇺🇦"),
        LanguageInfo("ur", TranslateLanguage.URDU,         "Urdu",        "اردو",              "🇵🇰"),
        LanguageInfo("vi", TranslateLanguage.VIETNAMESE,   "Vietnamese",  "Tiếng Việt",        "🇻🇳"),
        LanguageInfo("zh", TranslateLanguage.CHINESE,      "Chinese",     "中文",               "🇨🇳"),
    )

    // Download state per language code — reactive so UI updates automatically
    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    /**
     * Check which models are already downloaded on device.
     * Call once when settings screen opens.
     */
    suspend fun refreshDownloadStates() {
        val states = mutableMapOf<String, DownloadState>()
        allLanguages.forEach { lang ->
            if (lang.code == "en") return@forEach // English is always available
            states[lang.code] = checkIfDownloaded(lang)
        }
        _downloadStates.value = states
    }

    /**
     * Download the translation model for a language.
     * Updates downloadStates reactively so UI shows progress.
     */
    suspend fun downloadLanguage(lang: LanguageInfo) {
        if (lang.code == "en") return
        updateState(lang.code, DownloadState.DOWNLOADING)

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(lang.mlKitCode)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        val translator = Translation.getClient(options)

        try {
            val success = suspendCancellableCoroutine { continuation ->
                translator.downloadModelIfNeeded()
                    .addOnSuccessListener { continuation.resume(true) }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Download failed for ${lang.code}", e)
                        continuation.resume(false)
                    }
            }
            updateState(lang.code, if (success) DownloadState.DOWNLOADED else DownloadState.FAILED)
        } catch (e: Exception) {
            Log.w(TAG, "Download error for ${lang.code}", e)
            updateState(lang.code, DownloadState.FAILED)
        } finally {
            translator.close()
        }
    }

    /**
     * Delete the translation model for a language to free storage.
     */
    suspend fun deleteLanguage(lang: LanguageInfo) {
        if (lang.code == "en") return

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(lang.mlKitCode)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        val translator = Translation.getClient(options)

        try {
            suspendCancellableCoroutine { continuation ->
                // ML Kit doesn't have a direct delete API per translator
                // We use RemoteModelManager to delete the model
                val remoteModelManager = com.google.mlkit.common.model.RemoteModelManager.getInstance()
                val model = com.google.mlkit.nl.translate.TranslateRemoteModel.Builder(lang.mlKitCode).build()
                remoteModelManager.deleteDownloadedModel(model)
                    .addOnSuccessListener { continuation.resume(Unit) }
                    .addOnFailureListener { continuation.resume(Unit) }
            }
            updateState(lang.code, DownloadState.NOT_DOWNLOADED)
        } catch (e: Exception) {
            Log.w(TAG, "Delete error for ${lang.code}", e)
        } finally {
            translator.close()
        }
    }

    /**
     * Download all languages at once — useful for "Download All" button.
     * Downloads sequentially to avoid hammering the network.
     */
    suspend fun downloadAll() {
        allLanguages
            .filter { it.code != "en" }
            .filter { _downloadStates.value[it.code] != DownloadState.DOWNLOADED }
            .forEach { lang ->
                downloadLanguage(lang)
            }
    }

    private suspend fun checkIfDownloaded(lang: LanguageInfo): DownloadState {
        return try {
            val remoteModelManager = com.google.mlkit.common.model.RemoteModelManager.getInstance()
            val model = com.google.mlkit.nl.translate.TranslateRemoteModel.Builder(lang.mlKitCode).build()
            suspendCancellableCoroutine { continuation ->
                remoteModelManager.isModelDownloaded(model)
                    .addOnSuccessListener { isDownloaded ->
                        continuation.resume(
                            if (isDownloaded) DownloadState.DOWNLOADED
                            else DownloadState.NOT_DOWNLOADED
                        )
                    }
                    .addOnFailureListener {
                        continuation.resume(DownloadState.NOT_DOWNLOADED)
                    }
            }
        } catch (_: Exception) {
            DownloadState.NOT_DOWNLOADED
        }
    }

    private fun updateState(code: String, state: DownloadState) {
        _downloadStates.value = _downloadStates.value.toMutableMap().apply {
            put(code, state)
        }
    }
}
