package com.talksync.model

// one saved transcript turn. room stores this, history rebuilds convos from it later.

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcripts")
data class TranscriptEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val text: String,
    val timestamp: Long,
    val volumeLevel: Float,
    val emotionLabel: String?,
    /**
     * Speaker bucket key, e.g. "speaker-0" / "speaker-1".
     * Stable for diarization within a conversation; null for legacy rows.
     */
    val speakerKey: String? = null,
    /**
     * Display-friendly label for the speaker. Defaults to "Speaker 1",
     * "Speaker 2", … unless the user has named the bound face identity.
     */
    val speakerLabel: String? = null,
    /**
     * Optional persistent face-identity id. When set, the speakerLabel is
     * derived from the matching FaceIdentity row, allowing renames to
     * propagate across conversations.
     */
    val faceIdentityId: String? = null,
)
