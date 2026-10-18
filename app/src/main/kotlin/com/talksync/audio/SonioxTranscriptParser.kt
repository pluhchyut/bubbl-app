package com.talksync.audio

import org.json.JSONObject

internal class SonioxTranscriptParser {
    private val buffer = DiarizedTranscriptBuffer("")

    @Synchronized
    fun reset() = buffer.clear()

    @Synchronized
    fun parse(json: JSONObject): List<SpeechUpdate> {
        val updates = mutableListOf<SpeechUpdate>()
        val provisional = mutableListOf<SpeechSegment>()
        val tokens = json.optJSONArray("tokens")
        if (tokens != null) for (i in 0 until tokens.length()) {
            val token = tokens.optJSONObject(i) ?: continue
            val text = token.optString("text")
            val isFinal = token.optBoolean("is_final")
            if (text == "<end>" || text == "<fin>") {
                if (isFinal) buffer.finish()?.let { updates += it.asFinal() }
                continue
            }
            val speaker = token.optString("speaker").toIntOrNull()?.takeIf { it > 0 }?.minus(1)
            val language = token.optString("language").takeIf { it.isNotBlank() && it != "null" }
            val segment = SpeechSegment(text, speaker, language)
            if (isFinal) {
                buffer.append(segment)?.let { updates += it.asFinal() }
            } else {
                provisional += segment
            }
        }
        if (json.optBoolean("finished")) {
            buffer.finish()?.let { updates += it.asFinal() }
            provisional.clear()
        }
        updates += SpeechUpdate.Partial(buffer.preview(provisional))
        return updates
    }
}
