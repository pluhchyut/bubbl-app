package com.talksync.vision

// jvm-friendly scoring math for "which face is probably talking".

object SpeakerFusionScorer {

    data class Input(
        /** [0..1] mouth motion (aperture + velocity) from lip landmarks. */
        val mouthMotionScore: Float,
        /** [0..1] directional audio agreement (see FaceAngleMapping). */
        val directionalAudioScore: Float,
        /** [0..1] how consistent this face has been the previous speaker. */
        val previousSpeakerConsistencyScore: Float,
        /** [0..1] how visible / centered the face is right now. */
        val faceVisibilityScore: Float,
        /** [0..1] head-pose + eye-gaze attention from MediaPipe landmarks. */
        val gazeAttentionScore: Float = 0f,
        /** [0..1] current global audio activity, used to gate visual speech cues. */
        val audioActivityScore: Float = 1f,
    )

    data class Weights(
        val mouthMotion: Float = SpeakerFusionConfig.MOUTH_MOTION_WEIGHT,
        val gazeAttention: Float = SpeakerFusionConfig.GAZE_ATTENTION_WEIGHT,
        val directionalAudio: Float = SpeakerFusionConfig.DIRECTIONAL_AUDIO_WEIGHT,
        val consistency: Float = SpeakerFusionConfig.CONSISTENCY_WEIGHT,
        val visibility: Float = SpeakerFusionConfig.VISIBILITY_WEIGHT,
    ) {
        val sum: Float get() = mouthMotion + gazeAttention + directionalAudio + consistency + visibility
    }

    /**
     * Weighted fusion. When the directional-audio signal has zero confidence
     * (e.g. mono capture), callers should pass [directionalAudioScore] = 0 and
     * set [audioAvailable] = false — the audio weight is redistributed onto
     * mouth motion so the remaining signals still sum to 1. [audioActivityScore]
     * is a global amplitude gate: quiet rooms damp visual mouth/gaze cues, while
     * current speech energy lets those cues switch faster than diarization.
     */
    fun score(
        input: Input,
        weights: Weights = Weights(),
        audioAvailable: Boolean = true,
    ): Float {
        val mouth = clamp01(input.mouthMotionScore)
        val audio = clamp01(input.directionalAudioScore)
        val consistency = clamp01(input.previousSpeakerConsistencyScore)
        val visibility = clamp01(input.faceVisibilityScore)
        val gaze = clamp01(input.gazeAttentionScore)
        val audioActivity = clamp01(input.audioActivityScore)
        val audioVisualGate = 0.35f + (audioActivity * 0.65f)
        val gatedMouth = mouth * audioVisualGate
        val gatedGaze = gaze * audioVisualGate
        return if (audioAvailable) {
            gatedMouth * weights.mouthMotion +
                gatedGaze * weights.gazeAttention +
                audio * weights.directionalAudio +
                consistency * weights.consistency +
                visibility * weights.visibility
        } else {
            val lipLift = weights.mouthMotion + weights.directionalAudio
            gatedMouth * lipLift +
                gatedGaze * weights.gazeAttention +
                consistency * weights.consistency +
                visibility * weights.visibility
        }.coerceIn(0f, 1f)
    }

    private fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)
}
