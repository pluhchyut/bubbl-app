package com.talksync.vision

// connects lip landmarks, the visual buffer, and recognizer output without making screens care.

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Glues the visual-speech pipeline together:
 *
 *   LipLandmarkAnalyzer.LipSignal + Bitmap
 *      → MouthCropExtractor → VisualSpeechBuffer
 *      → VisualSpeechRecognizer → AudioVisualFuser
 *      → StateFlow<Map<faceId, AVHypothesis>>
 *
 * The audio side calls [onAudioTranscript] when a transcript lands; the
 * coordinator fuses it with the latest visual hypothesis for that face
 * and emits the fused result.
 */
class LipReadingCoordinator(context: Context) {

    companion object {
        private const val TAG = "LipReadingCoordinator"
        private const val VISUAL_TTL_MS = 2_500L
    }

    private val recognizer = VisualSpeechRecognizer(context)
    private val buffer = VisualSpeechBuffer()
    private val fuser = AudioVisualFuser()

    /** Latest visual-only hypothesis per face, before fusion with audio. */
    private val latestVisual = ConcurrentHashMap<String, AVHypothesis>()

    /** Latest lipActivityScore per face, used for fusion gating. */
    private val latestLipActivity = ConcurrentHashMap<String, Float>()

    /** Latest noise level seen on the most recent frame. */
    @Volatile private var latestNoiseLevel: Float = 0f

    private val _lipReadingResults = MutableStateFlow<Map<String, AVHypothesis>>(emptyMap())
    val lipReadingResults: StateFlow<Map<String, AVHypothesis>> = _lipReadingResults.asStateFlow()

    /**
     * Feed one frame for one face. Safe to call from the camera background
     * thread. The bitmap is NOT recycled here; the caller owns it.
     */
    fun onLipSignal(
        faceId: String,
        lipSignal: LipLandmarkAnalyzer.LipSignal,
        bitmap: Bitmap,
        currentNoiseLevel: Float,
    ) {
        latestNoiseLevel = currentNoiseLevel.coerceIn(0f, 1f)
        latestLipActivity[faceId] = lipSignal.combinedScore

        val crop = MouthCropExtractor.extract(bitmap, lipSignal) ?: return
        val now = SystemClock.uptimeMillis()
        val window = buffer.addFrame(faceId, crop, lipSignal.apertureScore, now) ?: return

        recognizer.transcribe(window) { text, confidence ->
            if (text.isBlank() && confidence <= 0f) return@transcribe
            val hypothesis = AVHypothesis(
                text = text,
                confidence = confidence,
                source = AVSource.VISUAL,
                timestamp = System.currentTimeMillis(),
            )
            latestVisual[faceId] = hypothesis
            publish(faceId, hypothesis)
        }
    }

    /**
     * Fold a freshly arrived audio transcript into the running results.
     * If [anchorFaceId] is null we have no face to fuse with, so we just
     * publish the audio hypothesis under a synthetic key so the UI still
     * sees it.
     */
    fun onAudioTranscript(
        text: String,
        confidence: Float,
        anchorFaceId: String?,
    ) {
        if (text.isBlank() && confidence <= 0f) return
        val now = System.currentTimeMillis()
        val faceId = anchorFaceId ?: "audio-only"
        val visual = anchorFaceId?.let { freshVisual(it, now) }
        val lipActivity = anchorFaceId?.let { latestLipActivity[it] } ?: 0f

        val fused = fuser.fuse(
            audioText = text,
            audioConfidence = confidence,
            visualText = visual?.text.orEmpty(),
            visualConfidence = visual?.confidence ?: 0f,
            noiseLevel = latestNoiseLevel,
            lipActivityScore = lipActivity,
            timestamp = now,
        )
        publish(faceId, fused)
    }

    /** Drop cached visual hypotheses for a face that's gone away. */
    fun clearFace(faceId: String) {
        buffer.clearFace(faceId)
        latestVisual.remove(faceId)
        latestLipActivity.remove(faceId)
        _lipReadingResults.value = _lipReadingResults.value - faceId
    }

    fun release() {
        try {
            recognizer.release()
        } catch (e: Throwable) {
            Log.w(TAG, "release failed: ${e.message}")
        }
        buffer.clearAll()
        latestVisual.clear()
        latestLipActivity.clear()
        _lipReadingResults.value = emptyMap()
    }

    private fun freshVisual(faceId: String, now: Long): AVHypothesis? {
        val v = latestVisual[faceId] ?: return null
        return if (now - v.timestamp <= VISUAL_TTL_MS) v else null
    }

    private fun publish(faceId: String, hypothesis: AVHypothesis) {
        _lipReadingResults.update { current -> current + (faceId to hypothesis) }
    }
}
