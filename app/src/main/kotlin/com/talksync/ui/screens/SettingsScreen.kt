package com.talksync.ui.screens

// settings for speech, translation, captions, demo knobs, and other chaos.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.talksync.audio.SpeechBackendKind
import com.talksync.audio.toWordVolumes
import com.talksync.data.AppSettings
import com.talksync.model.BubbleAnchorMode
import com.talksync.model.ConversationDistanceMode
import com.talksync.model.EmphasisToneMode
import com.talksync.model.SpeechBubbleData
import com.talksync.translation.LanguageModelManager
import com.talksync.ui.components.SpeechBubble
import com.talksync.ui.theme.BrandAccent
import com.talksync.ui.theme.HairlineWhite
import com.talksync.ui.theme.Radii
import com.talksync.ui.theme.Spacing
import com.talksync.ui.theme.StatusAlertCoral
import com.talksync.ui.theme.StatusLiveGreen
import com.talksync.viewmodel.SettingsViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val downloadableLanguages = remember {
        LanguageModelManager.allLanguages.filter { it.code != "en" }
    }
    var demoUnlockTaps by remember { mutableIntStateOf(0) }
    val demoUnlocked = demoUnlockTaps >= 5 || settings.demoSpeakersEnabled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl, vertical = Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(
                modifier = Modifier.clickable { demoUnlockTaps++ },
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                Text(
                    text = "Settings",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Tune captions, translation, and demo behavior.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        SettingsSection(title = "Display") {
            SliderRow(
                label = "Text size",
                valueLabel = "${settings.textSizeSp.toInt()}sp",
                value = settings.textSizeSp,
                valueRange = 12f..28f,
                onValueChange = settingsViewModel::updateTextSize,
            )
            Text(
                text = "Sample text",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = settings.textSizeSp.sp,
            )

            Spacer(modifier = Modifier.height(16.dp))

            SliderRow(
                label = "Bubble opacity",
                valueLabel = "${(settings.bubbleOpacity * 100).toInt()}%",
                value = settings.bubbleOpacity,
                valueRange = 0.4f..1f,
                onValueChange = settingsViewModel::updateBubbleOpacity,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                SpeechBubble(
                    bubbleData = SpeechBubbleData(
                        text = "Sample bubble",
                        isVisible = true,
                        wordVolumes = "Sample bubble".toWordVolumes(
                            volumes = listOf(0.48f, 0.74f),
                            isFinal = true,
                        ),
                    ),
                    baseTextSizeSp = settings.textSizeSp,
                    bubbleOpacity = settings.bubbleOpacity,
                    emphasisEnabled = settings.visualEmphasisEnabled,
                    emphasisColorIntensity = settings.emphasisColorIntensity,
                    emphasisToneMode = EmphasisToneMode.fromValue(settings.emphasisToneMode),
                    emphasisToneFadeSeconds = settings.emphasisToneFadeSeconds,
                    emphasisToneResponse = settings.emphasisToneResponse,
                    emphasisQuietFloor = settings.emphasisQuietFloor,
                    emphasisLoudTrigger = settings.emphasisLoudTrigger,
                    showOriginalLanguage = false,
                    animateToneRelax = false,
                    maxBubbleWidth = 260.dp,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            SliderRow(
                label = "Emphasis color intensity",
                valueLabel = "${(settings.emphasisColorIntensity * 100).toInt()}%",
                value = settings.emphasisColorIntensity,
                valueRange = 0f..1f,
                onValueChange = settingsViewModel::updateEmphasisColorIntensity,
            )

            SliderRow(
                label = "Fade duration",
                valueLabel = "${settings.fadeDurationSeconds.toInt()}s",
                value = settings.fadeDurationSeconds,
                valueRange = 1f..8f,
                onValueChange = settingsViewModel::updateFadeDuration,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(title = "History") {
            SliderRow(
                label = "Conversation gap",
                valueLabel = formatConversationGapLabel(settings.conversationGapSeconds),
                value = settings.conversationGapSeconds,
                valueRange = 15f..300f,
                onValueChange = settingsViewModel::updateConversationGapSeconds,
            )
            Text(
                text = "Captions spoken within this gap stay in one conversation. Longer pauses start a new conversation log.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(title = "Features") {
            ToggleRow(
                label = "Show emotion indicators",
                checked = settings.showEmotionIndicators,
                onCheckedChange = settingsViewModel::updateShowEmotionIndicators,
            )
            ToggleRow(
                label = "Show inferred or expanded text",
                checked = settings.showInferredText,
                onCheckedChange = settingsViewModel::updateShowInferredText,
            )
            ToggleRow(
                label = "Apply visual emphasis",
                checked = settings.visualEmphasisEnabled,
                onCheckedChange = settingsViewModel::updateVisualEmphasis,
            )
            ToggleRow(
                label = "Background noise filtering",
                checked = settings.backgroundNoiseFiltering,
                onCheckedChange = settingsViewModel::updateBackgroundNoiseFiltering,
            )
            ToggleRow(
                label = "Directional audio cues",
                checked = settings.directionalAudioCuesEnabled,
                onCheckedChange = settingsViewModel::updateDirectionalAudioCuesEnabled,
            )
            ToggleRow(
                label = "Show debug overlays",
                checked = settings.debugModeEnabled,
                onCheckedChange = settingsViewModel::updateDebugModeEnabled,
            )
            ToggleRow(
                label = "Advanced emphasis tuning (experimental)",
                checked = settings.advancedEmphasisTuningEnabled,
                onCheckedChange = settingsViewModel::updateAdvancedEmphasisTuningEnabled,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(title = "Audio") {
            SliderRow(
                label = "Loudness sensitivity",
                valueLabel = String.format("%.1fx", settings.volumeSensitivity),
                value = settings.volumeSensitivity,
                valueRange = 0.5f..2f,
                onValueChange = settingsViewModel::updateVolumeSensitivity,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(title = "Accessibility") {
            SliderRow(
                label = "Fixed emphasis offset",
                valueLabel = formatSignedPercent(settings.emphasisVolumeOffset),
                value = settings.emphasisVolumeOffset,
                valueRange = -0.30f..0.30f,
                onValueChange = settingsViewModel::updateEmphasisVolumeOffset,
            )
            Text(
                text = "Shifts all emphasis up or down while keeping the live loudness changes dynamic. Raise it if everything feels too quiet, lower it if everything feels too intense.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }

        if (settings.advancedEmphasisTuningEnabled) {
            Spacer(modifier = Modifier.height(16.dp))

            SettingsSection(title = "Advanced Emphasis") {
                Text(
                    text = "Experimental controls for how tone color behaves and how quickly the emphasis tiers separate from each other.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )

                Text(
                    text = "Tone color behavior",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    EmphasisToneMode.entries.forEach { option ->
                        FilterChip(
                            selected = settings.emphasisToneMode == option.value,
                            onClick = { settingsViewModel.updateEmphasisToneMode(option.value) },
                            label = { Text(option.value) },
                        )
                    }
                }

                SliderRow(
                    label = "Tone fade duration",
                    valueLabel = String.format("%.1fs", settings.emphasisToneFadeSeconds),
                    value = settings.emphasisToneFadeSeconds,
                    valueRange = 0.3f..2.5f,
                    onValueChange = settingsViewModel::updateEmphasisToneFadeSeconds,
                )

                SliderRow(
                    label = "Tone response",
                    valueLabel = String.format("%.2fx", settings.emphasisToneResponse),
                    value = settings.emphasisToneResponse,
                    valueRange = 0.7f..1.6f,
                    onValueChange = settingsViewModel::updateEmphasisToneResponse,
                )

                SliderRow(
                    label = "Quiet floor",
                    valueLabel = String.format("%.2f", settings.emphasisQuietFloor),
                    value = settings.emphasisQuietFloor,
                    valueRange = 0.10f..0.32f,
                    onValueChange = settingsViewModel::updateEmphasisQuietFloor,
                )

                SliderRow(
                    label = "Loud trigger",
                    valueLabel = String.format("%.2f", settings.emphasisLoudTrigger),
                    value = settings.emphasisLoudTrigger,
                    valueRange = 0.45f..0.85f,
                    onValueChange = settingsViewModel::updateEmphasisLoudTrigger,
                )

                Text(
                    text = "Fade to white keeps the word shape but relaxes the tone back to white. Maximum tone keeps the strongest color locked in.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(title = "Speech engine") {
            Text(
                text = "Choose which backend handles live transcription, diarization, and language detection.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                SpeechBackendKind.entries.forEach { option ->
                    val label = when (option) {
                        SpeechBackendKind.DEEPGRAM -> "Deepgram"
                        SpeechBackendKind.SONIOX -> "Soniox"
                        SpeechBackendKind.AZURE -> "Azure Speech"
                    }
                    FilterChip(
                        selected = settings.speechBackend == option.value,
                        onClick = { settingsViewModel.updateSpeechBackend(option.value) },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                text = "Deepgram: fastest, multi-lingual, single-key setup. Soniox: real-time multilingual with per-token diarization; needs sonioxApiKey in secrets.properties. Azure Speech: built-in diarization and language ID; needs azureSpeechKey. Face-down mode uses Soniox.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ToggleRow(
                label = "Extended languages (Soniox)",
                checked = settings.extendedLanguagesEnabled,
                onCheckedChange = settingsViewModel::updateExtendedLanguagesEnabled,
            )
            Text(
                text = "Off: biased toward the 12 languages we test (incl. Spanish & Chinese) for best accuracy. On: biases across all 60 Soniox languages — enable if a speaker uses something outside the default set. Applies to Soniox only.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(title = "Language & Translation") {
            ToggleRow(
                label = "Auto translate to English",
                checked = settings.translationEnabled,
                onCheckedChange = settingsViewModel::updateTranslationEnabled,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "When someone speaks a different language, their words appear in English in the bubble. Tap the globe button during a conversation to see the original.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Language model download section
            val downloadStates by LanguageModelManager.downloadStates
                .collectAsState()

            val downloadedCount = downloadStates.values
                .count { it == LanguageModelManager.DownloadState.DOWNLOADED }
            val totalCount = downloadableLanguages.size
            val isAnyDownloading = downloadStates.values
                .any { it == LanguageModelManager.DownloadState.DOWNLOADING }

            LaunchedEffect(Unit) {
                settingsViewModel.refreshLanguageDownloadStates()
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Offline language models",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = "$downloadedCount of $totalCount downloaded | about 30 MB each",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                if (isAnyDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = BrandAccent,
                    )
                } else {
                    TextButton(
                        onClick = settingsViewModel::downloadAllLanguages,
                        enabled = downloadedCount < totalCount,
                    ) {
                        Text(
                            text = if (downloadedCount == totalCount) "All downloaded" else "Download all",
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress bar showing overall download progress
            if (downloadedCount > 0) {
                LinearProgressIndicator(
                    progress = { downloadedCount.toFloat() / totalCount.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = BrandAccent,
                    trackColor = HairlineWhite,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Individual language rows
            // Using Column instead of LazyColumn since this is inside
            // a vertically scrolling Column already
            downloadableLanguages
                .forEach { lang ->
                    val state = downloadStates[lang.code]
                        ?: LanguageModelManager.DownloadState.NOT_DOWNLOADED

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = lang.flag,
                                fontSize = 20.sp,
                            )
                            Column {
                                Text(
                                    text = lang.displayName,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp,
                                )
                                Text(
                                    text = lang.nativeName,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                )
                            }
                        }

                        when (state) {
                            LanguageModelManager.DownloadState.DOWNLOADING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            LanguageModelManager.DownloadState.DOWNLOADED -> {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = "Downloaded",
                                        tint = StatusLiveGreen,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    IconButton(
                                        onClick = {
                                            settingsViewModel.deleteLanguage(lang)
                                        },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Delete,
                                            contentDescription = "Delete ${lang.displayName} model",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                            LanguageModelManager.DownloadState.FAILED -> {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Failed",
                                        tint = StatusAlertCoral,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    TextButton(
                                        onClick = {
                                            settingsViewModel.downloadLanguage(lang)
                                        },
                                    ) {
                                        Text("Retry", fontSize = 12.sp)
                                    }
                                }
                            }
                            LanguageModelManager.DownloadState.NOT_DOWNLOADED -> {
                                IconButton(
                                    onClick = {
                                        settingsViewModel.downloadLanguage(lang)
                                    },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Download,
                                        contentDescription = "Download ${lang.displayName}",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                    // Divider between languages except last one
                    if (lang != downloadableLanguages.last()) {
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            thickness = 0.5.dp,
                        )
                    }
                }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(title = "Mode") {
            Text(
                text = "Choose how you plan to use bubbl!.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                listOf("Handheld", "Headset").forEach { option ->
                    FilterChip(
                        selected = settings.presentationMode == option,
                        onClick = { settingsViewModel.updatePresentationMode(option) },
                        label = { Text(option) },
                    )
                }
            }

            Text(
                text = "Bubble anchoring",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            Text(
                text = "Classic uses the current screen overlay tracker. AR Experimental adds motion-stabilized anchoring so the caption feels more locked to the detected speaker as the phone moves. Face-down turns the conversation view into a simple text-first transcript screen with no camera feed.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                BubbleAnchorMode.entries.forEach { option ->
                    FilterChip(
                        selected = settings.bubbleAnchorMode == option.value,
                        onClick = { settingsViewModel.updateBubbleAnchorMode(option.value) },
                        label = { Text(option.value) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Speaker distance",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            Text(
                text = "Focus mode optimizes for one nearby speaker — bigger text, " +
                    "tighter to the mouth. Group mode shrinks captions and widens the " +
                    "frame so multiple speakers across a table stay readable.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                ConversationDistanceMode.entries.forEach { option ->
                    FilterChip(
                        selected = settings.conversationDistanceMode == option.value,
                        onClick = {
                            settingsViewModel.updateConversationDistanceMode(option.value)
                        },
                        label = { Text(option.value) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(title = "Cardboard VR") {
            Text(
                text = "Pseudo-depth parallax. A small horizontal shift between the two eye " +
                    "panels makes captions appear to float in front of the camera feed. " +
                    "0 px = flat at infinity. 40 px is a comfortable mid-distance illusion. " +
                    "Go higher if you want captions to feel very close.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            SliderRow(
                label = "Parallax shift",
                valueLabel = if (settings.cardboardParallaxPx == 0) "off" else "${settings.cardboardParallaxPx} px",
                value = settings.cardboardParallaxPx.toFloat(),
                valueRange = 0f..80f,
                onValueChange = { settingsViewModel.updateCardboardParallaxPx(it.toInt()) },
            )
        }

        if (demoUnlocked) {
            DemoSpeakersSection(
                settings = settings,
                onToggleDemoSpeakers = settingsViewModel::updateDemoSpeakersEnabled,
                onUpdateDemoSpeakerNames = settingsViewModel::updateDemoSpeakerNames,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(title = "About") {
            Text(
                text = "bubbl!",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.displayMedium,
            )
            Text(
                text = "Version 1.0.0",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Built for the deaf and hard-of-hearing community.",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "bubbl! keeps captions close to the speaker so the conversation stays in view.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun DemoSpeakersSection(
    settings: AppSettings,
    onToggleDemoSpeakers: (Boolean) -> Unit,
    onUpdateDemoSpeakerNames: (String) -> Unit,
) {
    val currentNames = settings.demoSpeakerNames.split(",").map { it.trim() }
    var name0 by remember(currentNames.getOrElse(0) { "Alice" }) {
        mutableStateOf(currentNames.getOrElse(0) { "Alice" })
    }
    var name1 by remember(currentNames.getOrElse(1) { "Bob" }) {
        mutableStateOf(currentNames.getOrElse(1) { "Bob" })
    }
    var name2 by remember(currentNames.getOrElse(2) { "Carlos" }) {
        mutableStateOf(currentNames.getOrElse(2) { "Carlos" })
    }

    fun commit() {
        onUpdateDemoSpeakerNames("$name0,$name1,$name2")
    }

    SettingsSection(title = "Presenter Mode 🎤") {
        Text(
            text = "Hard-codes 3 speaker names so diarization noise doesn't matter. Deepgram's speaker-0/1/2 map straight to the names below.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 18.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        ToggleRow(
            label = "Lock speaker names",
            checked = settings.demoSpeakersEnabled,
            onCheckedChange = onToggleDemoSpeakers,
        )
        if (settings.demoSpeakersEnabled) {
            Spacer(modifier = Modifier.height(4.dp))
            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = BrandAccent,
                unfocusedBorderColor = HairlineWhite,
                focusedLabelColor = BrandAccent,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                cursorColor = BrandAccent,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = name0,
                onValueChange = { name0 = it; commit() },
                label = { Text("Speaker 1 (speaker-0)") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name1,
                onValueChange = { name1 = it; commit() },
                label = { Text("Speaker 2 (speaker-1)") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name2,
                onValueChange = { name2 = it; commit() },
                label = { Text("Speaker 3 (speaker-2)") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun formatConversationGapLabel(seconds: Float): String {
    val totalSeconds = seconds.toInt()
    val minutes = totalSeconds / 60
    val remainingSeconds = totalSeconds % 60
    return if (minutes > 0) {
        "${minutes}m ${remainingSeconds}s"
    } else {
        "${remainingSeconds}s"
    }
}

private fun formatSignedPercent(value: Float): String {
    val percent = (value * 100f).toInt()
    return if (percent > 0) {
        "+$percent%"
    } else {
        "$percent%"
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.xl),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, HairlineWhite),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(
                text = title,
                color = BrandAccent,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.12.sp,
                ),
            )
            content()
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(Radii.l),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, HairlineWhite),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = label,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = valueLabel,
                    color = BrandAccent,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.height(40.dp),
                colors = SliderDefaults.colors(
                    thumbColor = BrandAccent,
                    activeTrackColor = BrandAccent,
                    inactiveTrackColor = HairlineWhite,
                ),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(Radii.l),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, HairlineWhite),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.m, vertical = Spacing.s),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = BrandAccent,
                    uncheckedBorderColor = HairlineWhite,
                ),
            )
        }
    }
}
