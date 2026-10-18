package com.talksync.audio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiarizedTranscriptParserTest {
    private fun deepgram(vararg words: Pair<String, Int?>, final: Boolean = true, end: Boolean = false): JSONObject =
        JSONObject().put("type", "Results").put("is_final", final).put("speech_final", end)
            .put("channel", JSONObject().put("detected_language", "en").put("alternatives", JSONArray().put(
                JSONObject().put("transcript", words.joinToString(" ") { it.first })
                    .put("words", JSONArray().apply { words.forEach { (text, speaker) ->
                        put(JSONObject().put("word", text.lowercase()).put("punctuated_word", text).apply {
                            speaker?.let { put("speaker", it) }
                        })
                    } }),
            )))

    private fun soniox(vararg tokens: Triple<String, Boolean, Int?>, finished: Boolean = false): JSONObject =
        JSONObject().put("finished", finished).put("tokens", JSONArray().apply {
            tokens.forEach { (text, final, speaker) ->
                put(JSONObject().put("text", text).put("is_final", final).put("language", "es").apply {
                    speaker?.let { put("speaker", it.toString()) }
                })
            }
        })

    private fun List<SpeechUpdate>.finals() = filterIsInstance<SpeechUpdate.Final>().map { it.result }
    private fun List<SpeechUpdate>.live() = filterIsInstance<SpeechUpdate.Partial>().last().segments

    @Test fun `deepgram splits an A B A result into ordered turns`() {
        val result = DeepgramTranscriptParser().parse(deepgram(
            "Hello," to 0, "there." to 0, "Hi!" to 1, "Welcome." to 0, end = true,
        )).finals()
        assertEquals(listOf("Hello, there.", "Hi!", "Welcome."), result.map { it.text })
        assertEquals(listOf("speaker-0", "speaker-1", "speaker-0"), result.map { it.speakerKey })
    }

    @Test fun `deepgram retains confirmed chunks until empty endpoint`() {
        val parser = DeepgramTranscriptParser()
        assertTrue(parser.parse(deepgram("First" to 0)).finals().isEmpty())
        assertEquals("First second", parser.parse(deepgram("second" to 0)).live().single().text)
        val final = parser.parse(deepgram(end = true)).finals().single()
        assertEquals("First second", final.text)
        assertEquals(0, final.speakerOrdinal)
    }

    @Test fun `deepgram commits previous speaker before the next live caption`() {
        val parser = DeepgramTranscriptParser()
        parser.parse(deepgram("First turn." to 0))
        val next = parser.parse(deepgram("Second turn." to 1))
        assertEquals("First turn.", next.finals().single().text)
        assertEquals("speaker-1", next.live().single().speakerKey)
    }

    @Test fun `deepgram provisional revisions do not get duplicated in final`() {
        val parser = DeepgramTranscriptParser()
        parser.parse(deepgram("I" to 0))
        assertEquals("I can", parser.parse(deepgram("can" to 0, final = false)).live().single().text)
        parser.parse(deepgram("could" to 0, final = false))
        assertEquals("I could.", parser.parse(deepgram("could." to 0, end = true)).finals().single().text)
    }

    @Test fun `deepgram utterance end commits buffered text exactly once`() {
        val parser = DeepgramTranscriptParser()
        parser.parse(deepgram("Still here." to 2))
        val end = JSONObject().put("type", "UtteranceEnd")
        assertEquals("speaker-2", parser.parse(end).finals().single().speakerKey)
        assertTrue(parser.parse(end).finals().isEmpty())
    }

    @Test fun `deepgram partials carry distinct speaker labels`() {
        val live = DeepgramTranscriptParser().parse(deepgram("Yes" to 0, "No" to 1, final = false)).live()
        assertEquals(listOf(0, 1), live.map { it.speakerOrdinal })
    }

    @Test fun `deepgram unknown speaker stays unknown and reset clears old text`() {
        val parser = DeepgramTranscriptParser()
        parser.parse(deepgram("old" to 3))
        parser.reset()
        val final = parser.parse(deepgram("new" to null, end = true)).finals().single()
        assertEquals("new", final.text)
        assertNull(final.speakerKey)
    }

    @Test fun `soniox splits confirmed token speakers without inserting spaces`() {
        val finals = SonioxTranscriptParser().parse(soniox(
            Triple("Ho", true, 1), Triple("la ", true, 1), Triple("sí", true, 2),
            Triple("<end>", true, null),
        )).finals()
        assertEquals(listOf("Hola", "sí"), finals.map { it.text })
        assertEquals(listOf(0, 1), finals.map { it.speakerOrdinal })
        assertEquals(listOf("es", "es"), finals.map { it.detectedLanguage })
    }

    @Test fun `soniox handles multiple endpoints in a single frame`() {
        val finals = SonioxTranscriptParser().parse(soniox(
            Triple("One", true, 1), Triple("<end>", true, null),
            Triple("Two", true, 2), Triple("<end>", true, null),
        )).finals()
        assertEquals(listOf("One", "Two"), finals.map { it.text })
    }

    @Test fun `soniox endpoint does not erase following utterance in same frame`() {
        val parser = SonioxTranscriptParser()
        val updates = parser.parse(soniox(
            Triple("One", true, 1), Triple("<end>", true, null), Triple("Two", true, 2),
        ))
        assertEquals("One", updates.finals().single().text)
        assertEquals("Two", updates.live().single().text)
        assertEquals("Two", parser.parse(soniox(finished = true)).finals().single().text)
    }

    @Test fun `soniox provisional speaker cannot relabel confirmed speaker`() {
        val parser = SonioxTranscriptParser()
        val updates = parser.parse(soniox(Triple("Yes", true, 1), Triple("No", false, 2)))
        assertEquals(listOf(0, 1), updates.live().map { it.speakerOrdinal })
        assertEquals(0, parser.parse(soniox(Triple("<end>", true, null))).finals().single().speakerOrdinal)
    }

    @Test fun `soniox replaces provisional tokens instead of accumulating them`() {
        val parser = SonioxTranscriptParser()
        parser.parse(soniox(Triple("I ", true, 1), Triple("can", false, 1)))
        assertEquals("I could", parser.parse(soniox(Triple("could", false, 1))).live().single().text)
        assertEquals("I could.", parser.parse(soniox(Triple("could.", true, 1), finished = true)).finals().single().text)
    }

    @Test fun `soniox nonfinal endpoint does not commit confirmed text`() {
        val parser = SonioxTranscriptParser()
        val updates = parser.parse(soniox(Triple("Hello", true, 1), Triple("<end>", false, null)))
        assertTrue(updates.finals().isEmpty())
        assertEquals("Hello", updates.live().single().text)
    }

    @Test fun `soniox finished clears provisional tail and reset clears identities`() {
        val parser = SonioxTranscriptParser()
        parser.parse(soniox(Triple("old", true, 2)))
        parser.reset()
        val updates = parser.parse(soniox(Triple("new", true, null), Triple("guess", false, 1), finished = true))
        assertNull(updates.finals().single().speakerOrdinal)
        assertTrue(updates.live().isEmpty())
    }
}
