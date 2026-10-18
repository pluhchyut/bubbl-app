package com.talksync.vision

import com.talksync.model.SpeechBubbleData
import com.talksync.vision.SpeakerFusionConfig.ASSIGNMENT_TIMEOUT_MS
import com.talksync.vision.SpeakerFusionConfig.PINNED_ASSIGNMENT_TIMEOUT_MS
import com.talksync.vision.SpeakerFusionConfig.PIN_HOLD_MS
import org.junit.Assert.*
import org.junit.Test

class SpeakerFaceAssignmentStoreTest {
    private val pending = SpeechBubbleData.PENDING_SPEAKER_KEY

    @Test fun `confirming a pending speaker preserves the observed face`() {
        val store = SpeakerFaceAssignmentStore()
        store.bindPending("face-a", 0.7f, 100L)
        assertEquals("face-a", store.adopt(pending, "speaker-0", 110L))
        assertNull(store.faceIdFor(pending))
    }

    @Test fun `returning speaker keeps their binding when pending cue follows another face`() {
        val store = SpeakerFaceAssignmentStore()
        store.bindPending("face-a", 0.7f, 100L)
        store.adopt(pending, "speaker-0", 110L)
        store.bindPending("face-b", 0.8f, 200L)
        assertEquals("face-a", store.adopt(pending, "speaker-0", 210L))
        assertEquals("face-a", store.faceIdFor("speaker-0"))
        assertNull(store.faceIdFor(pending))
    }

    @Test fun `second speaker cannot adopt first speakers locked face`() {
        val store = SpeakerFaceAssignmentStore()
        store.bindPending("face-a", 0.7f, 100L)
        store.adopt(pending, "speaker-0", 110L)
        store.bindPending("face-a", 0.8f, 200L)
        assertNull(store.adopt(pending, "speaker-1", 210L))
        assertNull(store.faceIdFor("speaker-1"))
        assertEquals("face-a", store.faceIdFor("speaker-0"))
    }

    @Test fun `two speakers can keep separate locked face bindings`() {
        val store = SpeakerFaceAssignmentStore()
        store.bindPending("face-a", 0.7f, 100L)
        store.adopt(pending, "speaker-0", 110L)
        store.bindPending("face-b", 0.8f, 200L)
        assertEquals("face-b", store.adopt(pending, "speaker-1", 210L))
        assertEquals("face-a", store.faceIdFor("speaker-0"))
        assertEquals("face-b", store.faceIdFor("speaker-1"))
    }

    // Binds speaker-0 to face-a and keeps face-a visible for the full hold time.
    private fun storeWithPinnedSpeaker(): Pair<SpeakerFaceAssignmentStore, Long> {
        val store = SpeakerFaceAssignmentStore()
        store.bindPending("face-a", 0.7f, 100L)
        store.adopt(pending, "speaker-0", 110L)
        val pinnedAt = 110L + PIN_HOLD_MS
        store.pruneMissingFaces(setOf("face-a"), pinnedAt)
        return store to pinnedAt
    }

    @Test fun `speaker held on one face for the hold time is pinned`() {
        val (store, _) = storeWithPinnedSpeaker()
        assertTrue(store.snapshotAssignments().getValue("speaker-0").isPinned)
    }

    @Test fun `speaker is not pinned before the hold time`() {
        val store = SpeakerFaceAssignmentStore()
        store.bindPending("face-a", 0.7f, 100L)
        store.adopt(pending, "speaker-0", 110L)
        store.pruneMissingFaces(setOf("face-a"), 110L + PIN_HOLD_MS - 1)
        assertFalse(store.snapshotAssignments().getValue("speaker-0").isPinned)
    }

    @Test fun `pending slot is never pinned`() {
        val store = SpeakerFaceAssignmentStore()
        store.bindPending("face-a", 0.9f, 100L)
        store.pruneMissingFaces(setOf("face-a"), 100L + PIN_HOLD_MS)
        assertFalse(store.snapshotAssignments().getValue(pending).isPinned)
    }

    @Test fun `unpinned speaker is dropped once its face is gone past the normal timeout`() {
        val store = SpeakerFaceAssignmentStore()
        store.bindPending("face-a", 0.7f, 100L)
        store.adopt(pending, "speaker-0", 110L)
        store.pruneMissingFaces(emptySet(), 110L + ASSIGNMENT_TIMEOUT_MS + 1)
        assertNull(store.faceIdFor("speaker-0"))
    }

    @Test fun `pinned speaker survives a face dropout longer than the normal timeout`() {
        val (store, pinnedAt) = storeWithPinnedSpeaker()
        store.pruneMissingFaces(emptySet(), pinnedAt + ASSIGNMENT_TIMEOUT_MS + 1)
        assertEquals("face-a", store.faceIdFor("speaker-0"))
    }

    @Test fun `pinned speaker is released after the pinned timeout`() {
        val (store, pinnedAt) = storeWithPinnedSpeaker()
        store.pruneMissingFaces(emptySet(), pinnedAt + PINNED_ASSIGNMENT_TIMEOUT_MS + 1)
        assertNull(store.faceIdFor("speaker-0"))
    }

    @Test fun `pinned speaker keeps its face when no faces are visible`() {
        val (store, pinnedAt) = storeWithPinnedSpeaker()
        assertNull(store.resolve("speaker-0", emptyList(), emptyMap(), now = pinnedAt + 100L))
        assertEquals("face-a", store.faceIdFor("speaker-0"))
    }

    @Test fun `unpinned speaker is cleared when no faces are visible`() {
        val store = SpeakerFaceAssignmentStore()
        store.bindPending("face-a", 0.7f, 100L)
        store.adopt(pending, "speaker-0", 110L)
        assertNull(store.resolve("speaker-0", emptyList(), emptyMap(), now = 200L))
        assertNull(store.faceIdFor("speaker-0"))
    }

    @Test fun `clearing the store drops pinned locks`() {
        val (store, _) = storeWithPinnedSpeaker()
        store.clear()
        assertNull(store.faceIdFor("speaker-0"))
        assertTrue(store.snapshotAssignments().isEmpty())
    }

    @Test fun `pinned speaker keeps its face when the pending cue follows someone else`() {
        val (store, pinnedAt) = storeWithPinnedSpeaker()
        store.bindPending("face-b", 0.9f, pinnedAt + 100L)
        assertEquals("face-a", store.adopt(pending, "speaker-0", pinnedAt + 110L))
        assertTrue(store.snapshotAssignments().getValue("speaker-0").isPinned)
    }
}
