package com.talksync.audio

// quick left/right balance check before the bigger direction math gets involved.

import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Computes a stereo panning score from interleaved stereo PCM audio.
 *
 * Input: raw 16-bit little-endian interleaved stereo PCM at 16kHz
 * Output: pan score -1.0 (fully left) to +1.0 (fully right), 0.0 = center
 *
 * Formula: (rmsRight - rmsLeft) / (rmsRight + rmsLeft + epsilon)
 *
 * NOTE: Pixel 10a (and most phones) have top/bottom mic placement, not true left/right stereo.
 * Pan scores reflect front/back separation, not horizontal direction. This signal acts as a
 * tiebreaker only — lip activity is the primary signal on this hardware.
 * PAN_WEIGHT in SpeakerFaceAssignmentStore is set to 0.18f accordingly.
 */
class StereoPanAnalyzer {

    private val _panScore = MutableStateFlow(0f)
    val panScore: StateFlow<Float> = _panScore.asStateFlow()

    private var smoothedPan = 0f
    private val SMOOTH_ALPHA = 0.25f
    private val EPSILON = 1e-6f
    private val MIN_COMBINED_RMS = 80f

    fun onStereoPcmChunk(interleavedStereo: ByteArray) {
        if (interleavedStereo.size < 8) return

        val frameCount = interleavedStereo.size / 4
        var sumSqL = 0.0
        var sumSqR = 0.0

        for (frame in 0 until frameCount) {
            val base = frame * 4
            val sampleL = ((interleavedStereo[base + 1].toInt() shl 8) or
                (interleavedStereo[base + 0].toInt() and 0xFF)).toShort().toDouble()
            val sampleR = ((interleavedStereo[base + 3].toInt() shl 8) or
                (interleavedStereo[base + 2].toInt() and 0xFF)).toShort().toDouble()
            sumSqL += sampleL * sampleL
            sumSqR += sampleR * sampleR
        }

        val rmsL = sqrt(sumSqL / frameCount).toFloat()
        val rmsR = sqrt(sumSqR / frameCount).toFloat()
        val combined = rmsL + rmsR

        if (combined < MIN_COMBINED_RMS) {
            smoothedPan *= 0.92f
            _panScore.value = smoothedPan
            return
        }

        val rawPan = (rmsR - rmsL) / (combined + EPSILON)
        smoothedPan += (rawPan - smoothedPan) * SMOOTH_ALPHA
        _panScore.value = smoothedPan
    }

    fun panToFaceX(pan: Float): Float = ((pan + 1f) / 2f).coerceIn(0f, 1f)

    fun reset() {
        smoothedPan = 0f
        _panScore.value = 0f
    }
}
