package com.talksync.audio

// azure speech backend. sdk callbacks go in, normal app flows come out.

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecord
import android.util.Log
import com.microsoft.cognitiveservices.speech.AutoDetectSourceLanguageConfig
import com.microsoft.cognitiveservices.speech.CancellationReason
import com.microsoft.cognitiveservices.speech.PropertyId
import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechConfig
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import com.microsoft.cognitiveservices.speech.audio.AudioStreamFormat
import com.microsoft.cognitiveservices.speech.audio.PushAudioInputStream
import com.microsoft.cognitiveservices.speech.transcription.ConversationTranscriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AzureSpeechEngine(private val context: Context) : SpeechBackend {

    companion object {
        private const val TAG = "AzureSpeechEngine"
        private const val RECONNECT_DELAY_MS = 3_000L
        // Proactively restart before Azure's ~10-min session hard-limit.
        private const val SESSION_WATCHDOG_MS = 8 * 60 * 1_000L
        // 4-language limit for AutoDetectSourceLanguageConfig with ConversationTranscriber
        private val AUTO_LANGUAGES = listOf(
            "en-US",
            "es-ES",
            "fr-FR",
            "de-DE",
        )
    }

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

    private var speechConfig: SpeechConfig? = null
    private var audioConfig: AudioConfig? = null
    private var autoDetectConfig: AutoDetectSourceLanguageConfig? = null
    private var pushStream: PushAudioInputStream? = null
    private var transcriber: ConversationTranscriber? = null
    private var audioRecord: AudioRecord? = null
    private var engineScope: CoroutineScope? = null
    private var callbackScope: CoroutineScope? = null
    private var audioJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile
    private var active = false
    @Volatile
    private var audioStreamingEnabled = true
    @Volatile
    private var sessionStartEmitted = false

    private val audioManager = context.getSystemService(AudioManager::class.java)
    // speakerOrdinals is accessed from Azure's native callback thread AND from
    // stop()/clear() on other threads — must be guarded.
    private val speakerOrdinals = linkedMapOf<String, Int>()
    private val speakerOrdinalsLock = Any()

    override fun start(externalScope: CoroutineScope) {
        if (active) return
        if (!AzureSpeechConfig.isConfigured) {
            connectionError.value = "Add azureSpeechKey and azureSpeechRegion to TalkSync/secrets.properties"
            isConnected.value = false
            return
        }
        active = true
        audioStreamingEnabled = true
        isRunning.value = true
        sessionStartEmitted = false
        callbackScope = externalScope
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        connectionError.value = null
        engineScope?.launch { connect() }
    }

    override fun stop() {
        if (!active && transcriber == null) {
            isRunning.value = false
            return
        }
        active = false
        audioStreamingEnabled = false
        isRunning.value = false
        watchdogJob?.cancel()
        watchdogJob = null
        audioJob?.cancel()
        stopAudioRecord()
        teardownTranscriber()
        engineScope?.cancel()
        engineScope = null
        callbackScope = null
        isConnected.value = false
        clearInputRoute()
        connectionError.value = null
        partialText.value = ""
        finalText.value = ""
        synchronized(speakerOrdinalsLock) { speakerOrdinals.clear() }
        sessionStartEmitted = false
    }

    override fun release() {
        stop()
    }

    override fun setAudioStreamingEnabled(enabled: Boolean) {
        audioStreamingEnabled = enabled
    }

    private fun connect() {
        try {
            val cfg = SpeechConfig.fromSubscription(
                AzureSpeechConfig.API_KEY,
                AzureSpeechConfig.REGION,
            )
            // TrueText post-processing adds inline punctuation, capitalization
            // and smart formatting (numbers, currencies, dates) to the
            // recognized text without changing the conversation recognition mode
            // that ConversationTranscriber relies on for diarization.
            cfg.setProperty(
                PropertyId.SpeechServiceResponse_PostProcessingOption,
                "TrueText",
            )
            cfg.setProperty(PropertyId.SpeechServiceResponse_DiarizeIntermediateResults, "true")
            speechConfig = cfg

            val autoDetect = AutoDetectSourceLanguageConfig.fromLanguages(AUTO_LANGUAGES)
            autoDetectConfig = autoDetect

            val stream = PushAudioInputStream.create(
                AudioStreamFormat.getWaveFormatPCM(MIC_SAMPLE_RATE.toLong(), 16, 1),
            )
            pushStream = stream
            val audio = AudioConfig.fromStreamInput(stream)
            audioConfig = audio

            val t = ConversationTranscriber(cfg, autoDetect, audio)
            transcriber = t

            t.transcribing.addEventListener { _, e ->
                val text = e.result.text?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    emitSessionStartIfNeeded()
                    emitPartial(text, runCatching { e.result.speakerId }.getOrNull())
                }
            }
            t.transcribed.addEventListener { _, e ->
                val result = e.result
                if (result.reason != ResultReason.RecognizedSpeech) return@addEventListener
                val text = result.text?.trim().orEmpty()
                if (text.isEmpty()) return@addEventListener
                val speakerId = runCatching { result.speakerId }.getOrNull()
                val language = runCatching {
                    result.properties.getProperty(
                        PropertyId.SpeechServiceConnection_AutoDetectSourceLanguageResult,
                    )
                }.getOrNull()?.takeIf { it.isNotBlank() }
                emitFinal(text, speakerId, language)
            }
            t.sessionStarted.addEventListener { _, _ ->
                Log.d(TAG, "Azure session started")
            }
            t.sessionStopped.addEventListener { _, _ ->
                Log.d(TAG, "Azure session stopped")
            }
            t.canceled.addEventListener { _, e ->
                val detail = e.errorDetails?.takeIf { it.isNotBlank() }
                val message = when (e.reason) {
                    CancellationReason.Error -> detail ?: "Azure recognition error (${e.errorCode})"
                    CancellationReason.EndOfStream -> null
                    else -> detail ?: "Azure canceled (${e.reason})"
                }
                Log.w(TAG, "Azure canceled: reason=${e.reason} code=${e.errorCode} detail=$detail")
                if (message != null) {
                    connectionError.value = message
                    isConnected.value = false
                }
                // Reconnect if session dropped unexpectedly (not from explicit stop()).
                if (active && e.reason != CancellationReason.EndOfStream) {
                    scheduleReconnect()
                }
            }

            t.startTranscribingAsync().get()
            startSessionWatchdog()
            startAudioStream()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start Azure transcriber", t)
            connectionError.value = t.message ?: "Azure init failed"
            isConnected.value = false
            teardownTranscriber()
        }
    }

    private fun scheduleReconnect() {
        engineScope?.launch {
            Log.d(TAG, "Azure: scheduling reconnect in ${RECONNECT_DELAY_MS}ms")
            stopAudioRecord()
            teardownTranscriber()
            delay(RECONNECT_DELAY_MS)
            if (active) {
                Log.d(TAG, "Azure: reconnecting…")
                connect()
            }
        }
    }

    private fun startSessionWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = engineScope?.launch {
            delay(SESSION_WATCHDOG_MS)
            if (active) {
                Log.d(TAG, "Azure: session watchdog triggered — proactive reconnect")
                scheduleReconnect()
            }
        }
    }

    private fun emitSessionStartIfNeeded() {
        if (sessionStartEmitted) return
        sessionStartEmitted = true
        val scope = callbackScope ?: return
        scope.launch { speechStarted.emit(Unit) }
    }

    private fun emitPartial(text: String, speakerId: String?) {
        val scope = callbackScope ?: return
        val ordinal = resolveSpeakerOrdinal(speakerId)
        scope.launch {
            partialText.emit(text)
            speechUpdates.emit(SpeechUpdate.Partial(listOf(SpeechSegment(text, ordinal))))
        }
    }

    private fun emitFinal(text: String, speakerId: String?, language: String?) {
        val scope = callbackScope ?: return
        val ordinal = resolveSpeakerOrdinal(speakerId)
        val normalizedLanguage = language
            ?.lowercase()
            ?.substringBefore('-')
            ?.takeIf { it.isNotBlank() }
        scope.launch {
            finalText.emit(text)
            partialText.emit("")
            val result = FinalizedSpeech(
                text = text,
                speakerKey = ordinal?.let { "speaker-$it" },
                speakerOrdinal = ordinal,
                detectedLanguage = normalizedLanguage,
            )
            finalizedSpeech.emit(result)
            speechUpdates.emit(SpeechUpdate.Final(result))
        }
    }

    private fun resolveSpeakerOrdinal(speakerId: String?): Int? {
        val id = speakerId
            ?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
            ?: return null
        return synchronized(speakerOrdinalsLock) {
            speakerOrdinals.getOrPut(id) { speakerOrdinals.size }
        }
    }

    private fun startAudioStream() {
        audioJob?.cancel()
        audioJob = engineScope?.launch {
            val prepareResult = prepareAudioRecord(audioManager)
            val preparedRecord = prepareResult.prepared
            if (preparedRecord == null) {
                val message = prepareResult.errorSummary ?: "AudioRecord failed to initialize"
                connectionError.value = message
                isConnected.value = false
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
                while (isActive && active) {
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
                                    stereoAudioChunks.tryEmit(rawChunk)
                                    downmixStereoToMono(rawChunk)
                                }

                                else -> {
                                    stereoAudioChunks.tryEmit(expandMonoToStereo(rawChunk))
                                    rawChunk
                                }
                            }

                            audioPcmChunks.tryEmit(monoChunk)
                            // Keep the audio clock moving while gated so endpointing can finish a turn.
                            val speechChunk = if (audioStreamingEnabled) monoChunk else ByteArray(monoChunk.size)
                            runCatching { pushStream?.write(speechChunk) }
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

    private fun teardownTranscriber() {
        val transcriberRef = transcriber
        val streamRef = pushStream
        val audioCfg = audioConfig
        val autoCfg = autoDetectConfig
        val speechCfg = speechConfig
        transcriber = null
        pushStream = null
        audioConfig = null
        autoDetectConfig = null
        speechConfig = null

        // Azure close + stop can block for hundreds of ms. Do it off the caller thread
        // with a non-cancellable context so engineScope.cancel() doesn't abort teardown.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            withContext(NonCancellable) {
                runCatching { streamRef?.close() }
                runCatching { transcriberRef?.stopTranscribingAsync()?.get() }
                runCatching { transcriberRef?.close() }
                runCatching { audioCfg?.close() }
                runCatching { autoCfg?.close() }
                runCatching { speechCfg?.close() }
            }
        }
    }
}
