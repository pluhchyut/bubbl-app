package com.talksync.emotion

// hume gives us a lot back, this file turns it into the small EmotionState the app uses.

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

class HumeEmotionEngine {

    val emotionState = MutableStateFlow(EmotionState())
    val isConnected = MutableStateFlow(false)

    private var webSocket: WebSocket? = null
    private var engineScope: CoroutineScope? = null
    private var shouldReconnect = false
    private var audioSendJob: Job? = null
    private var faceSendJob: Job? = null
    private var keepAliveJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile
    private var latestFaceJpeg: ByteArray? = null

    @Volatile
    private var lastAudioSentAt: Long = 0L

    @Volatile
    private var lastFaceCaptureAt: Long = 0L

    @Volatile
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    private var lastFaceScores: EmotionFuser.HumeScores? = null
    private var lastProsodyScores: EmotionFuser.HumeScores? = null
    private var lastBurstScores: EmotionFuser.HumeScores? = null
    private var lastTextSignal: TextEmotionAnalyzer.TextSignal? = null
    private var lastLocalFaceBounds: RectF? = null
    private var lastFaceScoresAt: Long = 0L
    private var lastExpressiveEmotion: EmotionState = EmotionState()
    private var lastExpressiveEmotionAt: Long = 0L

    private val audioBuffer = ByteArrayOutputStream()
    private val audioLock = Any()

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    fun start(externalScope: CoroutineScope) {
        if (shouldReconnect) {
            return
        }
        shouldReconnect = true
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        connect(externalScope)
    }

    fun stop() {
        shouldReconnect = false
        reconnectJob?.cancel()
        cancelSendLoops()
        engineScope?.cancel()
        engineScope = null
        webSocket?.close(1000, "User stopped")
        webSocket = null
        latestFaceJpeg = null
        lastFaceScores = null
        lastProsodyScores = null
        lastBurstScores = null
        lastTextSignal = null
        lastExpressiveEmotion = EmotionState()
        lastExpressiveEmotionAt = 0L
        clearAudioBuffer()
        emotionState.tryEmit(EmotionState())
        isConnected.tryEmit(false)
    }

    fun onAudioChunk(pcmBytes: ByteArray) {
        synchronized(audioLock) {
            audioBuffer.write(pcmBytes)
        }
    }

    fun onCameraJpeg(jpegBytes: ByteArray) {
        latestFaceJpeg = jpegBytes.copyOf()
        lastFaceCaptureAt = System.currentTimeMillis()
    }

    fun updateLensFacing(lensFacing: Int) {
        this.lensFacing = lensFacing
    }

    fun updateLocalFaceBoundingBox(faceBounds: RectF?) {
        lastLocalFaceBounds = faceBounds?.let { RectF(it) }
    }

    fun updateTranscriptText(text: String) {
        lastTextSignal = TextEmotionAnalyzer.analyze(text)
        recalculateEmotion()
    }

    fun onCameraFrame(imageProxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastFaceCaptureAt < HumeConfig.FACE_CAPTURE_INTERVAL_MS) {
                return
            }
            latestFaceJpeg = imageProxyToJpeg(imageProxy)
            lastFaceCaptureAt = now
        } catch (_: Throwable) {
        } finally {
            imageProxy.close()
        }
    }

    private fun connect(externalScope: CoroutineScope) {
        if (
            HumeConfig.API_KEY.isBlank() ||
            HumeConfig.API_KEY == HumeConfig.PLACEHOLDER_API_KEY
        ) {
            Log.w(TAG, "Missing Hume API key. Add it to HumeConfig.kt")
            isConnected.tryEmit(false)
            return
        }

        val request = Request.Builder()
            .url(HumeConfig.WS_URL)
            .header("X-Hume-Api-Key", HumeConfig.API_KEY)
            .build()

        webSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isConnected.tryEmit(true)
                    sendConfigMessage(webSocket)
                    startAudioSendLoop(webSocket)
                    startFaceSendLoop(webSocket)
                    startKeepAliveLoop(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text, externalScope)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Hume socket failure", t)
                    handleDisconnect()
                    scheduleReconnect(externalScope)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "Hume socket closed: code=$code reason=$reason")
                    handleDisconnect()
                    if (code != 1000) {
                        scheduleReconnect(externalScope)
                    }
                }
            },
        )
    }

    private fun sendConfigMessage(ws: WebSocket) {
        val config = JSONObject().apply {
            put(
                "models",
                JSONObject().apply {
                    put("face", JSONObject())
                    put(
                        "prosody",
                        JSONObject().apply {
                            put("identify_speakers", false)
                        },
                    )
                },
            )
            put("stream_window_ms", 500)
        }
        Log.d(TAG, "Sending Hume config")
        ws.send(config.toString())
    }

    private fun scheduleReconnect(externalScope: CoroutineScope) {
        if (!shouldReconnect) {
            return
        }
        reconnectJob?.cancel()
        reconnectJob = engineScope?.launch {
            delay(RECONNECT_DELAY_MS)
            if (shouldReconnect) {
                connect(externalScope)
            }
        }
    }

    private fun startAudioSendLoop(ws: WebSocket) {
        audioSendJob?.cancel()
        audioSendJob = engineScope?.launch {
            while (isActive && shouldReconnect) {
                delay(HumeConfig.AUDIO_CHUNK_INTERVAL_MS)
                val chunk = consumeAudioChunk()
                if (chunk.isEmpty()) {
                    continue
                }
                sendProsodyPayload(ws, chunk)
                lastAudioSentAt = System.currentTimeMillis()
                Log.d(TAG, "Sent Hume audio chunk bytes=${chunk.size}")
            }
        }
    }

    private fun startFaceSendLoop(ws: WebSocket) {
        faceSendJob?.cancel()
        faceSendJob = engineScope?.launch {
            while (isActive && shouldReconnect) {
                delay(HumeConfig.FACE_CAPTURE_INTERVAL_MS)
                val jpeg = latestFaceJpeg ?: continue
                sendFacePayload(ws, jpeg)
                Log.d(TAG, "Sent Hume face frame bytes=${jpeg.size}")
            }
        }
    }

    private fun startKeepAliveLoop(ws: WebSocket) {
        keepAliveJob?.cancel()
        keepAliveJob = engineScope?.launch {
            while (isActive && shouldReconnect) {
                delay(HumeConfig.KEEPALIVE_INTERVAL_MS)
                val now = System.currentTimeMillis()
                if (now - lastAudioSentAt < HumeConfig.KEEPALIVE_INTERVAL_MS) {
                    continue
                }
                sendProsodyPayload(ws, generateSilence())
                lastAudioSentAt = now
                Log.d(TAG, "Sent Hume keepalive silence")
            }
        }
    }

    private fun sendProsodyPayload(ws: WebSocket, pcmBytes: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(pcmBytes)
        val message = JSONObject().apply {
            put("data", encoded)
            put(
                "models",
                JSONObject().apply {
                    put("prosody", JSONObject())
                },
            )
            put("raw_text", false)
        }
        ws.send(message.toString())
    }

    private fun sendFacePayload(ws: WebSocket, jpegBytes: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(jpegBytes)
        val message = JSONObject().apply {
            put("data", encoded)
            put("models", JSONObject().apply { put("face", JSONObject()) })
            put("raw_text", false)
        }
        ws.send(message.toString())
    }

    private fun handleMessage(text: String, scope: CoroutineScope) {
        try {
            val json = JSONObject(text)

            json.optString("warning").takeIf { it.isNotBlank() }?.let { warning ->
                Log.w(TAG, "Hume warning: $warning")
            }
            json.optString("error").takeIf { it.isNotBlank() }?.let { error ->
                Log.w(TAG, "Hume error: $error")
            }
            json.optJSONObject("face")?.let { faceObject ->
                extractPrediction(faceObject)?.let { prediction ->
                    lastFaceScores = parseEmotions(prediction)
                    lastFaceScoresAt = System.currentTimeMillis()
                }
            }

            json.optJSONObject("prosody")?.let { prosodyObject ->
                extractPrediction(prosodyObject)?.let { prediction ->
                    lastProsodyScores = parseEmotions(prediction)
                }
            }
            json.optJSONObject("burst")?.let { burstObject ->
                extractPrediction(burstObject)?.let { prediction ->
                    lastBurstScores = parseEmotions(prediction)
                }
            }

            if (
                lastFaceScores != null ||
                lastProsodyScores != null ||
                lastBurstScores != null ||
                lastTextSignal != null
            ) {
                recalculateEmotion(scope)
            }
        } catch (_: Exception) {
        }
    }

    private fun recalculateEmotion(scope: CoroutineScope? = null) {
        if (
            lastFaceScores != null &&
            System.currentTimeMillis() - lastFaceScoresAt > HumeConfig.FACE_RESULT_STALE_MS
        ) {
            lastFaceScores = null
        }
        val fusedEmotion = EmotionFuser.fuse(
            faceScores = lastFaceScores,
            prosodyScores = lastProsodyScores,
            burstScores = lastBurstScores,
            textSignal = lastTextSignal,
        )
        val effectiveEmotion = when {
            fusedEmotion.label != ToneLabel.NEUTRAL && fusedEmotion.confidence >= 0.12f -> {
                lastExpressiveEmotion = fusedEmotion
                lastExpressiveEmotionAt = System.currentTimeMillis()
                fusedEmotion
            }

            lastExpressiveEmotion.label != ToneLabel.NEUTRAL &&
                System.currentTimeMillis() - lastExpressiveEmotionAt <= HumeConfig.EMOTION_HOLD_MS -> {
                lastExpressiveEmotion.copy(
                    dominantFaceEmotion = fusedEmotion.dominantFaceEmotion ?: lastExpressiveEmotion.dominantFaceEmotion,
                    dominantProsodyEmotion = fusedEmotion.dominantProsodyEmotion ?: lastExpressiveEmotion.dominantProsodyEmotion,
                    dominantBurstEmotion = fusedEmotion.dominantBurstEmotion ?: lastExpressiveEmotion.dominantBurstEmotion,
                    dominantTextEmotion = fusedEmotion.dominantTextEmotion ?: lastExpressiveEmotion.dominantTextEmotion,
                    confidence = maxOf(
                        fusedEmotion.confidence,
                        (lastExpressiveEmotion.confidence * 0.82f).coerceAtLeast(0.28f),
                    ),
                    isLowConfidence = true,
                )
            }

            else -> fusedEmotion
        }
        Log.d(
            TAG,
            "Fused emotion label=${effectiveEmotion.label} confidence=${effectiveEmotion.confidence} face=${effectiveEmotion.dominantFaceEmotion} prosody=${effectiveEmotion.dominantProsodyEmotion} burst=${effectiveEmotion.dominantBurstEmotion} text=${effectiveEmotion.dominantTextEmotion}",
        )
        if (scope != null) {
            scope.launch {
                emotionState.emit(effectiveEmotion)
            }
        } else {
            emotionState.tryEmit(effectiveEmotion)
        }
    }

    private fun extractPrediction(modelJson: JSONObject): JSONObject? {
        val groupedPredictions = modelJson.optJSONArray("grouped_predictions")
        if (groupedPredictions != null) {
            for (groupIndex in 0 until groupedPredictions.length()) {
                val group = groupedPredictions.optJSONObject(groupIndex) ?: continue
                val predictions = group.optJSONArray("predictions") ?: continue
                for (predictionIndex in 0 until predictions.length()) {
                    val prediction = predictions.optJSONObject(predictionIndex) ?: continue
                    if (prediction.optJSONArray("emotions") != null) {
                        return prediction
                    }
                }
            }
        }

        val predictions = modelJson.optJSONArray("predictions")
        if (predictions != null) {
            for (predictionIndex in 0 until predictions.length()) {
                val prediction = predictions.optJSONObject(predictionIndex) ?: continue
                if (prediction.optJSONArray("emotions") != null) {
                    return prediction
                }
            }
        }

        return null
    }

    private fun parseEmotions(predictionJson: JSONObject): EmotionFuser.HumeScores {
        val emotionsArray = predictionJson.optJSONArray("emotions")
            ?: return EmotionFuser.HumeScores(emptyList())
        val emotions = mutableListOf<Pair<String, Float>>()
        for (index in 0 until emotionsArray.length()) {
            val item = emotionsArray.optJSONObject(index) ?: continue
            val name = item.optString("name")
            val score = item.optDouble("score", 0.0).toFloat()
            if (name.isNotBlank()) {
                emotions += name to score
            }
        }
        return EmotionFuser.HumeScores(emotions)
    }

    private fun handleDisconnect() {
        isConnected.tryEmit(false)
        reconnectJob?.cancel()
        cancelSendLoops()
        webSocket = null
    }

    private fun cancelSendLoops() {
        audioSendJob?.cancel()
        faceSendJob?.cancel()
        keepAliveJob?.cancel()
        audioSendJob = null
        faceSendJob = null
        keepAliveJob = null
    }

    private fun consumeAudioChunk(): ByteArray {
        synchronized(audioLock) {
            if (audioBuffer.size() == 0) {
                return ByteArray(0)
            }
            val bytes = audioBuffer.toByteArray()
            audioBuffer.reset()
            return bytes
        }
    }

    private fun clearAudioBuffer() {
        synchronized(audioLock) {
            audioBuffer.reset()
        }
    }

    private fun imageProxyToJpeg(imageProxy: ImageProxy): ByteArray {
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
        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null,
        )
        val rawJpegOutput = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, imageProxy.width, imageProxy.height),
            HumeConfig.FACE_JPEG_QUALITY,
            rawJpegOutput,
        )
        val originalBitmap = BitmapFactory.decodeByteArray(
            rawJpegOutput.toByteArray(),
            0,
            rawJpegOutput.size(),
        ) ?: return rawJpegOutput.toByteArray()

        var workingBitmap: Bitmap = originalBitmap
        val rotatedBitmap = workingBitmap.rotateAndMirror(
            rotationDegrees = imageProxy.imageInfo.rotationDegrees,
            mirrorHorizontally = lensFacing == CameraSelector.LENS_FACING_FRONT,
        )
        if (rotatedBitmap !== workingBitmap) {
            workingBitmap.recycle()
            workingBitmap = rotatedBitmap
        }

        val croppedBitmap = workingBitmap.cropToFace(lastLocalFaceBounds)
        if (croppedBitmap !== workingBitmap) {
            workingBitmap.recycle()
            workingBitmap = croppedBitmap
        }

        val scaledBitmap = workingBitmap.scaleDownForTransport()
        if (scaledBitmap !== workingBitmap) {
            workingBitmap.recycle()
            workingBitmap = scaledBitmap
        }

        val outputStream = ByteArrayOutputStream()
        workingBitmap.compress(Bitmap.CompressFormat.JPEG, HumeConfig.FACE_JPEG_QUALITY, outputStream)
        workingBitmap.recycle()
        return outputStream.toByteArray()
    }

    private fun Bitmap.rotateAndMirror(rotationDegrees: Int, mirrorHorizontally: Boolean): Bitmap {
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

    private fun Bitmap.cropToFace(faceBounds: RectF?): Bitmap {
        val bounds = faceBounds ?: return this
        val widthPx = width.toFloat()
        val heightPx = height.toFloat()
        val paddingX = bounds.width() * HumeConfig.FACE_CROP_PADDING
        val paddingY = bounds.height() * HumeConfig.FACE_CROP_PADDING

        val left = ((bounds.left - paddingX).coerceIn(0f, 1f) * widthPx).toInt()
        val top = ((bounds.top - paddingY).coerceIn(0f, 1f) * heightPx).toInt()
        val right = ((bounds.right + paddingX).coerceIn(0f, 1f) * widthPx).toInt()
        val bottom = ((bounds.bottom + paddingY).coerceIn(0f, 1f) * heightPx).toInt()

        val cropWidth = (right - left).coerceAtLeast(1)
        val cropHeight = (bottom - top).coerceAtLeast(1)
        if (cropWidth >= width && cropHeight >= height) {
            return this
        }

        return Bitmap.createBitmap(
            this,
            left.coerceAtLeast(0),
            top.coerceAtLeast(0),
            cropWidth.coerceAtMost(width - left.coerceAtLeast(0)),
            cropHeight.coerceAtMost(height - top.coerceAtLeast(0)),
        )
    }

    private fun Bitmap.scaleDownForTransport(): Bitmap {
        val longestEdge = maxOf(width, height)
        if (longestEdge <= HumeConfig.FACE_MAX_LONG_EDGE_PX) {
            return this
        }

        val scale = HumeConfig.FACE_MAX_LONG_EDGE_PX / longestEdge.toFloat()
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun generateSilence(): ByteArray {
        return ByteArray(SILENCE_CHUNK_BYTES)
    }

    private companion object {
        const val TAG = "HumeEmotionEngine"
        const val RECONNECT_DELAY_MS = 2_000L
        const val SILENCE_CHUNK_BYTES = 16_000
    }
}
