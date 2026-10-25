package com.talksync.data

// datastore settings. these defaults are basically the fresh-install vibe.

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.talksync.audio.SpeechBackendKind
import com.talksync.model.BubbleAnchorMode
import com.talksync.model.ConversationDistanceMode
import com.talksync.model.EmphasisToneMode
import com.talksync.translation.TranslationTargets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "talksync_settings")

data class AppSettings(
    val textSizeSp: Float = 18f,
    val bubbleOpacity: Float = 0.8f,
    val fadeDurationSeconds: Float = 5f,
    val conversationGapSeconds: Float = 60f,
    val volumeSensitivity: Float = 1f,
    val emphasisVolumeOffset: Float = 0f,
    val showEmotionIndicators: Boolean = false,
    val showInferredText: Boolean = false,
    val visualEmphasisEnabled: Boolean = true,
    val backgroundNoiseFiltering: Boolean = false,
    val emphasisColorIntensity: Float = 1f,
    val advancedEmphasisTuningEnabled: Boolean = false,
    val emphasisToneMode: String = EmphasisToneMode.FADE_TO_WHITE.value,
    val emphasisToneFadeSeconds: Float = 1.1f,
    val emphasisToneResponse: Float = 1f,
    val emphasisQuietFloor: Float = 0.20f,
    val emphasisLoudTrigger: Float = 0.65f,
    val presentationMode: String = "Handheld",
    val bubbleAnchorMode: String = BubbleAnchorMode.CLASSIC.value,
    val debugModeEnabled: Boolean = false,
    val translationEnabled: Boolean = true,
    /** Code of the language captions are translated into ("Translate into" in Settings). */
    val targetLanguage: String = TranslationTargets.DEFAULT_CODE,
    val speechBackend: String = SpeechBackendKind.DEEPGRAM.value,
    /**
     * Controls how the bubble overlay scales for the speaker's distance.
     *
     *  - "focus" — single nearby speaker; bigger text, tighter to mouth.
     *  - "group" — multiple speakers further away; smaller text, wider lanes.
     */
    val conversationDistanceMode: String = ConversationDistanceMode.FOCUS.value,
    /**
     * Total horizontal disparity (px) applied between the two Cardboard eye
     * panels. Left eye shifts right by half this value, right eye shifts left
     * by the same amount, making captions appear to float in front of the
     * camera passthrough plane. 0 = no parallax (flat, at infinity).
     * Range 0–80 px; 55 px is tuned for the Pixel 10a's 422 ppi display
     * (the old 40 px default was calibrated for lower-density screens).
     */
    val cardboardParallaxPx: Int = 55,
    val useUltraWideCamera: Boolean = false,
    val demoSpeakersEnabled: Boolean = false,
    val demoSpeakerNames: String = "Alice,Bob,Carlos",
    val directionalAudioCuesEnabled: Boolean = true,
    /**
     * When true, the Soniox backend biases recognition across the full
     * 60-language set instead of the focused default 12. Off by default so the
     * languages we actually test/demo stay the most accurate.
     */
    val extendedLanguagesEnabled: Boolean = false,
)

class SettingsStore(context: Context) {
    private val dataStore = context.dataStore

    val settingsFlow: Flow<AppSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            AppSettings(
                textSizeSp = preferences[textSizeKey] ?: 18f,
                bubbleOpacity = preferences[bubbleOpacityKey] ?: 0.8f,
                fadeDurationSeconds = preferences[fadeDurationKey] ?: 5f,
                conversationGapSeconds = preferences[conversationGapKey] ?: 60f,
                volumeSensitivity = preferences[volumeSensitivityKey] ?: 1f,
                emphasisVolumeOffset = preferences[emphasisVolumeOffsetKey] ?: 0f,
                showEmotionIndicators = preferences[showEmotionIndicatorsKey] ?: false,
                showInferredText = preferences[showInferredTextKey] ?: false,
                visualEmphasisEnabled = preferences[visualEmphasisKey] ?: true,
                backgroundNoiseFiltering = preferences[backgroundNoiseFilteringKey] ?: false,
                emphasisColorIntensity = preferences[emphasisColorIntensityKey] ?: 1f,
                advancedEmphasisTuningEnabled = preferences[advancedEmphasisKey] ?: false,
                emphasisToneMode = preferences[emphasisToneModeKey]
                    ?: EmphasisToneMode.FADE_TO_WHITE.value,
                emphasisToneFadeSeconds = preferences[emphasisToneFadeSecondsKey] ?: 1.1f,
                emphasisToneResponse = preferences[emphasisToneResponseKey] ?: 1f,
                emphasisQuietFloor = preferences[emphasisQuietFloorKey] ?: 0.20f,
                emphasisLoudTrigger = preferences[emphasisLoudTriggerKey] ?: 0.65f,
                presentationMode = preferences[presentationModeKey] ?: "Handheld",
                bubbleAnchorMode = preferences[bubbleAnchorModeKey] ?: BubbleAnchorMode.CLASSIC.value,
                debugModeEnabled = preferences[debugModeKey] ?: false,
                translationEnabled = preferences[translationEnabledKey] ?: true,
                targetLanguage = preferences[targetLanguageKey] ?: TranslationTargets.DEFAULT_CODE,
                speechBackend = preferences[speechBackendKey] ?: SpeechBackendKind.DEEPGRAM.value,
                conversationDistanceMode = preferences[conversationDistanceModeKey]
                    ?: ConversationDistanceMode.FOCUS.value,
                cardboardParallaxPx = preferences[cardboardParallaxKey] ?: 55,
                useUltraWideCamera = preferences[useUltraWideCameraKey] ?: false,
                demoSpeakersEnabled = preferences[demoSpeakersEnabledKey] ?: false,
                demoSpeakerNames = preferences[demoSpeakerNamesKey] ?: "Alice,Bob,Carlos",
                directionalAudioCuesEnabled = preferences[directionalAudioCuesKey] ?: true,
                extendedLanguagesEnabled = preferences[extendedLanguagesKey] ?: false,
            )
        }

    suspend fun updateTextSize(value: Float) {
        dataStore.edit { it[textSizeKey] = value }
    }

    suspend fun updateBubbleOpacity(value: Float) {
        dataStore.edit { it[bubbleOpacityKey] = value }
    }

    suspend fun updateFadeDuration(value: Float) {
        dataStore.edit { it[fadeDurationKey] = value }
    }

    suspend fun updateConversationGapSeconds(value: Float) {
        dataStore.edit { it[conversationGapKey] = value }
    }

    suspend fun updateVolumeSensitivity(value: Float) {
        dataStore.edit { it[volumeSensitivityKey] = value }
    }

    suspend fun updateEmphasisVolumeOffset(value: Float) {
        dataStore.edit { it[emphasisVolumeOffsetKey] = value }
    }

    suspend fun updateShowEmotionIndicators(value: Boolean) {
        dataStore.edit { it[showEmotionIndicatorsKey] = value }
    }

    suspend fun updateShowInferredText(value: Boolean) {
        dataStore.edit { it[showInferredTextKey] = value }
    }

    suspend fun updateVisualEmphasis(value: Boolean) {
        dataStore.edit { it[visualEmphasisKey] = value }
    }

    suspend fun updateBackgroundNoiseFiltering(value: Boolean) {
        dataStore.edit { it[backgroundNoiseFilteringKey] = value }
    }

    suspend fun updateEmphasisColorIntensity(value: Float) {
        dataStore.edit { it[emphasisColorIntensityKey] = value }
    }

    suspend fun updateAdvancedEmphasisTuningEnabled(value: Boolean) {
        dataStore.edit { it[advancedEmphasisKey] = value }
    }

    suspend fun updateEmphasisToneMode(value: String) {
        dataStore.edit { it[emphasisToneModeKey] = value }
    }

    suspend fun updateEmphasisToneFadeSeconds(value: Float) {
        dataStore.edit { it[emphasisToneFadeSecondsKey] = value }
    }

    suspend fun updateEmphasisToneResponse(value: Float) {
        dataStore.edit { it[emphasisToneResponseKey] = value }
    }

    suspend fun updateEmphasisQuietFloor(value: Float) {
        dataStore.edit { it[emphasisQuietFloorKey] = value }
    }

    suspend fun updateEmphasisLoudTrigger(value: Float) {
        dataStore.edit { it[emphasisLoudTriggerKey] = value }
    }

    suspend fun updatePresentationMode(value: String) {
        dataStore.edit { it[presentationModeKey] = value }
    }

    suspend fun updateBubbleAnchorMode(value: String) {
        dataStore.edit { it[bubbleAnchorModeKey] = value }
    }

    suspend fun updateDebugModeEnabled(value: Boolean) {
        dataStore.edit { it[debugModeKey] = value }
    }

    suspend fun updateTranslationEnabled(value: Boolean) {
        dataStore.edit { it[translationEnabledKey] = value }
    }

    suspend fun updateTargetLanguage(value: String) {
        TranslationTargets.forCode(value) // only codes from the picker are valid
        dataStore.edit { it[targetLanguageKey] = value }
    }

    suspend fun updateSpeechBackend(value: String) {
        dataStore.edit { it[speechBackendKey] = value }
    }

    suspend fun updateConversationDistanceMode(value: String) {
        dataStore.edit { it[conversationDistanceModeKey] = value }
    }

    suspend fun updateCardboardParallaxPx(value: Int) {
        dataStore.edit { it[cardboardParallaxKey] = value }
    }

    suspend fun updateUseUltraWideCamera(value: Boolean) {
        dataStore.edit { it[useUltraWideCameraKey] = value }
    }

    suspend fun updateDemoSpeakersEnabled(value: Boolean) {
        dataStore.edit { it[demoSpeakersEnabledKey] = value }
    }

    suspend fun updateDemoSpeakerNames(value: String) {
        dataStore.edit { it[demoSpeakerNamesKey] = value }
    }

    suspend fun updateDirectionalAudioCuesEnabled(value: Boolean) {
        dataStore.edit { it[directionalAudioCuesKey] = value }
    }

    suspend fun updateExtendedLanguagesEnabled(value: Boolean) {
        dataStore.edit { it[extendedLanguagesKey] = value }
    }

    private companion object {
        val textSizeKey = floatPreferencesKey("text_size_sp")
        val bubbleOpacityKey = floatPreferencesKey("bubble_opacity")
        val fadeDurationKey = floatPreferencesKey("fade_duration_seconds")
        val conversationGapKey = floatPreferencesKey("conversation_gap_seconds")
        val volumeSensitivityKey = floatPreferencesKey("volume_sensitivity")
        val emphasisVolumeOffsetKey = floatPreferencesKey("emphasis_volume_offset")
        val showEmotionIndicatorsKey = booleanPreferencesKey("show_emotion_indicators")
        val showInferredTextKey = booleanPreferencesKey("show_inferred_text")
        val visualEmphasisKey = booleanPreferencesKey("visual_emphasis_enabled")
        val backgroundNoiseFilteringKey = booleanPreferencesKey("background_noise_filtering")
        val emphasisColorIntensityKey = floatPreferencesKey("emphasis_color_intensity")
        val advancedEmphasisKey = booleanPreferencesKey("advanced_emphasis_tuning_enabled")
        val emphasisToneModeKey = stringPreferencesKey("emphasis_tone_mode")
        val emphasisToneFadeSecondsKey = floatPreferencesKey("emphasis_tone_fade_seconds")
        val emphasisToneResponseKey = floatPreferencesKey("emphasis_tone_response")
        val emphasisQuietFloorKey = floatPreferencesKey("emphasis_quiet_floor")
        val emphasisLoudTriggerKey = floatPreferencesKey("emphasis_loud_trigger")
        val presentationModeKey = stringPreferencesKey("presentation_mode")
        val bubbleAnchorModeKey = stringPreferencesKey("bubble_anchor_mode")
        val debugModeKey = booleanPreferencesKey("debug_mode_enabled")
        val translationEnabledKey = booleanPreferencesKey("translation_enabled")
        val targetLanguageKey = stringPreferencesKey("target_language")
        val speechBackendKey = stringPreferencesKey("speech_backend")
        val conversationDistanceModeKey = stringPreferencesKey("conversation_distance_mode")
        val cardboardParallaxKey = intPreferencesKey("cardboard_parallax_px")
        val useUltraWideCameraKey = booleanPreferencesKey("use_ultra_wide_camera")
        val demoSpeakersEnabledKey = booleanPreferencesKey("demo_speakers_enabled")
        val demoSpeakerNamesKey = stringPreferencesKey("demo_speaker_names")
        val directionalAudioCuesKey = booleanPreferencesKey("directional_audio_cues_enabled")
        val extendedLanguagesKey = booleanPreferencesKey("extended_languages_enabled")
    }
}
