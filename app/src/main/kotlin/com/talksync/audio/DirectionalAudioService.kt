package com.talksync.audio

// figures out left/center/right-ish speech direction from stereo pcm.
// it uses gcc-phat math rn, but keep AudioDirection the same if real beamforming replaces it.
// angles are device-frame degrees: negative left, zero center, positive right.

import android.util.Log
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Snapshot produced by [DirectionalAudioService]. Angle is expressed in the
 * same horizontal coordinate system as [com.talksync.vision.FaceAngleMapping]
 * (camera frame, degrees, negative = left).
 */
data class AudioDirection(
    val timestampMs: Long,
    val angleDegrees: Float,
    val confidence: Float,
    val isSpeech: Boolean,
) {
    companion object {
        val IDLE = AudioDirection(
            timestampMs = 0L,
            angleDegrees = 0f,
            confidence = 0f,
            isSpeech = false,
        )
    }
}

class DirectionalAudioService(
    /** Half-aperture of the angle output, in degrees — clamp |angle| ≤ this. */
    private val angleHalfRangeDegrees: Float = 35f,
    /** Sample rate assumed for incoming stereo PCM. */
    private val sampleRateHz: Int = MIC_SAMPLE_RATE,
) {

    private val _direction = MutableStateFlow(AudioDirection.IDLE)
    val direction: StateFlow<AudioDirection> = _direction.asStateFlow()

    @Volatile
    private var stereoCapture: Boolean = false
    private val _isStereoCapture = MutableStateFlow(false)
    val isStereoCapture: StateFlow<Boolean> = _isStereoCapture.asStateFlow()

    private var smoothedAngle: Float = 0f
    private var smoothedConfidence: Float = 0f
    private var noiseFloor: Float = 120f

    fun onStereoPcmChunk(interleavedStereo: ByteArray, nowMs: Long = System.currentTimeMillis()) {
        if (interleavedStereo.size < MIN_CHUNK_BYTES) return
        val frameCount = interleavedStereo.size / 4
        if (frameCount < MIN_FRAMES) return

        val left = FloatArray(frameCount)
        val right = FloatArray(frameCount)
        var sumSqL = 0.0
        var sumSqR = 0.0
        var diffAbs = 0.0
        var commonAbs = 0.0
        for (frame in 0 until frameCount) {
            val base = frame * 4
            val l = ((interleavedStereo[base + 1].toInt() shl 8) or
                (interleavedStereo[base + 0].toInt() and 0xFF)).toShort().toFloat()
            val r = ((interleavedStereo[base + 3].toInt() shl 8) or
                (interleavedStereo[base + 2].toInt() and 0xFF)).toShort().toFloat()
            left[frame] = l
            right[frame] = r
            sumSqL += (l * l).toDouble()
            sumSqR += (r * r).toDouble()
            diffAbs += abs(l - r).toDouble()
            commonAbs += (abs(l) + abs(r)).toDouble()
        }
        val rmsL = sqrt(sumSqL / frameCount).toFloat()
        val rmsR = sqrt(sumSqR / frameCount).toFloat()
        val rms = (rmsL + rmsR) * 0.5f

        // Mono-capture detection: both engines duplicate L=R when the hardware is
        // mono, so a near-zero inter-channel difference means we have no useful
        // direction information. Collapse confidence to 0 in that case.
        val isTrueStereo = commonAbs > 1e-3 && (diffAbs / commonAbs) > MONO_PASSTHROUGH_RATIO
        if (stereoCapture != isTrueStereo) {
            stereoCapture = isTrueStereo
            _isStereoCapture.value = isTrueStereo
        }

        // Adaptive noise floor — slow upward drift, quick downward track.
        noiseFloor = if (rms < noiseFloor) {
            noiseFloor * 0.96f + rms * 0.04f
        } else {
            noiseFloor * 0.995f + rms * 0.005f
        }
        val speechThreshold = max(MIN_SPEECH_RMS, noiseFloor * SPEECH_GATE_RATIO)
        val isSpeech = rms > speechThreshold

        if (!isTrueStereo || !isSpeech) {
            smoothedConfidence *= 0.85f
            // Keep the last known angle but let confidence decay — fast enough to
            // matter, slow enough that a brief silence doesn't release assignment.
            publish(nowMs, smoothedAngle, smoothedConfidence, isSpeech)
            return
        }

        val peak = gccPhatPeak(left, right) ?: run {
            smoothedConfidence *= 0.85f
            publish(nowMs, smoothedAngle, smoothedConfidence, isSpeech)
            return
        }

        val normalizedLag = (peak.lag.toFloat() / MAX_LAG_SAMPLES).coerceIn(-1f, 1f)
        val rawAngle = normalizedLag * angleHalfRangeDegrees
        val rawConfidence = peak.strength.coerceIn(0f, 1f)

        smoothedAngle += (rawAngle - smoothedAngle) * ANGLE_SMOOTH_ALPHA
        smoothedConfidence += (rawConfidence - smoothedConfidence) * CONFIDENCE_SMOOTH_ALPHA

        publish(nowMs, smoothedAngle, smoothedConfidence, isSpeech)
    }

    fun reset() {
        smoothedAngle = 0f
        smoothedConfidence = 0f
        noiseFloor = 120f
        stereoCapture = false
        _isStereoCapture.value = false
        _direction.value = AudioDirection.IDLE
    }

    private fun publish(nowMs: Long, angle: Float, confidence: Float, speech: Boolean) {
        if (speech) Log.d("DirectionalAudio", "isSpeech=true angle=$angle conf=$confidence stereo=$stereoCapture noiseFloor=$noiseFloor")
        val clampedAngle = angle.coerceIn(-angleHalfRangeDegrees, angleHalfRangeDegrees)
        val clampedConf = confidence.coerceIn(0f, 1f)
        _direction.value = AudioDirection(
            timestampMs = nowMs,
            angleDegrees = clampedAngle,
            confidence = clampedConf,
            isSpeech = speech,
        )
    }

    private data class CorrelationPeak(val lag: Int, val strength: Float)

    /**
     * Simplified GCC-PHAT: time-domain PHAT-ish cross-correlation over a small lag
     * window. We search |lag| ≤ [MAX_LAG_SAMPLES] which covers realistic on-device
     * mic spacing (≈15 cm @ 340 m/s ≈ 7 samples @ 16 kHz). This is intentionally
     * lightweight; a full FFT/PHAT can replace this body when a proper mic-array
     * is available without touching the public contract.
     */
    private fun gccPhatPeak(left: FloatArray, right: FloatArray): CorrelationPeak? {
        val n = left.size
        if (n <= MAX_LAG_SAMPLES * 2) return null

        var bestLag = 0
        var bestValue = Float.NEGATIVE_INFINITY
        var absSum = 0f

        // Whitening-lite: divide each lag's correlation by its own magnitude so
        // loud broadband noise doesn't dominate a tonal source, approximating the
        // PHAT normalization without an FFT pass.
        for (lag in -MAX_LAG_SAMPLES..MAX_LAG_SAMPLES) {
            val start = if (lag >= 0) 0 else -lag
            val end = if (lag >= 0) n - lag else n
            if (end - start < MIN_OVERLAP) continue
            var corr = 0f
            var magL = 0f
            var magR = 0f
            var i = start
            while (i < end) {
                val l = left[i]
                val r = right[i + lag]
                corr += l * r
                magL += l * l
                magR += r * r
                i++
            }
            val denom = sqrt((magL * magR).toDouble()).toFloat() + 1e-6f
            val phat = corr / denom
            val mag = abs(phat)
            absSum += mag
            if (phat > bestValue) {
                bestValue = phat
                bestLag = lag
            }
        }

        if (absSum <= 1e-6f) return null
        // peak-to-average ratio is the sharpness heuristic we use for confidence:
        // a decisive lag gives a tall spike; diffuse sources give a flat ridge.
        val avg = absSum / (MAX_LAG_SAMPLES * 2 + 1)
        val sharpness = ((bestValue - avg) / (avg + 1e-6f))
            .coerceIn(0f, 4f)
        val confidence = (ln(1f + sharpness) / ln(5f)).coerceIn(0f, 1f)
        return CorrelationPeak(lag = bestLag, strength = confidence)
    }

    companion object {
        private const val MIN_CHUNK_BYTES = 64
        private const val MIN_FRAMES = 160
        private const val MIN_OVERLAP = 80
        private const val MAX_LAG_SAMPLES = 8
        private const val MONO_PASSTHROUGH_RATIO = 0.01
        private const val MIN_SPEECH_RMS = 80f
        private const val SPEECH_GATE_RATIO = 1.8f
        private const val ANGLE_SMOOTH_ALPHA = 0.30f
        private const val CONFIDENCE_SMOOTH_ALPHA = 0.40f
    }
}
