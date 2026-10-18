package com.talksync.emotion

// tiny emotion snapshot. most screens just care what tone it is and how sure we are.

enum class ToneLabel {
    JOYFUL,
    WARM,
    FRUSTRATED,
    SAD,
    FEARFUL,
    SURPRISED,
    CONFUSED,
    NEUTRAL,
    ;

    companion object {
        fun fromPersistedLabel(label: String?): ToneLabel {
            return entries.firstOrNull { it.name.equals(label, ignoreCase = true) } ?: NEUTRAL
        }
    }
}

data class EmotionState(
    val label: ToneLabel = ToneLabel.NEUTRAL,
    val dominantFaceEmotion: String? = null,
    val dominantProsodyEmotion: String? = null,
    val dominantBurstEmotion: String? = null,
    val dominantTextEmotion: String? = null,
    val confidence: Float = 0f,
    val isLowConfidence: Boolean = false,
)

fun EmotionState.persistedLabel(): String? {
    return label.takeUnless { it == ToneLabel.NEUTRAL }?.name
}
