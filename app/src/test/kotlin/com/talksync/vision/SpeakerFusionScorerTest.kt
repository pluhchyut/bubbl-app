package com.talksync.vision

// tests speaker scoring weights without needing android, cameras, or sadness.

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerFusionScorerTest {

    @Test
    fun `weights sum to one`() {
        val sum = SpeakerFusionScorer.Weights().sum
        assertEquals(1f, sum, 1e-4f)
    }

    @Test
    fun `all-max signals score one`() {
        val score = SpeakerFusionScorer.score(
            input = SpeakerFusionScorer.Input(
                mouthMotionScore = 1f,
                directionalAudioScore = 1f,
                previousSpeakerConsistencyScore = 1f,
                faceVisibilityScore = 1f,
                gazeAttentionScore = 1f,
                audioActivityScore = 1f,
            ),
        )
        assertEquals(1f, score, 1e-4f)
    }

    @Test
    fun `all-zero signals score zero`() {
        val score = SpeakerFusionScorer.score(
            input = SpeakerFusionScorer.Input(0f, 0f, 0f, 0f),
        )
        assertEquals(0f, score, 1e-4f)
    }

    @Test
    fun `default weights apply spec ratios`() {
        val score = SpeakerFusionScorer.score(
            input = SpeakerFusionScorer.Input(
                mouthMotionScore = 1f,
                directionalAudioScore = 0f,
                previousSpeakerConsistencyScore = 0f,
                faceVisibilityScore = 0f,
            ),
        )
        assertEquals(SpeakerFusionConfig.MOUTH_MOTION_WEIGHT, score, 1e-4f)
    }

    @Test
    fun `audio-unavailable redistributes audio weight to mouth motion`() {
        val input = SpeakerFusionScorer.Input(
            mouthMotionScore = 1f,
            directionalAudioScore = 0f,
            previousSpeakerConsistencyScore = 0f,
            faceVisibilityScore = 0f,
        )
        val scoreWithAudio = SpeakerFusionScorer.score(input, audioAvailable = true)
        val scoreNoAudio = SpeakerFusionScorer.score(input, audioAvailable = false)
        // mouth carries audio's weight when audio is unavailable
        val expectedLift = SpeakerFusionConfig.MOUTH_MOTION_WEIGHT +
            SpeakerFusionConfig.DIRECTIONAL_AUDIO_WEIGHT
        assertEquals(expectedLift, scoreNoAudio, 1e-4f)
        assertTrue(scoreNoAudio > scoreWithAudio)
    }

    @Test
    fun `gaze attention contributes to visual speaker score`() {
        val score = SpeakerFusionScorer.score(
            input = SpeakerFusionScorer.Input(
                mouthMotionScore = 0f,
                directionalAudioScore = 0f,
                previousSpeakerConsistencyScore = 0f,
                faceVisibilityScore = 0f,
                gazeAttentionScore = 1f,
                audioActivityScore = 1f,
            ),
        )
        assertEquals(SpeakerFusionConfig.GAZE_ATTENTION_WEIGHT, score, 1e-4f)
    }

    @Test
    fun `low audio activity dampens mouth and gaze cues`() {
        val quietScore = SpeakerFusionScorer.score(
            input = SpeakerFusionScorer.Input(
                mouthMotionScore = 1f,
                directionalAudioScore = 0f,
                previousSpeakerConsistencyScore = 0f,
                faceVisibilityScore = 0f,
                gazeAttentionScore = 1f,
                audioActivityScore = 0f,
            ),
        )
        val activeScore = SpeakerFusionScorer.score(
            input = SpeakerFusionScorer.Input(
                mouthMotionScore = 1f,
                directionalAudioScore = 0f,
                previousSpeakerConsistencyScore = 0f,
                faceVisibilityScore = 0f,
                gazeAttentionScore = 1f,
                audioActivityScore = 1f,
            ),
        )
        assertTrue(activeScore > quietScore)
    }

    @Test
    fun `input is clamped to unit interval`() {
        val score = SpeakerFusionScorer.score(
            input = SpeakerFusionScorer.Input(
                mouthMotionScore = 4f,
                directionalAudioScore = -1f,
                previousSpeakerConsistencyScore = 2f,
                faceVisibilityScore = -3f,
            ),
        )
        assertTrue(score in 0f..1f)
    }

    @Test
    fun `a strong direction reading cannot outvote a clearly more active mouth`() {
        val moreActiveMouth = SpeakerFusionScorer.score(
            input = SpeakerFusionScorer.Input(
                mouthMotionScore = 0.5f,
                directionalAudioScore = 0f,
                previousSpeakerConsistencyScore = 0f,
                faceVisibilityScore = 0f,
            ),
        )
        val quieterMouthWithDirection = SpeakerFusionScorer.score(
            input = SpeakerFusionScorer.Input(
                mouthMotionScore = 0.4f,
                directionalAudioScore = 1f,
                previousSpeakerConsistencyScore = 0f,
                faceVisibilityScore = 0f,
            ),
        )
        assertTrue(moreActiveMouth > quieterMouthWithDirection)
    }

    @Test
    fun `mouth motion dominates over weaker signals`() {
        val mouthHeavy = SpeakerFusionScorer.score(
            input = SpeakerFusionScorer.Input(
                mouthMotionScore = 1f,
                directionalAudioScore = 0f,
                previousSpeakerConsistencyScore = 0f,
                faceVisibilityScore = 0f,
            ),
        )
        val consistencyHeavy = SpeakerFusionScorer.score(
            input = SpeakerFusionScorer.Input(
                mouthMotionScore = 0f,
                directionalAudioScore = 0f,
                previousSpeakerConsistencyScore = 1f,
                faceVisibilityScore = 0f,
            ),
        )
        assertTrue(mouthHeavy > consistencyHeavy)
    }
}
