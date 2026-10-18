package com.talksync.music

// turns fft bands plus a song seed into repeatable music visuals.

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/* -------------------------------------------------------------------------- */
/* Style: derived deterministically from the song's seed                      */
/* -------------------------------------------------------------------------- */

/**
 * The full procedural style of one song's visualization.
 *
 * Every field here is a pure function of the song seed — there is no
 * randomness during rendering, only audio-driven modulation of these
 * fixed parameters. That guarantees: same song → same artwork, every time.
 *
 * The audio reactivity layer ([VisualFrame]) modulates size, brightness,
 * and rotation within the constraints of the style.
 */
data class VisualStyle(
    val seed: Long,
    /** Background color (low-alpha for trail effect). */
    val background: Int,
    /** Up to 5 base hues (HSV) covering the layered palette. */
    val palette: List<Int>,
    /** "orbit", "radial", "lattice", "ribbon", "petal". */
    val geometry: String,
    /** Layer count (3..6). More layers = more visual density. */
    val layers: Int,
    /** Symmetry order (3..12). Drives radial repetition. */
    val symmetry: Int,
    /** Base rotation rate, radians per second; sign = direction. */
    val rotationRate: Float,
    /** Pulse stiffness — how much bass moves the radii (0.5..2.0). */
    val pulseGain: Float,
    /** Trail decay — how fast frames fade (0.85..0.99). Higher = longer ghosts. */
    val trailDecay: Float,
    /** Stroke vs fill bias (0=fill, 1=stroke). */
    val strokeBias: Float,
    /** Per-layer phase offsets so layers don't sync up. */
    val layerPhases: FloatArray,
    /** Per-layer radius scale (0.1..1.0). */
    val layerRadii: FloatArray,
) {
    companion object {
        /** Build the deterministic style from the song hash. */
        fun fromSeed(seed: Long): VisualStyle {
            val rng = Random(seed)

            val baseHue = rng.nextFloat() * 360f
            val paletteSize = 4 + rng.nextInt(2) // 4 or 5 swatches
            val spread = 25f + rng.nextFloat() * 70f
            val palette = (0 until paletteSize).map { i ->
                val hue = (baseHue + i * spread + rng.nextFloat() * 12f) % 360f
                val sat = 0.55f + rng.nextFloat() * 0.4f
                val value = 0.85f + rng.nextFloat() * 0.15f
                hsvToArgb(alpha = 0xFF, h = hue, s = sat, v = value)
            }

            val bgValue = if (rng.nextFloat() < 0.5f) 0.04f else 0.08f
            val background = hsvToArgb(
                alpha = 0xFF,
                h = (baseHue + 180f) % 360f,
                s = 0.30f + rng.nextFloat() * 0.20f,
                v = bgValue,
            )

            val geomChoices = listOf("particles", "lissajous", "plasma", "warp", "kaleidoscope")
            val geometry = geomChoices[rng.nextInt(geomChoices.size)]

            val layers = 3 + rng.nextInt(4) // 3..6
            val symmetry = 3 + rng.nextInt(10) // 3..12
            val rotationRate = (rng.nextFloat() * 0.8f + 0.15f) *
                if (rng.nextBoolean()) 1f else -1f
            val pulseGain = 0.5f + rng.nextFloat() * 1.5f
            val trailDecay = 0.86f + rng.nextFloat() * 0.12f
            val strokeBias = rng.nextFloat()

            val layerPhases = FloatArray(layers) { rng.nextFloat() * (Math.PI.toFloat() * 2f) }
            val layerRadii = FloatArray(layers) { 0.18f + rng.nextFloat() * 0.82f }

            return VisualStyle(
                seed = seed,
                background = background,
                palette = palette,
                geometry = geometry,
                layers = layers,
                symmetry = symmetry,
                rotationRate = rotationRate,
                pulseGain = pulseGain,
                trailDecay = trailDecay,
                strokeBias = strokeBias,
                layerPhases = layerPhases,
                layerRadii = layerRadii,
            )
        }

        /** Convert HSV (h: 0..360, s/v: 0..1) to ARGB int. */
        fun hsvToArgb(alpha: Int, h: Float, s: Float, v: Float): Int {
            val c = v * s
            val hh = (h % 360f) / 60f
            val x = c * (1f - abs(hh % 2f - 1f))
            val (r1, g1, b1) = when {
                hh < 1f -> Triple(c, x, 0f)
                hh < 2f -> Triple(x, c, 0f)
                hh < 3f -> Triple(0f, c, x)
                hh < 4f -> Triple(0f, x, c)
                hh < 5f -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            val m = v - c
            val r = ((r1 + m) * 255f).toInt().coerceIn(0, 255)
            val g = ((g1 + m) * 255f).toInt().coerceIn(0, 255)
            val b = ((b1 + m) * 255f).toInt().coerceIn(0, 255)
            return (alpha shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

/**
 * Per-frame state passed to the renderer. Snapshot of the live audio
 * reactivity — produced ~30 times per second.
 *
 * Bands (8 entries) are linear-frequency averaged FFT magnitudes,
 * normalized 0..1 by an exponential running max so quiet rooms still get
 * nice modulation.
 */
data class VisualFrame(
    /** 8 frequency bands, low → high, each ~0..1. */
    val bands: FloatArray,
    /** Overall RMS energy 0..1, smoothed. */
    val energy: Float,
    /** Bass impulse (band[0..1] minus running average), gated 0..1. */
    val bassPulse: Float,
    /** Treble shimmer, gated 0..1. */
    val treble: Float,
    /** Monotonic timeline in seconds since visualization started. */
    val time: Float,
)

/* -------------------------------------------------------------------------- */
/* Engine: AudioRecord → FFT → bands → flow                                   */
/* -------------------------------------------------------------------------- */

/**
 * Streams audio-reactive visualization frames while running. Owns its own
 * AudioRecord — keep this off the conversation pipeline so we don't fight
 * Deepgram for the mic.
 *
 * Lifecycle:
 *   start(scope) → frames flow ticks at ~30Hz.
 *   stop()      → releases AudioRecord and cancels the loop.
 *
 * The current [VisualStyle] is updated whenever the recognizer reports a
 * new song; [setStyle] simply swaps the StateFlow value. If no song is
 * known yet, a "neutral" style derived from a fallback seed is used so
 * the screen still has art to show.
 */
class AudioVisualEngine {

    private val styleState = MutableStateFlow(VisualStyle.fromSeed(NEUTRAL_SEED))
    val style: StateFlow<VisualStyle> = styleState.asStateFlow()

    private val frameState = MutableStateFlow(emptyFrame())
    val frame: StateFlow<VisualFrame> = frameState.asStateFlow()

    private val isActiveState = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = isActiveState.asStateFlow()

    private var captureJob: Job? = null
    private var record: AudioRecord? = null

    /** Update the style. Safe to call from any thread. */
    fun setStyle(style: VisualStyle) {
        styleState.value = style
    }

    /** Reset the style to the neutral pre-recognition look. */
    fun resetStyle() {
        styleState.value = VisualStyle.fromSeed(NEUTRAL_SEED)
    }

    fun start(context: Context, scope: CoroutineScope) {
        if (captureJob != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted; engine idle")
            return
        }
        captureJob = scope.launch(Dispatchers.IO) {
            runCaptureLoop()
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        try { record?.stop() } catch (_: Throwable) {}
        try { record?.release() } catch (_: Throwable) {}
        record = null
        isActiveState.value = false
    }

    private suspend fun runCaptureLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioRecord min buffer unavailable")
            return
        }
        val bufferSize = max(minBuffer * 2, FFT_SIZE * 2 * 4)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord init threw", t)
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec.release() } catch (_: Throwable) {}
            Log.w(TAG, "AudioRecord not initialized")
            return
        }
        record = rec
        isActiveState.value = true

        try {
            rec.startRecording()
            val fft = SimpleFft(FFT_SIZE)
            val real = FloatArray(FFT_SIZE)
            val imag = FloatArray(FFT_SIZE)
            val pcmShorts = ShortArray(FFT_SIZE)
            var runningMax = 1f
            var smoothedEnergy = 0f
            var bassRunning = 0f
            var trebleRunning = 0f
            val startNs = System.nanoTime()

            while (isActiveState.value) {
                var collected = 0
                while (collected < FFT_SIZE && isActiveState.value) {
                    val read = rec.read(pcmShorts, collected, FFT_SIZE - collected)
                    if (read <= 0) break
                    collected += read
                }
                if (collected < FFT_SIZE) break

                // Hann window into real, zero imag.
                var sumSq = 0.0
                for (i in 0 until FFT_SIZE) {
                    val w = 0.5f * (1f - kotlin.math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)).toFloat())
                    val s = pcmShorts[i] / 32768f
                    sumSq += s * s
                    real[i] = s * w
                    imag[i] = 0f
                }
                val rms = kotlin.math.sqrt(sumSq / FFT_SIZE).toFloat()

                fft.transform(real, imag)

                // 8 log-spaced bands across 0..SAMPLE_RATE/2.
                val bands = FloatArray(BANDS)
                val nyquistBin = FFT_SIZE / 2
                val maxF = SAMPLE_RATE / 2f
                val minF = 60f
                for (b in 0 until BANDS) {
                    val lo = minF * Math.pow((maxF / minF).toDouble(), b.toDouble() / BANDS).toFloat()
                    val hi = minF * Math.pow((maxF / minF).toDouble(), (b + 1.0) / BANDS).toFloat()
                    val loBin = ((lo / maxF) * nyquistBin).toInt().coerceIn(1, nyquistBin - 1)
                    val hiBin = ((hi / maxF) * nyquistBin).toInt().coerceIn(loBin + 1, nyquistBin)
                    var acc = 0f
                    var ct = 0
                    for (k in loBin until hiBin) {
                        val mag = kotlin.math.sqrt(real[k] * real[k] + imag[k] * imag[k])
                        acc += mag
                        ct++
                    }
                    bands[b] = if (ct > 0) acc / ct else 0f
                }

                // Track running max for normalization (instant attack, faster release).
                val peak = bands.max()
                runningMax = if (peak > runningMax) {
                    peak
                } else {
                    runningMax * 0.968f + peak * 0.032f
                }
                runningMax = max(runningMax, 1e-3f)

                val normalized = FloatArray(BANDS) { (bands[it] / runningMax).coerceIn(0f, 1f) }
                val bassNow = (normalized[0] + normalized[1]) * 0.5f
                val trebleNow = (normalized[6] + normalized[7]) * 0.5f
                bassRunning = bassRunning * 0.60f + bassNow * 0.40f
                trebleRunning = trebleRunning * 0.55f + trebleNow * 0.45f
                val bassPulse = (bassNow - bassRunning).coerceAtLeast(0f) * 4.2f
                val treblePulse = (trebleNow - trebleRunning * 0.7f).coerceAtLeast(0f) * 2.8f

                smoothedEnergy = smoothedEnergy * 0.46f + min(rms * 10f, 1f) * 0.54f

                val time = ((System.nanoTime() - startNs) / 1_000_000_000.0).toFloat()
                frameState.value = VisualFrame(
                    bands = normalized,
                    energy = smoothedEnergy.coerceIn(0f, 1f),
                    bassPulse = bassPulse.coerceIn(0f, 1f),
                    treble = treblePulse.coerceIn(0f, 1f),
                    time = time,
                )
            }
        } finally {
            try { rec.stop() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
            record = null
            isActiveState.value = false
        }
    }

    private fun emptyFrame(): VisualFrame = VisualFrame(
        bands = FloatArray(BANDS) { 0f },
        energy = 0f,
        bassPulse = 0f,
        treble = 0f,
        time = 0f,
    )

    private companion object {
        const val TAG = "AudioVisualEngine"
        const val SAMPLE_RATE = 16_000
        const val FFT_SIZE = 512
        const val BANDS = 8
        // Used pre-recognition so the screen always has *some* visual style.
        const val NEUTRAL_SEED = 0x53756e73687931L // "Sunshy1"
    }
}

/* -------------------------------------------------------------------------- */
/* Renderer-side helpers — used by the Compose Canvas screen                  */
/* -------------------------------------------------------------------------- */

/** Smooth blend between two values for animation continuity. */
fun smoothstep(a: Float, b: Float, t: Float): Float {
    val x = ((t - a) / (b - a)).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** Soft-saturate an audio band to keep the visuals lively but bounded. */
fun softSat(x: Float): Float = (x / (1f + abs(x)))

/** Cheap "wobble" function for layered animation seeds. */
fun layerWobble(time: Float, phase: Float, freq: Float): Float {
    return 0.5f + 0.5f * sin(time * freq + phase)
}
