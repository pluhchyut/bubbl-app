package com.talksync.audio

// shared websocket-STT machinery: mic capture, audio pump, reconnect, and the
// SpeechBackend flow surface. providers (Soniox, Speechmatics) supply only the
// protocol-specific bits — request, handshake, message parsing, end-of-stream.
//
// DeepgramSpeechEngine deliberately does NOT extend this yet: it's the proven
// path and we keep its blast radius zero. This base exists so the two new
// engines don't duplicate the fiddly capture/reconnect code between them.

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecord
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import java.util.concurrent.TimeUnit

abstract class StreamingSpeechEngine(
    private val context: Context,
) : SpeechBackend {

    protected data class ConnectionFailure(val message: String, val retryable: Boolean)

    protected companion object {
        const val RECONNECT_DELAY_MS = 2_000L
    }

    /** Logcat tag. */
    protected abstract val tag: String
    /** Human-readable provider name, used in connection-error messages. */
    protected abstract val providerName: String
    /** Mic capture configs. Facedown engines override with UNPROCESSED. */
    // internal (not protected) because AudioCaptureConfig is an internal type.
    internal open val captureConfigs: List<AudioCaptureConfig> = defaultCaptureConfigs

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

    private var webSocketRef: WebSocket? = null
    /** The live socket, for subclasses that need to send mid-stream control msgs. */
    protected val webSocket: WebSocket? get() = webSocketRef

    private var audioRecord: AudioRecord? = null
    private var engineScope: CoroutineScope? = null
    private var callbackScope: CoroutineScope? = null
    private var audioJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile
    private var shouldReconnectFlag = false
    protected val shouldReconnect: Boolean get() = shouldReconnectFlag

    @Volatile
    private var audioStreamingEnabled = true

    /** Count of binary audio frames sent on the current connection (for seq_no). */
    @Volatile
    protected var sentAudioChunks = 0
        private set

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    // ---- provider hooks ------------------------------------------------------

    /** Build the connect request (URL + auth headers). Return null if unconfigured. */
    protected abstract fun buildRequest(): Request?

    /** Message shown when [buildRequest] returns null (missing key, etc.). */
    protected abstract fun missingConfigMessage(): String

    /** Send the provider handshake/config message. Called once the socket opens. */
    protected open fun onSocketOpen(webSocket: WebSocket) {}

    /** Parse one text frame from the provider. */
    protected abstract fun handleMessage(text: String, scope: CoroutineScope)

    /** Optional control payload flushed before the socket closes (e.g. EndOfStream / ""). */
    protected open fun closeStreamPayload(): String? = null

    /** Reset per-connection provider state (buffers, seq numbers, last speaker). */
    protected open fun onResetConnectionState() {}

    /** Whether a close code should trigger reconnect. */
    protected open fun isRetryableCloseCode(code: Int): Boolean = when (code) {
        1000, 1002, 1003, 1008 -> false
        else -> true
    }

    /** Map a socket-level failure to a user message + retry decision. */
    protected open fun mapSocketFailure(t: Throwable, response: Response?): ConnectionFailure {
        val responseCode = response?.code
        val rawMessage = t.message.orEmpty()
        val message = when {
            responseCode == 401 -> "Invalid $providerName API key"
            responseCode == 403 -> "$providerName API key unauthorized"
            responseCode == 429 -> "$providerName rate limit reached"
            responseCode in 500..599 -> "$providerName service error (${responseCode ?: "unknown"})"
            responseCode != null && responseCode !in 200..299 ->
                "$providerName rejected the request ($responseCode)"
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
        return ConnectionFailure(message, retryable)
    }

    // ---- emit helpers for subclasses ----------------------------------------

    protected suspend fun emitPartial(text: String) {
        partialText.emit(text)
        speechUpdates.emit(SpeechUpdate.Partial(listOf(SpeechSegment(text))))
    }

    protected suspend fun emitFinal(
        transcript: String,
        detectedLanguage: String? = null,
        isIntermediateFinal: Boolean = false,
        speakerOrdinal: Int? = null,
    ) {
        if (!isIntermediateFinal) {
            finalText.emit(transcript)
            partialText.emit("")
        }
        val result = FinalizedSpeech(
            text = transcript,
            speakerKey = speakerOrdinal?.let { "speaker-$it" },
            speakerOrdinal = speakerOrdinal,
            detectedLanguage = detectedLanguage,
            isIntermediateFinal = isIntermediateFinal,
            timestamp = System.currentTimeMillis(),
        )
        finalizedSpeech.emit(result)
        speechUpdates.emit(SpeechUpdate.Final(result))
    }

    protected suspend fun emitUpdates(updates: List<SpeechUpdate>) = speechUpdateMutex.withLock {
        for (update in updates) {
            when (update) {
                is SpeechUpdate.Partial -> {
                    partialText.emit(update.segments.joinToString(" ") { it.text })
                    speechUpdates.emit(update)
                }
                is SpeechUpdate.Final -> emitFinal(
                    update.result.text,
                    update.result.detectedLanguage,
                    speakerOrdinal = update.result.speakerOrdinal,
                )
            }
        }
    }

    protected suspend fun emitSpeechStarted() {
        speechStarted.emit(Unit)
    }

    /** Surface a provider-payload error and tear the socket down for a reconnect. */
    protected fun reportPayloadError(message: String) {
        Log.e(tag, "$providerName payload error: $message")
        connectionError.value = message
        isConnected.value = false
        stopAudioRecord()
        webSocketRef?.cancel()
        callbackScope?.let { scope ->
            if (shouldReconnectFlag) scheduleReconnect(scope)
        }
    }

    // ---- lifecycle -----------------------------------------------------------

    override fun start(externalScope: CoroutineScope) {
        if (shouldReconnectFlag) return
        shouldReconnectFlag = true
        audioStreamingEnabled = true
        isRunning.value = true
        callbackScope = externalScope
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        connectionError.value = null
        connect(externalScope)
    }

    override fun stop() {
        shouldReconnectFlag = false
        audioStreamingEnabled = false
        isRunning.value = false
        reconnectJob?.cancel()
        audioJob?.cancel()
        stopAudioRecord()
        sendCloseStream()
        webSocketRef?.close(1000, "User stopped")
        webSocketRef = null
        engineScope?.cancel()
        engineScope = null
        callbackScope = null
        isConnected.value = false
        clearInputRoute()
        connectionError.value = null
        partialText.value = ""
        finalText.value = ""
    }

    override fun release() {
        stop()
    }

    override fun setAudioStreamingEnabled(enabled: Boolean) {
        audioStreamingEnabled = enabled
    }

    private fun connect(externalScope: CoroutineScope) {
        val request = buildRequest()
        if (request == null) {
            connectionError.value = missingConfigMessage()
            isConnected.value = false
            return
        }
        sentAudioChunks = 0
        onResetConnectionState()

        webSocketRef = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(tag, "$providerName WebSocket connected")
                    connectionError.value = null
                    reconnectJob?.cancel()
                    onSocketOpen(webSocket)
                    startAudioStream()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (webSocket !== webSocketRef || !shouldReconnectFlag) return
                    handleMessage(text, externalScope)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(tag, "$providerName WebSocket failure", t)
                    isConnected.value = false
                    val failure = mapSocketFailure(t, response)
                    connectionError.value = failure.message
                    stopAudioRecord()
                    if (shouldReconnectFlag && failure.retryable) {
                        scheduleReconnect(externalScope)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(tag, "$providerName WebSocket closed: $code / $reason")
                    isConnected.value = false
                    stopAudioRecord()
                    if (shouldReconnectFlag && isRetryableCloseCode(code)) {
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
            if (shouldReconnectFlag) {
                connect(externalScope)
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
                    tag,
                    "Audio mode: ${if (preparedRecord.isStereo) "STEREO" else "MONO fallback"} (${captureConfig.label})",
                )
                publishInputRoute(record, captureConfig.label)
                isConnected.value = true
                connectionError.value = null
                while (isActive && shouldReconnectFlag) {
                    val bytesRead = record.read(buffer, 0, buffer.size)
                    when {
                        bytesRead > 0 -> {
                            val alignedBytes = bytesRead - (bytesRead % bytesPerFrame)
                            if (alignedBytes <= 0) continue

                            val rawChunk = buffer.copyOf(alignedBytes)
                            val monoChunk = when {
                                preparedRecord.isStereo -> {
                                    val stereoChunk = rawChunk
                                    stereoAudioChunks.tryEmit(stereoChunk)
                                    downmixStereoToMono(stereoChunk)
                                }

                                else -> {
                                    stereoAudioChunks.tryEmit(expandMonoToStereo(rawChunk))
                                    rawChunk
                                }
                            }

                            audioPcmChunks.tryEmit(monoChunk)
                            // Gating suppresses sound, not the silence needed for endpoint detection.
                            val speechChunk = if (audioStreamingEnabled) monoChunk else ByteArray(monoChunk.size)
                            val sent = webSocketRef?.send(speechChunk.toByteString()) ?: false
                            if (sent) sentAudioChunks++
                        }

                        bytesRead == 0 -> Unit
                        else -> error("AudioRecord read failed with code $bytesRead")
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(tag, "Audio streaming failed", t)
                connectionError.value = t.message ?: "Audio streaming failure"
                isConnected.value = false
                closeSocket(
                    reason = connectionError.value ?: "Audio streaming failure",
                    reconnectable = shouldReconnectFlag,
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
        Log.d(tag, "Active input route: ${route.label}")
    }

    private fun clearInputRoute() {
        inputRouteLabel.value = null
        isBluetoothInputActive.value = false
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

    private fun closeSocket(reason: String, reconnectable: Boolean) {
        val closeCode = if (reconnectable) 1011 else 1000
        sendCloseStream()
        try {
            webSocketRef?.close(closeCode, reason.take(120))
        } catch (_: Throwable) {
            webSocketRef?.cancel()
        }
    }

    private fun sendCloseStream() {
        val payload = closeStreamPayload() ?: return
        try {
            webSocketRef?.send(payload)
        } catch (_: Exception) {
        }
    }
}
