package com.talksync.ui.components

// home readiness card. tells what works now and what still needs setup.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.talksync.model.FunctionalityCheck
import com.talksync.model.FunctionalityReport
import com.talksync.model.FunctionalityStatus
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
fun FunctionalitySummaryCard(
    report: FunctionalityReport,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.xl),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, HairlineWhite),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                ) {
                    Text(
                        text = "System readiness",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = report.headline,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                ReadinessGlyph(
                    ready = report.readyCount,
                    total = report.checks.size,
                    blocking = report.blockingCount,
                )
            }

            if (compact) {
                val focusChecks = report.blockingChecks.ifEmpty {
                    report.checks.take(3)
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    focusChecks.forEach { check ->
                        CompactCheckRow(check = check)
                    }
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    report.checks.chunked(2).forEach { rowChecks ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        ) {
                            rowChecks.forEach { check ->
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(Radii.m),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, HairlineWhite),
                                ) {
                                    DetailedCheckRow(
                                        check = check,
                                        modifier = Modifier.padding(Spacing.m),
                                    )
                                }
                            }
                            if (rowChecks.size == 1) {
                                Box(modifier = Modifier.weight(1f)) {}
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadinessGlyph(ready: Int, total: Int, blocking: Int) {
    val tint = when {
        blocking > 0 -> StatusAlertCoralSoft
        ready == total && total > 0 -> StatusLiveGreenSoft
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val accent = when {
        blocking > 0 -> StatusAlertCoral
        ready == total && total > 0 -> StatusLiveGreen
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(tint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$ready/${total.coerceAtLeast(1)}",
            color = accent,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                letterSpacing = 0.sp,
            ),
        )
    }
}

@Composable
private fun CompactCheckRow(check: FunctionalityCheck) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(status = check.status)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Text(
                text = check.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = check.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        StatusPill(status = check.status)
    }
}

@Composable
private fun DetailedCheckRow(
    check: FunctionalityCheck,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status = check.status)
            Text(
                text = check.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            StatusPill(status = check.status)
        }
        Text(
            text = check.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StatusDot(status: FunctionalityStatus) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(statusAccent(status), CircleShape),
    )
}

@Composable
private fun StatusPill(status: FunctionalityStatus) {
    Text(
        text = statusLabel(status),
        color = statusAccent(status),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.06.sp,
            fontSize = 10.sp,
        ),
        modifier = Modifier
            .background(statusTint(status), RoundedCornerShape(Radii.pill))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun statusAccent(status: FunctionalityStatus): Color = when (status) {
    FunctionalityStatus.LIVE -> StatusLiveGreen
    FunctionalityStatus.READY -> MaterialTheme.colorScheme.onSurface
    FunctionalityStatus.WAITING -> StatusWarnAmber
    FunctionalityStatus.WARNING -> StatusWarnAmber
    FunctionalityStatus.NEEDS_SETUP -> StatusAlertCoral
}

@Composable
private fun statusTint(status: FunctionalityStatus): Color = when (status) {
    FunctionalityStatus.LIVE -> StatusLiveGreenSoft
    FunctionalityStatus.READY -> HairlineWhite
    FunctionalityStatus.WAITING -> StatusWarnAmberSoft
    FunctionalityStatus.WARNING -> StatusWarnAmberSoft
    FunctionalityStatus.NEEDS_SETUP -> StatusAlertCoralSoft
}

private fun statusLabel(status: FunctionalityStatus): String = when (status) {
    FunctionalityStatus.LIVE -> "LIVE"
    FunctionalityStatus.READY -> "READY"
    FunctionalityStatus.WAITING -> "WAITING"
    FunctionalityStatus.WARNING -> "WARNING"
    FunctionalityStatus.NEEDS_SETUP -> "SETUP"
}
