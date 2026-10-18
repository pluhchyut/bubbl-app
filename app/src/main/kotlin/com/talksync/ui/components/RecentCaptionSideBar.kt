package com.talksync.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.talksync.model.RecentCaptionState
import com.talksync.viewmodel.VisibleCaptionUi

/** Brief reading space for captions whose face bubble has finished. */
@Composable
fun RecentCaptionSideBar(
    entries: List<VisibleCaptionUi>,
    enabled: Boolean,
    panelWidth: Dp,
    maxPanelHeight: Dp,
    showOriginalLanguage: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val latestEntries by rememberUpdatedState(entries)
    LaunchedEffect(entries.lastOrNull()?.id) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }
    AnimatedVisibility(
        visible = enabled && entries.isNotEmpty(),
        enter = fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it },
        exit = fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { it },
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier.width(panelWidth).heightIn(max = maxPanelHeight),
            color = Color(0xFF101923).copy(alpha = 0.90f),
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 4.dp,
        ) {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    text = "Just said",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(latestEntries, key = { it.id }) { entry ->
                        val visibility = remember(entry.id) {
                            MutableTransitionState(false).apply { targetState = !entry.isFading }
                        }
                        LaunchedEffect(entry.isFading) { visibility.targetState = !entry.isFading }
                        AnimatedVisibility(
                            visibleState = visibility,
                            enter = fadeIn(tween(260)) + expandVertically(tween(260)),
                            exit = fadeOut(tween(RecentCaptionState.SIDE_FADE_MS.toInt())) +
                                shrinkVertically(tween(RecentCaptionState.SIDE_FADE_MS.toInt())),
                        ) {
                            val translated = entry.translatedText?.takeIf { it.isNotBlank() }
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                entry.speakerLabel?.let { label ->
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF8ADFD2),
                                    )
                                }
                                Text(
                                    text = if (showOriginalLanguage) entry.text else translated ?: entry.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.94f),
                                )
                                if (translated != null && !showOriginalLanguage) {
                                    Text(
                                        text = entry.text,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.60f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
