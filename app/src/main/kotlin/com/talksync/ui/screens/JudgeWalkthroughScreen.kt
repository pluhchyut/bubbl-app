package com.talksync.ui.screens

// scripted demo tour so judging/showcase mode does not depend on luck.

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.talksync.ui.theme.BrandAccent
import com.talksync.ui.theme.HairlineWhite
import com.talksync.ui.theme.Radii
import com.talksync.ui.theme.Spacing
import com.talksync.ui.theme.StatusAlertCoral
import com.talksync.audio.toWordVolumes
import com.talksync.data.AppSettings
import com.talksync.emotion.EmotionState
import com.talksync.emotion.ToneLabel
import com.talksync.model.ConversationInputMode
import com.talksync.model.EmphasisToneMode
import com.talksync.model.FunctionalityReport
import com.talksync.model.SpeechBubbleData
import com.talksync.ui.components.AslStatusPill
import com.talksync.ui.components.ConversationModeToggle
import com.talksync.ui.components.EmotionIndicator
import com.talksync.ui.components.FunctionalitySummaryCard
import com.talksync.ui.components.SpeechBubble
import kotlinx.coroutines.delay

private enum class JudgeDemoScene {
    ANCHORING,
    EMOTION,
    TRANSLATION,
    USER_AWARENESS,
    ASL,
    HISTORY,
    DIAGNOSTICS,
}

@Composable
fun JudgeWalkthroughScreen(
    settings: AppSettings,
    functionalityReport: FunctionalityReport,
    onBack: () -> Unit,
    onStartConversation: () -> Unit,
) {
    val scenes = remember { JudgeDemoScene.entries }
    var sceneIndex by rememberSaveable { mutableIntStateOf(0) }
    var autoPlay by rememberSaveable { mutableStateOf(true) }
    var showOriginalLanguage by rememberSaveable { mutableStateOf(false) }
    val currentScene = scenes[sceneIndex]
    val sceneCopy = remember(currentScene) { sceneCopy(currentScene) }
    val autoPlayDurationMs = 5_500
    var progressTarget by remember { mutableFloatStateOf(0f) }
    val autoPlayProgress by animateFloatAsState(
        targetValue = progressTarget,
        animationSpec = tween(
            durationMillis = if (progressTarget == 0f) 0 else autoPlayDurationMs,
            easing = LinearEasing,
        ),
        label = "judgeAutoPlayProgress",
    )

    LaunchedEffect(autoPlay, sceneIndex) {
        if (!autoPlay) {
            progressTarget = 0f
            return@LaunchedEffect
        }
        progressTarget = 0f
        kotlinx.coroutines.delay(30L)
        progressTarget = 1f
        delay(autoPlayDurationMs.toLong())
        sceneIndex = (sceneIndex + 1) % scenes.size
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF060607),
                        Color(0xFF0E0E11),
                        Color(0xFF131318),
                    ),
                ),
            )
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl, vertical = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
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
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                        Text(
                            text = "Walkthrough",
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = "Every feature, in under a minute.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Button(
                    onClick = onStartConversation,
                    shape = RoundedCornerShape(Radii.pill),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onBackground,
                        contentColor = MaterialTheme.colorScheme.background,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = Spacing.l,
                        vertical = Spacing.s,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(Spacing.s))
                    Text(
                        text = "Try live",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }

            FunctionalitySummaryCard(
                report = functionalityReport,
                compact = true,
            )

            Surface(
                shape = RoundedCornerShape(Radii.xl),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, HairlineWhite),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.l),
                ) {
                    LinearProgressIndicator(
                        progress = { if (autoPlay) autoPlayProgress else 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = BrandAccent,
                        trackColor = HairlineWhite,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                        ) {
                            Text(
                                text = "Scene ${sceneIndex + 1} / ${scenes.size}",
                                color = BrandAccent,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.12.sp,
                                ),
                            )
                            Text(
                                text = sceneCopy.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = sceneCopy.body,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }

                        IconButton(
                            onClick = { autoPlay = !autoPlay },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                imageVector = if (autoPlay) {
                                    Icons.Outlined.PauseCircleOutline
                                } else {
                                    Icons.Outlined.PlayCircleOutline
                                },
                                contentDescription = if (autoPlay) "Pause autoplay" else "Resume autoplay",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }

                    if (currentScene == JudgeDemoScene.TRANSLATION) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = showOriginalLanguage,
                                onClick = { showOriginalLanguage = !showOriginalLanguage },
                                label = {
                                    Text(
                                        text = if (showOriginalLanguage) {
                                            "Showing original"
                                        } else {
                                            "Showing English"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Translate,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }

                    Crossfade(
                        targetState = currentScene,
                        animationSpec = tween(durationMillis = 320),
                        label = "judgeSceneCrossfade",
                    ) { scene ->
                        JudgeSceneStage(
                            scene = scene,
                            settings = settings,
                            showOriginalLanguage = showOriginalLanguage,
                            functionalityReport = functionalityReport,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            repeat(scenes.size) { index ->
                                val isActive = sceneIndex == index
                                Box(
                                    modifier = Modifier
                                        .size(
                                            width = if (isActive) 18.dp else 6.dp,
                                            height = 6.dp,
                                        )
                                        .background(
                                            color = if (isActive) BrandAccent else Color.White.copy(alpha = 0.22f),
                                            shape = RoundedCornerShape(Radii.pill),
                                        ),
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            IconButton(
                                onClick = {
                                    autoPlay = false
                                    sceneIndex = (sceneIndex - 1 + scenes.size) % scenes.size
                                },
                                modifier = Modifier
                                    .size(40.dp)
                                    .border(1.dp, HairlineWhite, CircleShape),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = "Previous scene",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(
                                onClick = {
                                    autoPlay = false
                                    sceneIndex = (sceneIndex + 1) % scenes.size
                                },
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(BrandAccent, CircleShape),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                    contentDescription = "Next scene",
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(Radii.l),
                color = Color.Transparent,
                border = BorderStroke(1.dp, HairlineWhite),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        text = "Pitch tip",
                        color = BrandAccent,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.12.sp,
                        ),
                    )
                    Text(
                        text = "Run the walkthrough first to land every feature fast, then tap Try live to prove the real camera + mic path in the room.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun JudgeSceneStage(
    scene: JudgeDemoScene,
    settings: AppSettings,
    showOriginalLanguage: Boolean,
    functionalityReport: FunctionalityReport,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = Color(0xFF090909),
    ) {
        when (scene) {
            JudgeDemoScene.ANCHORING -> AnchoringScene(settings)
            JudgeDemoScene.EMOTION -> EmotionScene(settings)
            JudgeDemoScene.TRANSLATION -> TranslationScene(settings, showOriginalLanguage)
            JudgeDemoScene.USER_AWARENESS -> UserAwarenessScene(settings)
            JudgeDemoScene.ASL -> AslScene(settings)
            JudgeDemoScene.HISTORY -> HistoryScene()
            JudgeDemoScene.DIAGNOSTICS -> DiagnosticsScene(functionalityReport)
        }
    }
}

@Composable
private fun AnchoringScene(settings: AppSettings) {
    val emphasisToneMode = EmphasisToneMode.fromValue(settings.emphasisToneMode)
    val demoTextSizeSp = judgeDemoTextSize(settings)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ) {
            Text(
                text = "2 people speaking",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.Top,
        ) {
            DemoSpeakerLane(
                modifier = Modifier.weight(1f),
                settings = settings,
                emphasisToneMode = emphasisToneMode,
                textSizeSp = demoTextSizeSp,
                silhouetteTint = Color(0x22FFFFFF),
                bubbleData = SpeechBubbleData(
                    id = "demo-anchor-1",
                    speakerKey = "speaker-0",
                    speakerLabel = "Speaker 1",
                    text = "The bubbles stay near whoever is talking.",
                    isVisible = true,
                    wordVolumes = "The bubbles stay near whoever is talking.".toWordVolumes(
                        volumes = listOf(0.32f, 0.48f, 0.61f, 0.51f, 0.58f, 0.63f, 0.55f),
                        isFinal = true,
                    ),
                    timestamp = System.currentTimeMillis(),
                ),
                bubbleAlignment = Alignment.Start,
            )
            DemoSpeakerLane(
                modifier = Modifier.weight(1f),
                settings = settings,
                emphasisToneMode = emphasisToneMode,
                textSizeSp = demoTextSizeSp,
                silhouetteTint = Color(0x22FFD166),
                bubbleData = SpeechBubbleData(
                    id = "demo-anchor-2",
                    speakerKey = "speaker-1",
                    speakerLabel = "Speaker 2",
                    text = "And they stay separated when more than one person joins in.",
                    isVisible = true,
                    wordVolumes = "And they stay separated when more than one person joins in.".toWordVolumes(
                        volumes = listOf(0.28f, 0.34f, 0.42f, 0.58f, 0.63f, 0.71f, 0.66f, 0.54f, 0.49f, 0.52f),
                        isFinal = true,
                    ),
                    timestamp = System.currentTimeMillis() + 1,
                ),
                bubbleAlignment = Alignment.End,
            )
        }

        JudgeNoteCard(
            text = "Each caption stays tied to a speaker instead of collapsing into one shared subtitle strip.",
        )
    }
}

@Composable
private fun EmotionScene(settings: AppSettings) {
    val emphasisToneMode = EmphasisToneMode.fromValue(settings.emphasisToneMode)
    val demoTextSizeSp = judgeDemoTextSize(settings)
    val emotion = EmotionState(
        label = ToneLabel.JOYFUL,
        dominantFaceEmotion = "joy",
        dominantProsodyEmotion = "interest",
        dominantTextEmotion = "appreciation",
        confidence = 0.82f,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(Radii.l),
                color = Color.Transparent,
                border = BorderStroke(1.dp, HairlineWhite),
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.m),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Text(
                        text = "Speaker 1",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    SpeechBubble(
                        bubbleData = SpeechBubbleData(
                            id = "demo-emotion",
                            speakerKey = "speaker-0",
                            speakerLabel = "Speaker 1",
                            text = "This catches tone from facial expression, vocal prosody, and words.",
                            isVisible = true,
                            wordVolumes = "This catches tone from facial expression, vocal prosody, and words.".toWordVolumes(
                                volumes = listOf(0.22f, 0.34f, 0.47f, 0.59f, 0.68f, 0.74f, 0.55f, 0.49f, 0.45f, 0.43f),
                                isFinal = true,
                            ),
                            emotionState = emotion,
                            timestamp = System.currentTimeMillis(),
                        ),
                        baseTextSizeSp = demoTextSizeSp,
                        bubbleOpacity = settings.bubbleOpacity,
                        emphasisEnabled = true,
                        emphasisColorIntensity = settings.emphasisColorIntensity,
                        emphasisToneMode = emphasisToneMode,
                        emphasisToneFadeSeconds = settings.emphasisToneFadeSeconds,
                        emphasisToneResponse = settings.emphasisToneResponse,
                        emphasisQuietFloor = settings.emphasisQuietFloor,
                        emphasisLoudTrigger = settings.emphasisLoudTrigger,
                        maxBubbleWidth = 320.dp,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(Radii.l),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, HairlineWhite),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.l),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Text(
                        text = "Emotion",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    EmotionIndicator(
                        emotionState = emotion,
                        enabled = true,
                    )
                    Text(
                        text = "Joyful",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
        }

        JudgeNoteCard(
            text = "Face, voice, and transcript cues blend into one stable tone signal for the listener.",
        )
    }
}

@Composable
private fun TranslationScene(
    settings: AppSettings,
    showOriginalLanguage: Boolean,
) {
    val emphasisToneMode = EmphasisToneMode.fromValue(settings.emphasisToneMode)
    val demoTextSizeSp = judgeDemoTextSize(settings)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .padding(18.dp),
    ) {
        SpeechBubble(
            bubbleData = SpeechBubbleData(
                id = "demo-translation",
                speakerKey = "speaker-0",
                speakerLabel = "Speaker 1",
                text = "Podemos revisar el prototipo despues del almuerzo?",
                translatedText = "Can we review the prototype after lunch?",
                detectedLanguage = "es",
                isVisible = true,
                wordVolumes = "Podemos revisar el prototipo despues del almuerzo?".toWordVolumes(
                    volumes = listOf(0.26f, 0.34f, 0.42f, 0.51f, 0.58f, 0.64f, 0.56f),
                    isFinal = true,
                ),
                timestamp = System.currentTimeMillis(),
            ),
            baseTextSizeSp = demoTextSizeSp,
            bubbleOpacity = settings.bubbleOpacity,
            emphasisEnabled = true,
            emphasisColorIntensity = settings.emphasisColorIntensity,
            emphasisToneMode = emphasisToneMode,
            emphasisToneFadeSeconds = settings.emphasisToneFadeSeconds,
            emphasisToneResponse = settings.emphasisToneResponse,
            emphasisQuietFloor = settings.emphasisQuietFloor,
            emphasisLoudTrigger = settings.emphasisLoudTrigger,
            showOriginalLanguage = showOriginalLanguage,
            maxBubbleWidth = 340.dp,
            modifier = Modifier.align(Alignment.Center),
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ) {
            Text(
                text = if (showOriginalLanguage) {
                    "Judges can tap back to the original transcript instantly."
                } else {
                    "English shows by default, with the original one tap away."
                },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun UserAwarenessScene(settings: AppSettings) {
    val emphasisToneMode = EmphasisToneMode.fromValue(settings.emphasisToneMode)
    val demoTextSizeSp = judgeDemoTextSize(settings)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        SpeechBubble(
            bubbleData = SpeechBubbleData(
                id = "demo-awareness",
                speakerKey = "speaker-0",
                speakerLabel = "Speaker 1",
                text = "The app also notices when the user starts talking or gets louder than normal.",
                isVisible = true,
                wordVolumes = "The app also notices when the user starts talking or gets louder than normal.".toWordVolumes(
                    volumes = listOf(0.22f, 0.28f, 0.34f, 0.48f, 0.56f, 0.69f, 0.81f, 0.73f, 0.65f, 0.58f, 0.51f, 0.46f),
                    isFinal = true,
                ),
                timestamp = System.currentTimeMillis(),
            ),
            baseTextSizeSp = demoTextSizeSp,
            bubbleOpacity = settings.bubbleOpacity,
            emphasisEnabled = true,
            emphasisColorIntensity = settings.emphasisColorIntensity,
            emphasisToneMode = emphasisToneMode,
            emphasisToneFadeSeconds = settings.emphasisToneFadeSeconds,
            emphasisToneResponse = settings.emphasisToneResponse,
            emphasisQuietFloor = settings.emphasisQuietFloor,
            emphasisLoudTrigger = settings.emphasisLoudTrigger,
            maxBubbleWidth = 360.dp,
        )

        JudgeSignalCard(
            icon = Icons.Outlined.GraphicEq,
            accentColor = StatusAlertCoral,
            title = "Speaking loudly",
            subtitle = "Louder than your usual baseline",
        )
        JudgeSignalCard(
            accentColor = Color(0xFF6EEB83),
            title = "You are speaking",
            subtitle = "Keeps the room oriented when the local user jumps in",
        )
    }
}

@Composable
private fun AslScene(settings: AppSettings) {
    val emphasisToneMode = EmphasisToneMode.fromValue(settings.emphasisToneMode)
    val demoTextSizeSp = judgeDemoTextSize(settings)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConversationModeToggle(
            selectedMode = ConversationInputMode.ASL_TRANSLATION,
            onModeSelected = {},
        )
        AslStatusPill(text = "ASL beta - I love you")
        SpeechBubble(
            bubbleData = SpeechBubbleData(
                id = "demo-asl",
                text = "I love you",
                isVisible = true,
                isPartial = false,
                wordVolumes = "I love you".toWordVolumes(
                    volumes = listOf(0.48f, 0.55f, 0.62f),
                    isFinal = true,
                ),
                timestamp = System.currentTimeMillis(),
            ),
            baseTextSizeSp = demoTextSizeSp,
            bubbleOpacity = settings.bubbleOpacity,
            emphasisEnabled = true,
            emphasisColorIntensity = settings.emphasisColorIntensity,
            emphasisToneMode = emphasisToneMode,
            emphasisToneFadeSeconds = settings.emphasisToneFadeSeconds,
            emphasisToneResponse = settings.emphasisToneResponse,
            emphasisQuietFloor = settings.emphasisQuietFloor,
            emphasisLoudTrigger = settings.emphasisLoudTrigger,
            maxBubbleWidth = 280.dp,
        )
        Text(
            text = "This beta path is bundled on-device, so it can demo even when a live signer is not in front of the camera.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
}

@Composable
private fun HistoryScene() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HistoryGroupCard(
            time = "Today - 3:14 PM",
            title = "Design review conversation",
            lines = listOf(
                "Speaker 1: The translation toggle helped a lot.",
                "Speaker 2: The emotion cue made the question feel less abrupt.",
                "Speaker 1: Let's keep the grouped history.",
            ),
        )
        HistoryGroupCard(
            time = "Today - 4:21 PM",
            title = "Quick follow-up after 1m gap",
            lines = listOf(
                "Speaker 2: Can you send the APK to Downloads?",
                "Speaker 1: Yep, and I'll keep the optimized build too.",
            ),
        )
        Text(
            text = "History groups short transcript bursts into conversations instead of a flat sentence list, so judges can see the product behaving like a real memory aid.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
}

@Composable
private fun DiagnosticsScene(functionalityReport: FunctionalityReport) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FunctionalitySummaryCard(
            report = functionalityReport,
            compact = false,
        )
        Text(
            text = "These checks update quietly in the background while the app is open, so it's easier to catch missing keys, disconnected services, or offline-only fallbacks before the judges ask.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
}

@Composable
private fun HistoryGroupCard(
    time: String,
    title: String,
    lines: List<String>,
) {
    Surface(
        shape = RoundedCornerShape(Radii.l),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, HairlineWhite),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text(
                text = time,
                color = BrandAccent,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.08.sp,
                ),
            )
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            lines.forEach { line ->
                Text(
                    text = line,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun judgeDemoTextSize(settings: AppSettings): Float = settings.textSizeSp.coerceIn(15f, 18f)

@Composable
private fun DemoSpeakerLane(
    modifier: Modifier = Modifier,
    settings: AppSettings,
    emphasisToneMode: EmphasisToneMode,
    textSizeSp: Float,
    silhouetteTint: Color,
    bubbleData: SpeechBubbleData,
    bubbleAlignment: Alignment.Horizontal,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radii.l),
        color = Color.Transparent,
        border = BorderStroke(1.dp, HairlineWhite),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.m),
            horizontalAlignment = bubbleAlignment,
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text(
                text = bubbleData.speakerLabel ?: bubbleData.speakerKey,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            AudienceSilhouette(tint = silhouetteTint)
            SpeechBubble(
                bubbleData = bubbleData,
                baseTextSizeSp = textSizeSp,
                bubbleOpacity = settings.bubbleOpacity,
                emphasisEnabled = true,
                emphasisColorIntensity = settings.emphasisColorIntensity,
                emphasisToneMode = emphasisToneMode,
                emphasisToneFadeSeconds = settings.emphasisToneFadeSeconds,
                emphasisToneResponse = settings.emphasisToneResponse,
                emphasisQuietFloor = settings.emphasisQuietFloor,
                emphasisLoudTrigger = settings.emphasisLoudTrigger,
                maxBubbleWidth = 220.dp,
            )
        }
    }
}

@Composable
private fun JudgeNoteCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.l),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, HairlineWhite),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun JudgeSignalCard(
    title: String,
    subtitle: String,
    accentColor: Color,
    icon: ImageVector? = null,
) {
    Surface(
        shape = RoundedCornerShape(Radii.l),
        color = Color(0xCC1A1A1A),
        border = BorderStroke(1.dp, HairlineWhite),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(accentColor.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(accentColor, CircleShape),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.64f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun AudienceSilhouette(
    modifier: Modifier = Modifier,
    tint: Color = Color(0x22FFFFFF),
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(tint, CircleShape),
        )
        Box(
            modifier = Modifier
                .width(82.dp)
                .height(120.dp)
                .background(tint, RoundedCornerShape(999.dp)),
        )
    }
}

private data class SceneCopy(
    val title: String,
    val body: String,
)

private fun sceneCopy(scene: JudgeDemoScene): SceneCopy = when (scene) {
    JudgeDemoScene.ANCHORING -> SceneCopy(
        title = "Speaker-anchored captions",
        body = "Shows how multiple caption bubbles stay near different people instead of piling into one subtitle strip.",
    )
    JudgeDemoScene.EMOTION -> SceneCopy(
        title = "Emotion + emphasis",
        body = "Highlights the fused emotion layer and dynamic emphasis styling that make tone easier to read.",
    )
    JudgeDemoScene.TRANSLATION -> SceneCopy(
        title = "Translation toggle",
        body = "Foreign-language speech defaults to English, and the judges can flip back to the original transcript instantly.",
    )
    JudgeDemoScene.USER_AWARENESS -> SceneCopy(
        title = "User voice awareness",
        body = "Surfaces when the deaf or hard-of-hearing user is speaking, plus a gentle loudness nudge when they're above baseline.",
    )
    JudgeDemoScene.ASL -> SceneCopy(
        title = "ASL beta mode",
        body = "Shows the dedicated sign-translation path so the judges can see the alternate communication flow even without a live signer.",
    )
    JudgeDemoScene.HISTORY -> SceneCopy(
        title = "Conversation memory",
        body = "Demonstrates grouped history so saved logs feel like conversations, not just disconnected sentences.",
    )
    JudgeDemoScene.DIAGNOSTICS -> SceneCopy(
        title = "Backend health",
        body = "Runs quiet readiness checks so you can catch missing config or disabled features before the live pitch starts.",
    )
}
