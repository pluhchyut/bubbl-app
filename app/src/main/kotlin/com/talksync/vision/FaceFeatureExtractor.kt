package com.talksync.vision

// makes a small face signature from landmarks, good enough for repeat-speaker matching.

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Builds a lightweight, pose-tolerant pseudo-embedding from MediaPipe's
 * 478 normalized face landmarks.
 *
 * This is **not** an FR-grade neural embedding — it's a geometric fingerprint
 * derived from pairwise distances between stable facial landmarks (eye corners,
 * nose tip, mouth corners, jaw, cheekbones), normalized by the inter-ocular
 * distance to remove camera-distance scale. The result is good enough to
 * cluster repeated appearances of the same person in the same session and
 * across short recurring sessions, which is what the "Google Photos knows
 * people" UX needs to bootstrap identity persistence.
 *
 * For higher-quality recognition we'd swap this for a MobileFaceNet TFLite
 * model and keep the same Store/Matcher contract.
 */
object FaceFeatureExtractor {

    // Stable, pose-robust landmarks across the 478-point MediaPipe face mesh.
    // Picked along eyes, nose, lip corners, jawline and cheekbones so the
    // pairwise distance matrix is dominated by bone structure, not expression.
    private val anchorIndices = intArrayOf(
        33, 133,    // left/right outer eye corners
        159, 145,   // left eye top/bottom
        386, 374,   // right eye top/bottom
        1,          // nose tip
        61, 291,    // mouth corners
        13, 14,     // upper / lower inner lip
        4,          // nasion
        152,        // chin
        234, 454,   // left/right tragus
        10, 168,    // mid forehead, glabella
        323, 93,    // cheekbones
    )

    /**
     * Compute a feature vector from a face's landmarks.
     *
     * @return null when the landmarks list is too short to build a valid
     *         embedding.
     */
    fun extract(landmarks: List<NormalizedLandmark>): FloatArray? {
        if (landmarks.size < 478) return null

        // Normalization scale = inter-ocular distance.
        val leftEye = landmarks[33]
        val rightEye = landmarks[133]
        val scale = hypot(
            (leftEye.x() - rightEye.x()).toDouble(),
            (leftEye.y() - rightEye.y()).toDouble(),
        ).toFloat().coerceAtLeast(1e-4f)

        val anchors = anchorIndices.toList().mapNotNull { idx ->
            landmarks.getOrNull(idx)
        }
        if (anchors.size < anchorIndices.size) return null

        val n = anchors.size
        val pairCount = n * (n - 1) / 2
        val vec = FloatArray(pairCount)
        var k = 0
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val dx = anchors[i].x() - anchors[j].x()
                val dy = anchors[i].y() - anchors[j].y()
                vec[k++] = (hypot(dx.toDouble(), dy.toDouble()).toFloat() / scale)
            }
        }
        return l2Normalize(vec)
    }

    /**
     * Cosine similarity in [-1, 1]. Both vectors are assumed to be
     * L2-normalized (which [extract] guarantees).
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var dot = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
        }
        return dot
    }

    fun encode(vec: FloatArray): String =
        vec.joinToString(separator = ",") { it.toString() }

    fun decode(encoded: String): FloatArray? {
        if (encoded.isBlank()) return null
        return try {
            encoded.split(',')
                .map { it.toFloat() }
                .toFloatArray()
        } catch (_: NumberFormatException) {
            null
        }
    }

    /**
     * Roll a new observation into a centroid using a sample-count-weighted
     * average. Matches the "running mean" approach Google Photos-style face
     * clustering uses to drift the centroid as a person's appearance
     * evolves.
     */
    fun mergeIntoCentroid(
        currentCentroid: FloatArray,
        currentSampleCount: Int,
        newSample: FloatArray,
    ): FloatArray {
        if (currentCentroid.size != newSample.size) return currentCentroid
        val weight = currentSampleCount.toFloat()
        val merged = FloatArray(currentCentroid.size) { i ->
            (currentCentroid[i] * weight + newSample[i]) / (weight + 1f)
        }
        return l2Normalize(merged)
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sumSquares = 0f
        for (v in vec) sumSquares += v * v
        val norm = sqrt(sumSquares).coerceAtLeast(1e-6f)
        return FloatArray(vec.size) { i -> vec[i] / norm }
    }
}
