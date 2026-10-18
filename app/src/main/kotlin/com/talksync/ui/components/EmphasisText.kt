package com.talksync.ui.components

// draws caption words with volume/tone emphasis, but still tries to stay readable first.

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.talksync.audio.WordVolume
import com.talksync.model.ExpandedToken
import com.talksync.model.EmphasisToneMode
import com.talksync.ui.theme.ExpansionYellow
import kotlin.math.pow

@Composable
fun EmphasisWord(
    wordVolume: WordVolume,
    baseSize: Float,
    emphasisEnabled: Boolean,
    colorEnabled: Boolean,
    colorIntensity: Float,
    toneMode: EmphasisToneMode,
    toneFadeDurationSeconds: Float,
    toneResponse: Float,
    quietFloor: Float,
    loudTrigger: Float,
    animateToneRelax: Boolean,
    modifier: Modifier = Modifier,
) {
    val tier = if (emphasisEnabled) {
        tier(
            volume = wordVolume.volume,
            toneResponse = toneResponse,
            quietFloor = quietFloor,
            loudTrigger = loudTrigger,
        )
    } else {
        Tier(
            sizeDelta = 0f,
            weight = FontWeight.Normal,
            color = Color.White,
            spacingEm = 0f,
        )
    }
    val initialToneMix = when {
        !emphasisEnabled || !colorEnabled -> 0f
        toneMode == EmphasisToneMode.MAXIMUM_TONE -> 1f
        animateToneRelax && wordVolume.isFinal -> 1f
        else -> 1f
    }
    var toneMixTarget by remember(
        wordVolume.word,
        wordVolume.isFinal,
        toneMode,
        animateToneRelax,
        emphasisEnabled,
        colorEnabled,
    ) {
        mutableFloatStateOf(initialToneMix)
    }

    LaunchedEffect(
        wordVolume.word,
        wordVolume.isFinal,
        toneMode,
        animateToneRelax,
        emphasisEnabled,
        colorEnabled,
    ) {
        toneMixTarget = when {
            !emphasisEnabled || !colorEnabled -> 0f
            toneMode == EmphasisToneMode.MAXIMUM_TONE -> 1f
            animateToneRelax && wordVolume.isFinal -> 0f
            else -> 1f
        }
    }

    val animatedSize by animateFloatAsState(
        targetValue = clamp(
            raw = baseSize + if (emphasisEnabled) tier.sizeDelta else 0f,
            base = baseSize,
        ),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "emphasis_size_${wordVolume.word}",
    )
    val animatedToneMix by animateFloatAsState(
        targetValue = toneMixTarget,
        animationSpec = tween(
            durationMillis = (toneFadeDurationSeconds.coerceIn(0.25f, 2.5f) * 1_000f).toInt(),
            easing = LinearEasing,
        ),
        label = "emphasis_tone_mix_${wordVolume.word}",
    )
    val animatedColor by animateColorAsState(
        targetValue = if (emphasisEnabled && colorEnabled) {
            lerp(
                Color.White,
                tier.color,
                colorIntensity.coerceIn(0f, 1f) * animatedToneMix,
            )
        } else {
            Color.White
        },
        animationSpec = tween(
            durationMillis = 250,
            easing = LinearEasing,
        ),
        label = "emphasis_color_${wordVolume.word}",
    )
    val animatedSpacing by animateFloatAsState(
        targetValue = if (emphasisEnabled) tier.spacingEm else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "emphasis_spacing_${wordVolume.word}",
    )

    // Fade-in for newly-arrived partial words. The composable is keyed on word+index
    // in EmphasisText so this state resets when a new word enters that slot.
    var wordHasEntered by remember { mutableStateOf(!wordVolume.isNew) }
    LaunchedEffect(Unit) { wordHasEntered = true }
    val wordAlpha by animateFloatAsState(
        targetValue = when {
            !wordHasEntered -> 0f
            wordVolume.isFinal -> 1f
            wordVolume.isNew -> 0.62f
            else -> 0.88f
        },
        animationSpec = tween(durationMillis = 160),
        label = "wordAlpha_${wordVolume.word}",
    )

    Text(
        text = wordVolume.word,
        fontSize = animatedSize.sp,
        fontWeight = if (emphasisEnabled) tier.weight else FontWeight.Normal,
        color = animatedColor,
        letterSpacing = animatedSpacing.em,
        modifier = modifier.alpha(wordAlpha).padding(end = 4.dp, bottom = 2.dp),
        softWrap = false,
        maxLines = 1,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmphasisText(
    wordVolumes: List<WordVolume>,
    baseSize: Float,
    emphasisEnabled: Boolean,
    colorEnabled: Boolean,
    toneMode: EmphasisToneMode = EmphasisToneMode.FADE_TO_WHITE,
    toneFadeDurationSeconds: Float = 1.1f,
    toneResponse: Float = 1f,
    quietFloor: Float = 0.20f,
    loudTrigger: Float = 0.65f,
    animateToneRelax: Boolean = true,
    modifier: Modifier = Modifier,
    colorIntensity: Float = 1f,
    expandedTokens: List<ExpandedToken> = emptyList(),
) {
    FlowRow(
        modifier = modifier.animateContentSize(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        ),
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        wordVolumes.forEachIndexed { index, wordVolume ->
            key("${index}_${wordVolume.word}") {
                EmphasisWord(
                    wordVolume = wordVolume,
                    baseSize = baseSize,
                    emphasisEnabled = emphasisEnabled,
                    colorEnabled = colorEnabled,
                    colorIntensity = colorIntensity,
                    toneMode = toneMode,
                    toneFadeDurationSeconds = toneFadeDurationSeconds,
                    toneResponse = toneResponse,
                    quietFloor = quietFloor,
                    loudTrigger = loudTrigger,
                    animateToneRelax = animateToneRelax,
                )

                expandedTokens.getOrNull(index)?.expanded?.let { expansion ->
                    Text(
                        text = "($expansion)",
                        color = ExpansionYellow,
                        fontSize = (baseSize - 1f).coerceAtLeast(10f).sp,
                        modifier = Modifier.padding(end = 6.dp, bottom = 2.dp),
                        softWrap = false,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private data class Tier(
    val sizeDelta: Float,
    val weight: FontWeight,
    val color: Color,
    val spacingEm: Float,
)

private fun tier(
    volume: Float,
    toneResponse: Float,
    quietFloor: Float,
    loudTrigger: Float,
): Tier {
    val shapedVolume = shapeVolume(volume, toneResponse)
    val quietThreshold = quietFloor.coerceIn(0.10f, 0.32f)
    val loudThreshold = loudTrigger.coerceIn(quietThreshold + 0.18f, 0.85f)
    val middleLowThreshold = quietThreshold + ((loudThreshold - quietThreshold) * 0.33f)
    val middleHighThreshold = quietThreshold + ((loudThreshold - quietThreshold) * 0.66f)

    return when {
        shapedVolume > loudThreshold -> Tier(+4f, FontWeight.SemiBold, Color(0xFFFF5C5C), 0.02f)
        shapedVolume > middleHighThreshold -> Tier(+2f, FontWeight.Medium, Color(0xFFFFAA80), 0.01f)
        shapedVolume > middleLowThreshold -> Tier(+1f, FontWeight.Normal, Color(0xFFFFFFFF), 0.00f)
        shapedVolume > quietThreshold -> Tier(-1f, FontWeight.Normal, Color(0xFFCCCCCC), 0.00f)
        else -> Tier(-2f, FontWeight.Normal, Color(0xFF888888), 0.00f)
    }
}

private fun shapeVolume(volume: Float, toneResponse: Float): Float {
    val clampedVolume = volume.coerceIn(0f, 1f)
    val clampedResponse = toneResponse.coerceIn(0.7f, 1.6f)
    return clampedVolume.toDouble()
        .pow((1f / clampedResponse).toDouble())
        .toFloat()
        .coerceIn(0f, 1f)
}

private fun clamp(raw: Float, base: Float): Float {
    return raw
        .coerceAtLeast((base - 4f).coerceAtLeast(10f))
        .coerceAtMost((base + 8f).coerceAtMost(36f))
}
