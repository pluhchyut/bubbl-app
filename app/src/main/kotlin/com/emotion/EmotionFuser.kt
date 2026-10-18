package com.talksync.emotion

// takes the huge emotion list and squishes it into the few tones the ui can actually show.

import kotlin.math.max

object EmotionFuser {
    private val labelMap: Map<String, ToneLabel> = mapOf(
        "Joy" to ToneLabel.JOYFUL,
        "Amusement" to ToneLabel.JOYFUL,
        "Excitement" to ToneLabel.JOYFUL,
        "Contentment" to ToneLabel.JOYFUL,
        "Ecstasy" to ToneLabel.JOYFUL,
        "Relief" to ToneLabel.JOYFUL,
        "Satisfaction" to ToneLabel.JOYFUL,
        "Triumph" to ToneLabel.JOYFUL,
        "Determination" to ToneLabel.JOYFUL,
        "Pride" to ToneLabel.JOYFUL,

        "Admiration" to ToneLabel.WARM,
        "Adoration" to ToneLabel.WARM,
        "Love" to ToneLabel.WARM,
        "Sympathy" to ToneLabel.WARM,
        "Interest" to ToneLabel.WARM,
        "Aesthetic Appreciation" to ToneLabel.WARM,
        "Calmness" to ToneLabel.WARM,
        "Desire" to ToneLabel.WARM,
        "Romance" to ToneLabel.WARM,

        "Anger" to ToneLabel.FRUSTRATED,
        "Contempt" to ToneLabel.FRUSTRATED,
        "Disgust" to ToneLabel.FRUSTRATED,
        "Distress" to ToneLabel.FRUSTRATED,
        "Envy" to ToneLabel.FRUSTRATED,
        "Empathic Pain" to ToneLabel.FRUSTRATED,
        "Pain" to ToneLabel.FRUSTRATED,
        "Craving" to ToneLabel.FRUSTRATED,

        "Sadness" to ToneLabel.SAD,
        "Disappointment" to ToneLabel.SAD,
        "Guilt" to ToneLabel.SAD,
        "Shame" to ToneLabel.SAD,
        "Nostalgia" to ToneLabel.SAD,

        "Fear" to ToneLabel.FEARFUL,
        "Anxiety" to ToneLabel.FEARFUL,
        "Horror" to ToneLabel.FEARFUL,
        "Embarrassment" to ToneLabel.FEARFUL,

        "Surprise (positive)" to ToneLabel.SURPRISED,
        "Surprise (negative)" to ToneLabel.SURPRISED,
        "Awe" to ToneLabel.SURPRISED,
        "Entrancement" to ToneLabel.SURPRISED,

        "Confusion" to ToneLabel.CONFUSED,
        "Doubt" to ToneLabel.CONFUSED,
        "Boredom" to ToneLabel.CONFUSED,
        "Tiredness" to ToneLabel.CONFUSED,
        "Awkwardness" to ToneLabel.CONFUSED,
        "Realization" to ToneLabel.CONFUSED,
        "Concentration" to ToneLabel.CONFUSED,
        "Contemplation" to ToneLabel.CONFUSED,
    )

    data class HumeScores(val emotions: List<Pair<String, Float>>)

    private data class Candidate(
        val name: String,
        val label: ToneLabel,
        val rawScore: Float,
        val weightedScore: Float,
    )

    fun fuse(
        faceScores: HumeScores?,
        prosodyScores: HumeScores?,
        burstScores: HumeScores?,
        textSignal: TextEmotionAnalyzer.TextSignal?,
    ): EmotionState {
        val merged = mutableMapOf<ToneLabel, Float>()
        val topFace = faceScores.topEmotion()
        val topProsody = prosodyScores.topEmotion()
        val topBurst = burstScores.topEmotion()
        val topText = textSignal?.evidence
        val faceCandidates = faceScores.toCandidates(HumeConfig.FACE_WEIGHT)
        val prosodyCandidates = prosodyScores.toCandidates(HumeConfig.PROSODY_WEIGHT)
        val burstCandidates = burstScores.toCandidates(HumeConfig.BURST_WEIGHT)
        val textCandidates = textSignal.toCandidates(HumeConfig.TEXT_WEIGHT)

        (faceCandidates + prosodyCandidates + burstCandidates + textCandidates)
            .filter { it.rawScore >= HumeConfig.EMOTION_THRESHOLD }
            .forEach { candidate ->
                merged[candidate.label] = (merged[candidate.label] ?: 0f) + candidate.weightedScore
            }

        merged.remove(ToneLabel.NEUTRAL)

        if (merged.isEmpty()) {
            val fallbackCandidate = (faceCandidates + prosodyCandidates + burstCandidates + textCandidates)
                .maxByOrNull { it.weightedScore }

            if (fallbackCandidate != null && fallbackCandidate.rawScore >= 0.06f) {
                return EmotionState(
                    label = fallbackCandidate.label,
                    dominantFaceEmotion = topFace?.first,
                    dominantProsodyEmotion = topProsody?.first,
                    dominantBurstEmotion = topBurst?.first,
                    dominantTextEmotion = topText,
                    confidence = fallbackCandidate.rawScore.coerceIn(0f, 1f),
                    isLowConfidence = true,
                )
            }

            return EmotionState(
                label = ToneLabel.NEUTRAL,
                dominantFaceEmotion = topFace?.first,
                dominantProsodyEmotion = topProsody?.first,
                dominantBurstEmotion = topBurst?.first,
                dominantTextEmotion = topText,
                confidence = max(
                    max(max(topFace?.second ?: 0f, topProsody?.second ?: 0f), topBurst?.second ?: 0f),
                    textSignal?.confidence ?: 0f,
                ),
                isLowConfidence = true,
            )
        }

        val winner = merged.maxByOrNull { it.value } ?: return EmotionState()
        val winnerLabel = winner.key
        val agreementCount = listOfNotNull(
            topFace?.first?.let(labelMap::get),
            topProsody?.first?.let(labelMap::get),
            topBurst?.first?.let(labelMap::get),
            textSignal?.label,
        ).count { label -> label == winnerLabel }
        val agreementBoost = when {
            agreementCount >= 3 -> 0.16f
            agreementCount == 2 -> 0.08f
            else -> 0f
        }
        val winnerScore = (winner.value + agreementBoost).coerceIn(0f, 1f)

        val winningFace = faceScores?.emotions
            ?.filter { labelMap[it.first] == winnerLabel }
            ?.maxByOrNull { it.second }
            ?.first
        val winningProsody = prosodyScores?.emotions
            ?.filter { labelMap[it.first] == winnerLabel }
            ?.maxByOrNull { it.second }
            ?.first
        val winningBurst = burstScores?.emotions
            ?.filter { labelMap[it.first] == winnerLabel }
            ?.maxByOrNull { it.second }
            ?.first

        return EmotionState(
            label = winnerLabel,
            dominantFaceEmotion = winningFace ?: topFace?.first,
            dominantProsodyEmotion = winningProsody ?: topProsody?.first,
            dominantBurstEmotion = winningBurst ?: topBurst?.first,
            dominantTextEmotion = if (textSignal?.label == winnerLabel) textSignal.evidence else topText,
            confidence = winnerScore,
            isLowConfidence = winnerScore < 0.45f,
        )
    }

    private fun HumeScores?.toCandidates(weight: Float): List<Candidate> {
        return this?.emotions
            ?.mapNotNull { (name, score) ->
                val label = labelMap[name] ?: return@mapNotNull null
                Candidate(
                    name = name,
                    label = label,
                    rawScore = score,
                    weightedScore = score * weight,
                )
            }
            ?.sortedByDescending { it.rawScore }
            ?: emptyList()
    }

    private fun HumeScores?.topEmotion(): Pair<String, Float>? {
        return this?.emotions?.maxByOrNull { it.second }
    }

    private fun TextEmotionAnalyzer.TextSignal?.toCandidates(weight: Float): List<Candidate> {
        return this?.let { signal ->
            listOf(
                Candidate(
                    name = signal.evidence,
                    label = signal.label,
                    rawScore = signal.confidence,
                    weightedScore = signal.confidence * weight,
                ),
            )
        } ?: emptyList()
    }
}
