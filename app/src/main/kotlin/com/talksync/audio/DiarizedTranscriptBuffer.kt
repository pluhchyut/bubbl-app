package com.talksync.audio

/** Accumulates confirmed text only until the speaker changes or speech ends. */
internal class DiarizedTranscriptBuffer(private val separator: String) {
    private var confirmed: SpeechSegment? = null

    fun append(segment: SpeechSegment): SpeechSegment? {
        if (segment.text.isEmpty()) return null
        val previous = confirmed
        val speaker = segment.speakerOrdinal ?: previous?.speakerOrdinal
        val next = segment.copy(speakerOrdinal = speaker)
        return if (previous != null && previous.speakerOrdinal != null &&
            speaker != null && previous.speakerOrdinal != speaker
        ) {
            confirmed = next
            previous.copy(text = previous.text.trim()).takeIf { it.text.isNotBlank() }
        } else {
            confirmed = next.copy(
                text = join(previous?.text.orEmpty(), next.text),
                detectedLanguage = next.detectedLanguage ?: previous?.detectedLanguage,
            )
            null
        }
    }

    fun preview(provisional: List<SpeechSegment>): List<SpeechSegment> {
        val segments = mutableListOf<SpeechSegment>()
        confirmed?.let(segments::add)
        for (segment in provisional) {
            val previous = segments.lastOrNull()
            val speaker = segment.speakerOrdinal ?: previous?.speakerOrdinal
            if (previous != null && (previous.speakerOrdinal == speaker || previous.speakerOrdinal == null)) {
                segments[segments.lastIndex] = segment.copy(
                    text = join(previous.text, segment.text),
                    speakerOrdinal = speaker,
                    detectedLanguage = segment.detectedLanguage ?: previous.detectedLanguage,
                )
            } else {
                segments += segment.copy(speakerOrdinal = speaker)
            }
        }
        return segments.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotBlank() }
    }

    fun finish(): SpeechSegment? = confirmed
        ?.copy(text = confirmed!!.text.trim())
        ?.takeIf { it.text.isNotBlank() }
        .also { confirmed = null }

    fun clear() { confirmed = null }

    private fun join(first: String, second: String): String = when {
        first.isEmpty() -> second
        second.isEmpty() -> first
        else -> first + separator + second
    }
}
