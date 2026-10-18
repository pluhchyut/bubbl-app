package com.talksync.vision

// rolling baseline so tiny jitter doesnt look like real speech activity.

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Estimates ambient noise level [0..1] from rolling audio volume samples.
 *
 * - Between utterances, ambient volume *is* the noise.
 * - During active speech, noise = volume - expected_speech_level (the
 *   adaptive average of recent speech volume).
 * - Smoothed with EMA(alpha=0.15) so the value doesn't jitter on every
 *   PCM chunk.
 */
class NoiseEstimator {

    companion object {
        private const val EMA_ALPHA = 0.15f
        private const val SPEECH_AVG_ALPHA = 0.05f
        private const val DEFAULT_SPEECH_LEVEL = 0.45f
    }

    private val _noiseLevel = MutableStateFlow(0f)
    val noiseLevel: StateFlow<Float> = _noiseLevel.asStateFlow()

    @Volatile private var smoothedNoise: Float = 0f
    @Volatile private var speechAverage: Float = DEFAULT_SPEECH_LEVEL

    fun onVolumeSample(volume: Float, isSpeechActive: Boolean) {
        val v = volume.coerceIn(0f, 1f)
        val target = if (isSpeechActive) {
            speechAverage += (v - speechAverage) * SPEECH_AVG_ALPHA
            // The portion of the signal that exceeds the expected speech
            // level is treated as headroom — i.e., loud over what's
            // typical for this speaker — and contributes to noise.
            abs(v - speechAverage).coerceIn(0f, 1f)
        } else {
            v
        }
        smoothedNoise += (target - smoothedNoise) * EMA_ALPHA
        _noiseLevel.value = smoothedNoise.coerceIn(0f, 1f)
    }

    fun reset() {
        smoothedNoise = 0f
        speechAverage = DEFAULT_SPEECH_LEVEL
        _noiseLevel.value = 0f
    }
}
