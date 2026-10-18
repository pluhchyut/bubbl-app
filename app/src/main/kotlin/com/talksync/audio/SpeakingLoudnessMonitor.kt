package com.talksync.audio

// watches your recent voice level so the app can say "too quiet" or "way too loud".

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the user's speaking volume baseline and detects when they are
 * speaking significantly louder than their normal level.
 *
 * Algorithm:
 * 1. Only samples volume when isUserTalking is true
 * 2. Maintains an exponential moving average of speaking volume as "baseline"
 * 3. When current utterance average exceeds baseline * LOUD_MULTIPLIER,
 *    emits isSpeakingLoud = true
 * 4. Resets after LOUD_HOLD_MS milliseconds automatically
 *
 * Designed to be forgiving — requires sustained loudness, not a single spike.
 */
class SpeakingLoudnessMonitor {

    private val _isSpeakingLoud = MutableStateFlow(false)
    val isSpeakingLoud: StateFlow<Boolean> = _isSpeakingLoud.asStateFlow()

    // 65% above baseline
    private val LOUD_MULTIPLIER = 1.65f
    private val MIN_BASELINE = 0.22f
    private val LOUD_HOLD_MS = 3_000L
    private val SPEECH_FLOOR = 0.12f
    private val BASELINE_ALPHA = 0.08f

    private var utteranceVolumeSum = 0f
    private var utteranceSampleCount = 0
    private var wasUserTalking = false

    private var baselineVolume = 0f
    private var baselineSampleCount = 0

    private var loudStateSetAt = 0L

    /**
     * Call this on every audio tick with the current volume and talking state.
     * Safe to call from coroutine context in ViewModel.
     *
     * @param currentVolume  Normalized volume 0..1 from AudioAnalyzer
     * @param isUserTalking  Whether user VAD detected speech
     * @param now            Current timestamp ms
     */
    fun onVolumeTick(
        currentVolume: Float,
        isUserTalking: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        if (_isSpeakingLoud.value && now - loudStateSetAt >= LOUD_HOLD_MS) {
            _isSpeakingLoud.value = false
        }

        if (isUserTalking) {
            if (!wasUserTalking) {
                utteranceVolumeSum = 0f
                utteranceSampleCount = 0
            }

            if (currentVolume >= SPEECH_FLOOR) {
                utteranceVolumeSum += currentVolume
                utteranceSampleCount++
            }

            wasUserTalking = true
        } else {
            if (wasUserTalking && utteranceSampleCount >= 5) {
                val utteranceAvg = utteranceVolumeSum / utteranceSampleCount

                if (baselineSampleCount < 3) {
                    baselineVolume = if (baselineSampleCount == 0) {
                        utteranceAvg
                    } else {
                        baselineVolume + (utteranceAvg - baselineVolume) * BASELINE_ALPHA
                    }
                    baselineSampleCount++
                } else {
                    val effectiveBaseline = baselineVolume.coerceAtLeast(MIN_BASELINE)
                    val isLoud = utteranceAvg > effectiveBaseline * LOUD_MULTIPLIER

                    if (isLoud) {
                        _isSpeakingLoud.value = true
                        loudStateSetAt = now
                    }

                    val alpha = if (utteranceAvg < baselineVolume) {
                        BASELINE_ALPHA * 1.5f
                    } else {
                        BASELINE_ALPHA * 0.5f
                    }
                    baselineVolume += (utteranceAvg - baselineVolume) * alpha
                }
            }

            wasUserTalking = false
        }
    }

    /**
     * Reset baseline — call when conversation ends or user changes environment.
     */
    fun reset() {
        baselineVolume = 0f
        baselineSampleCount = 0
        utteranceVolumeSum = 0f
        utteranceSampleCount = 0
        wasUserTalking = false
        _isSpeakingLoud.value = false
        loudStateSetAt = 0L
    }
}
