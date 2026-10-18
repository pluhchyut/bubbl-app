package com.talksync.audio

// speechmatics key lookup, kept next to the engine that uses it.

import com.talksync.BuildConfig

object SpeechmaticsConfig {
    const val PLACEHOLDER_API_KEY = "YOUR_SPEECHMATICS_API_KEY_HERE"
    val API_KEY: String = BuildConfig.SPEECHMATICS_API_KEY
    val isConfigured: Boolean
        get() = API_KEY.isNotBlank() && API_KEY != PLACEHOLDER_API_KEY
}
