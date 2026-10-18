package com.talksync.music

// audd token wrapper for song id. easier to swap demo keys when it is all here.

import com.talksync.BuildConfig

/**
 * AudD.io is a Shazam-style audio fingerprinting API. It accepts a short
 * audio clip and returns the matching song's title, artist, and album.
 * See https://audd.io/ for the free tier.
 *
 * The token is passed in via secrets.properties (auddApiToken=...) or the
 * AUDD_API_TOKEN env var. With no token, the API still works in test mode
 * for the first few hundred requests per IP/day, so the screen still works
 * for demo purposes — it just may rate-limit.
 */
object AudDConfig {
    const val PLACEHOLDER_API_TOKEN = "YOUR_AUDD_API_TOKEN_HERE"
    val API_TOKEN: String = BuildConfig.AUDD_API_TOKEN

    /** True when a real token was supplied. False = anonymous demo tier. */
    val hasToken: Boolean
        get() = API_TOKEN.isNotBlank() && API_TOKEN != PLACEHOLDER_API_TOKEN

    /** Effective token to send: real token, or empty string for the demo tier. */
    val effectiveToken: String
        get() = if (hasToken) API_TOKEN else ""
}
