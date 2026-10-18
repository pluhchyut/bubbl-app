package com.talksync.audio

// deepgram websocket speech engine. hidden behind SpeechBackend so we can swap providers.

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecord
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DeepgramSpeechEngine(
    private val context: Context,
    private val faceDown: Boolean = false,
) : SpeechBackend {

    companion object {
        private const val TAG = "DeepgramSpeechEngine"
        private const val RECONNECT_DELAY_MS = 2_000L
        // Nova-3 — best-in-class multilingual streaming model.
        // diarize=true tags each word with a speaker ordinal so we can label
        // "Speaker 1/2/3" and fuse the label with the right face.
        private const val DEEPGRAM_URL_BASE =
            "wss://api.deepgram.com/v1/listen" +
                "?model=nova-3" +
                "&interim_results=true" +
                "&language=multi" +
                "&punctuate=true" +
                "&smart_format=true" +
                "&diarize=true" +
                "&vad_events=true" +
                "&encoding=linear16" +
                "&sample_rate=16000" +
                "&channels=1"
        // Standard AR/conversation mode.
        private const val DEEPGRAM_URL =
            "$DEEPGRAM_URL_BASE&endpointing=300&utterance_end_ms=1000"
        // Facedown/tabletop: more lenient endpointing for table-distance voices and
        // natural pauses mid-sentence.
        private const val DEEPGRAM_URL_FACEDOWN =
            "$DEEPGRAM_URL_BASE&endpointing=600&utterance_end_ms=2000"
    }

    private val activeUrl get() = if (faceDown) DEEPGRAM_URL_FACEDOWN else DEEPGRAM_URL
    private val captureConfigs get() = if (faceDown) facedownCaptureConfigs else defaultCaptureConfigs

    private val transcriptParser = DeepgramTranscriptParser()

    private data class ConnectionFailure(
        val message: String,
        val retryable: Boolean,
    )

    private val speechUpdateMutex = Mutex()

    override val speechUpdates = MutableSharedFlow<SpeechUpdate>(extraBufferCapacity = 64)
    override val partialText = MutableStateFlow("")
    override val finalText = MutableStateFlow("")
    override val speechStarted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val connectionError = MutableStateFlow<String?>(null)
    override val isConnected = MutableStateFlow(false)
    override val isRunning = MutableStateFlow(false)
    override val isBluetoothInputActive = MutableStateFlow(false)
    override val inputRouteLabel = MutableStateFlow<String?>(null)
    override val finalizedSpeech = MutableSharedFlow<FinalizedSpeech>(extraBufferCapacity = 8)
    override val audioPcmChunks = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val stereoAudioChunks = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var engineScope: CoroutineScope? = null
    private var callbackScope: CoroutineScope? = null
    private var audioJob: Job? = null
    private var reconnectJob: Job? = null
    private var shouldReconnect = false

    @Volatile
    private var audioStreamingEnabled = true
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    override fun start(externalScope: CoroutineScope) {
        if (shouldReconnect) {
            return
        }
        shouldReconnect = true
        audioStreamingEnabled = true
        isRunning.value = true
        callbackScope = externalScope
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        connectionError.value = null
        connect(externalScope)
    }

    override fun stop() {
        shouldReconnect = false
        audioStreamingEnabled = false
        isRunning.value = false
        reconnectJob?.cancel()
        audioJob?.cancel()
        stopAudioRecord()
        sendCloseStream()
        webSocket?.close(1000, "User stopped")
        webSocket = null
        engineScope?.cancel()
        engineScope = null
        callbackScope = null
        isConnected.value = false
        clearInputRoute()
        connectionError.value = null
        partialText.value = ""
        finalText.value = ""
        transcriptParser.reset()
    }

    override fun release() {
        stop()
    }

    override fun setAudioStreamingEnabled(enabled: Boolean) {
        audioStreamingEnabled = enabled
    }

    private fun connect(externalScope: CoroutineScope) {
        if (
            DeepgramConfig.API_KEY.isBlank() ||
            DeepgramConfig.API_KEY == DeepgramConfig.PLACEHOLDER_API_KEY
        ) {
            // Failing loudly here saves a lot of head-scratching later when the UI just sits in reconnecting mode.
            connectionError.value = "Add deepgramApiKey to TalkSync/secrets.properties"
            isConnected.value = false
            return
        }

        transcriptParser.reset()
        val request = Request.Builder()
            .url(activeUrl)
            .header("Authorization", "Token ${DeepgramConfig.API_KEY}")
            .build()

        webSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "Deepgram WebSocket connected")
                    connectionError.value = null
                    reconnectJob?.cancel()
                    startAudioStream()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (webSocket !== this@DeepgramSpeechEngine.webSocket || !shouldReconnect) return
                    handleMessage(text, externalScope)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "Deepgram WebSocket failure", t)
                    val dgError = response?.header("dg-error")
                    val dgRequestId = response?.header("dg-request-id")
                    Log.e(
                        TAG,
                        "Response code: ${response?.code}, message: ${response?.message}, " +
                            "dg-error=$dgError, dg-request-id=$dgRequestId",
                    )
                    isConnected.value = false
                    val failure = mapSocketFailure(t, response, dgError)
                    connectionError.value = failure.message
                    stopAudioRecord()
                    if (shouldReconnect && failure.retryable) {
                        scheduleReconnect(externalScope)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "Deepgram WebSocket closed: $code / $reason")
                    isConnected.value = false
                    stopAudioRecord()
                    if (shouldReconnect && isRetryableCloseCode(code)) {
                        scheduleReconnect(externalScope)
                    }
                }
            },
        )
    }

    private fun scheduleReconnect(externalScope: CoroutineScope) {
        reconnectJob?.cancel()
        reconnectJob = engineScope?.launch {
            delay(RECONNECT_DELAY_MS)
            if (shouldReconnect) {
                connect(externalScope)
            }
        }
    }

    private fun handleMessage(text: String, scope: CoroutineScope) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "Results" -> handleResults(json, scope)
                "SpeechStarted" -> handleSpeechStarted(json, scope)
                "UtteranceEnd" -> handleUtteranceEnd(scope)
                "Metadata" -> handleMetadata(json)
                "Error" -> handleError(json)
                else -> Unit
            }
        } catch (_: Exception) {
            // bad payload, skip it
        }
    }

    private fun handleResults(json: JSONObject, scope: CoroutineScope) {
        val updates = transcriptParser.parse(json)
        scope.launch { emitUpdates(updates) }
    }

    private suspend fun emitUpdates(updates: List<SpeechUpdate>) = speechUpdateMutex.withLock {
        for (update in updates) {
            when (update) {
                is SpeechUpdate.Partial -> {
                    partialText.emit(update.segments.joinToString(" ") { it.text })
                    speechUpdates.emit(update)
                }
                is SpeechUpdate.Final -> {
                    finalText.emit(update.result.text)
                    partialText.emit("")
                    finalizedSpeech.emit(update.result)
                    speechUpdates.emit(update)
                }
            }
        }
    }

    private fun handleSpeechStarted(json: JSONObject, scope: CoroutineScope) {
        val timestamp = json.optDouble("timestamp", -1.0)
        if (timestamp >= 0.0) {
            Log.d(TAG, "SpeechStarted at $timestamp")
        }
        scope.launch {
            speechStarted.emit(Unit)
        }
    }

    private fun handleUtteranceEnd(scope: CoroutineScope) {
        val updates = transcriptParser.parse(JSONObject().put("type", "UtteranceEnd"))
        scope.launch { emitUpdates(updates) }
    }

    private fun handleMetadata(json: JSONObject) {
        val requestId = json.optString("request_id")
        if (requestId.isNotBlank()) {
            Log.d(TAG, "Deepgram request_id=$requestId")
        }
    }

    private fun handleError(json: JSONObject) {
        Log.e(TAG, "Deepgram error payload: $json")
        val description = json.optString("description", "Unknown Deepgram error")
        val variant = json.optString("variant")
        val failure = mapErrorPayload(description, variant)
        connectionError.value = failure.message
        isConnected.value = false
        stopAudioRecord()
        webSocket?.cancel()
        callbackScope?.let { scope ->
            if (shouldReconnect && failure.retryable) {
                scheduleReconnect(scope)
            }
        }
    }

    private fun startAudioStream() {
        audioJob?.cancel()
        audioJob = engineScope?.launch {
            val prepareResult = prepareAudioRecord(audioManager, captureConfigs)
            val preparedRecord = prepareResult.prepared
            if (preparedRecord == null) {
                isConnected.value = false
                val message = prepareResult.errorSummary ?: "AudioRecord failed to initialize"
                connectionError.value = message
                closeSocket(reason = message, reconnectable = false)
                return@launch
            }

            val record = preparedRecord.record
            val captureConfig = preparedRecord.captureConfig
            audioRecord = record
            val bytesPerFrame = preparedRecord.actualChannelCount * 2
            val chunkSize = (MIC_SAMPLE_RATE * bytesPerFrame * 20 / 1000)
                .coerceAtLeast(bytesPerFrame)
            val buffer = ByteArray(chunkSize)

            try {
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "AudioRecord failed to start (${captureConfig.label})"
                }
                Log.d(
                    TAG,
                    "Audio mode: ${if (preparedRecord.isStereo) "STEREO" else "MONO fallback"} (${captureConfig.label})",
                )
                publishInputRoute(record, captureConfig.label)
                isConnected.value = true
                connectionError.value = null
                while (isActive && shouldReconnect) {
                    val bytesRead = record.read(buffer, 0, buffer.size)
                    when {
                        bytesRead > 0 -> {
                            val alignedBytes = bytesRead - (bytesRead % bytesPerFrame)
                            if (alignedBytes <= 0) {
                                continue
                            }

                            val rawChunk = buffer.copyOf(alignedBytes)
                            val monoChunk = when {
                                preparedRecord.isStereo -> {
                                    val stereoChunk = rawChunk
                                    stereoAudioChunks.tryEmit(stereoChunk)
                                    downmixStereoToMono(stereoChunk)
                                }

                                else -> {
                                    // Feed pan analysis a centered stereo stream so unsupported
                                    // stereo devices don't leave stale left/right bias behind.
                                    stereoAudioChunks.tryEmit(expandMonoToStereo(rawChunk))
                                    rawChunk
                                }
                            }

                            audioPcmChunks.tryEmit(monoChunk)
                            // Preserve silence for endpointing when background-noise filtering gates the mic.
                            val speechChunk = if (audioStreamingEnabled) monoChunk else ByteArray(monoChunk.size)
                            webSocket?.send(speechChunk.toByteString())
                        }

                        bytesRead == 0 -> Unit
                        else -> error("AudioRecord read failed with code $bytesRead")
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    throw t
                }
                Log.e(TAG, "Audio streaming failed", t)
                connectionError.value = t.message ?: "Audio streaming failure"
                isConnected.value = false
                closeSocket(
                    reason = connectionError.value ?: "Audio streaming failure",
                    reconnectable = shouldReconnect,
                )
            } finally {
                try {
                    record.stop()
                } catch (_: Throwable) {
                }
                record.release()
                clearInputRoute()
                if (audioRecord === record) {
                    audioRecord = null
                }
            }
        }
    }

    private fun publishInputRoute(record: AudioRecord, captureLabel: String) {
        val route = resolveInputRoute(record, captureLabel)
        inputRouteLabel.value = route.label
        isBluetoothInputActive.value = route.bluetoothActive
        Log.d(TAG, "Active input route: ${route.label}")
    }

    private fun clearInputRoute() {
        inputRouteLabel.value = null
        isBluetoothInputActive.value = false
    }

    private fun mapSocketFailure(
        t: Throwable,
        response: Response?,
        dgError: String? = null,
    ): ConnectionFailure {
        val responseCode = response?.code
        val rawMessage = t.message.orEmpty()
        val dgDetail = dgError?.takeIf { it.isNotBlank() }
        val message = when {
            responseCode == 401 -> dgDetail ?: "Invalid Deepgram API key"
            responseCode == 403 -> dgDetail ?: "Deepgram API key unauthorized"
            responseCode == 429 -> "Deepgram rate limit reached"
            responseCode in 500..599 ->
                "Deepgram service error (${responseCode ?: "unknown"})"
            responseCode != null && responseCode !in 200..299 ->
                dgDetail ?: "Deepgram rejected the request ($responseCode)"
            rawMessage.contains("timeout", ignoreCase = true) -> "Connection timeout"
            rawMessage.contains("connection reset", ignoreCase = true) ||
                rawMessage.contains("broken pipe", ignoreCase = true) ||
                rawMessage.contains("socket closed", ignoreCase = true) ||
                rawMessage.contains("connection closed", ignoreCase = true) ->
                "Connection lost — reconnecting…"
            rawMessage.contains("unreachable", ignoreCase = true) ||
                rawMessage.contains("unresolved", ignoreCase = true) ||
                rawMessage.contains("unknownhost", ignoreCase = true) ||
                rawMessage.contains("no address", ignoreCase = true) ->
                "No internet connection"
            rawMessage.contains("trust", ignoreCase = true) ||
                rawMessage.contains("ssl", ignoreCase = true) ||
                rawMessage.contains("tls", ignoreCase = true) ||
                rawMessage.contains("handshake", ignoreCase = true) ->
                "Secure connection failed (TLS)"
            rawMessage.contains("network", ignoreCase = true) -> "Network error"
            else -> rawMessage.ifBlank { "WebSocket connection failed" }
        }
        val retryable = when {
            responseCode == null -> true
            responseCode in 500..599 -> true
            responseCode == 408 || responseCode == 429 -> true
            responseCode in 400..499 -> false
            else -> true
        }
        return ConnectionFailure(
            message = message,
            retryable = retryable,
        )
    }

    private fun mapErrorPayload(description: String, variant: String): ConnectionFailure {
        val message = if (variant.isBlank()) description else "$description ($variant)"
        val retryable = !message.contains("api key", ignoreCase = true) &&
            !message.contains("unauthorized", ignoreCase = true) &&
            !message.contains("forbidden", ignoreCase = true) &&
            !message.contains("authentication", ignoreCase = true)
        return ConnectionFailure(
            message = message,
            retryable = retryable,
        )
    }

    private fun isRetryableCloseCode(code: Int): Boolean {
        return when (code) {
            1000, 1002, 1003, 1008 -> false
            else -> true
        }
    }

    private fun stopAudioRecord() {
        audioJob?.cancel()
        audioJob = null
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }
        try {
            audioRecord?.release()
        } catch (_: Throwable) {
        }
        audioRecord = null
        clearInputRoute()
    }

    private fun closeSocket(
        reason: String,
        reconnectable: Boolean,
    ) {
        val closeCode = if (reconnectable) 1011 else 1000
        sendCloseStream()
        try {
            webSocket?.close(closeCode, reason.take(120))
        } catch (_: Throwable) {
            webSocket?.cancel()
        }
    }

    private fun sendCloseStream() {
        try {
            webSocket?.send(JSONObject().put("type", "CloseStream").toString())
        } catch (_: Exception) {
        }
    }

}
