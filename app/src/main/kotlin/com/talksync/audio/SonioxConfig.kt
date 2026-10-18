package com.talksync.audio

// soniox key lookup, kept next to the engine that uses it.

import com.talksync.BuildConfig

object SonioxConfig {
    const val PLACEHOLDER_API_KEY = "YOUR_SONIOX_API_KEY_HERE"
    val API_KEY: String = BuildConfig.SONIOX_API_KEY
    val isConfigured: Boolean
        get() = API_KEY.isNotBlank() && API_KEY != PLACEHOLDER_API_KEY
}
