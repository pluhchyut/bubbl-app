package com.talksync.vision

// one place for fusion weights, because scorer tests and runtime should not disagree.

object SpeakerFusionConfig {
    const val CAMERA_HORIZONTAL_FOV_DEGREES: Float = FaceAngleMapping.DEFAULT_HORIZONTAL_FOV_DEGREES
    const val MAX_ANGLE_TOLERANCE_DEGREES: Float = 25f

    // Visual speech is still primary, but gaze/head pose now contributes a
    // small stabilizing signal and stereo direction can help when available.
    // If directional audio is unavailable, its weight is redistributed onto
    // mouth motion inside SpeakerFusionScorer.
    const val MOUTH_MOTION_WEIGHT: Float = 0.72f
    const val GAZE_ATTENTION_WEIGHT: Float = 0.12f
    /** Lowered 0.08 → 0.03 (moved onto mouth motion): the phone's mic pair gives a
     *  coarse, orientation-blind angle, so direction should only break near-ties. */
    const val DIRECTIONAL_AUDIO_WEIGHT: Float = 0.03f
    const val CONSISTENCY_WEIGHT: Float = 0.10f
    const val VISIBILITY_WEIGHT: Float = 0.03f

    /** New-face score must beat current assignment by this margin to switch.
     *  Lowered from 0.15 → 0.08 so a clear lip signal can override a stale
     *  lock without waiting for it to fully decay. */
    const val SPEAKER_SWITCH_MARGIN: Float = 0.08f

    /** After this much time without the assigned face being seen, allow a switch. */
    const val ASSIGNMENT_TIMEOUT_MS: Long = 500L

    /** A voice that stays locked on the same visible face this long is pinned to it. */
    const val PIN_HOLD_MS: Long = 3_000L

    /** A pinned voice waits this long for its face to come back before letting go. */
    const val PINNED_ASSIGNMENT_TIMEOUT_MS: Long = 2_000L

    /** Below this fused score we refuse to anchor a transcript bubble to a face. */
    const val MIN_ASSIGN_SCORE: Float = 0.20f

    /** Minimum score for non-diarized visual active-speaker highlighting. */
    const val ACTIVE_SPEAKER_MIN_SCORE: Float = 0.18f

    /** Below this live audio level, visual cues must be very strong to highlight. */
    const val ACTIVE_SPEAKER_AUDIO_FLOOR: Float = 0.10f

    const val ACTIVE_SPEAKER_MOUTH_FLOOR: Float = 0.05f
    const val ACTIVE_SPEAKER_GAZE_FLOOR: Float = 0.58f
}
