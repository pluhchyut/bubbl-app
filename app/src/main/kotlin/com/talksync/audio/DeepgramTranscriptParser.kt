package com.talksync.audio

import org.json.JSONObject

internal class DeepgramTranscriptParser {
    private val buffer = DiarizedTranscriptBuffer(" ")

    @Synchronized
    fun reset() = buffer.clear()

    @Synchronized
    fun parse(json: JSONObject): List<SpeechUpdate> {
        val updates = mutableListOf<SpeechUpdate>()
        if (json.optString("type") == "UtteranceEnd") {
            buffer.finish()?.let { updates += it.asFinal() }
            updates += SpeechUpdate.Partial(emptyList())
            return updates
        }
        val channel = json.optJSONObject("channel") ?: return emptyList()
        val alternative = channel.optJSONArray("alternatives")?.optJSONObject(0) ?: return emptyList()
        val language = channel.optString("detected_language").takeIf { it.isNotBlank() && it != "null" }
        val words = alternative.optJSONArray("words")
        val segments = mutableListOf<SpeechSegment>()
        if (words != null) for (i in 0 until words.length()) {
            val word = words.optJSONObject(i) ?: continue
            val text = word.optString("punctuated_word").ifBlank { word.optString("word") }
            if (text.isBlank()) continue
            val speaker = word.optInt("speaker", -1).takeIf { it >= 0 }
            segments += SpeechSegment(text, speaker, language)
        }
        // Preserve provider formatting when all words belong to one speaker.
        val transcript = alternative.optString("transcript").trim()
        val speakers = segments.mapNotNull { it.speakerOrdinal }.distinct()
        val input = if (speakers.size <= 1 && transcript.isNotBlank()) {
            listOf(SpeechSegment(transcript, speakers.firstOrNull(), language))
        } else segments

        if (json.optBoolean("is_final")) {
            input.forEach { segment -> buffer.append(segment)?.let { updates += it.asFinal() } }
            if (json.optBoolean("speech_final")) {
                buffer.finish()?.let { updates += it.asFinal() }
            }
            updates += SpeechUpdate.Partial(buffer.preview(emptyList()))
        } else {
            updates += SpeechUpdate.Partial(buffer.preview(input))
        }
        return updates
    }
}

internal fun SpeechSegment.asFinal() = SpeechUpdate.Final(
    FinalizedSpeech(text, speakerKey, speakerOrdinal, detectedLanguage),
)
