package com.talksync.vision

// short rolling mouth-signal buffer, because one frame of lips is basically useless.

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-face rolling buffer of mouth crops feeding the lipreading model.
 *
 * Window of [WINDOW_FRAMES] frames is emitted every [STRIDE_FRAMES] new
 * frames (50% overlap). Frames with apertureScore below [MIN_APERTURE]
 * are dropped — they're closed-mouth and add no signal.
 */
class VisualSpeechBuffer {

    companion object {
        const val WINDOW_FRAMES = 29
        const val STRIDE_FRAMES = 14
        const val FRAME_SIZE = MouthCropExtractor.OUTPUT_SIZE * MouthCropExtractor.OUTPUT_SIZE
        const val MIN_APERTURE = 0.03f
        const val EVICT_AFTER_MS = 2_000L
    }

    private class FaceBuffer {
        val frames: ArrayDeque<FloatArray> = ArrayDeque()
        var framesSinceLastEmit: Int = 0
        var lastUpdatedAt: Long = 0L
    }

    private val buffers = ConcurrentHashMap<String, FaceBuffer>()

    /**
     * Append a frame for [faceId]. Returns a complete [WINDOW_FRAMES]-frame
     * window (29 * 96 * 96 floats) when ready, otherwise null.
     */
    fun addFrame(
        faceId: String,
        crop: FloatArray,
        apertureScore: Float,
        timestamp: Long,
    ): FloatArray? {
        if (crop.size != FRAME_SIZE) return null
        evictStale(timestamp)
        if (apertureScore < MIN_APERTURE) {
            // Still bump lastUpdatedAt so we don't immediately evict an
            // active speaker who briefly closes their mouth between words.
            buffers[faceId]?.lastUpdatedAt = timestamp
            return null
        }

        val buffer = buffers.computeIfAbsent(faceId) { FaceBuffer() }
        synchronized(buffer) {
            buffer.frames.addLast(crop)
            while (buffer.frames.size > WINDOW_FRAMES) {
                buffer.frames.removeFirst()
            }
            buffer.framesSinceLastEmit++
            buffer.lastUpdatedAt = timestamp

            return if (buffer.frames.size == WINDOW_FRAMES &&
                buffer.framesSinceLastEmit >= STRIDE_FRAMES
            ) {
                buffer.framesSinceLastEmit = 0
                flatten(buffer.frames)
            } else {
                null
            }
        }
    }

    fun clearFace(faceId: String) {
        buffers.remove(faceId)
    }

    fun clearAll() {
        buffers.clear()
    }

    private fun evictStale(now: Long) {
        if (buffers.isEmpty()) return
        val cutoff = now - EVICT_AFTER_MS
        val it = buffers.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.lastUpdatedAt > 0L && entry.value.lastUpdatedAt < cutoff) {
                it.remove()
            }
        }
    }

    private fun flatten(frames: ArrayDeque<FloatArray>): FloatArray {
        val out = FloatArray(WINDOW_FRAMES * FRAME_SIZE)
        var offset = 0
        for (f in frames) {
            System.arraycopy(f, 0, out, offset, FRAME_SIZE)
            offset += FRAME_SIZE
        }
        return out
    }
}
