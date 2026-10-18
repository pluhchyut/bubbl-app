package com.talksync.model

// how dramatic the caption styling gets when tone/volume changes.

enum class EmphasisToneMode(val value: String) {
    FADE_TO_WHITE("Fade to white"),
    MAXIMUM_TONE("Maximum tone");

    companion object {
        fun fromValue(value: String?): EmphasisToneMode {
            return entries.firstOrNull { it.value == value } ?: FADE_TO_WHITE
        }
    }
}
