package com.talksync.vision

// camerax analyzer that turns frames into tracked faces and mouth movement hints.

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.hypot

class FaceTracker : ImageAnalysis.Analyzer {
    private data class DetectedFace(
        val boundingBox: RectF,
        val nativeTrackingId: Int?,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .setMinFaceSize(0.15f)
            .build(),
    )
    private val executor = Executors.newSingleThreadExecutor()

    private val _trackedFaces = MutableStateFlow<List<TrackedFace>>(emptyList())
    val trackedFaces: StateFlow<List<TrackedFace>> = _trackedFaces.asStateFlow()

    private val _faceBoundingBox = MutableStateFlow<RectF?>(null)
    val faceBoundingBox: StateFlow<RectF?> = _faceBoundingBox.asStateFlow()

    private val _latestBitmap = MutableSharedFlow<Bitmap>(extraBufferCapacity = 1)
    val latestBitmap: SharedFlow<Bitmap> = _latestBitmap.asSharedFlow()

    val analysisExecutor: Executor = executor

    @Volatile
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    @Volatile
    private var frameCounter: Int = 0

    @Volatile
    private var nextTrackNumber: Int = 1

    init {
        scope.launch {
            while (isActive) {
                expireStaleTracks()
                delay(200L)
            }
        }
    }

    fun setLensFacing(lensFacing: Int) {
        if (this.lensFacing != lensFacing) {
            _trackedFaces.value = emptyList()
            _faceBoundingBox.value = null
        }
        this.lensFacing = lensFacing
    }

    override fun analyze(imageProxy: ImageProxy) {
        frameCounter += 1
        if (frameCounter % 2 != 0) {
            // skip every other frame so tracker doesn't cook the phone
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val frameLensFacing = lensFacing
        val bitmapForLips = imageProxyToScaledBitmap(imageProxy, frameLensFacing)
        if (bitmapForLips != null) {
            if (!_latestBitmap.tryEmit(bitmapForLips)) {
                bitmapForLips.recycle()
            }
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        try {
            detector.process(inputImage)
                .addOnSuccessListener(analysisExecutor) { faces ->
                    val normalizedFaces = faces.mapNotNull { face ->
                        val leftEye = face.leftEyeOpenProbability
                        val rightEye = face.rightEyeOpenProbability
                        android.util.Log.d("FaceTracker", "detected: L=$leftEye R=$rightEye bounds=${face.boundingBox}")
                        // Require both eyes confidently open — filters TVs, posters, and
                        // partial detections that consistently pollute trackedFaces.
                        val hasEye = leftEye != null && rightEye != null &&
                            leftEye > 0.55f && rightEye > 0.55f
                        if (!hasEye) return@mapNotNull null
                        DetectedFace(
                            boundingBox = normalizeBoundingBox(
                                boundingBox = face.boundingBox,
                                width = imageProxy.width,
                                height = imageProxy.height,
                                rotationDegrees = rotationDegrees,
                                lensFacing = frameLensFacing,
                            ),
                            nativeTrackingId = face.trackingId,
                        )
                    }
                    updateTracks(normalizedFaces)
                }
                .addOnFailureListener(analysisExecutor) {
                    expireStaleTracks(force = false)
                }
                .addOnCompleteListener(analysisExecutor) {
                    imageProxy.close()
                }
        } catch (_: Throwable) {
            imageProxy.close()
        }
    }

    /**
     * Converts the current ImageProxy to a scaled-down Bitmap for lip activity analysis.
     * Scales to max 480px wide (JPEG q85) — Tensor-class silicon has headroom,
     * and the extra resolution sharpens the lip-landmark signal on smaller faces.
     * Must be called before imageProxy.close().
     */
    private fun imageProxyToScaledBitmap(
        imageProxy: ImageProxy,
        lensFacing: Int,
    ): Bitmap? {
        return try {
            val width = imageProxy.width
            val height = imageProxy.height
            val yPlane = imageProxy.planes[0]
            val uPlane = imageProxy.planes[1]
            val vPlane = imageProxy.planes[2]

            // Build a properly-sized NV21 byte array by respecting each plane's
            // rowStride and pixelStride.  The naive approach (buffer.remaining()
            // concatenation) silently corrupts the data on semi-planar cameras
            // (pixelStride == 2, e.g. Pixel 10a back camera) because both U and V
            // buffers point into the same interleaved allocation and their
            // .remaining() values are twice what NV21 expects.
            val nv21 = ByteArray(width * height * 3 / 2)

            // Y plane — copy row by row to skip any row-stride padding.
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            var dstPos = 0
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, dstPos, width)
                dstPos += width
            }

            // VU interleaved (NV21 = V first) — sample every uvPixelStride-th byte
            // from each plane so planar (stride 1) and semi-planar (stride 2) both work.
            val vBuffer = vPlane.buffer
            val uBuffer = uPlane.buffer
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride
            for (row in 0 until height / 2) {
                val rowBase = row * uvRowStride
                for (col in 0 until width / 2) {
                    val src = rowBase + col * uvPixelStride
                    nv21[dstPos++] = vBuffer.get(src)
                    nv21[dstPos++] = uBuffer.get(src)
                }
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
            val decoded = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
                ?: return null
            val oriented = decoded.rotateAndMirror(
                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                mirrorHorizontally = lensFacing == CameraSelector.LENS_FACING_FRONT,
            )
            if (oriented !== decoded) {
                decoded.recycle()
            }

            val scale = MAX_BITMAP_WIDTH_PX / oriented.width.coerceAtLeast(1).toFloat()
            if (scale >= 1f) return oriented
            val scaled = Bitmap.createScaledBitmap(
                oriented,
                (oriented.width * scale).toInt().coerceAtLeast(1),
                (oriented.height * scale).toInt().coerceAtLeast(1),
                false,
            )
            if (scaled !== oriented) oriented.recycle()
            scaled
        } catch (_: Throwable) {
            null
        }
    }

    private fun Bitmap.rotateAndMirror(
        rotationDegrees: Int,
        mirrorHorizontally: Boolean,
    ): Bitmap {
        if (rotationDegrees == 0 && !mirrorHorizontally) {
            return this
        }

        val matrix = Matrix().apply {
            if (rotationDegrees != 0) {
                postRotate(rotationDegrees.toFloat())
            }
            if (mirrorHorizontally) {
                postScale(-1f, 1f, width / 2f, height / 2f)
            }
        }

        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    fun release() {
        try {
            detector.close()
        } catch (_: Throwable) {
        }
        executor.shutdown()
        scope.cancel()
        _trackedFaces.value = emptyList()
        _faceBoundingBox.value = null
    }

    private fun updateTracks(detections: List<DetectedFace>) {
        val now = System.currentTimeMillis()
        val previousTracks = _trackedFaces.value
        if (detections.isEmpty()) {
            if (previousTracks.any { now - it.lastSeenAt > FACE_LOST_TIMEOUT_MS }) {
                expireStaleTracks(force = true)
            }
            return
        }

        val unmatchedPrevious = previousTracks.toMutableList()
        val updatedTracks = mutableListOf<TrackedFace>()

        detections
            .sortedBy { it.boundingBox.centerX() }
            .forEach { detection ->
                // use native id first, distance fallback if tracker blips
                val bestMatchByTrackingId = detection.nativeTrackingId
                    ?.let { nativeId ->
                        unmatchedPrevious.firstOrNull { track -> track.nativeTrackingId == nativeId }
                    }
                val bestMatchByDistance = unmatchedPrevious
                    .map { track -> track to centerDistance(track.boundingBox, detection.boundingBox) }
                    .filter { (_, distance) -> distance <= TRACK_MATCH_DISTANCE }
                    .minByOrNull { (_, distance) -> distance }
                    ?.first
                val bestMatch = bestMatchByTrackingId ?: bestMatchByDistance

                if (bestMatch != null) {
                    unmatchedPrevious.remove(bestMatch)
                    updatedTracks += bestMatch.copy(
                        nativeTrackingId = detection.nativeTrackingId ?: bestMatch.nativeTrackingId,
                        boundingBox = RectF(detection.boundingBox),
                        lastSeenAt = now,
                    )
                } else {
                    updatedTracks += TrackedFace(
                        trackId = "face-${nextTrackNumber++}",
                        nativeTrackingId = detection.nativeTrackingId,
                        boundingBox = RectF(detection.boundingBox),
                        lastSeenAt = now,
                    )
                }
            }

        // A face the detector misses for a few frames (a blink fails the eyes-open
        // check) keeps its track and id, so its speaker link survives the blip.
        val coasting = unmatchedPrevious.filter { now - it.lastSeenAt <= FACE_LOST_TIMEOUT_MS }
        val ordered = (updatedTracks + coasting).sortedBy { it.boundingBox.centerX() }
        _trackedFaces.value = ordered
        _faceBoundingBox.value = updatedTracks.maxByOrNull { it.area }?.boundingBox?.let(::RectF)
    }

    private fun expireStaleTracks(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val currentFaces = _trackedFaces.value
        val filtered = currentFaces.filter { face ->
            now - face.lastSeenAt <= FACE_LOST_TIMEOUT_MS
        }

        if (force || filtered.size != currentFaces.size) {
            _trackedFaces.value = filtered
            _faceBoundingBox.value = filtered.maxByOrNull { it.area }?.boundingBox?.let(::RectF)
        }

        if (filtered.isEmpty()) {
            _faceBoundingBox.value = null
        }
    }

    private fun normalizeBoundingBox(
        boundingBox: Rect,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        lensFacing: Int,
    ): RectF {
        val rotatedWidth = if (rotationDegrees == 90 || rotationDegrees == 270) {
            height.toFloat()
        } else {
            width.toFloat()
        }
        val rotatedHeight = if (rotationDegrees == 90 || rotationDegrees == 270) {
            width.toFloat()
        } else {
            height.toFloat()
        }

        var left = boundingBox.left / rotatedWidth
        var top = boundingBox.top / rotatedHeight
        var right = boundingBox.right / rotatedWidth
        var bottom = boundingBox.bottom / rotatedHeight

        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            // selfie cam needs mirror fix
            val mirroredLeft = 1f - right
            val mirroredRight = 1f - left
            left = mirroredLeft
            right = mirroredRight
        }

        return RectF(
            left.coerceIn(0f, 1f),
            top.coerceIn(0f, 1f),
            right.coerceIn(0f, 1f),
            bottom.coerceIn(0f, 1f),
        )
    }

    private fun centerDistance(first: RectF, second: RectF): Float {
        return hypot(
            first.centerX() - second.centerX(),
            first.centerY() - second.centerY(),
        )
    }

    private companion object {
        const val FACE_LOST_TIMEOUT_MS = 800L
        const val TRACK_MATCH_DISTANCE = 0.22f
        // Matches the 1280x720 analysis stream (720px on the portrait short edge)
        // so the full camera resolution reaches MediaPipe instead of being
        // re-shrunk. Higher values would only upscale — the camera analysis
        // ResolutionStrategy is the real ceiling. MediaPipe runs on the GPU delegate.
        const val MAX_BITMAP_WIDTH_PX = 720
    }
}
