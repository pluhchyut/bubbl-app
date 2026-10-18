package com.talksync.model

/** Changes one speaker's caption without replacing other speakers' captions. */
internal object SpeakerBubbleState {
    fun upsert(
        current: List<SpeechBubbleData>,
        incoming: SpeechBubbleData,
        promotePending: Boolean = true,
    ): List<SpeechBubbleData> {
        val retained = current.filterNot {
            promotePending && it.isPartial && it.speakerKey == SpeechBubbleData.PENDING_SPEAKER_KEY &&
                incoming.speakerKey != SpeechBubbleData.PENDING_SPEAKER_KEY
        }
        return if (retained.any { it.speakerKey == incoming.speakerKey }) {
            retained.map { if (it.speakerKey == incoming.speakerKey) incoming else it }
        } else retained + incoming
    }

    fun isSameRevision(current: SpeechBubbleData, expected: SpeechBubbleData): Boolean =
        current.speakerKey == expected.speakerKey && current.timestamp == expected.timestamp &&
            current.text == expected.text && current.isPartial == expected.isPartial

    fun remove(current: List<SpeechBubbleData>, expected: SpeechBubbleData): List<SpeechBubbleData> =
        current.filterNot { isSameRevision(it, expected) }
}
