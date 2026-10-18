package com.talksync.model

// distance preset for grouping turns. tweak this and history changes what counts as one chat.

/**
 * Coarse "how far away is the speaker" hint that drives a few presentation
 * choices in the conversation overlay:
 *
 *  - [FOCUS] — single speaker close to the camera (interview / 1:1).
 *    Bigger text, tighter mouth lock, narrower bubble width so it doesn't
 *    spill across the frame.
 *  - [GROUP] — multiple people farther back (table / classroom). Smaller
 *    text, wider bubbles so they remain readable when shrunk to fit
 *    multiple speakers without overlap.
 */
enum class ConversationDistanceMode(val value: String) {
    FOCUS("Focus"),
    GROUP("Group"),
    ;

    /** Multiplier applied on top of the user's base text size. */
    val textScale: Float
        get() = when (this) {
            FOCUS -> 1.18f
            GROUP -> 0.86f
        }

    /** Maximum bubble width fraction of screen width. */
    val maxBubbleWidthFraction: Float
        get() = when (this) {
            FOCUS -> 0.78f
            GROUP -> 0.46f
        }

    /**
     * Vertical "lift" added between the mouth anchor and the bubble bottom,
     * in dp. Focus mode keeps captions tight; group mode pushes them up so
     * faces in the back stay clearly visible.
     */
    val anchorLiftDp: Float
        get() = when (this) {
            FOCUS -> 8f
            GROUP -> 22f
        }

    companion object {
        fun fromValue(value: String?): ConversationDistanceMode {
            return entries.firstOrNull { it.value == value } ?: FOCUS
        }
    }
}
