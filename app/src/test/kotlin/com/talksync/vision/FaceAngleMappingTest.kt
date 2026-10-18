package com.talksync.vision

// keeps face-position -> angle math from drifting into nonsense.

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceAngleMappingTest {

    @Test
    fun `center of frame maps to zero degrees`() {
        val angle = FaceAngleMapping.normalizedCenterToAngleDegrees(0.5f, 70f)
        assertEquals(0f, angle, 1e-4f)
    }

    @Test
    fun `left edge maps to negative half fov`() {
        val angle = FaceAngleMapping.normalizedCenterToAngleDegrees(0f, 70f)
        assertEquals(-35f, angle, 1e-4f)
    }

    @Test
    fun `right edge maps to positive half fov`() {
        val angle = FaceAngleMapping.normalizedCenterToAngleDegrees(1f, 70f)
        assertEquals(35f, angle, 1e-4f)
    }

    @Test
    fun `out-of-range center is clamped`() {
        val angleLo = FaceAngleMapping.normalizedCenterToAngleDegrees(-5f, 70f)
        val angleHi = FaceAngleMapping.normalizedCenterToAngleDegrees(5f, 70f)
        assertEquals(-35f, angleLo, 1e-4f)
        assertEquals(35f, angleHi, 1e-4f)
    }

    @Test
    fun `perfect angle match scores one`() {
        val score = FaceAngleMapping.directionalAudioScore(
            faceAngleDegrees = 10f,
            audioAngleDegrees = 10f,
            maxAngleToleranceDegrees = 25f,
        )
        assertEquals(1f, score, 1e-4f)
    }

    @Test
    fun `error beyond tolerance scores zero`() {
        val score = FaceAngleMapping.directionalAudioScore(
            faceAngleDegrees = -20f,
            audioAngleDegrees = 20f,
            maxAngleToleranceDegrees = 25f,
        )
        assertEquals(0f, score, 1e-4f)
    }

    @Test
    fun `partial mismatch scores linearly`() {
        val score = FaceAngleMapping.directionalAudioScore(
            faceAngleDegrees = 0f,
            audioAngleDegrees = 12.5f,
            maxAngleToleranceDegrees = 25f,
        )
        assertEquals(0.5f, score, 1e-4f)
    }

    @Test
    fun `zero tolerance is a safe no-op`() {
        val score = FaceAngleMapping.directionalAudioScore(
            faceAngleDegrees = 0f,
            audioAngleDegrees = 0f,
            maxAngleToleranceDegrees = 0f,
        )
        assertTrue(score == 0f)
    }
}
