package com.talksync.emotion

// hume key lookup lives here so the rest of the app doesnt have to go digging in BuildConfig.

import com.talksync.BuildConfig

object HumeConfig {
    const val PLACEHOLDER_API_KEY = "YOUR_HUME_API_KEY_HERE"
    val API_KEY: String = BuildConfig.HUME_API_KEY
    const val WS_URL = "wss://api.hume.ai/v0/stream/models"
    const val FACE_CAPTURE_INTERVAL_MS = 1_000L
    const val AUDIO_CHUNK_INTERVAL_MS = 500L
    const val KEEPALIVE_INTERVAL_MS = 45_000L
    const val EMOTION_THRESHOLD = 0.12f
    const val FACE_WEIGHT = 0.44f
    const val PROSODY_WEIGHT = 0.36f
    const val BURST_WEIGHT = 0.10f
    const val TEXT_WEIGHT = 0.10f
    const val FACE_PROB_THRESHOLD = 0.60
    const val FACE_MIN_SIZE_PX = 28
    const val FACE_JPEG_QUALITY = 72
    const val FACE_RESULT_STALE_MS = 3_500L
    const val FACE_CROP_PADDING = 0.22f
    const val FACE_MAX_LONG_EDGE_PX = 720
    const val EMOTION_HOLD_MS = 3_200L
}
