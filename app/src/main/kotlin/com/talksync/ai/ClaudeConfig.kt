package com.talksync.ai

// claude api key wrapper — same pattern as GeminiConfig.

import com.talksync.BuildConfig

object ClaudeConfig {
    const val PLACEHOLDER_API_KEY = "YOUR_ANTHROPIC_API_KEY_HERE"
    val API_KEY: String = BuildConfig.ANTHROPIC_API_KEY
    val isConfigured: Boolean
        get() = API_KEY.isNotBlank() && API_KEY != PLACEHOLDER_API_KEY
}
