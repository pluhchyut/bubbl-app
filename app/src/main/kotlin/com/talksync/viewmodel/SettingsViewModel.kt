package com.talksync.viewmodel

// thin ui wrapper around SettingsStore, plus a few compose-friendly derived values.

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.talksync.data.AppSettings
import com.talksync.data.SettingsStore
import com.talksync.translation.LanguageModelManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = SettingsStore(application)

    val settings: StateFlow<AppSettings> = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings(),
    )

    fun updateTextSize(value: Float) {
        viewModelScope.launch { settingsStore.updateTextSize(value) }
    }

    fun updateBubbleOpacity(value: Float) {
        viewModelScope.launch { settingsStore.updateBubbleOpacity(value) }
    }

    fun updateFadeDuration(value: Float) {
        viewModelScope.launch { settingsStore.updateFadeDuration(value) }
    }

    fun updateConversationGapSeconds(value: Float) {
        viewModelScope.launch { settingsStore.updateConversationGapSeconds(value) }
    }

    fun updateVolumeSensitivity(value: Float) {
        viewModelScope.launch { settingsStore.updateVolumeSensitivity(value) }
    }

    fun updateEmphasisVolumeOffset(value: Float) {
        viewModelScope.launch { settingsStore.updateEmphasisVolumeOffset(value) }
    }

    fun updateShowEmotionIndicators(value: Boolean) {
        viewModelScope.launch { settingsStore.updateShowEmotionIndicators(value) }
    }

    fun updateShowInferredText(value: Boolean) {
        viewModelScope.launch { settingsStore.updateShowInferredText(value) }
    }

    fun updateVisualEmphasis(value: Boolean) {
        viewModelScope.launch { settingsStore.updateVisualEmphasis(value) }
    }

    fun updateBackgroundNoiseFiltering(value: Boolean) {
        viewModelScope.launch { settingsStore.updateBackgroundNoiseFiltering(value) }
    }

    fun updateEmphasisColorIntensity(value: Float) {
        viewModelScope.launch { settingsStore.updateEmphasisColorIntensity(value) }
    }

    fun updateAdvancedEmphasisTuningEnabled(value: Boolean) {
        viewModelScope.launch { settingsStore.updateAdvancedEmphasisTuningEnabled(value) }
    }

    fun updateEmphasisToneMode(value: String) {
        viewModelScope.launch { settingsStore.updateEmphasisToneMode(value) }
    }

    fun updateEmphasisToneFadeSeconds(value: Float) {
        viewModelScope.launch { settingsStore.updateEmphasisToneFadeSeconds(value) }
    }

    fun updateEmphasisToneResponse(value: Float) {
        viewModelScope.launch { settingsStore.updateEmphasisToneResponse(value) }
    }

    fun updateEmphasisQuietFloor(value: Float) {
        viewModelScope.launch { settingsStore.updateEmphasisQuietFloor(value) }
    }

    fun updateEmphasisLoudTrigger(value: Float) {
        viewModelScope.launch { settingsStore.updateEmphasisLoudTrigger(value) }
    }

    fun updatePresentationMode(value: String) {
        viewModelScope.launch { settingsStore.updatePresentationMode(value) }
    }

    fun updateBubbleAnchorMode(value: String) {
        viewModelScope.launch { settingsStore.updateBubbleAnchorMode(value) }
    }

    fun updateDebugModeEnabled(value: Boolean) {
        viewModelScope.launch { settingsStore.updateDebugModeEnabled(value) }
    }

    fun updateTranslationEnabled(value: Boolean) {
        viewModelScope.launch { settingsStore.updateTranslationEnabled(value) }
    }

    fun updateTargetLanguage(value: String) {
        viewModelScope.launch { settingsStore.updateTargetLanguage(value) }
    }

    fun updateSpeechBackend(value: String) {
        viewModelScope.launch { settingsStore.updateSpeechBackend(value) }
    }

    fun updateConversationDistanceMode(value: String) {
        viewModelScope.launch { settingsStore.updateConversationDistanceMode(value) }
    }

    fun updateCardboardParallaxPx(value: Int) {
        viewModelScope.launch { settingsStore.updateCardboardParallaxPx(value) }
    }

    fun updateDemoSpeakersEnabled(value: Boolean) {
        viewModelScope.launch { settingsStore.updateDemoSpeakersEnabled(value) }
    }

    fun updateDemoSpeakerNames(value: String) {
        viewModelScope.launch { settingsStore.updateDemoSpeakerNames(value) }
    }

    fun updateDirectionalAudioCuesEnabled(value: Boolean) {
        viewModelScope.launch { settingsStore.updateDirectionalAudioCuesEnabled(value) }
    }

    fun updateExtendedLanguagesEnabled(value: Boolean) {
        viewModelScope.launch { settingsStore.updateExtendedLanguagesEnabled(value) }
    }

    fun refreshLanguageDownloadStates() {
        viewModelScope.launch {
            LanguageModelManager.refreshDownloadStates()
        }
    }

    fun downloadLanguage(lang: LanguageModelManager.LanguageInfo) {
        viewModelScope.launch {
            LanguageModelManager.downloadLanguage(lang)
        }
    }

    fun deleteLanguage(lang: LanguageModelManager.LanguageInfo) {
        viewModelScope.launch {
            LanguageModelManager.deleteLanguage(lang)
        }
    }

    fun downloadAllLanguages() {
        viewModelScope.launch {
            LanguageModelManager.downloadAll()
        }
    }
}
