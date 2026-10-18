package com.talksync.ai

// gemini key wrapper. boring, but it keeps secret lookup out of the actual ai code.

import com.talksync.BuildConfig

object GeminiConfig {
    const val PLACEHOLDER_API_KEY = "YOUR_GEMINI_API_KEY_HERE"
    val API_KEY: String = BuildConfig.GEMINI_API_KEY
    val isConfigured: Boolean
        get() = API_KEY.isNotBlank() && API_KEY != PLACEHOLDER_API_KEY
}
