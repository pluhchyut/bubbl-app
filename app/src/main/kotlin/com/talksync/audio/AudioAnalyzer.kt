package com.talksync.audio

// turns raw mic bytes into loudness numbers, because captions need simple volume info.

import com.talksync.emotion.HumeEmotionEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.sqrt

class AudioAnalyzer {
    private val _volumeLevel = MutableStateFlow(0f)
    val volumeLevel: StateFlow<Float> = _volumeLevel.asStateFlow()

    private val sampleHistory = ArrayDeque<Pair<Long, Float>>()
    private val historyLock = Any()
    private var humeEngine: HumeEmotionEngine? = null
    private var vadDetector: UserVoiceActivityDetector? = null
    private var rollingMax = DEFAULT_MAX_RMS
    private var rollingAverage = DEFAULT_AVERAGE_RMS
    private var rollingFloor = DEFAULT_FLOOR_RMS
    private var smoothedLevel = 0f

    @Volatile
    private var isRunning = false

    fun attachHumeEngine(engine: HumeEmotionEngine) {
        humeEngine = engine
    }

    fun attachVoiceActivityDetector(detector: UserVoiceActivityDetector) {
        vadDetector = detector
    }

    fun start() {
        isRunning = true
        rollingMax = DEFAULT_MAX_RMS
        rollingAverage = DEFAULT_AVERAGE_RMS
        rollingFloor = DEFAULT_FLOOR_RMS
        smoothedLevel = 0f
        _volumeLevel.value = 0f
        synchronized(historyLock) {
            sampleHistory.clear()
        }
    }

    fun stop() {
        isRunning = false
        rollingMax = DEFAULT_MAX_RMS
        rollingAverage = DEFAULT_AVERAGE_RMS
        rollingFloor = DEFAULT_FLOOR_RMS
        smoothedLevel = 0f
        _volumeLevel.value = 0f
        synchronized(historyLock) {
            sampleHistory.clear()
        }
    }

    fun onPcmChunk(pcmBytes: ByteArray) {
        if (!isRunning || pcmBytes.size < 2) {
            return
        }

        var sumSquares = 0.0
        val sampleCount = pcmBytes.size / 2
        for (byteIndex in 0 until sampleCount * 2 step 2) {
            val low = pcmBytes[byteIndex].toInt() and 0xFF
            val high = pcmBytes[byteIndex + 1].toInt()
            val sample = ((high shl 8) or low).toDouble()
            sumSquares += sample * sample
        }

        val rms = sqrt(sumSquares / sampleCount).toFloat()
        updateAdaptiveWindow(rms)

        // moving floor/ceiling so loudness stays useful in diff rooms
        val effectiveFloor = rollingFloor.coerceAtMost(rollingMax - MIN_DYNAMIC_RANGE_RMS)
        val rawNormalized = ((rms - effectiveFloor) / (rollingMax - effectiveFloor))
            .coerceIn(0f, 1f)
        smoothedLevel += (rawNormalized - smoothedLevel) * when {
            rawNormalized > smoothedLevel -> 0.32f
            else -> 0.16f
        }

        val normalized = smoothedLevel.coerceIn(0f, 1f)
        _volumeLevel.value = normalized
        recordSample(normalized)
        humeEngine?.onAudioChunk(pcmBytes)
        vadDetector?.onPcmChunk(pcmBytes)
    }

    fun averageVolume(windowMs: Long = 200L): Float {
        val now = System.currentTimeMillis()
        synchronized(historyLock) {
            trimHistory(now)
            val samples = sampleHistory.filter { it.first >= now - windowMs }
            return if (samples.isEmpty()) {
                _volumeLevel.value
            } else {
                samples.map { it.second }.average().toFloat()
            }
        }
    }

    fun release() {
        stop()
    }

    private fun recordSample(value: Float) {
        synchronized(historyLock) {
            val now = System.currentTimeMillis()
            sampleHistory.addLast(now to value)
            // short tail for emphasis logic
            trimHistory(now)
        }
    }

    private fun trimHistory(now: Long) {
        while (sampleHistory.isNotEmpty() && sampleHistory.first().first < now - 2_000L) {
            sampleHistory.removeFirst()
        }
    }

    private fun updateAdaptiveWindow(rms: Float) {
        val averageAlpha = if (rms > rollingAverage) 0.05f else 0.015f
        rollingAverage += (rms - rollingAverage) * averageAlpha

        val floorTarget = (rollingAverage * 0.12f).coerceIn(MIN_FLOOR_RMS, MAX_FLOOR_RMS)
        val floorAlpha = if (rms < rollingAverage * 0.55f) 0.08f else 0.02f
        rollingFloor += (floorTarget - rollingFloor) * floorAlpha

        val maxLowerBound = MAX_MAX_RMS - MIN_DYNAMIC_RANGE_RMS
        val lowerMaxBound = (rollingAverage * 1.55f).coerceIn(MIN_MAX_RMS, maxLowerBound)
        val upperMaxBound = (rollingAverage * 2.65f)
            .coerceIn(lowerMaxBound + MIN_DYNAMIC_RANGE_RMS, MAX_MAX_RMS)
        val candidateMax = max(rms, rollingMax * 0.992f)
        rollingMax = candidateMax.coerceIn(lowerMaxBound, upperMaxBound)
    }

    private companion object {
        const val DEFAULT_FLOOR_RMS = 180f
        const val DEFAULT_AVERAGE_RMS = 2_200f
        const val DEFAULT_MAX_RMS = 3_800f
        const val MIN_FLOOR_RMS = 120f
        const val MAX_FLOOR_RMS = 1_200f
        const val MIN_MAX_RMS = 2_000f
        const val MAX_MAX_RMS = 12_000f
        const val MIN_DYNAMIC_RANGE_RMS = 600f
    }
}
