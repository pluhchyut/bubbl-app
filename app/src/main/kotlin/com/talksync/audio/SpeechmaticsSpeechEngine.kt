package com.talksync.audio

// Speechmatics real-time STT over websocket — the facedown interim + "prefinal"
// engine. ElevenLabs Scribe later replaces the text as the authoritative final;
// Speechmatics is what the user sees live, and it supplies diarization.
//
// Protocol: send StartRecognition, stream raw PCM frames, receive
// AddPartialTranscript (live) and AddTranscript (committed segments). We
// accumulate committed segments into the running utterance and emit a real
// final on end-of-sentence (is_eos) or EndOfUtterance. Speaker labels ("S1",
// "S2") map to 0-indexed ordinals.

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.WebSocket
import org.json.JSONObject

class SpeechmaticsSpeechEngine(context: Context) : StreamingSpeechEngine(context) {

    override val tag = "SpeechmaticsSpeechEngine"
    override val providerName = "Speechmatics"
    // Facedown engine — raw UNPROCESSED mic for table-distance, multi-speaker.
    override val captureConfigs: List<AudioCaptureConfig> = facedownCaptureConfigs

    // Committed AddTranscript segments since the last utterance boundary.
    private val committed = StringBuilder()

    @Volatile
    private var lastSpeakerOrdinal: Int? = null

    override fun buildRequest(): Request? {
        if (!SpeechmaticsConfig.isConfigured) return null
        return Request.Builder()
            .url(SPEECHMATICS_URL)
            .header("Authorization", "Bearer ${SpeechmaticsConfig.API_KEY}")
            .build()
    }

    override fun missingConfigMessage(): String =
        "Add speechmaticsApiKey to TalkSync/secrets.properties"

    override fun onSocketOpen(webSocket: WebSocket) {
        val start = JSONObject().apply {
            put("message", "StartRecognition")
            put(
                "audio_format",
                JSONObject().apply {
                    put("type", "raw")
                    put("encoding", "pcm_s16le")
                    put("sample_rate", MIC_SAMPLE_RATE)
                },
            )
            put(
                "transcription_config",
                JSONObject().apply {
                    put("language", LANGUAGE)
                    put("operating_point", "enhanced")
                    put("enable_partials", true)
                    put("max_delay", 2.0)
                    put("diarization", "speaker")
                    // Ask the server to mark utterance ends on silence so we can
                    // commit a clean final for the ElevenLabs refinement pass.
                    put(
                        "conversation_config",
                        JSONObject().put("end_of_utterance_silence_trigger", 0.8),
                    )
                },
            )
        }
        webSocket.send(start.toString())
    }

    override fun closeStreamPayload(): String =
        JSONObject()
            .put("message", "EndOfStream")
            .put("last_seq_no", sentAudioChunks)
            .toString()

    override fun onResetConnectionState() {
        committed.setLength(0)
        lastSpeakerOrdinal = null
    }

    override fun isRetryableCloseCode(code: Int): Boolean = when (code) {
        // 4001 not-authorised, 4003 forbidden, 4005 quota — don't hammer these.
        1000, 4001, 4003, 4005 -> false
        else -> true
    }

    override fun handleMessage(text: String, scope: CoroutineScope) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("message")) {
            "AddPartialTranscript" -> {
                updateSpeaker(json)
                val tail = json.optString("transcript").trim()
                val live = joinLive(tail)
                if (live.isNotBlank()) scope.launch { emitPartial(live) }
            }

            "AddTranscript" -> {
                updateSpeaker(json)
                val segment = json.optString("transcript").trim()
                if (segment.isNotBlank()) {
                    if (committed.isNotEmpty()) committed.append(' ')
                    committed.append(segment)
                }
                val full = committed.toString().trim()
                val endOfSentence = hasEndOfSentence(json)
                scope.launch {
                    if (endOfSentence && full.isNotBlank()) {
                        committed.setLength(0)
                        emitFinal(full, speakerOrdinal = lastSpeakerOrdinal)
                    } else if (full.isNotBlank()) {
                        emitPartial(full)
                    }
                }
            }

            "EndOfUtterance" -> {
                val full = committed.toString().trim()
                committed.setLength(0)
                if (full.isNotBlank()) {
                    scope.launch { emitFinal(full, speakerOrdinal = lastSpeakerOrdinal) }
                }
            }

            "Error" -> {
                val reason = json.optString("reason").ifBlank { json.optString("type") }
                reportPayloadError("Speechmatics: ${reason.ifBlank { "error" }}")
            }
            // RecognitionStarted, AudioAdded, Info, Warning, EndOfTranscript → ignore.
        }
    }

    /** Live = committed segments + the current partial tail. */
    private fun joinLive(tail: String): String {
        val base = committed.toString().trim()
        return when {
            base.isBlank() -> tail
            tail.isBlank() -> base
            else -> "$base $tail"
        }
    }

    private fun updateSpeaker(json: JSONObject) {
        extractSpeakerOrdinal(json)?.let { lastSpeakerOrdinal = it }
    }

    private fun extractSpeakerOrdinal(json: JSONObject): Int? {
        val results = json.optJSONArray("results") ?: return null
        val counts = HashMap<Int, Int>()
        for (i in 0 until results.length()) {
            val speaker = results.optJSONObject(i)?.optString("speaker")
            val ordinal = speaker
                ?.removePrefix("S")
                ?.toIntOrNull()
                ?.let { (it - 1).coerceAtLeast(0) }
            if (ordinal != null) {
                counts[ordinal] = (counts[ordinal] ?: 0) + 1
            }
        }
        return counts.maxByOrNull { it.value }?.key
    }

    private fun hasEndOfSentence(json: JSONObject): Boolean {
        val results = json.optJSONArray("results") ?: return false
        for (i in 0 until results.length()) {
            if (results.optJSONObject(i)?.optBoolean("is_eos", false) == true) {
                return true
            }
        }
        return false
    }

    private companion object {
        const val SPEECHMATICS_URL = "wss://eu2.rt.speechmatics.com/v2"
        const val LANGUAGE = "en"
    }
}
