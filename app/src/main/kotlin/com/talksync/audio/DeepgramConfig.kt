package com.talksync.audio

// deepgram key lookup, separated so the websocket file can focus on websocket things.

import com.talksync.BuildConfig

object DeepgramConfig {
    const val PLACEHOLDER_API_KEY = "YOUR_DEEPGRAM_API_KEY_HERE"
    val API_KEY: String = BuildConfig.DEEPGRAM_API_KEY
}
