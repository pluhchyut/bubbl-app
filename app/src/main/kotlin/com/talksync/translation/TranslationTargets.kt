package com.talksync.translation

// the languages the "Translate into" setting offers. Claude does the translating, so this list only decides what the picker shows.

data class TranslationTarget(val code: String, val englishName: String)

object TranslationTargets {
    const val DEFAULT_CODE = "en"

    val all: List<TranslationTarget> = listOf(
        TranslationTarget("en", "English"),
        TranslationTarget("es", "Spanish"),
        TranslationTarget("fr", "French"),
        TranslationTarget("de", "German"),
        TranslationTarget("pt", "Portuguese"),
        TranslationTarget("zh", "Chinese"),
        TranslationTarget("ja", "Japanese"),
        TranslationTarget("ko", "Korean"),
        TranslationTarget("hi", "Hindi"),
        TranslationTarget("ar", "Arabic"),
    )

    /** The setting is only ever written from this list, so an unknown code means a bug: fail loudly. */
    fun forCode(code: String): TranslationTarget =
        all.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("Unsupported translation target: $code")
}
