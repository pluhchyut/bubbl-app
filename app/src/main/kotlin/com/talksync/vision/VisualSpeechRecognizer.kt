package com.talksync.vision

// turns mouth cues into rough speech signals for the fusion layer. very approximate.

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.ln

/**
 * Wraps a TFLite lipreading model that takes a 29-frame [1, 29, 96, 96, 1]
 * tensor and outputs CTC logits over a character vocabulary.
 *
 * Drop the model file at: app/src/main/assets/lipnet.tflite
 * Drop the vocabulary at: app/src/main/assets/lipnet_vocab.txt
 *   (one token per line, last line is "<blank>")
 *
 * If the model is missing the recognizer becomes a no-op that returns
 * ("", 0f) — useful for wiring the pipeline before the model lands.
 */
class VisualSpeechRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "VisualSpeechRecognizer"
        private const val MODEL_ASSET = "lipnet.tflite"
        private const val VOCAB_ASSET = "lipnet_vocab.txt"
        private const val FRAMES = VisualSpeechBuffer.WINDOW_FRAMES
        private const val SIZE = MouthCropExtractor.OUTPUT_SIZE
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "VSR-Inference").apply { isDaemon = true }
    }

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var nnApiDelegate: NnApiDelegate? = null
    @Volatile private var vocab: List<String> = emptyList()
    @Volatile private var blankIndex: Int = -1
    @Volatile private var modelMissing: Boolean = false
    @Volatile private var initialized: Boolean = false

    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(FRAMES * SIZE * SIZE * 4)
        .order(ByteOrder.nativeOrder())

    private fun ensureInitialized() {
        if (initialized || modelMissing) return
        synchronized(this) {
            if (initialized || modelMissing) return
            try {
                val modelBuffer = loadModelFile()
                if (modelBuffer == null) {
                    Log.w(TAG, "Model asset '$MODEL_ASSET' not found — VSR disabled.")
                    modelMissing = true
                    return
                }
                vocab = loadVocab()
                blankIndex = vocab.indexOf("<blank>").let { if (it < 0) vocab.size - 1 else it }

                val options = Interpreter.Options().apply {
                    numThreads = 2
                }
                val interp = try {
                    val delegate = NnApiDelegate()
                    nnApiDelegate = delegate
                    options.addDelegate(delegate)
                    Interpreter(modelBuffer, options)
                } catch (e: Throwable) {
                    Log.w(TAG, "NNAPI init failed, falling back to CPU: ${e.message}")
                    nnApiDelegate?.close()
                    nnApiDelegate = null
                    Interpreter(modelBuffer, Interpreter.Options().apply { numThreads = 2 })
                }
                interpreter = interp
                initialized = true
                Log.d(TAG, "VSR initialized. vocab=${vocab.size}, blank=$blankIndex")
            } catch (e: Throwable) {
                Log.e(TAG, "VSR init failed", e)
                modelMissing = true
            }
        }
    }

    /**
     * Run inference asynchronously. [frames] must be FRAMES * SIZE * SIZE
     * floats in [-1, 1]. [onResult] is invoked on the inference thread.
     */
    fun transcribe(
        frames: FloatArray,
        onResult: (text: String, confidence: Float) -> Unit,
    ) {
        if (frames.size != FRAMES * SIZE * SIZE) {
            onResult("", 0f)
            return
        }
        executor.execute {
            ensureInitialized()
            val interp = interpreter
            if (modelMissing || interp == null) {
                onResult("", 0f)
                return@execute
            }
            try {
                inputBuffer.rewind()
                for (v in frames) inputBuffer.putFloat(v)
                inputBuffer.rewind()

                val outShape = interp.getOutputTensor(0).shape() // [1, T, V]
                val seqLen = outShape.getOrNull(1) ?: 0
                val vocabSize = outShape.getOrNull(2) ?: vocab.size
                if (seqLen <= 0 || vocabSize <= 0) {
                    onResult("", 0f)
                    return@execute
                }
                val output = Array(1) { Array(seqLen) { FloatArray(vocabSize) } }
                interp.run(inputBuffer, output)

                val (text, conf) = ctcGreedyDecode(output[0], vocabSize)
                onResult(text, conf)
            } catch (e: Throwable) {
                Log.w(TAG, "Inference failed: ${e.message}")
                onResult("", 0f)
            }
        }
    }

    fun release() {
        executor.execute {
            try {
                interpreter?.close()
            } catch (_: Throwable) {
            }
            try {
                nnApiDelegate?.close()
            } catch (_: Throwable) {
            }
            interpreter = null
            nnApiDelegate = null
            initialized = false
        }
        executor.shutdown()
    }

    /**
     * CTC greedy decode: argmax per timestep, collapse repeats, drop blanks.
     * Confidence = mean softmax-prob of the chosen non-blank tokens.
     */
    private fun ctcGreedyDecode(
        logits: Array<FloatArray>,
        vocabSize: Int,
    ): Pair<String, Float> {
        val sb = StringBuilder()
        var prev = -1
        var probSum = 0f
        var probCount = 0
        for (t in logits.indices) {
            val row = logits[t]
            // argmax
            var best = 0
            var bestVal = row[0]
            for (i in 1 until vocabSize) {
                if (row[i] > bestVal) {
                    bestVal = row[i]
                    best = i
                }
            }
            if (best != prev && best != blankIndex) {
                val token = vocab.getOrNull(best)
                if (!token.isNullOrEmpty() && token != "<blank>") {
                    sb.append(token)
                }
                probSum += softmaxProb(row, best, vocabSize)
                probCount++
            }
            prev = best
        }
        val confidence = if (probCount > 0) probSum / probCount else 0f
        return sb.toString().trim() to confidence.coerceIn(0f, 1f)
    }

    private fun softmaxProb(logits: FloatArray, index: Int, size: Int): Float {
        var maxV = logits[0]
        for (i in 1 until size) if (logits[i] > maxV) maxV = logits[i]
        var sum = 0f
        for (i in 0 until size) sum += exp((logits[i] - maxV).toDouble()).toFloat()
        if (sum <= 0f) return 0f
        return (exp((logits[index] - maxV).toDouble()).toFloat() / sum)
            .also { if (it.isNaN()) return 0f }
    }

    private fun loadModelFile(): MappedByteBuffer? {
        return try {
            val afd = context.assets.openFd(MODEL_ASSET)
            FileInputStream(afd.fileDescriptor).use { fis ->
                fis.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength,
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadVocab(): List<String> {
        return try {
            context.assets.open(VOCAB_ASSET).bufferedReader().use { r ->
                r.readLines().map { it.trimEnd('\r', '\n') }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Vocab missing — using ASCII fallback: ${e.message}")
            (('a'..'z').map { it.toString() } + " " + "'" + "<blank>")
        }
    }
}
