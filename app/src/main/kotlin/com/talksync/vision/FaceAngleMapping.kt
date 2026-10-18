package com.talksync.vision

// plain math for turning face x-position into an approximate camera angle.

import android.graphics.RectF
import kotlin.math.abs

object FaceAngleMapping {

    const val DEFAULT_HORIZONTAL_FOV_DEGREES: Float = 70f

    /**
     * Map a normalized horizontal face center [0..1] to a signed angle in degrees
     * where 0 is camera center, negative = left, positive = right.
     *
     * faceAngle = ((faceCenterX / frameWidth) - 0.5) * cameraHorizontalFovDegrees
     *
     * Since upstream [TrackedFace.boundingBox] is already normalized to [0..1]
     * by [FaceTracker.normalizeBoundingBox], callers pass normalizedCenterX
     * directly and do not need to supply frameWidth.
     */
    fun normalizedCenterToAngleDegrees(
        normalizedCenterX: Float,
        horizontalFovDegrees: Float = DEFAULT_HORIZONTAL_FOV_DEGREES,
    ): Float {
        val clamped = normalizedCenterX.coerceIn(0f, 1f)
        return (clamped - 0.5f) * horizontalFovDegrees
    }

    /** Shortcut for the common `RectF` case. */
    fun rectCenterToAngleDegrees(
        normalizedBox: RectF,
        horizontalFovDegrees: Float = DEFAULT_HORIZONTAL_FOV_DEGREES,
    ): Float = normalizedCenterToAngleDegrees(normalizedBox.centerX(), horizontalFovDegrees)

    /**
     * Score how well [faceAngleDegrees] agrees with [audioAngleDegrees]. Returns
     * 1.0 for a perfect match, falling linearly to 0.0 as angular error reaches
     * [maxAngleToleranceDegrees].
     */
    fun directionalAudioScore(
        faceAngleDegrees: Float,
        audioAngleDegrees: Float,
        maxAngleToleranceDegrees: Float = SpeakerFusionConfig.MAX_ANGLE_TOLERANCE_DEGREES,
    ): Float {
        if (maxAngleToleranceDegrees <= 0f) return 0f
        val error = abs(audioAngleDegrees - faceAngleDegrees)
        return (1f - (error / maxAngleToleranceDegrees)).coerceIn(0f, 1f)
    }
}
