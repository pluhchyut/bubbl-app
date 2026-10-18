package com.talksync.ui.components

// tiny intent chips kept separate so transcript rows dont get even more crowded.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.talksync.model.IntentLabel

@Composable
fun IntentLabelChips(
    labels: List<IntentLabel>,
    modifier: Modifier = Modifier,
    maxLabels: Int = 2,
    compact: Boolean = false,
) {
    val visibleLabels = labels.distinct().take(maxLabels)
    if (visibleLabels.isEmpty()) {
        return
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp),
    ) {
        visibleLabels.forEach { label ->
            IntentLabelChip(
                label = label,
                compact = compact,
            )
        }
    }
}

@Composable
private fun IntentLabelChip(
    label: IntentLabel,
    compact: Boolean,
) {
    val tint = label.intentColor()
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = tint.copy(alpha = if (compact) 0.14f else 0.18f),
    ) {
        Text(
            text = label.displayText,
            modifier = Modifier.padding(
                horizontal = if (compact) 7.dp else 8.dp,
                vertical = if (compact) 3.dp else 4.dp,
            ),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = if (compact) 10.sp else 11.sp,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun IntentLabel.intentColor(): Color {
    return when (this) {
        IntentLabel.QUESTION -> Color(0xFF73C7FF)
        IntentLabel.INSTRUCTION -> Color(0xFFFFC857)
        IntentLabel.JOKE -> Color(0xFFD28CFF)
        IntentLabel.IMPORTANT -> Color(0xFFFF6B6B)
    }
}
