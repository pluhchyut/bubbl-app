package com.talksync.vision

// mediapipe lip/face landmark analyzer. this is where the visual speech signal comes from.

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Computes precise lip movement signals per tracked face using MediaPipe
 * Face Landmarker's facial landmarks.
 *
 * TODO: Before building, download the model file:
 *   URL: https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
 *   Destination: app/src/main/assets/face_landmarker.task
 *
 * Two signals are computed per face:
 *
 * 1. APERTURE SCORE — how open the mouth is right now
 *    Uses upper/lower lip landmark distance normalized by face height.
 *
 * 2. VELOCITY SCORE — how fast the mouth is moving frame to frame
 *    Computes displacement of key lip landmarks between frames.
 *
 * Both scores are combined into a single LipSignal per face which replaces
 * the old LipActivityAnalyzer.FaceActivity in SpeakerFaceAssignmentStore.
 *
 * Face matching strategy:
 * MediaPipe detects faces independently from ML Kit FaceTracker.
 * We match MediaPipe faces to TrackedFace entries by bounding box proximity
 * since they share the same camera frames.
 *
 * Call onFrame() from a background thread.
 * Read lipSignals from any thread via StateFlow.
 */
class LipLandmarkAnalyzer(context: Context) {

    /**
     * Four lip contour segments used by the debug overlay.
     * Each entry is an ordered list of normalized (x, y) points
     * that should be drawn as a polyline.
     *
     *   [0] outer upper lip  (61→…→291)
     *   [1] outer lower lip  (291→…→61)
     *   [2] inner upper lip  (78→…→308)
     *   [3] inner lower lip  (308→…→78)
     *
     * Empty when the landmarker hasn't produced results yet.
     */
    data class LipContour(
        val outerUpper: List<Pair<Float, Float>>,
        val outerLower: List<Pair<Float, Float>>,
        val innerUpper: List<Pair<Float, Float>>,
        val innerLower: List<Pair<Float, Float>>,
    ) {
        val isEmpty: Boolean
            get() = outerUpper.isEmpty()
    }

    data class LipSignal(
        val faceId: String,
        val apertureScore: Float,
        val velocityScore: Float,
        val combinedScore: Float,
        val updatedAt: Long,
        val headPose: HeadPose = HeadPose(),
        val gaze: GazeSignal = GazeSignal(),
        val visualAttentionScore: Float = 0f,
        // Normalized [0..1] image-space coordinates of the mouth center,
        // computed as the midpoint of upper- and lower-lip inner landmarks.
        // Used by the bubble overlay to anchor captions near the mouth
        // rather than the face bounding box.
        val mouthCenterX: Float = 0.5f,
        val mouthCenterY: Float = 0.5f,
        // Full lip contour for the debug landmark overlay. Always populated
        // when landmarker is running; empty list before first result.
        val contour: LipContour = EMPTY_CONTOUR,
    )

    data class HeadPose(
        val yawDegrees: Float = 0f,
        val pitchDegrees: Float = 0f,
        val rollDegrees: Float = 0f,
        val frontalScore: Float = 0f,
    )

    data class GazeSignal(
        val horizontal: Float = 0f,
        val vertical: Float = 0f,
        val attentionScore: Float = 0f,
    )

    private val appContext = context.applicationContext
    private val tag = "LipLandmarkAnalyzer"
    private val stateLock = Any()

    private val _lipSignals = MutableStateFlow<Map<String, LipSignal>>(emptyMap())
    val lipSignals: StateFlow<Map<String, LipSignal>> = _lipSignals.asStateFlow()

    /**
     * Per-face geometric pseudo-embedding vector keyed by faceId. Used by
     * [FaceIdentityMatcher] to cluster repeated appearances of the same
     * person across sessions. Updated every frame in lockstep with
     * [lipSignals].
     */
    private val _faceFeatures = MutableStateFlow<Map<String, FloatArray>>(emptyMap())
    val faceFeatures: StateFlow<Map<String, FloatArray>> = _faceFeatures.asStateFlow()

    private var faceLandmarker: FaceLandmarker? = null
    private var isInitialized = false
    private var inFlightBitmap: Bitmap? = null

    private val previousLipPositions = mutableMapOf<Int, List<Pair<Float, Float>>>()

    // Minimum mouth aperture ratio to be considered "open"
    // Face height normalized — empirically ~0.02 for slight opening
    private val apertureThreshold = 0.018f

    // Key lip landmark indices in MediaPipe's face model.
    private val upperLipInner = 13
    private val lowerLipInner = 14
    private val velocityLandmarks = listOf(12, 13, 14, 15, 61, 291, 87, 317)
    private val noseTip = 1
    private val chin = 152
    private val leftEyeOuter = 33
    private val leftEyeInner = 133
    private val rightEyeInner = 362
    private val rightEyeOuter = 263
    private val leftEyeUpper = 159
    private val leftEyeLower = 145
    private val rightEyeUpper = 386
    private val rightEyeLower = 374
    private val leftIrisIndices = intArrayOf(468, 469, 470, 471, 472)
    private val rightIrisIndices = intArrayOf(473, 474, 475, 476, 477)

    // Ordered landmark index lists for the four lip contour segments.
    // Outer ring traces the visible lip edge; inner ring traces the
    // opening of the mouth. Drawn as polylines in the debug overlay.
    private val outerUpperIndices = intArrayOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291)
    private val outerLowerIndices = intArrayOf(291, 375, 321, 405, 314, 17, 84, 181, 91, 146, 61)
    private val innerUpperIndices = intArrayOf(78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308)
    private val innerLowerIndices = intArrayOf(308, 324, 318, 402, 317, 14, 87, 178, 88, 95, 78)

    @Volatile
    private var lastKnownTrackedFaces: List<TrackedFace> = emptyList()

    @Volatile
    private var analysisInFlight = false

    fun initialize() {
        if (isInitialized) {
            return
        }
        // Prefer the GPU delegate: it roughly halves inference latency, which
        // lets the higher-resolution frames be processed without starving the
        // lip-tracking cadence. Fall back to CPU on devices where GPU init fails
        // (no GL support, emulator, etc.).
        faceLandmarker = createLandmarker(Delegate.GPU)?.also {
            Log.d(tag, "FaceLandmarker initialized on GPU")
        } ?: createLandmarker(Delegate.CPU)?.also {
            Log.d(tag, "FaceLandmarker initialized on CPU (GPU unavailable)")
        }
        isInitialized = faceLandmarker != null
        if (!isInitialized) {
            Log.e(tag, "Failed to initialize FaceLandmarker on GPU and CPU")
        }
    }

    private fun createLandmarker(delegate: Delegate): FaceLandmarker? {
        return try {
            val baseOptions = BaseOptions.builder()
                .setDelegate(delegate)
                .setModelAssetPath("face_landmarker.task")
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(4)
                .setMinFaceDetectionConfidence(0.40f)
                .setMinFacePresenceConfidence(0.40f)
                .setMinTrackingConfidence(0.40f)
                .setOutputFaceBlendshapes(false)
                .setOutputFacialTransformationMatrixes(true)
                .setResultListener(this::handleResult)
                .setErrorListener { error ->
                    analysisInFlight = false
                    releaseInFlightBitmap()
                    Log.w(tag, "FaceLandmarker error: $error")
                }
                .build()

            FaceLandmarker.createFromOptions(appContext, options)
        } catch (e: Exception) {
            Log.w(tag, "FaceLandmarker init failed on $delegate", e)
            null
        }
    }

    /**
     * Process a camera frame.
     * Call from background thread with the same scaled bitmap used by FaceTracker.
     */
    fun onFrame(
        bitmap: Bitmap,
        trackedFaces: List<TrackedFace>,
        now: Long = SystemClock.uptimeMillis(),
    ) {
        val landmarker = faceLandmarker ?: run {
            bitmap.recycle()
            return
        }
        if (!isInitialized) {
            bitmap.recycle()
            return
        }

        lastKnownTrackedFaces = trackedFaces
        if (trackedFaces.isEmpty()) {
            clearSignals()
            bitmap.recycle()
            return
        }

        if (analysisInFlight) {
            bitmap.recycle()
            return
        }

        try {
            analysisInFlight = true
            val ownedBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false).also {
                    bitmap.recycle()
                }
            }
            synchronized(stateLock) {
                inFlightBitmap = ownedBitmap
            }
            val mpImage = BitmapImageBuilder(ownedBitmap).build()
            landmarker.detectAsync(mpImage, now)
        } catch (e: Exception) {
            analysisInFlight = false
            releaseInFlightBitmap()
            Log.w(tag, "Frame processing error", e)
        }
    }

    fun updateTrackedFaces(faces: List<TrackedFace>) {
        lastKnownTrackedFaces = faces
        if (faces.isEmpty()) {
            clearSignals()
        }
    }

    fun release() {
        try {
            faceLandmarker?.close()
        } catch (_: Throwable) {
        }
        analysisInFlight = false
        releaseInFlightBitmap()
        faceLandmarker = null
        isInitialized = false
        lastKnownTrackedFaces = emptyList()
        clearSignals()
    }

    private fun handleResult(
        result: FaceLandmarkerResult,
        @Suppress("UNUSED_PARAMETER") input: MPImage,
    ) {
        analysisInFlight = false
        releaseInFlightBitmap()
        val allLandmarks = result.faceLandmarks()
        if (allLandmarks.isEmpty()) {
            synchronized(stateLock) {
                previousLipPositions.clear()
            }
            _lipSignals.value = emptyMap()
            _faceFeatures.value = emptyMap()
            return
        }

        val trackedFaces = lastKnownTrackedFaces
        if (trackedFaces.isEmpty()) {
            clearSignals()
            return
        }

        val updatedSignals = mutableMapOf<String, LipSignal>()
        val updatedFeatures = mutableMapOf<String, FloatArray>()
        val now = System.currentTimeMillis()

        allLandmarks.forEachIndexed { mpIndex, landmarks ->
            if (landmarks.isEmpty()) {
                return@forEachIndexed
            }

            val upperInner = landmarks.getOrNull(upperLipInner)
            val lowerInner = landmarks.getOrNull(lowerLipInner)
            val nose = landmarks.getOrNull(noseTip)
            val chinPoint = landmarks.getOrNull(chin)
            if (upperInner == null || lowerInner == null || nose == null || chinPoint == null) {
                return@forEachIndexed
            }

            val lipGap = abs(lowerInner.y() - upperInner.y())
            val faceHeight = abs(chinPoint.y() - nose.y()).coerceAtLeast(0.01f)
            val normalizedAperture = (lipGap / faceHeight).coerceIn(0f, 1f)
            val apertureScore = if (normalizedAperture < apertureThreshold) {
                0f
            } else {
                ((normalizedAperture - apertureThreshold) / (0.18f - apertureThreshold)).coerceIn(0f, 1f)
            }

            val currentPositions = velocityLandmarks.mapNotNull { idx ->
                landmarks.getOrNull(idx)?.let { landmark -> landmark.x() to landmark.y() }
            }
            val previousPositions = synchronized(stateLock) {
                val previous = previousLipPositions[mpIndex]
                previousLipPositions[mpIndex] = currentPositions
                previous
            }

            val velocityScore = if (
                previousPositions != null &&
                previousPositions.size == currentPositions.size &&
                currentPositions.isNotEmpty()
            ) {
                val totalDisplacement = currentPositions.zip(previousPositions)
                    .sumOf { (current, previous) ->
                        hypot(
                            (current.first - previous.first).toDouble(),
                            (current.second - previous.second).toDouble(),
                        )
                    }
                    .toFloat()
                val averageDisplacement = totalDisplacement / currentPositions.size
                (averageDisplacement / 0.025f).coerceIn(0f, 1f)
            } else {
                0f
            }

            val matchedFaceId = matchToTrackedFace(landmarks, trackedFaces) ?: return@forEachIndexed
            // Aperture (how open the mouth is) is more reliable than velocity
            // for distinguishing the active speaker — velocity fires on swallows,
            // head movements, and micro-expressions. Raised from 0.60 → 0.75.
            val combinedScore = (apertureScore * 0.75f) + (velocityScore * 0.25f)

            val mouthCenterX = ((upperInner.x() + lowerInner.x()) * 0.5f).coerceIn(0f, 1f)
            val mouthCenterY = ((upperInner.y() + lowerInner.y()) * 0.5f).coerceIn(0f, 1f)

            val headPose = estimateHeadPose(landmarks)
            val gaze = estimateGaze(landmarks, headPose)
            val visualAttentionScore = ((headPose.frontalScore * 0.58f) + (gaze.attentionScore * 0.42f))
                .coerceIn(0f, 1f)
            val contour = extractLipContour(landmarks)

            updatedSignals[matchedFaceId] = LipSignal(
                faceId = matchedFaceId,
                apertureScore = apertureScore,
                velocityScore = velocityScore,
                combinedScore = combinedScore,
                updatedAt = now,
                headPose = headPose,
                gaze = gaze,
                visualAttentionScore = visualAttentionScore,
                mouthCenterX = mouthCenterX,
                mouthCenterY = mouthCenterY,
                contour = contour,
            )

            FaceFeatureExtractor.extract(landmarks)?.let { feature ->
                updatedFeatures[matchedFaceId] = feature
            }
        }

        _lipSignals.value = updatedSignals
        _faceFeatures.value = updatedFeatures
    }

    /**
     * Extract the four ordered lip contour segments from a landmark list.
     * Points are in normalized [0..1] image space (x right, y down).
     * Returns [EMPTY_CONTOUR] if any index is out of bounds.
     */
    private fun extractLipContour(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
    ): LipContour {
        fun segment(indices: IntArray): List<Pair<Float, Float>> {
            val pts = mutableListOf<Pair<Float, Float>>()
            for (idx in indices) {
                val lm = landmarks.getOrNull(idx) ?: return emptyList()
                pts.add(lm.x() to lm.y())
            }
            return pts
        }
        return LipContour(
            outerUpper = segment(outerUpperIndices),
            outerLower = segment(outerLowerIndices),
            innerUpper = segment(innerUpperIndices),
            innerLower = segment(innerLowerIndices),
        )
    }

    private fun estimateHeadPose(
        landmarks: List<NormalizedLandmark>,
    ): HeadPose {
        val leftEye = midpoint(leftEyeOuter, leftEyeInner, landmarks) ?: return HeadPose()
        val rightEye = midpoint(rightEyeInner, rightEyeOuter, landmarks) ?: return HeadPose()
        val nose = landmarks.getOrNull(noseTip) ?: return HeadPose()
        val mouth = midpoint(upperLipInner, lowerLipInner, landmarks) ?: return HeadPose()

        val eyeCenterX = (leftEye.first + rightEye.first) * 0.5f
        val eyeCenterY = (leftEye.second + rightEye.second) * 0.5f
        val eyeSpan = hypot(
            (rightEye.first - leftEye.first).toDouble(),
            (rightEye.second - leftEye.second).toDouble(),
        ).toFloat().coerceAtLeast(0.001f)
        val eyeToMouth = abs(mouth.second - eyeCenterY).coerceAtLeast(0.001f)

        val yaw = (((nose.x() - eyeCenterX) / eyeSpan) * 90f).coerceIn(-45f, 45f)
        val noseDrop = ((nose.y() - eyeCenterY) / eyeToMouth).coerceIn(-1f, 2f)
        val pitch = ((0.45f - noseDrop) * 68f).coerceIn(-45f, 45f)
        val roll = atan2(
            (rightEye.second - leftEye.second).toDouble(),
            (rightEye.first - leftEye.first).toDouble(),
        ).toFloat() * RAD_TO_DEG

        val yawScore = (1f - (abs(yaw) / 34f)).coerceIn(0f, 1f)
        val pitchScore = (1f - (abs(pitch) / 34f)).coerceIn(0f, 1f)
        val rollScore = (1f - (abs(roll) / 26f)).coerceIn(0f, 1f)
        val frontalScore = ((yawScore * 0.52f) + (pitchScore * 0.28f) + (rollScore * 0.20f))
            .coerceIn(0f, 1f)

        return HeadPose(
            yawDegrees = yaw,
            pitchDegrees = pitch,
            rollDegrees = roll,
            frontalScore = frontalScore,
        )
    }

    private fun estimateGaze(
        landmarks: List<NormalizedLandmark>,
        headPose: HeadPose,
    ): GazeSignal {
        val eyeDirections = listOfNotNull(
            eyeDirection(
                irisIndices = leftIrisIndices,
                outerIndex = leftEyeOuter,
                innerIndex = leftEyeInner,
                upperIndex = leftEyeUpper,
                lowerIndex = leftEyeLower,
                landmarks = landmarks,
            ),
            eyeDirection(
                irisIndices = rightIrisIndices,
                outerIndex = rightEyeOuter,
                innerIndex = rightEyeInner,
                upperIndex = rightEyeUpper,
                lowerIndex = rightEyeLower,
                landmarks = landmarks,
            ),
        )

        if (eyeDirections.isEmpty()) {
            return GazeSignal(attentionScore = headPose.frontalScore)
        }

        val horizontal = eyeDirections.map { it.first }.average().toFloat().coerceIn(-1f, 1f)
        val vertical = eyeDirections.map { it.second }.average().toFloat().coerceIn(-1f, 1f)
        val eyeCenterScore = (1f - ((abs(horizontal) / 0.76f) * 0.62f + (abs(vertical) / 0.72f) * 0.38f))
            .coerceIn(0f, 1f)
        val attentionScore = ((eyeCenterScore * 0.62f) + (headPose.frontalScore * 0.38f))
            .coerceIn(0f, 1f)

        return GazeSignal(
            horizontal = horizontal,
            vertical = vertical,
            attentionScore = attentionScore,
        )
    }

    private fun eyeDirection(
        irisIndices: IntArray,
        outerIndex: Int,
        innerIndex: Int,
        upperIndex: Int,
        lowerIndex: Int,
        landmarks: List<NormalizedLandmark>,
    ): Pair<Float, Float>? {
        val iris = averagePoint(irisIndices, landmarks) ?: return null
        val outer = landmarks.getOrNull(outerIndex) ?: return null
        val inner = landmarks.getOrNull(innerIndex) ?: return null
        val upper = landmarks.getOrNull(upperIndex) ?: return null
        val lower = landmarks.getOrNull(lowerIndex) ?: return null

        val minX = minOf(outer.x(), inner.x())
        val maxX = maxOf(outer.x(), inner.x())
        val centerX = (minX + maxX) * 0.5f
        val width = (maxX - minX).coerceAtLeast(0.001f)

        val minY = minOf(upper.y(), lower.y())
        val maxY = maxOf(upper.y(), lower.y())
        val centerY = (minY + maxY) * 0.5f
        val height = (maxY - minY).coerceAtLeast(0.001f)

        val horizontal = ((iris.first - centerX) / (width * 0.5f)).coerceIn(-1f, 1f)
        val vertical = ((iris.second - centerY) / (height * 0.5f)).coerceIn(-1f, 1f)
        return horizontal to vertical
    }

    private fun midpoint(
        firstIndex: Int,
        secondIndex: Int,
        landmarks: List<NormalizedLandmark>,
    ): Pair<Float, Float>? {
        val first = landmarks.getOrNull(firstIndex) ?: return null
        val second = landmarks.getOrNull(secondIndex) ?: return null
        return ((first.x() + second.x()) * 0.5f) to ((first.y() + second.y()) * 0.5f)
    }

    private fun averagePoint(
        indices: IntArray,
        landmarks: List<NormalizedLandmark>,
    ): Pair<Float, Float>? {
        var x = 0f
        var y = 0f
        var count = 0
        indices.forEach { index ->
            val landmark = landmarks.getOrNull(index) ?: return@forEach
            x += landmark.x()
            y += landmark.y()
            count++
        }
        return if (count == 0) null else (x / count) to (y / count)
    }

    /**
     * Match a MediaPipe face to a TrackedFace from ML Kit by finding the closest face center.
     */
    private fun matchToTrackedFace(
        landmarks: List<NormalizedLandmark>,
        trackedFaces: List<TrackedFace>,
    ): String? {
        if (trackedFaces.isEmpty()) {
            return null
        }

        val nose = landmarks.getOrNull(noseTip) ?: return null
        val mpCenterX = nose.x()
        val mpCenterY = nose.y()

        return trackedFaces.minByOrNull { face ->
            hypot(
                (face.boundingBox.centerX() - mpCenterX).toDouble(),
                (face.boundingBox.centerY() - mpCenterY).toDouble(),
            )
        }?.trackId
    }

    private fun clearSignals() {
        synchronized(stateLock) {
            previousLipPositions.clear()
        }
        _lipSignals.value = emptyMap()
        _faceFeatures.value = emptyMap()
    }

    private fun releaseInFlightBitmap() {
        val bitmap = synchronized(stateLock) {
            inFlightBitmap.also {
                inFlightBitmap = null
            }
        }
        bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
    }

    companion object {
        val EMPTY_CONTOUR = LipContour(
            outerUpper = emptyList(),
            outerLower = emptyList(),
            innerUpper = emptyList(),
            innerLower = emptyList(),
        )
        private const val RAD_TO_DEG = 57.29578f
    }
}
