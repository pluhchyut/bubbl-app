package com.talksync.model

import org.junit.Assert.*
import org.junit.Test

class RecentCaptionStateTest {
    @Test fun `side lifetime starts at handoff not original speech time`() {
        val recent = RecentCaptionState.add(emptyList(), "turn-a", now = 20_000L)
        assertFalse(RecentCaptionState.advance(recent, 29_999L).single().isFading)
        assertTrue(RecentCaptionState.advance(recent, 30_000L).single().isFading)
        assertEquals(30_600L, RecentCaptionState.nextTransitionAt(RecentCaptionState.advance(recent, 30_000L)))
        assertTrue(RecentCaptionState.advance(recent, 30_600L).isEmpty())
    }

    @Test fun `two speakers expire independently`() {
        val a = RecentCaptionState.add(emptyList(), "speaker-a-turn-1", 0L)
        val both = RecentCaptionState.add(a, "speaker-b-turn-1", 5_000L)
        val firstFading = RecentCaptionState.advance(both, 10_000L)
        assertTrue(firstFading[0].isFading)
        assertFalse(firstFading[1].isFading)
        val secondOnly = RecentCaptionState.advance(firstFading, 10_600L)
        assertEquals("speaker-b-turn-1", secondOnly.single().captionId)
        assertEquals(15_000L, RecentCaptionState.nextTransitionAt(secondOnly))
    }

    @Test fun `repeated handoff or translation update never restarts side timer`() {
        val first = RecentCaptionState.add(emptyList(), "turn-a", 0L)
        assertEquals(first, RecentCaptionState.add(first, "turn-a", 9_000L))
        assertTrue(RecentCaptionState.advance(RecentCaptionState.add(first, "turn-a", 9_000L), 10_600L).isEmpty())
    }

    @Test fun `multiple turns from one speaker remain separate`() {
        val first = RecentCaptionState.add(emptyList(), "speaker-a-turn-1", 0L)
        val both = RecentCaptionState.add(first, "speaker-a-turn-2", 1_000L)
        assertEquals(2, both.size)
        assertEquals("speaker-a-turn-2", RecentCaptionState.advance(both, 10_600L).single().captionId)
    }

    @Test fun `empty or fully elapsed side state has no remaining timer`() {
        assertNull(RecentCaptionState.nextTransitionAt(emptyList()))
        val first = RecentCaptionState.add(emptyList(), "turn-a", 0L)
        assertNull(RecentCaptionState.nextTransitionAt(RecentCaptionState.advance(first, 60_000L)))
    }
}
