package com.talksync.model

// ready-to-draw bubble data. raw transcripts stay separate so ui code can breathe.

import com.talksync.audio.WordVolume
import com.talksync.emotion.EmotionState

data class SpeechBubbleData(
    val id: String = "",
    val speakerKey: String = DEFAULT_SPEAKER_KEY,
    val speakerLabel: String? = null,
    val anchorFaceId: String? = null,
    val lipDrivenAnchorId: String? = null,
    val text: String = "",
    val isPartial: Boolean = false,
    val isVisible: Boolean = false,
    val volumeLevel: Float = 0f,
    val wordVolumes: List<WordVolume> = emptyList(),
    val emotionState: EmotionState = EmotionState(),
    val detectedLanguage: String? = null,
    val translatedText: String? = null,
    val isTranslating: Boolean = false,
    val expandedTokens: List<ExpandedToken> = emptyList(),
    val intentLabels: List<IntentLabel> = emptyList(),
    val threadId: String? = null,
    val replyToSpeakerKey: String? = null,
    val threadTurnIndex: Int = 0,
    val isThreadHighlighted: Boolean = false,
    val timestamp: Long = 0L,
) {
    companion object {
        const val DEFAULT_SPEAKER_KEY = "speaker-0"
        const val PENDING_SPEAKER_KEY = "speaker-pending"
    }
}
