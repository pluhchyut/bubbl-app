package com.talksync.audio

// azure speech keys live here so setup code doesnt become a scavenger hunt.

import com.talksync.BuildConfig

object AzureSpeechConfig {
    const val PLACEHOLDER_API_KEY = "YOUR_AZURE_SPEECH_KEY_HERE"
    val API_KEY: String = BuildConfig.AZURE_SPEECH_KEY
    val REGION: String = BuildConfig.AZURE_SPEECH_REGION

    val isConfigured: Boolean
        get() = API_KEY.isNotBlank() &&
            API_KEY != PLACEHOLDER_API_KEY &&
            REGION.isNotBlank()
}
