package com.talksync.model

import com.talksync.audio.DeepgramTranscriptParser
import com.talksync.audio.SpeechUpdate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpeakerBubbleStateTest {
    private fun bubble(speaker: String, text: String, timestamp: Long = 1L, partial: Boolean = false) =
        SpeechBubbleData(id = "bubble-$speaker", speakerKey = speaker, speakerLabel = speaker,
            text = text, timestamp = timestamp, isPartial = partial, isVisible = true)

    @Test fun `two speakers keep separate captions and repeated words`() {
        val first = bubble("speaker-0", "Yes")
        val second = bubble("speaker-1", "Yes")
        val state = SpeakerBubbleState.upsert(listOf(first), second)
        assertEquals(listOf(first, second), state)
    }

    @Test fun `returning speaker updates only their own bubble`() {
        val a = bubble("speaker-0", "Hello")
        val b = bubble("speaker-1", "Hi")
        val a2 = bubble("speaker-0", "Welcome", 2L)
        assertEquals(listOf(a2, b), SpeakerBubbleState.upsert(listOf(a, b), a2))
    }

    @Test fun `pending promotion removes ghost and preserves other speakers`() {
        val a = bubble("speaker-0", "Hello")
        val pending = bubble(SpeechBubbleData.PENDING_SPEAKER_KEY, "Hi", partial = true)
        val b = bubble("speaker-1", "Hi")
        assertEquals(listOf(a, b), SpeakerBubbleState.upsert(listOf(a, pending), b))
    }

    @Test fun `unknown and known provisional speakers can coexist`() {
        val pending = bubble(SpeechBubbleData.PENDING_SPEAKER_KEY, "Still speaking", partial = true)
        val known = bubble("speaker-1", "Reply", partial = true)
        assertEquals(listOf(pending, known), SpeakerBubbleState.upsert(listOf(pending), known, promotePending = false))
    }

    @Test fun `unknown final remains visible when identified speaker starts`() {
        val unknown = bubble(SpeechBubbleData.PENDING_SPEAKER_KEY, "Hello")
        val known = bubble("speaker-1", "Hi", partial = true)
        assertEquals(listOf(unknown, known), SpeakerBubbleState.upsert(listOf(unknown), known))
    }

    @Test fun `expired first caption cannot clear second speaker or newer turn`() {
        val old = bubble("speaker-0", "Hello")
        val fresh = bubble("speaker-0", "Hello", 2L)
        val b = bubble("speaker-1", "Hi")
        assertEquals(listOf(b), SpeakerBubbleState.remove(listOf(old, b), old))
        assertEquals(listOf(fresh, b), SpeakerBubbleState.remove(listOf(fresh, b), old))
    }

    @Test fun `late translation cannot patch newer partial for same speaker`() {
        val old = bubble("speaker-0", "Hello")
        val fresh = bubble("speaker-0", "Hello", 2L, partial = true)
        assertFalse(SpeakerBubbleState.isSameRevision(fresh, old))
    }

    @Test fun `one provider frame renders two speaker captions end to end`() {
        val frame = JSONObject("""{"type":"Results","is_final":true,"speech_final":true,
            "channel":{"alternatives":[{"transcript":"Hello Hi",
            "words":[{"word":"Hello","speaker":0},{"word":"Hi","speaker":1}]}]}}""")
        var bubbles = emptyList<SpeechBubbleData>()
        for (update in DeepgramTranscriptParser().parse(frame)) {
            if (update is SpeechUpdate.Final) {
                bubbles = SpeakerBubbleState.upsert(bubbles,
                    bubble(update.result.speakerKey!!, update.result.text))
            }
        }
        assertEquals(listOf("speaker-0", "speaker-1"), bubbles.map { it.speakerKey })
        assertEquals(listOf("Hello", "Hi"), bubbles.map { it.text })
    }
}
