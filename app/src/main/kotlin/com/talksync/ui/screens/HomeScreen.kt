package com.talksync.ui.screens

// first screen. brand, big actions, and "is the app ready" status all live here.

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.talksync.model.FunctionalityReport
import com.talksync.model.FunctionalityStatus
import com.talksync.ui.components.FunctionalitySummaryCard
import com.talksync.ui.theme.BrandAccent
import com.talksync.ui.theme.BrandAccentFont
import com.talksync.ui.theme.BrandGlow
import com.talksync.ui.theme.HairlineWhite
import com.talksync.ui.theme.Radii
import com.talksync.ui.theme.Spacing
import com.talksync.ui.theme.StatusAlertCoral
import com.talksync.ui.theme.StatusAlertCoralSoft
import com.talksync.ui.theme.StatusLiveGreen
import com.talksync.ui.theme.StatusLiveGreenSoft
import com.talksync.ui.theme.StatusWarnAmber
import com.talksync.ui.theme.StatusWarnAmberSoft

@Composable
fun HomeScreen(
    functionalityReport: FunctionalityReport,
    onStartConversation: () -> Unit,
    onSoundDirection: () -> Unit,
    onJudgeWalkthrough: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onCardboard: () -> Unit = {},
    onMusicMirror: () -> Unit = {},
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        val isLandscape = maxWidth > maxHeight
        val horizontalInset = if (isLandscape) Spacing.xxl else Spacing.xl
        val topInset = if (isLandscape) Spacing.l else Spacing.xl

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    PaddingValues(
                        start = horizontalInset,
                        end = horizontalInset,
                        top = topInset,
                        bottom = Spacing.xl,
                    ),
                ),
        ) {
            HomeTopBar(
                report = functionalityReport,
                onSettings = onSettings,
            )

            Spacer(modifier = Modifier.height(Spacing.l))

            if (isLandscape) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xxl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.m),
                    ) {
                        BrandLockup()
                        Text(
                            text = "Live captions stay close to the speaker so conversations feel natural.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.m),
                    ) {
                        HomeActionStack(
                            onStartConversation = onStartConversation,
                            onSoundDirection = onSoundDirection,
                            onJudgeWalkthrough = onJudgeWalkthrough,
                            onHistory = onHistory,
                            onCardboard = onCardboard,
                            onMusicMirror = onMusicMirror,
                        )
                        FunctionalitySummaryCard(
                            report = functionalityReport,
                            compact = true,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.l),
                ) {
                    Spacer(modifier = Modifier.height(Spacing.s))
                    BrandLockup()
                    Text(
                        text = "Live captions stay close to the speaker so conversations feel natural.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    HomeActionStack(
                        onStartConversation = onStartConversation,
                        onSoundDirection = onSoundDirection,
                        onJudgeWalkthrough = onJudgeWalkthrough,
                        onHistory = onHistory,
                        onCardboard = onCardboard,
                        onMusicMirror = onMusicMirror,
                    )
                    FunctionalitySummaryCard(
                        report = functionalityReport,
                        compact = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(
    report: FunctionalityReport,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeStatusPill(report = report)

        IconButton(
            onClick = onSettings,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "Open settings",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun HomeStatusPill(report: FunctionalityReport) {
    val aggregate = when {
        report.checks.any { it.status == FunctionalityStatus.NEEDS_SETUP } -> StatusTone.ALERT
        report.checks.any { it.status == FunctionalityStatus.WARNING } -> StatusTone.WARN
        report.checks.any { it.status == FunctionalityStatus.LIVE } -> StatusTone.LIVE
        else -> StatusTone.IDLE
    }
    val label = when (aggregate) {
        StatusTone.LIVE -> "All systems ready"
        StatusTone.WARN -> "Some features limited"
        StatusTone.ALERT -> "Setup needed"
        StatusTone.IDLE -> "Ready"
    }
    val dotColor = when (aggregate) {
        StatusTone.LIVE -> StatusLiveGreen
        StatusTone.WARN -> StatusWarnAmber
        StatusTone.ALERT -> StatusAlertCoral
        StatusTone.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val tint = when (aggregate) {
        StatusTone.LIVE -> StatusLiveGreenSoft
        StatusTone.WARN -> StatusWarnAmberSoft
        StatusTone.ALERT -> StatusAlertCoralSoft
        StatusTone.IDLE -> MaterialTheme.colorScheme.surface
    }

    Surface(
        shape = RoundedCornerShape(Radii.pill),
        color = tint,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = HairlineWhite,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            PulsingDot(color = dotColor, enabled = aggregate == StatusTone.LIVE)
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.02.sp,
                ),
            )
        }
    }
}

private enum class StatusTone { LIVE, WARN, ALERT, IDLE }

@Composable
private fun PulsingDot(color: Color, enabled: Boolean) {
    val transition = rememberInfiniteTransition(label = "homeStatusDot")
    val alpha by transition.animateFloat(
        initialValue = if (enabled) 0.55f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "homeStatusAlpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color.copy(alpha = if (enabled) alpha else 1f), CircleShape),
    )
}

@Composable
private fun BrandLockup() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = "bubbl!",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.displayLarge.copy(
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 24.dp, height = 2.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(BrandAccent, Color.Transparent),
                        ),
                    ),
            )
            Text(
                text = "See every word.",
                color = BrandAccent,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = BrandAccentFont,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Normal,
                    fontSize = 18.sp,
                ),
                modifier = Modifier.alpha(0.96f),
            )
        }
    }
}

@Composable
private fun HomeActionStack(
    onStartConversation: () -> Unit,
    onSoundDirection: () -> Unit,
    onJudgeWalkthrough: () -> Unit,
    onHistory: () -> Unit,
    onCardboard: () -> Unit = {},
    onMusicMirror: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        PrimaryActionButton(
            label = "Start Conversation",
            icon = Icons.Outlined.PlayArrow,
            onClick = onStartConversation,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            SecondaryActionButton(
                label = "Sound Direction",
                icon = Icons.Outlined.GraphicEq,
                onClick = onSoundDirection,
                modifier = Modifier.weight(1f),
            )
            SecondaryActionButton(
                label = "Walkthrough",
                icon = Icons.Outlined.AutoAwesome,
                onClick = onJudgeWalkthrough,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            SecondaryActionButton(
                label = "History",
                icon = Icons.Outlined.History,
                onClick = onHistory,
                modifier = Modifier.weight(1f),
            )
            SecondaryActionButton(
                label = "Cardboard VR",
                icon = Icons.Outlined.RemoveRedEye,
                onClick = onCardboard,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            SecondaryActionButton(
                label = "Music Mirror",
                icon = Icons.Outlined.MusicNote,
                onClick = onMusicMirror,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PrimaryActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(BrandGlow, Color.Transparent),
                    radius = 800f,
                ),
                shape = RoundedCornerShape(Radii.l),
            )
            .padding(1.dp),
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(Radii.l),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background,
            ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(Spacing.s))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp,
                ),
            )
            Spacer(modifier = Modifier.size(Spacing.s))
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SecondaryActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .height(52.dp)
            .border(
                width = 1.dp,
                color = HairlineWhite,
                shape = RoundedCornerShape(Radii.l),
            ),
        shape = RoundedCornerShape(Radii.l),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = Spacing.m, vertical = Spacing.s),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.size(Spacing.s))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}
