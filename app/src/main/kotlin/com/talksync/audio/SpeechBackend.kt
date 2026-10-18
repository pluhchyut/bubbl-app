package com.talksync.audio

// the speech engine deal: deepgram and azure both have to pretend to be this shape.

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class FinalizedSpeech(
    val text: String,
    val speakerKey: String? = null,
    val speakerOrdinal: Int? = null,
    val detectedLanguage: String? = null,
    val isIntermediateFinal: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)

data class SpeechSegment(
    val text: String,
    val speakerOrdinal: Int? = null,
    val detectedLanguage: String? = null,
) {
    val speakerKey: String? get() = speakerOrdinal?.let { "speaker-$it" }
}

/** One ordered stream keeps a final and the next speaker's partial from racing. */
sealed interface SpeechUpdate {
    data class Partial(val segments: List<SpeechSegment>) : SpeechUpdate
    data class Final(val result: FinalizedSpeech) : SpeechUpdate
}

interface SpeechBackend {
    val speechUpdates: SharedFlow<SpeechUpdate>
    val partialText: StateFlow<String>
    val finalText: StateFlow<String>
    val speechStarted: SharedFlow<Unit>
    val connectionError: StateFlow<String?>
    val isConnected: StateFlow<Boolean>
    val isRunning: StateFlow<Boolean>
    val isBluetoothInputActive: StateFlow<Boolean>
    val inputRouteLabel: StateFlow<String?>
    val finalizedSpeech: SharedFlow<FinalizedSpeech>
    val audioPcmChunks: SharedFlow<ByteArray>
    val stereoAudioChunks: SharedFlow<ByteArray>

    fun start(externalScope: CoroutineScope)
    fun stop()
    fun release()
    fun setAudioStreamingEnabled(enabled: Boolean)
}

enum class SpeechBackendKind(val value: String) {
    DEEPGRAM("deepgram"),
    SONIOX("soniox"),
    AZURE("azure"),
    ;

    companion object {
        fun fromValue(value: String?): SpeechBackendKind = values()
            .firstOrNull { it.value == value }
            ?: DEEPGRAM
    }
}
