package com.talksync.vision

// keeps speaker-to-face memory warm so bubbles stay near the right person.

import com.talksync.audio.AudioDirection
import com.talksync.model.SpeechBubbleData
import com.talksync.vision.SpeakerFusionConfig.ASSIGNMENT_TIMEOUT_MS
import com.talksync.vision.SpeakerFusionConfig.ACTIVE_SPEAKER_AUDIO_FLOOR
import com.talksync.vision.SpeakerFusionConfig.ACTIVE_SPEAKER_GAZE_FLOOR
import com.talksync.vision.SpeakerFusionConfig.ACTIVE_SPEAKER_MIN_SCORE
import com.talksync.vision.SpeakerFusionConfig.ACTIVE_SPEAKER_MOUTH_FLOOR
import com.talksync.vision.SpeakerFusionConfig.CAMERA_HORIZONTAL_FOV_DEGREES
import com.talksync.vision.SpeakerFusionConfig.MIN_ASSIGN_SCORE
import com.talksync.vision.SpeakerFusionConfig.PINNED_ASSIGNMENT_TIMEOUT_MS
import com.talksync.vision.SpeakerFusionConfig.PIN_HOLD_MS
import com.talksync.vision.SpeakerFusionConfig.SPEAKER_SWITCH_MARGIN
import kotlin.math.abs

/**
 * Maintains stable, confidence-locked speaker-to-face assignments.
 *
 * Four signals fused by [SpeakerFusionScorer]:
 *   - Mouth motion            (primary)  — LipLandmarkAnalyzer.combinedScore
 *   - Gaze/head attention               — face orientation + iris position
 *   - Directional audio       (agreement) — GCC-PHAT angle-of-arrival vs face angle
 *   - Previous-speaker consistency       — existing assignment confidence
 *   - Face visibility                     — size + centering in frame
 *
 * Override hysteresis (see [SpeakerFusionConfig]):
 *   - Best score must beat incumbent by at least [SPEAKER_SWITCH_MARGIN], OR
 *   - incumbent face has been missing for [ASSIGNMENT_TIMEOUT_MS]+.
 *
 * Pinning: a voice that stays locked on the same visible face for [PIN_HOLD_MS]
 * is pinned. A pinned voice never decays below the lock, is not taken by lip
 * cues or a lone remaining face, and survives dropouts for [PINNED_ASSIGNMENT_TIMEOUT_MS].
 *
 * Invariants:
 *   - Each face assigned to at most one speaker at a time
 *   - Assignments survive brief face-tracker dropouts within the timeout grace
 *   - PENDING_SPEAKER_KEY has its own isolated assignment slot
 */
class SpeakerFaceAssignmentStore {

    private val LOCK_THRESHOLD = 0.60f
    private val DECAY_RATE = 0.012f
    private val CONFIRM_GAIN = 0.18f
    private val MIN_CONFIDENCE = 0.08f
    private val MAX_CONFIDENCE = 1.00f

    data class Assignment(
        val faceId: String,
        val confidence: Float,
        val assignedAt: Long,
        val lastConfirmedAt: Long,
        val lastDecayAt: Long,
        val lastSeenAt: Long,
        val isPinned: Boolean = false,
    )

    data class ActiveSpeakerCandidate(
        val faceId: String,
        val score: Float,
        val mouthScore: Float,
        val gazeAttentionScore: Float,
        val directionalAudioScore: Float,
        val visibilityScore: Float,
        val audioActivityScore: Float,
        val updatedAt: Long = System.currentTimeMillis(),
    )

    private data class CandidateScore(
        val faceId: String,
        val score: Float,
        val mouthScore: Float,
        val gazeAttentionScore: Float,
        val directionalAudioScore: Float,
        val visibilityScore: Float,
        val audioActivityScore: Float,
    )

    private val assignments = mutableMapOf<String, Assignment>()

    fun resolve(
        speakerKey: String,
        faces: List<TrackedFace>,
        lipSignals: Map<String, LipLandmarkAnalyzer.LipSignal>,
        audioDirection: AudioDirection? = null,
        audioActivityScore: Float = 1f,
        now: Long = System.currentTimeMillis(),
    ): String? {
        if (faces.isEmpty()) {
            // A pinned voice keeps its face through a full dropout; pruning lets it go later.
            if (assignments[speakerKey]?.isPinned != true) assignments.remove(speakerKey)
            return null
        }

        if (faces.size == 1) {
            val faceId = faces.first().trackId
            pruneMissingFaces(setOf(faceId), now)
            // A pinned voice waits for its own face instead of jumping to the only one left.
            val pinned = assignments[speakerKey]?.takeIf { it.isPinned }
            if (pinned != null && pinned.faceId != faceId) return null
            if (speakerKey != SpeechBubbleData.PENDING_SPEAKER_KEY && assignments.any { (key, assignment) ->
                    key != speakerKey && assignment.faceId == faceId && assignment.confidence >= LOCK_THRESHOLD
                }
            ) return assignments[speakerKey]?.faceId?.takeIf { it == faceId }
            upsert(speakerKey, faceId, 1f, now)
            return faceId
        }

        val liveFaceIds = faces.map { it.trackId }.toSet()
        touchLastSeen(liveFaceIds, now)
        pruneMissingFaces(liveFaceIds, now)
        decayAll(now)

        val current = assignments[speakerKey]

        val scores = scoreCandidates(
            speakerKey = speakerKey,
            faces = faces,
            lipSignals = lipSignals,
            audioDirection = audioDirection,
            audioActivityScore = audioActivityScore,
        )

        if (scores.isEmpty()) {
            return current?.faceId ?: faces.first().trackId
        }

        val bestFaceId = scores.maxByOrNull { it.value }!!.key
        val bestScore = scores[bestFaceId]!!
        val currentScore = current?.let { scores[it.faceId] } ?: 0f
        val incumbentLive = current?.faceId in liveFaceIds
        val incumbentMissingMs = if (current != null && !incumbentLive) {
            now - current.lastSeenAt
        } else 0L

        val shouldOverride = when {
            current == null -> true
            !incumbentLive && incumbentMissingMs >= missingTimeoutFor(current) -> true
            current.confidence < MIN_CONFIDENCE + 0.05f -> true
            bestFaceId != current.faceId &&
                bestScore >= currentScore + SPEAKER_SWITCH_MARGIN &&
                current.confidence < LOCK_THRESHOLD -> true
            else -> false
        }

        val targetFaceId = if (shouldOverride) bestFaceId else current!!.faceId
        val targetScore = scores[targetFaceId] ?: 0f

        if (targetScore < MIN_ASSIGN_SCORE && current == null) {
            return null
        }

        val newConfidence = if (current != null && current.faceId == targetFaceId) {
            (current.confidence + bestScore * CONFIRM_GAIN).coerceAtMost(MAX_CONFIDENCE)
        } else {
            (bestScore * 0.5f).coerceIn(MIN_CONFIDENCE, 0.55f)
        }

        // Holding on to a missing face must not refresh when it was last seen,
        // or its timeout never runs out while the speaker keeps talking.
        val seenAt = if (targetFaceId in liveFaceIds) now else current?.lastSeenAt ?: now
        upsert(speakerKey, targetFaceId, newConfidence, now, seenAt)
        return targetFaceId
    }

    fun remove(speakerKey: String) {
        assignments.remove(speakerKey)
    }

    /**
     * Read-only accessor for the face currently bound to [speakerKey]. Used
     * by display-label code that needs to look up the bound face identity
     * (e.g. for Google Photos-style naming) without mutating state.
     */
    fun faceIdFor(speakerKey: String): String? = assignments[speakerKey]?.faceId

    fun activeSpeakerCandidate(
        faces: List<TrackedFace>,
        lipSignals: Map<String, LipLandmarkAnalyzer.LipSignal>,
        audioDirection: AudioDirection? = null,
        audioActivityScore: Float = 0f,
    ): ActiveSpeakerCandidate? {
        if (faces.isEmpty() || lipSignals.isEmpty()) {
            return null
        }

        val best = scoreCandidateDetails(
            speakerKey = null,
            faces = faces,
            lipSignals = lipSignals,
            audioDirection = audioDirection,
            audioActivityScore = audioActivityScore,
            includeConsistency = false,
            applyOccupiedPenalty = false,
        ).maxByOrNull { it.score } ?: return null

        val audioSupportsSpeech = best.audioActivityScore >= ACTIVE_SPEAKER_AUDIO_FLOOR
        val visualSpeechEvidence = best.mouthScore >= ACTIVE_SPEAKER_MOUTH_FLOOR ||
            (best.mouthScore >= ACTIVE_SPEAKER_MOUTH_FLOOR * 0.55f &&
                best.gazeAttentionScore >= ACTIVE_SPEAKER_GAZE_FLOOR)
        val veryStrongMouthEvidence = best.mouthScore >= ACTIVE_SPEAKER_MOUTH_FLOOR * 3.5f

        if (best.score < ACTIVE_SPEAKER_MIN_SCORE) {
            return null
        }
        if (!visualSpeechEvidence) {
            return null
        }
        if (!audioSupportsSpeech && !veryStrongMouthEvidence) {
            return null
        }

        return ActiveSpeakerCandidate(
            faceId = best.faceId,
            score = best.score,
            mouthScore = best.mouthScore,
            gazeAttentionScore = best.gazeAttentionScore,
            directionalAudioScore = best.directionalAudioScore,
            visibilityScore = best.visibilityScore,
            audioActivityScore = best.audioActivityScore,
        )
    }

    /**
     * Snapshot the current speakerKey → Assignment map for debug overlays.
     * The returned map is a defensive copy so callers can iterate without
     * worrying about concurrent mutation.
     */
    fun snapshotAssignments(): Map<String, Assignment> = assignments.toMap()

    /**
     * Move the face bound to [fromSpeakerKey] onto [toSpeakerKey] at locked
     * confidence. Used when a pending/provisional speaker gets a real diarized
     * label — the anchor must survive the rename.
     */
    fun adopt(
        fromSpeakerKey: String,
        toSpeakerKey: String,
        now: Long = System.currentTimeMillis(),
    ): String? {
        if (fromSpeakerKey == toSpeakerKey) return assignments[toSpeakerKey]?.faceId
        val source = assignments.remove(fromSpeakerKey) ?: return assignments[toSpeakerKey]?.faceId
        assignments[toSpeakerKey]?.let { return it.faceId }
        if (assignments.any { (_, assignment) ->
                assignment.faceId == source.faceId && assignment.confidence >= LOCK_THRESHOLD
            }
        ) return null
        assignments[toSpeakerKey] = source.copy(
            assignedAt = now,
            lastConfirmedAt = now,
            lastDecayAt = now,
            lastSeenAt = now,
            confidence = source.confidence.coerceAtLeast(LOCK_THRESHOLD),
        )
        return source.faceId
    }

    fun bindPending(faceId: String, confidence: Float, now: Long = System.currentTimeMillis()) {
        upsert(SpeechBubbleData.PENDING_SPEAKER_KEY, faceId, confidence, now)
    }

    /**
     * Drop assignments whose face has been missing beyond the timeout grace.
     * Live faces have their lastSeenAt refreshed so the grace only counts from
     * the moment a track actually disappears.
     */
    fun pruneMissingFaces(
        liveFaceIds: Set<String>,
        now: Long = System.currentTimeMillis(),
    ) {
        touchLastSeen(liveFaceIds, now)
        pinHeldAssignments(liveFaceIds, now)
        assignments.entries.removeAll { (_, a) ->
            a.faceId !in liveFaceIds && (now - a.lastSeenAt) > missingTimeoutFor(a)
        }
    }

    fun clear() {
        assignments.clear()
    }

    private fun scoreCandidates(
        speakerKey: String,
        faces: List<TrackedFace>,
        lipSignals: Map<String, LipLandmarkAnalyzer.LipSignal>,
        audioDirection: AudioDirection?,
        audioActivityScore: Float,
    ): Map<String, Float> {
        return scoreCandidateDetails(
            speakerKey = speakerKey,
            faces = faces,
            lipSignals = lipSignals,
            audioDirection = audioDirection,
            audioActivityScore = audioActivityScore,
            includeConsistency = true,
            applyOccupiedPenalty = true,
        ).associate { it.faceId to it.score }
    }

    private fun scoreCandidateDetails(
        speakerKey: String?,
        faces: List<TrackedFace>,
        lipSignals: Map<String, LipLandmarkAnalyzer.LipSignal>,
        audioDirection: AudioDirection?,
        audioActivityScore: Float,
        includeConsistency: Boolean,
        applyOccupiedPenalty: Boolean,
    ): List<CandidateScore> {
        val occupiedByOther = assignments
            .filterKeys { it != speakerKey }
            .filter { (_, a) -> a.confidence >= LOCK_THRESHOLD }
            .values
            .map { it.faceId }
            .toSet()

        val audioConfidence = audioDirection?.confidence ?: 0f
        val audioAvailable = audioConfidence > 0.05f
        val audioAngle = audioDirection?.angleDegrees ?: 0f

        val current = speakerKey?.let { assignments[it] }

        val maxArea = faces.maxOf { it.boundingBox.width() * it.boundingBox.height() }
            .coerceAtLeast(1e-4f)

        return faces.map { face ->
            val lipSignal = lipSignals[face.trackId]
            val mouth = lipSignal?.combinedScore ?: 0f
            val gazeAttention = lipSignal?.visualAttentionScore ?: 0f

            val faceAngle = FaceAngleMapping.rectCenterToAngleDegrees(
                face.boundingBox,
                CAMERA_HORIZONTAL_FOV_DEGREES,
            )
            val directional = if (audioAvailable) {
                FaceAngleMapping.directionalAudioScore(faceAngle, audioAngle) *
                    audioConfidence
            } else 0f

            val consistency = if (includeConsistency && current?.faceId == face.trackId) {
                current.confidence
            } else {
                0f
            }

            val centerX = face.boundingBox.centerX()
            val centerY = face.boundingBox.centerY()
            val centering = 1f - (abs(centerX - 0.5f) + abs(centerY - 0.5f)).coerceIn(0f, 1f)
            val relativeArea = (face.boundingBox.width() * face.boundingBox.height() / maxArea)
                .coerceIn(0f, 1f)
            val visibility = (0.6f * centering + 0.4f * relativeArea).coerceIn(0f, 1f)

            var score = SpeakerFusionScorer.score(
                input = SpeakerFusionScorer.Input(
                    mouthMotionScore = mouth,
                    directionalAudioScore = directional,
                    previousSpeakerConsistencyScore = consistency,
                    faceVisibilityScore = visibility,
                    gazeAttentionScore = gazeAttention,
                    audioActivityScore = audioActivityScore,
                ),
                audioAvailable = audioAvailable,
            )

            // A face already locked (≥ LOCK_THRESHOLD confidence) to another
            // speaker gets a heavy penalty so a clear lip signal on face B
            // doesn't accidentally pull it away from the speaker it belongs to.
            // Tightened from 0.40 → 0.20 to make the lock much harder to steal.
            if (applyOccupiedPenalty && face.trackId in occupiedByOther) {
                score *= 0.20f
            }

            CandidateScore(
                faceId = face.trackId,
                score = score,
                mouthScore = mouth,
                gazeAttentionScore = gazeAttention,
                directionalAudioScore = directional,
                visibilityScore = visibility,
                audioActivityScore = audioActivityScore.coerceIn(0f, 1f),
            )
        }
    }

    private fun touchLastSeen(liveFaceIds: Set<String>, now: Long) {
        assignments.entries.forEach { (key, a) ->
            if (a.faceId in liveFaceIds) {
                assignments[key] = a.copy(lastSeenAt = now)
            }
        }
    }

    /** A voice that has stayed locked on one visible face for [PIN_HOLD_MS] is pinned to it. */
    private fun pinHeldAssignments(liveFaceIds: Set<String>, now: Long) {
        assignments.entries.forEach { (key, a) ->
            if (!a.isPinned && key != SpeechBubbleData.PENDING_SPEAKER_KEY &&
                a.faceId in liveFaceIds && a.confidence >= LOCK_THRESHOLD &&
                now - a.assignedAt >= PIN_HOLD_MS
            ) {
                assignments[key] = a.copy(isPinned = true)
            }
        }
    }

    private fun missingTimeoutFor(assignment: Assignment): Long =
        if (assignment.isPinned) PINNED_ASSIGNMENT_TIMEOUT_MS else ASSIGNMENT_TIMEOUT_MS

    private fun decayAll(now: Long) {
        val decayed = assignments.mapValues { (_, a) ->
            val elapsedSeconds = (now - a.lastDecayAt) / 1000f
            // Pinned voices never fade below the lock, so a quiet speaker keeps their face.
            val floor = if (a.isPinned) LOCK_THRESHOLD else MIN_CONFIDENCE
            val decayedConf = (a.confidence - DECAY_RATE * elapsedSeconds)
                .coerceAtLeast(floor)
            a.copy(
                confidence = decayedConf,
                lastDecayAt = now,
            )
        }
        assignments.putAll(decayed)
    }

    private fun upsert(
        speakerKey: String,
        faceId: String,
        confidence: Float,
        now: Long,
        seenAt: Long = now,
    ) {
        // Hold time and pinning belong to one face; moving to another face starts over.
        val sameFace = assignments[speakerKey]?.takeIf { it.faceId == faceId }
        assignments[speakerKey] = Assignment(
            faceId = faceId,
            confidence = confidence,
            assignedAt = sameFace?.assignedAt ?: now,
            lastConfirmedAt = now,
            lastDecayAt = now,
            lastSeenAt = seenAt,
            isPinned = sameFace?.isPinned ?: false,
        )
    }
}
