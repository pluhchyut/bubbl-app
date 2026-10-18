package com.talksync.audio

// local "is the user talking" check. tries to not call random room noise speech.

import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Detects when the local user (the deaf/HoH person wearing the buds) is speaking.
 *
 * Uses WebRTC VAD — a fast, lightweight GMM-based voice activity detector.
 * Runs entirely on-device, no network required.
 *
 * When the user is detected as talking, the app can:
 * - Show a "you are speaking" indicator
 * - Optionally suppress their speech from being captioned
 * - Track it for the "speaking too loud" feature
 *
 * Call onPcmChunk() from the audio thread with the same PCM data
 * that goes to AudioAnalyzer. Read isUserTalking from any thread.
 */
class UserVoiceActivityDetector {

    private val _isUserTalking = MutableStateFlow(false)
    val isUserTalking: StateFlow<Boolean> = _isUserTalking.asStateFlow()

    // How many consecutive active frames before we declare the user is talking
    private val ACTIVE_FRAMES_THRESHOLD = 4
    // How many consecutive silent frames before we declare they stopped
    private val SILENT_FRAMES_THRESHOLD = 12

    private var activeFrameCount = 0
    private var silentFrameCount = 0

    private val vad = VadWebRTC(
        sampleRate = SampleRate.SAMPLE_RATE_16K,
        frameSize = FrameSize.FRAME_SIZE_160,
        mode = Mode.VERY_AGGRESSIVE,
        silenceDurationMs = 300,
        speechDurationMs = 50,
    )

    // 10ms @ 16kHz
    private val FRAME_SIZE_SAMPLES = 160
    private val FRAME_SIZE_BYTES = FRAME_SIZE_SAMPLES * 2
    private val frameBuffer = mutableListOf<Byte>()

    /**
     * Feed raw 16-bit mono PCM at 16kHz.
     * Safe to call from audio thread.
     */
    fun onPcmChunk(pcmBytes: ByteArray) {
        frameBuffer.addAll(pcmBytes.toList())

        while (frameBuffer.size >= FRAME_SIZE_BYTES) {
            val frameBytes = frameBuffer.take(FRAME_SIZE_BYTES).toByteArray()
            repeat(FRAME_SIZE_BYTES) { frameBuffer.removeAt(0) }

            val samples = ShortArray(FRAME_SIZE_SAMPLES) { i ->
                val lo = frameBytes[i * 2].toInt() and 0xFF
                val hi = frameBytes[i * 2 + 1].toInt()
                ((hi shl 8) or lo).toShort()
            }

            val isSpeech = vad.isSpeech(samples)

            if (isSpeech) {
                activeFrameCount++
                silentFrameCount = 0
                if (activeFrameCount >= ACTIVE_FRAMES_THRESHOLD) {
                    _isUserTalking.value = true
                }
            } else {
                silentFrameCount++
                activeFrameCount = 0
                if (silentFrameCount >= SILENT_FRAMES_THRESHOLD) {
                    _isUserTalking.value = false
                }
            }
        }
    }

    fun release() {
        vad.close()
        _isUserTalking.value = false
        frameBuffer.clear()
    }
}
