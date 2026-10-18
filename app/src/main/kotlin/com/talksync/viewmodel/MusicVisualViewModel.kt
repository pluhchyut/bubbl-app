package com.talksync.viewmodel

// runs the capture -> recognize -> visualize flow for the music screen.

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.talksync.music.AudioVisualEngine
import com.talksync.music.RecognitionResult
import com.talksync.music.RecognizedSong
import com.talksync.music.SongRecognizer
import com.talksync.music.VisualStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI states for the music visualization screen.
 *
 *   IDLE        — engine running on ambient input, no song matched yet.
 *   LISTENING   — recording the recognition clip; engine briefly paused
 *                 to free the mic.
 *   IDENTIFIED  — a song was matched; style frozen to its seed.
 *   NO_MATCH    — recognition completed but no fingerprint hit.
 *   ERROR       — capture or network failure; user can retry.
 */
enum class MusicVisualPhase { IDLE, LISTENING, IDENTIFIED, NO_MATCH, ERROR }

data class MusicVisualUiState(
    val phase: MusicVisualPhase = MusicVisualPhase.IDLE,
    val song: RecognizedSong? = null,
    val errorMessage: String? = null,
    val auddTokenConfigured: Boolean = true,
)

class MusicVisualViewModel(application: Application) : AndroidViewModel(application) {

    val engine = AudioVisualEngine()
    private val recognizer = SongRecognizer()

    private val uiState = MutableStateFlow(
        MusicVisualUiState(
            auddTokenConfigured = com.talksync.music.AudDConfig.hasToken,
        )
    )
    val state: StateFlow<MusicVisualUiState> = uiState.asStateFlow()

    val style: StateFlow<VisualStyle> = engine.style
    val frame = engine.frame

    private var recognitionJob: Job? = null

    /** Start the ambient visualization engine. Call from LaunchedEffect. */
    fun startEngine() {
        engine.start(getApplication<Application>().applicationContext, viewModelScope)
    }

    /** Stop the engine and any running recognition. Call from onDispose. */
    fun stopEngine() {
        recognitionJob?.cancel()
        recognitionJob = null
        engine.stop()
    }

    /**
     * Trigger song recognition.
     *
     * The visual engine briefly stops while we hand the mic to the
     * recognizer (Android only allows one AudioRecord at a time on most
     * devices). When the recognizer returns we restart the engine and
     * either lock to the song's seed or surface the error.
     */
    fun identifySong() {
        if (uiState.value.phase == MusicVisualPhase.LISTENING) return
        val ctx = getApplication<Application>().applicationContext
        recognitionJob?.cancel()
        recognitionJob = viewModelScope.launch {
            uiState.value = uiState.value.copy(
                phase = MusicVisualPhase.LISTENING,
                errorMessage = null,
            )
            engine.stop()
            val outcome = recognizer.recognize(ctx)
            engine.start(ctx, viewModelScope)
            when (outcome) {
                is RecognitionResult.Match -> {
                    engine.setStyle(VisualStyle.fromSeed(outcome.song.stableSeed))
                    uiState.value = MusicVisualUiState(
                        phase = MusicVisualPhase.IDENTIFIED,
                        song = outcome.song,
                        auddTokenConfigured = uiState.value.auddTokenConfigured,
                    )
                }
                is RecognitionResult.NoMatch -> {
                    uiState.value = uiState.value.copy(
                        phase = MusicVisualPhase.NO_MATCH,
                        song = null,
                        errorMessage = null,
                    )
                }
                is RecognitionResult.Failure -> {
                    uiState.value = uiState.value.copy(
                        phase = MusicVisualPhase.ERROR,
                        errorMessage = outcome.reason,
                    )
                }
            }
        }
    }

    /** Drop the recognized song and revert to the ambient/neutral style. */
    fun clearSong() {
        engine.resetStyle()
        uiState.value = uiState.value.copy(
            phase = MusicVisualPhase.IDLE,
            song = null,
            errorMessage = null,
        )
    }

    override fun onCleared() {
        super.onCleared()
        stopEngine()
    }

    /** Helper for previewing a deterministic style without the API. */
    @Suppress("unused", "UNUSED_PARAMETER")
    fun previewWithSeed(seed: Long, ctx: Context) {
        engine.setStyle(VisualStyle.fromSeed(seed))
        uiState.value = MusicVisualUiState(
            phase = MusicVisualPhase.IDENTIFIED,
            song = RecognizedSong("Preview", "Seed:$seed", null, null, null),
            auddTokenConfigured = uiState.value.auddTokenConfigured,
        )
    }
}
