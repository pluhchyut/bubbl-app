package com.talksync.audio

// tests for the audio math, mostly making sure silence/loudness edge cases dont lie.

import org.junit.Assert.assertTrue
import org.junit.Test

class AudioAnalyzerTest {

    @Test
    fun `sustained loud input keeps adaptive window valid`() {
        val analyzer = AudioAnalyzer()
        val loudPcm = pcmChunk(sample = 32_000, sampleCount = 160)

        analyzer.start()

        repeat(24) {
            analyzer.onPcmChunk(loudPcm)
        }

        assertTrue(analyzer.volumeLevel.value in 0f..1f)
    }

    private fun pcmChunk(sample: Int, sampleCount: Int): ByteArray {
        val bytes = ByteArray(sampleCount * 2)
        repeat(sampleCount) { index ->
            bytes[index * 2] = (sample and 0xFF).toByte()
            bytes[index * 2 + 1] = ((sample ushr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
