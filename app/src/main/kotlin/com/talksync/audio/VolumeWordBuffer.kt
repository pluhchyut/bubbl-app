package com.talksync.audio

// matches words with nearby volume samples so emphasized captions dont feel random.

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WordVolume(
    val word: String,
    val volume: Float,
    val isFinal: Boolean,
    val isNew: Boolean = false,
)

private data class WordSlot(
    val word: String,
    val samples: MutableList<Float> = mutableListOf(),
) {
    fun average(): Float {
        return if (samples.isEmpty()) {
            0.5f
        } else {
            val sortedSamples = samples
                .map { it.coerceIn(0f, 1f) }
                .sorted()
            val lowerTrimCount = (sortedSamples.size * 0.25f).toInt()
            val trimmedSamples = sortedSamples.drop(lowerTrimCount).ifEmpty { sortedSamples }
            val upperWindowCount = (sortedSamples.size * 0.35f).toInt().coerceAtLeast(1)
            val upperWindow = sortedSamples.takeLast(upperWindowCount)

            val trimmedAverage = trimmedSamples.average().toFloat()
            val upperWindowAverage = upperWindow.average().toFloat()

            ((trimmedAverage * 0.58f) + (upperWindowAverage * 0.42f))
                .coerceIn(0f, 1f)
        }
    }
}

class VolumeWordBuffer {
    private val slots = mutableListOf<WordSlot>()
    private var currentWords: List<String> = emptyList()
    private val recentSamples = ArrayDeque<Float>()

    private val _wordVolumes = MutableStateFlow<List<WordVolume>>(emptyList())
    val wordVolumes: StateFlow<List<WordVolume>> = _wordVolumes.asStateFlow()

    @Synchronized
    fun addSample(volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        recentSamples.addLast(clampedVolume)
        while (recentSamples.size > MAX_RECENT_SAMPLES) {
            recentSamples.removeAt(0)
        }

        slots.lastOrNull()?.samples?.add(clampedVolume)
        if (currentWords.isNotEmpty()) {
            // volume tick — no new words, pass size as sentinel so nothing is marked isNew
            emitCurrent(currentWords, isFinal = false, newWordStartIndex = currentWords.size)
        }
    }

    @Synchronized
    fun onPartialResult(partialText: String) {
        val words = tokenize(partialText)
        currentWords = words
        val confirmedCount = slots.size

        when {
            words.size > slots.size -> {
                val newWords = words.drop(slots.size)
                val backfilledSamples = takeRecentSamplesForNewWords(newWords.size)
                newWords.forEachIndexed { index, word ->
                    slots.add(
                        WordSlot(
                            word = word,
                            samples = backfilledSamples.getOrNull(index)?.toMutableList()
                                ?: mutableListOf(),
                        ),
                    )
                }
            }

            words.size < slots.size -> {
                repeat(slots.size - words.size) {
                    slots.removeLastOrNull()
                }
            }
        }

        emitCurrent(words, isFinal = false, newWordStartIndex = confirmedCount)
    }

    @Synchronized
    fun onFinalResult(finalText: String): List<WordVolume> {
        val words = tokenize(finalText)
        currentWords = emptyList()

        while (slots.size < words.size) {
            slots.add(WordSlot(word = words[slots.size]))
        }
        while (slots.size > words.size) {
            slots.removeLastOrNull()
        }

        val result = words.mapIndexed { index, word ->
            WordVolume(
                word = word,
                volume = slots.getOrNull(index)?.average() ?: 0.5f,
                isFinal = true,
            )
        }

        _wordVolumes.value = result
        slots.clear()
        recentSamples.clear()
        return result
    }

    @Synchronized
    fun clear() {
        currentWords = emptyList()
        slots.clear()
        recentSamples.clear()
        _wordVolumes.value = emptyList()
    }

    private fun emitCurrent(words: List<String>, isFinal: Boolean, newWordStartIndex: Int = words.size) {
        _wordVolumes.value = words.mapIndexed { index, word ->
            WordVolume(
                word = word,
                volume = slots.getOrNull(index)?.average() ?: 0.5f,
                isFinal = isFinal,
                isNew = index >= newWordStartIndex,
            )
        }
    }

    private fun tokenize(text: String): List<String> {
        return text.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
    }

    private fun splitRecentSamples(newWordCount: Int): List<List<Float>> {
        if (newWordCount <= 0 || recentSamples.isEmpty()) {
            return List(newWordCount.coerceAtLeast(0)) { emptyList() }
        }

        val sampleList = recentSamples.toList()
        val chunkSize = (sampleList.size.toFloat() / newWordCount).coerceAtLeast(1f)

        return List(newWordCount) { index ->
            val startIndex = (index * chunkSize).toInt().coerceAtMost(sampleList.lastIndex)
            val endExclusive = if (index == newWordCount - 1) {
                sampleList.size
            } else {
                ((index + 1) * chunkSize).toInt().coerceAtLeast(startIndex + 1)
                    .coerceAtMost(sampleList.size)
            }
            sampleList.subList(startIndex, endExclusive)
        }
    }

    private fun takeRecentSamplesForNewWords(newWordCount: Int): List<List<Float>> {
        if (newWordCount <= 0) {
            return emptyList()
        }

        val totalTransferCount = recentSamples.size
            .coerceAtMost(newWordCount * HANDOFF_SAMPLES_PER_WORD)
            .coerceAtLeast(newWordCount.coerceAtMost(recentSamples.size))

        if (totalTransferCount <= 0) {
            return List(newWordCount) { emptyList() }
        }

        val samplesToTransfer = recentSamples
            .takeLast(totalTransferCount)

        slots.lastOrNull()?.let { previousSlot ->
            repeat(totalTransferCount.coerceAtMost(previousSlot.samples.size)) {
                previousSlot.samples.removeAt(previousSlot.samples.size - 1)
            }
        }

        return splitTransferredSamples(samplesToTransfer, newWordCount)
    }

    private fun splitTransferredSamples(
        samples: List<Float>,
        newWordCount: Int,
    ): List<List<Float>> {
        if (newWordCount <= 0 || samples.isEmpty()) {
            return List(newWordCount.coerceAtLeast(0)) { emptyList() }
        }

        val chunkSize = (samples.size.toFloat() / newWordCount).coerceAtLeast(1f)
        return List(newWordCount) { index ->
            val startIndex = (index * chunkSize).toInt().coerceAtMost(samples.lastIndex)
            val endExclusive = if (index == newWordCount - 1) {
                samples.size
            } else {
                ((index + 1) * chunkSize).toInt()
                    .coerceAtLeast(startIndex + 1)
                    .coerceAtMost(samples.size)
            }
            samples.subList(startIndex, endExclusive)
        }
    }

    private companion object {
        const val MAX_RECENT_SAMPLES = 24
        const val HANDOFF_SAMPLES_PER_WORD = 8
    }
}

fun String.toWordVolumes(
    volumes: List<Float> = emptyList(),
    isFinal: Boolean = true,
): List<WordVolume> {
    val words = trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    return words.mapIndexed { index, word ->
        WordVolume(
            word = word,
            volume = volumes.getOrNull(index)
                ?: volumes.lastOrNull()
                ?: 0.5f,
            isFinal = isFinal,
        )
    }
}
