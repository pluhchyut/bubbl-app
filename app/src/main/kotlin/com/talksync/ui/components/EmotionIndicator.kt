package com.talksync.ui.components

// small tone badge. takes EmotionState and makes it glanceable.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.talksync.emotion.EmotionState
import com.talksync.emotion.ToneLabel
import com.talksync.ui.theme.HairlineWhite
import com.talksync.ui.theme.IndicatorBlack
import com.talksync.ui.theme.Radii

@Composable
fun EmotionIndicator(
    emotionState: EmotionState,
    enabled: Boolean,
    showNeutralDebug: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val showNeutralState = showNeutralDebug && emotionState.label == ToneLabel.NEUTRAL
    val visible = enabled && (
        showNeutralState || (emotionState.label != ToneLabel.NEUTRAL && emotionState.confidence > 0f)
    ) || showNeutralState
    val indicatorAlpha = when {
        showNeutralState -> 0.38f
        emotionState.isLowConfidence -> 0.45f
        else -> 1f
    }
    val accentColor = emotionState.label.accentColor()
    val indicatorText = when {
        showNeutralDebug -> buildString {
            append(
                if (showNeutralState) {
                    "neutral"
                } else {
                    emotionState.label.shortLabel()
                },
            )
            emotionState.dominantFaceEmotion?.let {
                append(" | f:")
                append(it.lowercase())
            }
            emotionState.dominantProsodyEmotion?.let {
                append(" | v:")
                append(it.lowercase())
            }
            emotionState.dominantBurstEmotion?.let {
                append(" | b:")
                append(it.lowercase())
            }
            emotionState.dominantTextEmotion?.let {
                append(" | t:")
                append(it.lowercase())
            }
        }

        else -> emotionState.label.shortLabel()
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(Radii.pill),
            color = IndicatorBlack,
            modifier = Modifier.alpha(indicatorAlpha),
            border = BorderStroke(1.dp, HairlineWhite),
        ) {
            if (showNeutralState) {
                Text(
                    text = indicatorText,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(
                                color = accentColor.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(Radii.pill),
                            ),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Text(
                            text = emotionState.label.badgeText(),
                            color = accentColor,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = indicatorText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Box(
                            modifier = Modifier
                                .width(34.dp)
                                .height(3.dp)
                                .background(
                                    color = Color.White.copy(alpha = 0.18f),
                                    shape = RoundedCornerShape(Radii.pill),
                                ),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width((34.dp * emotionState.confidence.coerceIn(0.18f, 1f)))
                                    .height(3.dp)
                                    .background(
                                        color = accentColor,
                                        shape = RoundedCornerShape(Radii.pill),
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun ToneLabel.shortLabel(): String = when (this) {
    ToneLabel.JOYFUL -> "joyful"
    ToneLabel.WARM -> "warm"
    ToneLabel.FRUSTRATED -> "frustrated"
    ToneLabel.SAD -> "sad"
    ToneLabel.FEARFUL -> "nervous"
    ToneLabel.SURPRISED -> "surprised"
    ToneLabel.CONFUSED -> "confused"
    ToneLabel.NEUTRAL -> ""
}

private fun ToneLabel.badgeText(): String = when (this) {
    ToneLabel.JOYFUL -> "J"
    ToneLabel.WARM -> "W"
    ToneLabel.FRUSTRATED -> "F"
    ToneLabel.SAD -> "S"
    ToneLabel.FEARFUL -> "N"
    ToneLabel.SURPRISED -> "U"
    ToneLabel.CONFUSED -> "C"
    ToneLabel.NEUTRAL -> ""
}

private fun ToneLabel.accentColor(): Color = when (this) {
    ToneLabel.JOYFUL -> Color(0xFF6EEB83)
    ToneLabel.WARM -> Color(0xFFFFD166)
    ToneLabel.FRUSTRATED -> Color(0xFFFF6B6B)
    ToneLabel.SAD -> Color(0xFF8DA9C4)
    ToneLabel.FEARFUL -> Color(0xFFFF9F6E)
    ToneLabel.SURPRISED -> Color(0xFF7FDBFF)
    ToneLabel.CONFUSED -> Color(0xFFD3D3D3)
    ToneLabel.NEUTRAL -> Color.White
}
