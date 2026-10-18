package com.talksync.audio

// Soniox real-time STT over websocket. Streaming token model: each message
// carries newly-final tokens plus the current provisional tail. We accumulate
// final tokens into the running utterance and treat the endpoint-detection
// "<end>" token (or `finished`) as the utterance boundary.
//
// Selectable as the normal-mode backend alongside Deepgram (Settings → Speech
// engine). Diarization comes from per-token `speaker` labels.

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class SonioxSpeechEngine(
    context: Context,
    faceDown: Boolean = false,
    extendedLanguages: Boolean = false,
) : StreamingSpeechEngine(context) {

    override val tag = "SonioxSpeechEngine"
    override val providerName = "Soniox"
    // Facedown/tabletop uses the raw UNPROCESSED far-field mic, like the other
    // facedown engines; normal mode uses the default capture configs.
    override val captureConfigs: List<AudioCaptureConfig> =
        if (faceDown) facedownCaptureConfigs else defaultCaptureConfigs

    // Default to the focused hint set we test/demo; the "extended languages"
    // setting biases across the full Soniox set for unexpected languages.
    private val languageHints: List<String> =
        if (extendedLanguages) EXTENDED_LANGUAGE_HINTS else DEFAULT_LANGUAGE_HINTS

    private val transcriptParser = SonioxTranscriptParser()

    override fun buildRequest(): Request? {
        if (!SonioxConfig.isConfigured) return null
        return Request.Builder().url(SONIOX_URL).build()
    }

    override fun missingConfigMessage(): String =
        "Add sonioxApiKey to TalkSync/secrets.properties"

    override fun onSocketOpen(webSocket: okhttp3.WebSocket) {
        val config = JSONObject().apply {
            put("api_key", SonioxConfig.API_KEY)
            put("model", MODEL)
            put("audio_format", "pcm_s16le")
            put("sample_rate", MIC_SAMPLE_RATE)
            put("num_channels", 1)
            put("enable_speaker_diarization", true)
            put("enable_language_identification", true)
            put("enable_endpoint_detection", true)
            put("language_hints", JSONArray(languageHints))
        }
        webSocket.send(config.toString())
    }

    // Empty string tells Soniox the audio stream is complete.
    override fun closeStreamPayload(): String = ""

    override fun onResetConnectionState() {
        transcriptParser.reset()
    }

    override fun handleMessage(text: String, scope: CoroutineScope) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return

        val errorCode = json.optInt("error_code", 0)
        if (errorCode != 0) {
            val msg = json.optString("error_message").ifBlank { "Soniox error $errorCode" }
            reportPayloadError("Soniox: $msg")
            return
        }

        val updates = transcriptParser.parse(json)
        scope.launch { emitUpdates(updates) }
    }

    private companion object {
        const val SONIOX_URL = "wss://stt-rt.soniox.com/transcribe-websocket"
        const val MODEL = "stt-rt-v4"
        // Default hint set — the languages we actually test/demo. These bias
        // recognition; Soniox still auto-detects, but a focused list is more
        // accurate for expected input. Toggle "extended languages" in Settings
        // to bias across the full Soniox set instead (EXTENDED_LANGUAGE_HINTS).
        // Tamil is included so Tamil (and Tamil/English code-switching) is
        // tuned by default without enabling extended languages.
        val DEFAULT_LANGUAGE_HINTS = listOf(
            "en", "ta", "es", "fr", "de", "zh", "hi", "ar", "pt", "ru", "ja", "ko", "it",
        )

        // Full Soniox stt-rt-v4 language set (60), used when the user enables
        // extended languages in Settings (e.g. for judges speaking something
        // outside the default 12).
        val EXTENDED_LANGUAGE_HINTS = listOf(
            "af", "sq", "ar", "az", "eu", "be", "bn", "bs", "bg", "ca",
            "zh", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "gl",
            "de", "el", "gu", "he", "hi", "hu", "id", "it", "ja", "kn",
            "kk", "ko", "lv", "lt", "mk", "ms", "ml", "mr", "no", "fa",
            "pl", "pt", "pa", "ro", "ru", "sr", "sk", "sl", "es", "sw",
            "sv", "tl", "ta", "te", "th", "tr", "uk", "ur", "vi", "cy",
        )
    }
}
