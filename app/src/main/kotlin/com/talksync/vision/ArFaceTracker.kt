package com.talksync.vision

// arcore face tracker that feeds face positions back into the caption overlay.

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.media.Image
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.max

class ArFaceTracker {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build(),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _faceRect = MutableStateFlow<RectF?>(null)
    val faceRect: StateFlow<RectF?> = _faceRect.asStateFlow()

    @Volatile
    private var isProcessing = false

    @Volatile
    private var lastProcessedAt = 0L

    @Volatile
    private var lastSeenAt = 0L

    init {
        scope.launch {
            while (isActive) {
                if (lastSeenAt > 0L && System.currentTimeMillis() - lastSeenAt > FACE_LOST_TIMEOUT_MS) {
                    _faceRect.value = null
                }
                delay(200L)
            }
        }
    }

    fun processCameraImage(
        image: Image,
        viewWidth: Int,
        viewHeight: Int,
        onJpegReady: (ByteArray) -> Unit,
    ) {
        val now = System.currentTimeMillis()
        if (isProcessing || now - lastProcessedAt < FACE_PROCESS_INTERVAL_MS || viewWidth <= 0 || viewHeight <= 0) {
            image.close()
            return
        }
        isProcessing = true
        lastProcessedAt = now

        val jpegBytes = try {
            imageToJpeg(image)
        } catch (_: Throwable) {
            isProcessing = false
            return
        } finally {
            image.close()
        }

        onJpegReady(jpegBytes)

        val uprightBitmap = try {
            jpegToPortraitBitmap(jpegBytes)
        } catch (_: Throwable) {
            isProcessing = false
            return
        }
        val inputImage = InputImage.fromBitmap(uprightBitmap, 0)

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face != null) {
                    lastSeenAt = System.currentTimeMillis()
                    _faceRect.value = mapRectToView(
                        boundingBox = RectF(face.boundingBox),
                        contentWidth = uprightBitmap.width.toFloat(),
                        contentHeight = uprightBitmap.height.toFloat(),
                        viewWidth = viewWidth.toFloat(),
                        viewHeight = viewHeight.toFloat(),
                    )
                } else if (System.currentTimeMillis() - lastSeenAt > FACE_LOST_TIMEOUT_MS) {
                    _faceRect.value = null
                }
            }
            .addOnFailureListener {
                if (System.currentTimeMillis() - lastSeenAt > FACE_LOST_TIMEOUT_MS) {
                    _faceRect.value = null
                }
            }
            .addOnCompleteListener {
                uprightBitmap.recycle()
                isProcessing = false
            }
    }

    fun release() {
        try {
            detector.close()
        } catch (_: Throwable) {
        }
        scope.cancel()
        _faceRect.value = null
    }

    private fun imageToJpeg(image: Image): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val output = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 60, output)
        return output.toByteArray()
    }

    private fun jpegToPortraitBitmap(jpegBytes: ByteArray): Bitmap {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        if (bitmap.width <= bitmap.height) {
            return bitmap
        }
        val matrix = Matrix().apply { postRotate(90f) }
        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotatedBitmap !== bitmap) {
            bitmap.recycle()
        }
        return rotatedBitmap
    }

    private fun mapRectToView(
        boundingBox: RectF,
        contentWidth: Float,
        contentHeight: Float,
        viewWidth: Float,
        viewHeight: Float,
    ): RectF {
        val scale = max(viewWidth / contentWidth, viewHeight / contentHeight)
        val scaledWidth = contentWidth * scale
        val scaledHeight = contentHeight * scale
        val xOffset = (viewWidth - scaledWidth) / 2f
        val yOffset = (viewHeight - scaledHeight) / 2f
        return RectF(
            (boundingBox.left * scale) + xOffset,
            (boundingBox.top * scale) + yOffset,
            (boundingBox.right * scale) + xOffset,
            (boundingBox.bottom * scale) + yOffset,
        )
    }

    private companion object {
        const val FACE_PROCESS_INTERVAL_MS = 350L
        const val FACE_LOST_TIMEOUT_MS = 1_500L
    }
}
