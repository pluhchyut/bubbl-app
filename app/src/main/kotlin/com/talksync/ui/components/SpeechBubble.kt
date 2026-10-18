package com.talksync.ui.components

// caption bubble component: original text, translation, emphasis, speaker stuff, all in here.

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.talksync.audio.WordVolume
import com.talksync.audio.toWordVolumes
import com.talksync.model.EmphasisToneMode
import com.talksync.model.SpeechBubbleData
import com.talksync.model.RecentCaptionState
import com.talksync.ui.theme.BubbleBlack
import com.talksync.ui.theme.HairlineWhite
import com.talksync.ui.theme.Radii
import com.talksync.ui.theme.StatusLiveGreen

@Composable
fun SpeechBubble(
    bubbleData: SpeechBubbleData,
    wordVolumes: List<WordVolume> = bubbleData.wordVolumes,
    baseTextSizeSp: Float,
    bubbleOpacity: Float,
    emphasisEnabled: Boolean,
    emphasisColorIntensity: Float,
    emphasisToneMode: EmphasisToneMode,
    emphasisToneFadeSeconds: Float,
    emphasisToneResponse: Float,
    emphasisQuietFloor: Float,
    emphasisLoudTrigger: Float,
    showOriginalLanguage: Boolean = false,
    animateToneRelax: Boolean = true,
    visualHighlightStrength: Float = 0f,
    maxBubbleWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val highlightStrength = visualHighlightStrength.coerceIn(0f, 1f)
    val threadHighlightStrength = if (bubbleData.isThreadHighlighted) 1f else 0f
    val threadAccent = Color(0xFF73C7FF)
    val targetAlpha = if (!bubbleData.isVisible) 0f else 1f
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = if (bubbleData.isVisible) 260 else RecentCaptionState.BUBBLE_FADE_MS.toInt()),
        label = "speechBubbleAlpha",
    )
    val animatedScale by animateFloatAsState(
        targetValue = if (bubbleData.isVisible) 1f else 0.96f,
        animationSpec = spring(
            dampingRatio = 0.92f,
            stiffness = 180f,
        ),
        label = "speechBubbleScale",
    )
    val animatedTranslationY by animateFloatAsState(
        targetValue = if (bubbleData.isVisible) 0f else 10f,
        animationSpec = spring(
            dampingRatio = 0.96f,
            stiffness = 170f,
        ),
        label = "speechBubbleTranslationY",
    )
    val bubbleShape = RoundedCornerShape(26.dp)
    val bubbleShadow by animateDpAsState(
        targetValue = if (bubbleData.isVisible) {
            (18f + highlightStrength * 10f + threadHighlightStrength * 5f).dp
        } else {
            0.dp
        },
        animationSpec = tween(durationMillis = 280),
        label = "speechBubbleShadow",
    )
    val hasTranslation = bubbleData.translatedText?.isNotBlank() == true
    val translationHighlightStrength = if (hasTranslation && !showOriginalLanguage) 1f else 0f
    val translationAccent = Color(0xFF8ADFD2)
    val bubbleBorderColor by animateColorAsState(
        targetValue = if (highlightStrength > 0.02f) {
            StatusLiveGreen.copy(alpha = 0.28f + highlightStrength * 0.54f)
        } else if (threadHighlightStrength > 0f) {
            threadAccent.copy(alpha = 0.46f)
        } else if (translationHighlightStrength > 0f) {
            translationAccent.copy(alpha = 0.34f)
        } else {
            Color.White.copy(alpha = 0.14f)
        },
        animationSpec = tween(durationMillis = 240),
        label = "speechBubbleBorder",
    )
    val renderedWordVolumes = wordVolumes
    val displayedExpandedTokens = if (bubbleData.expandedTokens.isEmpty()) {
        emptyList()
    } else {
        bubbleData.expandedTokens.take(renderedWordVolumes.size)
    }
    val primaryText = if (hasTranslation && !showOriginalLanguage) {
        bubbleData.translatedText.orEmpty()
    } else {
        bubbleData.text
    }
    val bubbleBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF0F1620).copy(alpha = bubbleOpacity.coerceIn(0.45f, 1f)),
            Color(0xFF172330).copy(alpha = bubbleOpacity.coerceIn(0.45f, 1f)),
            if (translationHighlightStrength > 0f) {
                Color(0xFF12302E).copy(alpha = (bubbleOpacity * 0.98f).coerceIn(0.45f, 1f))
            } else {
                Color(0xFF10201A).copy(alpha = (bubbleOpacity * 0.96f).coerceIn(0.45f, 1f))
            },
        ),
    )

    Box(
        modifier = modifier
            .wrapContentWidth(unbounded = false)
            .widthIn(min = 120.dp, max = maxBubbleWidth)
            .alpha(animatedAlpha)
            .shadow(
                elevation = bubbleShadow,
                shape = bubbleShape,
                clip = false,
            )
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
                translationY = animatedTranslationY
            }
            .border(
                width = if (
                    highlightStrength > 0.02f ||
                    threadHighlightStrength > 0f ||
                    translationHighlightStrength > 0f
                ) {
                    2.dp
                } else {
                    1.dp
                },
                color = bubbleBorderColor,
                shape = bubbleShape,
            )
            .background(
                brush = bubbleBrush,
                shape = bubbleShape,
            )
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.94f,
                    stiffness = 170f,
                ),
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Column(
            modifier = Modifier.wrapContentWidth(unbounded = false),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier.wrapContentWidth(unbounded = false),
            ) {
                if (hasTranslation && !showOriginalLanguage) {
                    Text(
                        text = primaryText,
                        color = Color.White,
                        fontSize = baseTextSizeSp.sp,
                    )
                } else if (renderedWordVolumes.isNotEmpty()) {
                    EmphasisText(
                        wordVolumes = renderedWordVolumes,
                        baseSize = baseTextSizeSp,
                        emphasisEnabled = emphasisEnabled,
                        colorEnabled = emphasisColorIntensity > 0f,
                        colorIntensity = emphasisColorIntensity,
                        toneMode = emphasisToneMode,
                        toneFadeDurationSeconds = emphasisToneFadeSeconds,
                        toneResponse = emphasisToneResponse,
                        quietFloor = emphasisQuietFloor,
                        loudTrigger = emphasisLoudTrigger,
                        animateToneRelax = animateToneRelax,
                        expandedTokens = displayedExpandedTokens,
                        modifier = Modifier
                            .wrapContentWidth(unbounded = false),
                    )
                } else if (primaryText.isNotBlank()) {
                    Text(
                        text = primaryText,
                        color = Color.White,
                        fontSize = baseTextSizeSp.sp,
                    )
                }
            }

            if (bubbleData.intentLabels.isNotEmpty()) {
                IntentLabelChips(
                    labels = bubbleData.intentLabels,
                    maxLabels = 2,
                    compact = true,
                )
            }

            // lang label + translated/original alt view
            if (hasTranslation) {
                val labelText = if (showOriginalLanguage) {
                    bubbleData.detectedLanguage
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::languageName)
                        ?: "original"
                } else {
                    "translated"
                }
                Text(
                    text = labelText,
                    color = if (showOriginalLanguage) {
                        Color.White.copy(alpha = 0.45f)
                    } else {
                        translationAccent.copy(alpha = 0.74f)
                    },
                    fontStyle = FontStyle.Italic,
                    style = MaterialTheme.typography.labelSmall,
                )
            } else if (bubbleData.isTranslating) {
                Text(
                    text = "translation processing…",
                    color = Color.White.copy(alpha = 0.40f),
                    fontStyle = FontStyle.Italic,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun languageName(code: String): String = when (code.lowercase().take(2)) {
    "es" -> "🇪🇸 Spanish"
    "fr" -> "🇫🇷 French"
    "de" -> "🇩🇪 German"
    "it" -> "🇮🇹 Italian"
    "pt" -> "🇵🇹 Portuguese"
    "ru" -> "🇷🇺 Russian"
    "zh" -> "🇨🇳 Chinese"
    "ja" -> "🇯🇵 Japanese"
    "ko" -> "🇰🇷 Korean"
    "ar" -> "🇸🇦 Arabic"
    "hi" -> "🇮🇳 Hindi"
    "nl" -> "🇳🇱 Dutch"
    "pl" -> "🇵🇱 Polish"
    "tr" -> "🇹🇷 Turkish"
    "sv" -> "🇸🇪 Swedish"
    "da" -> "🇩🇰 Danish"
    "fi" -> "🇫🇮 Finnish"
    "no" -> "🇳🇴 Norwegian"
    "uk" -> "🇺🇦 Ukrainian"
    "vi" -> "🇻🇳 Vietnamese"
    "th" -> "🇹🇭 Thai"
    "id" -> "🇮🇩 Indonesian"
    "af" -> "🇿🇦 Afrikaans"
    "cs" -> "🇨🇿 Czech"
    "el" -> "🇬🇷 Greek"
    "he" -> "🇮🇱 Hebrew"
    "hu" -> "🇭🇺 Hungarian"
    "ro" -> "🇷🇴 Romanian"
    "sk" -> "🇸🇰 Slovak"
    "bg" -> "🇧🇬 Bulgarian"
    else -> code.uppercase()
}
