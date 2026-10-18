package com.talksync.translation

// asl-ish gloss helper. useful approximation, not pretending to be full sign language.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.talksync.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.hypot

class AslTranslationEngine(
    context: Context,
) {
    private val optimizedVariant = BuildConfig.OPTIMIZED_VARIANT

    data class FinalizedTranslation(
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private data class Candidate(
        val token: String,
        val kind: Kind,
        val score: Float,
        val debugLabel: String,
    ) {
        enum class Kind {
            LETTER,
            WORD,
        }
    }

    private val appContext = context.applicationContext
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _partialTranslation = MutableStateFlow("")
    val partialTranslation: StateFlow<String> = _partialTranslation.asStateFlow()

    private val _statusText = MutableStateFlow(DEFAULT_STATUS)
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isTrackingHands = MutableStateFlow(false)
    val isTrackingHands: StateFlow<Boolean> = _isTrackingHands.asStateFlow()

    private val _finalizedTranslation = MutableSharedFlow<FinalizedTranslation>(extraBufferCapacity = 8)
    val finalizedTranslation: SharedFlow<FinalizedTranslation> = _finalizedTranslation.asSharedFlow()

    private var gestureRecognizer: GestureRecognizer? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var lastAnalysisAt = 0L
    private var lastSignalAt = 0L
    private var lastHandSeenAt = 0L
    private var analysisInFlight = false
    private var pendingCandidate: Candidate? = null
    private var pendingCandidateCount = 0
    private var lastCommittedToken: String? = null
    private var awaitingDifferentToken = false
    private var inFlightBitmap: Bitmap? = null
    private val committedWords = mutableListOf<String>()
    private val liveLetters = StringBuilder()

    fun start() {
        if (_isRunning.value) {
            return
        }
        _isRunning.value = true
        _statusText.value = DEFAULT_STATUS
        engineScope.launch {
            if (gestureRecognizer == null) {
                gestureRecognizer = createRecognizer()
            }
        }
    }

    fun stop() {
        if (!_isRunning.value) {
            return
        }
        flushTranslation(finalizeOnly = true)
        _isRunning.value = false
        _isTrackingHands.value = false
        _statusText.value = DEFAULT_STATUS
        _partialTranslation.value = ""
        pendingCandidate = null
        pendingCandidateCount = 0
        awaitingDifferentToken = false
        lastCommittedToken = null
        committedWords.clear()
        liveLetters.clear()
        analysisInFlight = false
    }

    fun release() {
        stop()
        try {
            gestureRecognizer?.close()
        } catch (_: Throwable) {
        }
        gestureRecognizer = null
        releaseInFlightBitmap()
        engineScope.cancel()
    }

    fun updateLensFacing(lensFacing: Int) {
        this.lensFacing = lensFacing
    }

    fun onCameraFrame(imageProxy: ImageProxy) {
        try {
            if (!_isRunning.value) {
                return
            }

            val now = SystemClock.uptimeMillis()
            if (analysisInFlight || now - lastAnalysisAt < ANALYSIS_INTERVAL_MS) {
                return
            }
            lastAnalysisAt = now

            val recognizer = gestureRecognizer ?: return
            analysisInFlight = true
            val bitmap = imageProxyToBitmap(imageProxy, lensFacing)
            synchronized(this) {
                inFlightBitmap = bitmap
            }
            val mpImage = BitmapImageBuilder(bitmap).build()
            recognizer.recognizeAsync(mpImage, now)
        } catch (_: Throwable) {
            analysisInFlight = false
            releaseInFlightBitmap()
            _statusText.value = "ASL beta unavailable"
        } finally {
            imageProxy.close()
        }
    }

    fun onPreviewBitmap(bitmap: Bitmap) {
        if (!_isRunning.value) {
            bitmap.recycle()
            return
        }

        val now = SystemClock.uptimeMillis()
        if (analysisInFlight || now - lastAnalysisAt < previewCaptureIntervalMs()) {
            bitmap.recycle()
            return
        }

        val recognizer = gestureRecognizer ?: run {
            bitmap.recycle()
            return
        }
        lastAnalysisAt = now
        analysisInFlight = true

        engineScope.launch {
            try {
                val preparedBitmap = prepareBitmap(bitmap)
                synchronized(this@AslTranslationEngine) {
                    inFlightBitmap = preparedBitmap
                }
                val mpImage = BitmapImageBuilder(preparedBitmap).build()
                recognizer.recognizeAsync(mpImage, now)
            } catch (_: Throwable) {
                analysisInFlight = false
                releaseInFlightBitmap()
                _statusText.value = "ASL beta unavailable"
            }
        }
    }

    private fun createRecognizer(): GestureRecognizer {
        val baseOptions = BaseOptions.builder()
            .setDelegate(Delegate.CPU)
            .setModelAssetPath(MODEL_ASSET_PATH)
            .build()

        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.45f)
            .setMinHandPresenceConfidence(0.40f)
            .setMinTrackingConfidence(0.45f)
            .setResultListener(this::handleResult)
            .setErrorListener { _ ->
                analysisInFlight = false
                releaseInFlightBitmap()
                _statusText.value = "ASL beta unavailable"
            }
            .build()

        return GestureRecognizer.createFromOptions(appContext, options)
    }

    private fun handleResult(
        result: GestureRecognizerResult,
        @Suppress("UNUSED_PARAMETER") inputImage: com.google.mediapipe.framework.image.MPImage,
    ) {
        analysisInFlight = false
        releaseInFlightBitmap()
        val now = SystemClock.uptimeMillis()
        val gestures = result.gestures()
        val landmarks = result.landmarks()

        if (gestures.isEmpty() || landmarks.isEmpty()) {
            onNoHands(now)
            return
        }

        lastHandSeenAt = now
        _isTrackingHands.value = true

        val bestGesture = gestures.firstOrNull()?.firstOrNull()
        val handLandmarks = landmarks.firstOrNull().orEmpty()
        val handedness = result.handednesses().firstOrNull()?.firstOrNull()?.categoryName().orEmpty()
        val candidate = resolveCandidate(
            gestureName = bestGesture?.categoryName().orEmpty(),
            gestureScore = bestGesture?.score() ?: 0f,
            landmarks = handLandmarks,
            handedness = handedness,
        )

        if (candidate == null) {
            _statusText.value = "ASL beta · hand detected"
            if (now - lastSignalAt >= IDLE_FINALIZE_MS) {
                flushTranslation(finalizeOnly = true)
            }
            return
        }

        _statusText.value = "ASL beta · ${candidate.debugLabel}"
        lastSignalAt = now

        if (awaitingDifferentToken && candidate.token == lastCommittedToken) {
            return
        }

        if (pendingCandidate?.token == candidate.token) {
            // same token has to repeat before commit
            pendingCandidateCount += 1
        } else {
            pendingCandidate = candidate
            pendingCandidateCount = 1
        }

        if (pendingCandidateCount >= REQUIRED_STABLE_FRAMES) {
            commitCandidate(candidate)
            pendingCandidate = null
            pendingCandidateCount = 0
        }
    }

    private fun onNoHands(now: Long) {
        _isTrackingHands.value = false
        pendingCandidate = null
        pendingCandidateCount = 0

        if (now - lastHandSeenAt > RELEASE_RESET_MS) {
            awaitingDifferentToken = false
            lastCommittedToken = null
        }

        if (buildCurrentPhrase().isBlank()) {
            _statusText.value = DEFAULT_STATUS
            return
        }

        if (now - lastSignalAt >= IDLE_FINALIZE_MS) {
            flushTranslation(finalizeOnly = true)
            _statusText.value = DEFAULT_STATUS
        } else {
            _statusText.value = "ASL beta · hold or finish sign"
        }
    }

    private fun commitCandidate(candidate: Candidate) {
        if (candidate.kind == Candidate.Kind.WORD) {
            flushLettersIntoWords()
            committedWords += candidate.token
        } else {
            if (committedWords.isNotEmpty() && liveLetters.isEmpty()) {
                committedWords += ""
            }
            liveLetters.append(candidate.token)
        }

        awaitingDifferentToken = true
        lastCommittedToken = candidate.token
        _partialTranslation.value = buildCurrentPhrase()
    }

    private fun flushTranslation(finalizeOnly: Boolean) {
        flushLettersIntoWords()
        val finalText = buildCurrentPhrase().trim()
        if (finalText.isNotBlank()) {
            _finalizedTranslation.tryEmit(
                FinalizedTranslation(
                    text = finalText,
                ),
            )
        }
        committedWords.clear()
        liveLetters.clear()
        _partialTranslation.value = ""
        pendingCandidate = null
        pendingCandidateCount = 0
        if (!finalizeOnly) {
            awaitingDifferentToken = false
            lastCommittedToken = null
        }
    }

    private fun flushLettersIntoWords() {
        if (liveLetters.isEmpty()) {
            return
        }
        committedWords += liveLetters.toString()
        liveLetters.clear()
    }

    private fun buildCurrentPhrase(): String {
        val words = committedWords.filter { it.isNotBlank() }.toMutableList()
        if (liveLetters.isNotEmpty()) {
            words += liveLetters.toString()
        }
        return words.joinToString(separator = " ").trim()
    }

    private fun resolveCandidate(
        gestureName: String,
        gestureScore: Float,
        landmarks: List<NormalizedLandmark>,
        handedness: String,
    ): Candidate? {
        cannedGestureCandidate(gestureName, gestureScore)?.let { return it }
        return heuristicLetterCandidate(landmarks, handedness)
    }

    private fun cannedGestureCandidate(
        gestureName: String,
        gestureScore: Float,
    ): Candidate? {
        if (gestureScore < MIN_GESTURE_SCORE) {
            return null
        }

        val word = when (gestureName) {
            "ILoveYou" -> "I love you"
            "Thumb_Up" -> "yes"
            "Thumb_Down" -> "no"
            "Open_Palm" -> "hello"
            "Closed_Fist" -> "stop"
            "Pointing_Up" -> "wait"
            "Victory" -> "peace"
            else -> null
        } ?: return null

        return Candidate(
            token = word,
            kind = Candidate.Kind.WORD,
            score = gestureScore,
            debugLabel = word.lowercase(),
        )
    }

    private fun heuristicLetterCandidate(
        landmarks: List<NormalizedLandmark>,
        handedness: String,
    ): Candidate? {
        if (landmarks.size < 21) {
            return null
        }

        val indexExtended = isFingerExtended(landmarks, tip = 8, pip = 6)
        val middleExtended = isFingerExtended(landmarks, tip = 12, pip = 10)
        val ringExtended = isFingerExtended(landmarks, tip = 16, pip = 14)
        val pinkyExtended = isFingerExtended(landmarks, tip = 20, pip = 18)
        val thumbExtended = isThumbExtended(landmarks, handedness)
        val indexMiddleSpread = distance(landmarks[8], landmarks[12]) > 0.11f

        val letter = when {
            thumbExtended && !indexExtended && !middleExtended && !ringExtended && !pinkyExtended -> "A"
            !thumbExtended && indexExtended && middleExtended && ringExtended && pinkyExtended -> "B"
            !thumbExtended && indexExtended && !middleExtended && !ringExtended && !pinkyExtended -> "D"
            !thumbExtended && !indexExtended && !middleExtended && !ringExtended && pinkyExtended -> "I"
            thumbExtended && indexExtended && !middleExtended && !ringExtended && !pinkyExtended -> "L"
            thumbExtended && !indexExtended && !middleExtended && !ringExtended && pinkyExtended -> "Y"
            !thumbExtended && indexExtended && middleExtended && !ringExtended && !pinkyExtended && indexMiddleSpread -> "V"
            !thumbExtended && indexExtended && middleExtended && ringExtended && !pinkyExtended -> "W"
            else -> null
        } ?: return null

        return Candidate(
            token = letter,
            kind = Candidate.Kind.LETTER,
            score = 0.6f,
            debugLabel = "letter $letter",
        )
    }

    private fun isFingerExtended(
        landmarks: List<NormalizedLandmark>,
        tip: Int,
        pip: Int,
    ): Boolean {
        return landmarks[tip].y() < landmarks[pip].y() &&
            abs(landmarks[tip].y() - landmarks[pip].y()) > 0.05f
    }

    private fun isThumbExtended(
        landmarks: List<NormalizedLandmark>,
        handedness: String,
    ): Boolean {
        val tip = landmarks[4]
        val ip = landmarks[3]
        val mcp = landmarks[2]
        return if (handedness.equals("Left", ignoreCase = true)) {
            tip.x() < ip.x() && ip.x() < mcp.x()
        } else {
            tip.x() > ip.x() && ip.x() > mcp.x()
        }
    }

    private fun distance(
        first: NormalizedLandmark,
        second: NormalizedLandmark,
    ): Float {
        return hypot(first.x() - second.x(), first.y() - second.y())
    }

    private fun imageProxyToBitmap(
        imageProxy: ImageProxy,
        lensFacing: Int,
    ): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val jpegStream = ByteArrayOutputStream()
        YuvImage(
            nv21,
            ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null,
        ).compressToJpeg(
            Rect(0, 0, imageProxy.width, imageProxy.height),
            72,
            jpegStream,
        )

        val decodedBitmap = BitmapFactory.decodeByteArray(
            jpegStream.toByteArray(),
            0,
            jpegStream.size(),
        )

        val matrix = Matrix().apply {
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                postScale(-1f, 1f, decodedBitmap.width / 2f, decodedBitmap.height / 2f)
            }
        }
        val transformedBitmap = Bitmap.createBitmap(
            decodedBitmap,
            0,
            0,
            decodedBitmap.width,
            decodedBitmap.height,
            matrix,
            true,
        )
        if (transformedBitmap !== decodedBitmap) {
            decodedBitmap.recycle()
        }
        return transformedBitmap
    }

    private fun prepareBitmap(
        bitmap: Bitmap,
    ): Bitmap {
        val argbBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false).also {
                bitmap.recycle()
            }
        }

        val maxSide = maxOf(argbBitmap.width, argbBitmap.height)
        val maxInputLongEdgePx = maxInputLongEdgePx()
        val scale = if (maxSide > maxInputLongEdgePx) {
            maxInputLongEdgePx.toFloat() / maxSide.toFloat()
        } else {
            1f
        }

        val scaledBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                argbBitmap,
                (argbBitmap.width * scale).toInt().coerceAtLeast(1),
                (argbBitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            argbBitmap
        }

        if (scaledBitmap !== argbBitmap) {
            argbBitmap.recycle()
        }
        return scaledBitmap
    }

    private fun releaseInFlightBitmap() {
        val bitmap = synchronized(this) {
            inFlightBitmap.also {
                inFlightBitmap = null
            }
        }
        bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
    }

    private fun previewCaptureIntervalMs(): Long {
        return if (optimizedVariant) 260L else PREVIEW_CAPTURE_INTERVAL_MS
    }

    private fun maxInputLongEdgePx(): Int {
        return if (optimizedVariant) 360 else MAX_INPUT_LONG_EDGE_PX
    }

    private companion object {
        const val MODEL_ASSET_PATH = "gesture_recognizer.task"
        const val ANALYSIS_INTERVAL_MS = 120L
        const val PREVIEW_CAPTURE_INTERVAL_MS = 180L
        const val REQUIRED_STABLE_FRAMES = 3
        const val RELEASE_RESET_MS = 420L
        const val IDLE_FINALIZE_MS = 1_100L
        const val MIN_GESTURE_SCORE = 0.68f
        const val MAX_INPUT_LONG_EDGE_PX = 480
        const val DEFAULT_STATUS = "ASL beta · show one hand"
    }
}
