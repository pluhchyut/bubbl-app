package com.talksync.music

// small in-place fft for the visualizer. not a whole dsp textbook, thank goodness.

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Radix-2 Cooley–Tukey FFT for [size] = power of two.
 *
 * Operates on parallel real / imag float arrays. For real input, callers
 * pre-fill `real` with samples and zero `imag`. Result is in-place: bin k
 * holds the complex value (real[k], imag[k]).
 *
 * Used by [AudioVisualEngine] to bucket live audio into 8 bands per frame.
 * Pure Kotlin so no native dep is needed; ~1024 samples at 30Hz is well
 * within the budget on every modern device.
 */
class SimpleFft(val size: Int) {
    init {
        require(size > 0 && (size and (size - 1)) == 0) {
            "FFT size must be power of two (got $size)"
        }
    }

    private val logN: Int = (ln(size.toDouble()) / ln(2.0)).toInt()
    private val cosTable = FloatArray(size / 2)
    private val sinTable = FloatArray(size / 2)

    init {
        for (i in 0 until size / 2) {
            val angle = -2.0 * Math.PI * i / size
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
    }

    /** In-place transform. `real`/`imag` must each have length [size]. */
    fun transform(real: FloatArray, imag: FloatArray) {
        require(real.size == size && imag.size == size)

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
        }

        // Cooley–Tukey butterflies.
        var stage = 1
        while (stage <= logN) {
            val m = 1 shl stage
            val mHalf = m shr 1
            val tableStep = size / m
            var k = 0
            while (k < size) {
                var t = 0
                for (offset in 0 until mHalf) {
                    val i1 = k + offset
                    val i2 = i1 + mHalf
                    val wr = cosTable[t]
                    val wi = sinTable[t]
                    val tr = wr * real[i2] - wi * imag[i2]
                    val ti = wr * imag[i2] + wi * real[i2]
                    real[i2] = real[i1] - tr
                    imag[i2] = imag[i1] - ti
                    real[i1] += tr
                    imag[i1] += ti
                    t += tableStep
                }
                k += m
            }
            stage++
        }
    }

    /**
     * Convenience: transform then return magnitudes for the first half
     * (positive frequencies). Allocates a new array; not for hot loops.
     */
    fun magnitudes(real: FloatArray, imag: FloatArray): FloatArray {
        transform(real, imag)
        val out = FloatArray(size / 2)
        for (i in 0 until size / 2) {
            val r = real[i]
            val im = imag[i]
            out[i] = kotlin.math.sqrt(r * r + im * im)
        }
        return out
    }
}
