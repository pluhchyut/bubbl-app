package com.talksync.model

/** Transient side-bar membership; the saved transcript has its own lifetime. */
internal data class RecentCaptionRef(
    val captionId: String,
    val fadeAtMs: Long,
    val isFading: Boolean = false,
)

internal object RecentCaptionState {
    const val BUBBLE_FADE_MS = 520L
    const val SIDE_HOLD_MS = 10_000L
    const val SIDE_FADE_MS = 600L

    fun add(current: List<RecentCaptionRef>, captionId: String, now: Long): List<RecentCaptionRef> {
        if (current.any { it.captionId == captionId }) return current
        return current + RecentCaptionRef(captionId, fadeAtMs = now + SIDE_HOLD_MS)
    }

    fun advance(current: List<RecentCaptionRef>, now: Long): List<RecentCaptionRef> =
        current.filter { now < it.fadeAtMs + SIDE_FADE_MS }
            .map { it.copy(isFading = now >= it.fadeAtMs) }

    fun nextTransitionAt(current: List<RecentCaptionRef>): Long? =
        current.minOfOrNull { if (it.isFading) it.fadeAtMs + SIDE_FADE_MS else it.fadeAtMs }
}
