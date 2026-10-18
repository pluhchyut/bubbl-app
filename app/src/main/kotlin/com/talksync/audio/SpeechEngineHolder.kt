package com.talksync.audio

// keeps the current speech engine swappable without making every flow collector freak out.

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class SpeechEngineHolder(
    private val context: Context,
    initialKind: SpeechBackendKind = SpeechBackendKind.DEEPGRAM,
) : SpeechBackend {

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

    private var currentKind: SpeechBackendKind = initialKind
    private var faceDownActive: Boolean = false
    private var extendedLanguagesActive: Boolean = false
    private var current: SpeechBackend = buildBackend(initialKind)

    private var forwardJob: Job? = null
    private var externalScope: CoroutineScope? = null

    @Volatile
    private var running: Boolean = false
    @Volatile
    private var audioStreamingEnabled: Boolean = true

    val activeKind: SpeechBackendKind get() = currentKind

    override fun start(externalScope: CoroutineScope) {
        this.externalScope = externalScope
        running = true
        current.setAudioStreamingEnabled(audioStreamingEnabled)
        forward(externalScope, current)
        current.start(externalScope)
    }

    override fun stop() {
        running = false
        current.stop()
        forwardJob?.cancel()
        forwardJob = null
        resetExposedFlows()
    }

    override fun release() {
        running = false
        current.release()
        forwardJob?.cancel()
        forwardJob = null
        resetExposedFlows()
    }

    override fun setAudioStreamingEnabled(enabled: Boolean) {
        audioStreamingEnabled = enabled
        current.setAudioStreamingEnabled(enabled)
    }

    fun setBackend(kind: SpeechBackendKind) {
        if (kind == currentKind) return
        swapBackend(kind)
    }

    /** Force a rebuild of the current backend with updated facedown config. */
    fun setFaceDownMode(active: Boolean) {
        faceDownActive = active
        // Always rebuild so the URL params and capture configs update even if
        // the kind (DEEPGRAM) hasn't changed.
        swapBackend(currentKind)
    }

    /**
     * Toggle the extended Soniox language hint set. Only the Soniox engine
     * consumes the hint list, so we avoid disrupting other backends.
     */
    fun setExtendedLanguages(active: Boolean) {
        if (active == extendedLanguagesActive) return
        extendedLanguagesActive = active
        if (faceDownActive || currentKind == SpeechBackendKind.SONIOX) {
            swapBackend(currentKind)
        }
    }

    private fun swapBackend(kind: SpeechBackendKind) {
        val wasRunning = running
        val savedScope = externalScope
        current.stop()
        current.release()
        forwardJob?.cancel()
        forwardJob = null
        resetExposedFlows()

        current = buildBackend(kind)
        currentKind = kind
        current.setAudioStreamingEnabled(audioStreamingEnabled)

        if (wasRunning && savedScope != null) {
            forward(savedScope, current)
            current.start(savedScope)
        }
    }

    private fun buildBackend(kind: SpeechBackendKind): SpeechBackend =
        if (faceDownActive) {
            // Facedown: Soniox interim/prefinal (+ diarization) over the raw
            // UNPROCESSED far-field mic (final wired in the ViewModel).
            SonioxSpeechEngine(context, faceDown = true, extendedLanguages = extendedLanguagesActive)
        } else when (kind) {
            SpeechBackendKind.DEEPGRAM -> DeepgramSpeechEngine(context)
            SpeechBackendKind.SONIOX -> SonioxSpeechEngine(context, extendedLanguages = extendedLanguagesActive)
            SpeechBackendKind.AZURE -> AzureSpeechEngine(context)
        }

    private fun resetExposedFlows() {
        partialText.value = ""
        finalText.value = ""
        connectionError.value = null
        isConnected.value = false
        isRunning.value = false
        isBluetoothInputActive.value = false
        inputRouteLabel.value = null
    }

    private fun forward(scope: CoroutineScope, backend: SpeechBackend) {
        forwardJob?.cancel()
        forwardJob = scope.launch {
            launch { backend.speechUpdates.collect { speechUpdates.emit(it) } }
            launch { backend.partialText.collect { partialText.value = it } }
            launch { backend.finalText.collect { finalText.value = it } }
            launch { backend.speechStarted.collect { speechStarted.emit(it) } }
            launch { backend.connectionError.collect { connectionError.value = it } }
            launch { backend.isConnected.collect { isConnected.value = it } }
            launch { backend.isRunning.collect { isRunning.value = it } }
            launch { backend.isBluetoothInputActive.collect { isBluetoothInputActive.value = it } }
            launch { backend.inputRouteLabel.collect { inputRouteLabel.value = it } }
            launch { backend.finalizedSpeech.collect { finalizedSpeech.emit(it) } }
            launch { backend.audioPcmChunks.collect { audioPcmChunks.tryEmit(it) } }
            launch { backend.stereoAudioChunks.collect { stereoAudioChunks.tryEmit(it) } }
        }
    }
}
