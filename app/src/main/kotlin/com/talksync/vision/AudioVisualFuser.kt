package com.talksync.vision

// mixes audio guesses and face/mouth guesses, then picks the least sketchy speaker match.

import kotlin.math.max
import kotlin.math.min

data class AVHypothesis(
    val text: String,
    val confidence: Float,
    val source: AVSource,
    val timestamp: Long,
)

enum class AVSource { AUDIO, VISUAL, FUSED }

/**
 * Combines noisy audio transcripts with visual lipreading hypotheses.
 *
 * The audio weight drops as ambient noise rises; below a threshold the
 * visual hypothesis is ignored entirely. When both sources roughly agree
 * we award an "agreement bonus" to the final confidence.
 */
class AudioVisualFuser {

    companion object {
        private const val MIN_VISUAL_CONFIDENCE = 0.15f
        private const val MIN_LIP_ACTIVITY = 0.05f
        private const val AGREEMENT_THRESHOLD = 0.80f
        private const val AGREEMENT_BONUS = 0.15f
    }

    fun fuse(
        audioText: String,
        audioConfidence: Float,
        visualText: String,
        visualConfidence: Float,
        noiseLevel: Float,
        lipActivityScore: Float,
        timestamp: Long = System.currentTimeMillis(),
    ): AVHypothesis {
        val audioConf = audioConfidence.coerceIn(0f, 1f)
        val visualConf = visualConfidence.coerceIn(0f, 1f)
        val ignoreVisual = visualText.isBlank() ||
            visualConf < MIN_VISUAL_CONFIDENCE ||
            lipActivityScore < MIN_LIP_ACTIVITY

        if (ignoreVisual) {
            return AVHypothesis(audioText, audioConf, AVSource.AUDIO, timestamp)
        }
        if (audioText.isBlank()) {
            return AVHypothesis(visualText, visualConf, AVSource.VISUAL, timestamp)
        }

        val (audioWeight, visualWeight) = weightsFor(noiseLevel)

        // Choose the surface text by which weighted-confidence is higher.
        val audioScore = audioConf * audioWeight
        val visualScore = visualConf * visualWeight
        val chosenText: String
        val chosenSource: AVSource
        if (audioScore >= visualScore) {
            chosenText = audioText
            chosenSource = if (audioWeight >= 0.999f) AVSource.AUDIO else AVSource.FUSED
        } else {
            chosenText = visualText
            chosenSource = AVSource.FUSED
        }

        // Fused confidence is the convex combination, optionally boosted
        // by source agreement.
        var fused = audioConf * audioWeight + visualConf * visualWeight
        val similarity = levenshteinSimilarity(
            normalizeForCompare(audioText),
            normalizeForCompare(visualText),
        )
        if (similarity > AGREEMENT_THRESHOLD) {
            fused *= (1f + AGREEMENT_BONUS)
        }

        return AVHypothesis(
            text = chosenText,
            confidence = fused.coerceIn(0f, 1f),
            source = chosenSource,
            timestamp = timestamp,
        )
    }

    private fun weightsFor(noiseLevel: Float): Pair<Float, Float> {
        val n = noiseLevel.coerceIn(0f, 1f)
        return when {
            n < 0.30f -> 0.90f to 0.10f
            n < 0.60f -> 0.70f to 0.30f
            n < 0.80f -> 0.50f to 0.50f
            else      -> 0.30f to 0.70f
        }
    }

    private fun normalizeForCompare(s: String): String {
        return s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun levenshteinSimilarity(a: String, b: String): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1f
        val dist = levenshtein(a, b)
        return (1f - dist.toFloat() / maxLen.toFloat()).coerceIn(0f, 1f)
    }

    private fun levenshtein(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n
        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)
        for (i in 1..n) {
            curr[0] = i
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost,
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[m]
    }
}
