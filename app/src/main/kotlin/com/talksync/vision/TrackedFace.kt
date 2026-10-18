package com.talksync.vision

// one face snapshot from vision: box, confidence, mouth activity, timing, all that.

import android.graphics.RectF

data class TrackedFace(
    val trackId: String,
    val nativeTrackingId: Int? = null,
    val boundingBox: RectF,
    val lastSeenAt: Long,
) {
    val area: Float
        get() = boundingBox.width() * boundingBox.height()
}
