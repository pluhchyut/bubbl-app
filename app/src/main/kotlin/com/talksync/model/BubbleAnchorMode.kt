package com.talksync.model

// how bubbles decide where to stick when faces and screen edges get crowded.

enum class BubbleAnchorMode(val value: String) {
    CLASSIC("Classic"),
    AR_EXPERIMENTAL("AR Experimental"),
    FACE_DOWN("Face-down"),
    ;

    companion object {
        fun fromValue(value: String?): BubbleAnchorMode {
            return entries.firstOrNull { it.value == value } ?: CLASSIC
        }
    }
}
