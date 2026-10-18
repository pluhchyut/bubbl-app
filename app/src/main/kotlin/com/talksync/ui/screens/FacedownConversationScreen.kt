package com.talksync.ui.screens

// tabletop mode for when the phone is face down and people need shared notes.

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.talksync.data.AppSettings
import com.talksync.model.ConversationInputMode
import com.talksync.model.IntentLabel
import com.talksync.model.SpeechBubbleData
import com.talksync.ui.components.IntentLabelChips
import com.talksync.util.PermissionUtil
import com.talksync.viewmodel.ConversationViewModel
import com.talksync.viewmodel.VisibleCaptionUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FacedownConversationScreen(
    conversationViewModel: ConversationViewModel,
    settings: AppSettings,
    autoTabletopMode: Boolean = false,
    onBack: () -> Unit,
    onHistory: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val speakerBubbles by conversationViewModel.speakerBubbles.collectAsStateWithLifecycle()
    val bubbleData by conversationViewModel.bubbleData.collectAsStateWithLifecycle()
    val currentTranscript by conversationViewModel.currentTranscript.collectAsStateWithLifecycle()
    val connectionStatus by conversationViewModel.connectionStatus.collectAsStateWithLifecycle()
    val inputMode by conversationViewModel.inputMode.collectAsStateWithLifecycle()
    val showOriginalLanguage by conversationViewModel.showOriginalLanguage.collectAsStateWithLifecycle()
    val activeSpeakerCount by conversationViewModel.activeSpeakerCount.collectAsStateWithLifecycle()

    var hasPermission by rememberSaveable {
        mutableStateOf(PermissionUtil.hasMicrophonePermission(conversationViewModel.getApplication()))
    }
    var showPermissionDialog by rememberSaveable { mutableStateOf(false) }
    var requestedOnce by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        showPermissionDialog = !granted
    }

    LaunchedEffect(inputMode) {
        if (inputMode != ConversationInputMode.TRANSCRIPTION) {
            conversationViewModel.setInputMode(ConversationInputMode.TRANSCRIPTION)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = PermissionUtil.hasMicrophonePermission(conversationViewModel.getApplication())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasPermission) {
        if (!hasPermission && !requestedOnce) {
            requestedOnce = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(hasPermission) {
        conversationViewModel.unbindCamera()
        conversationViewModel.setFaceDownMode(true)
        if (hasPermission) {
            conversationViewModel.startConversation()
        }
        onDispose {
            conversationViewModel.stopConversation()
            conversationViewModel.setFaceDownMode(false)
            conversationViewModel.unbindCamera()
        }
    }

    val livePartial = remember(speakerBubbles, bubbleData) {
        speakerBubbles
            .filter { it.isPartial && it.text.isNotBlank() && it.isVisible }
            .maxByOrNull { it.timestamp }
            ?: bubbleData.takeIf { it.isPartial && it.text.isNotBlank() && it.isVisible }
    }

    val anyForeignLanguageDetected = remember(currentTranscript, speakerBubbles, bubbleData) {
        currentTranscript.any { it.hasVisibleTranslation() } ||
            speakerBubbles.any { it.hasVisibleTranslation() } ||
            bubbleData.hasVisibleTranslation()
    }

    // Finalized rows only — live partial goes to the pinned bottom panel.
    val transcriptRows = remember(currentTranscript, showOriginalLanguage) {
        buildFinalizedTranscriptRows(
            captions = currentTranscript,
            showOriginalLanguage = showOriginalLanguage,
        )
    }

    val tabletopLiveRow = if (autoTabletopMode && livePartial != null) {
        val hasTranslation = livePartial.hasVisibleTranslation()
        val liveText = if (hasTranslation && !showOriginalLanguage) {
            livePartial.translatedText.orEmpty()
        } else {
            livePartial.text
        }
        liveText
            .takeIf { it.isNotBlank() }
            ?.let { text ->
                FaceDownTranscriptRow(
                    id = "live-${livePartial.speakerKey}-${livePartial.timestamp}",
                    speakerKey = livePartial.speakerKey,
                    speakerLabel = livePartial.speakerLabel ?: fallbackSpeakerLabel(livePartial.speakerKey),
                    text = text,
                    timestamp = livePartial.timestamp,
                    isLive = true,
                    metadataLabel = if (hasTranslation && !showOriginalLanguage) "live translated" else "live",
                    intentLabels = livePartial.intentLabels,
                    threadId = livePartial.threadId,
                    isThreadHighlighted = livePartial.isThreadHighlighted,
                )
            }
    } else {
        null
    }
    val displayedRows = if (tabletopLiveRow != null) {
        transcriptRows + tabletopLiveRow
    } else {
        transcriptRows
    }

    val transcriptListState = rememberLazyListState()
    val latestRowId = displayedRows.lastOrNull()?.id
    val latestRowText = displayedRows.lastOrNull()?.text

    LaunchedEffect(displayedRows.size, latestRowId, latestRowText) {
        if (displayedRows.isNotEmpty()) {
            transcriptListState.animateScrollToItem(displayedRows.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // ── Top bar ──────────────────────────────────────────────────────
        FaceDownTopBar(
            autoTabletopMode = autoTabletopMode,
            connectionStatus = connectionStatus,
            speakerCount = activeSpeakerCount,
            anyForeignLanguageDetected = anyForeignLanguageDetected,
            showOriginalLanguage = showOriginalLanguage,
            onToggleLanguage = conversationViewModel::toggleShowOriginalLanguage,
            onBack = onBack,
            onHistory = onHistory,
        )

        // ── Scrollable transcript ─────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (!hasPermission) {
                FaceDownPermissionFallback(
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                )
            } else if (displayedRows.isEmpty()) {
                FaceDownEmptyState(
                    connectionStatus = connectionStatus,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    state = transcriptListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 16.dp,
                        bottom = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(
                            items = displayedRows,
                            key = { row -> row.id },
                        ) { row ->
                            FaceDownTranscriptRow(
                            row = row,
                            textSizeSp = settings.textSizeSp.coerceIn(16f, 26f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // ── Pinned live caption panel ─────────────────────────────────────
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f),
        )
        LiveCaptionPanel(
            liveText = if (autoTabletopMode) null else livePartial?.text?.takeIf { it.isNotBlank() },
            speakerLabel = livePartial?.let {
                it.speakerLabel ?: fallbackSpeakerLabel(it.speakerKey)
            },
            connectionStatus = connectionStatus,
            textSizeSp = settings.textSizeSp.coerceIn(16f, 28f),
            showIdleHint = !autoTabletopMode,
        )
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Microphone required") },
            text = {
                Text("Face-down mode needs microphone access so bubbl! can keep transcribing without the camera.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermissionDialog = false
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                ) {
                    Text("Grant microphone")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

// ── Data model ────────────────────────────────────────────────────────────────

private data class FaceDownTranscriptRow(
    val id: String,
    val speakerKey: String,
    val speakerLabel: String,
    val text: String,
    val timestamp: Long,
    val lastTimestamp: Long = timestamp,
    val isLive: Boolean = false,
    val metadataLabel: String? = null,
    val intentLabels: List<IntentLabel> = emptyList(),
    val threadId: String? = null,
    val isThreadHighlighted: Boolean = false,
)

// ── Top bar ───────────────────────────────────────────────────────────────────

@Composable
private fun FaceDownTopBar(
    autoTabletopMode: Boolean,
    connectionStatus: String,
    speakerCount: Int,
    anyForeignLanguageDetected: Boolean,
    showOriginalLanguage: Boolean,
    onToggleLanguage: () -> Unit,
    onBack: () -> Unit,
    onHistory: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlatControlButton(
            onClick = onBack,
            contentDescription = "Back",
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
        )
        // Mode label + speaker count
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (autoTabletopMode) "Tabletop mode" else "Face-down mode",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildString {
                append(connectionStatus)
                if (speakerCount > 1) append(" · $speakerCount speakers")
            }
            Text(
                text = sub,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (anyForeignLanguageDetected) {
            FlatControlButton(
                onClick = onToggleLanguage,
                contentDescription = if (showOriginalLanguage) "Show translation" else "Show original language",
                icon = if (showOriginalLanguage) Icons.Outlined.Translate else Icons.Outlined.Language,
            )
        }
        FlatControlButton(
            onClick = onHistory,
            contentDescription = "Open history",
            icon = Icons.Outlined.History,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))
}

// ── Transcript row ────────────────────────────────────────────────────────────

@Composable
private fun FaceDownTranscriptRow(
    row: FaceDownTranscriptRow,
    textSizeSp: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SpeakerChip(speakerKey = row.speakerKey, speakerLabel = row.speakerLabel)
            Spacer(modifier = Modifier.weight(1f))
            val ts = row.timestamp.toFaceDownTimestamp()
            if (ts.isNotBlank()) {
                Text(
                    text = ts,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
        Text(
            text = row.text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = textSizeSp.sp,
            lineHeight = (textSizeSp + 9f).sp,
        )
        if (row.intentLabels.isNotEmpty()) {
            IntentLabelChips(
                labels = row.intentLabels,
                maxLabels = 3,
                compact = true,
            )
        }
        if (row.metadataLabel != null) {
            Text(
                text = row.metadataLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ── Speaker chip ──────────────────────────────────────────────────────────────

@Composable
private fun SpeakerChip(speakerKey: String, speakerLabel: String) {
    val color = speakerColorFor(speakerKey)
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.13f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, CircleShape),
            )
            Text(
                text = speakerLabel,
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Pinned live caption panel ─────────────────────────────────────────────────

@Composable
private fun LiveCaptionPanel(
    liveText: String?,
    speakerLabel: String?,
    connectionStatus: String,
    textSizeSp: Float,
    showIdleHint: Boolean = true,
) {
    val live = connectionStatus.equals("Live", ignoreCase = true)
    val hasText = !liveText.isNullOrBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Status row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        color = if (live) com.talksync.ui.theme.StatusLiveGreen
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        shape = CircleShape,
                    ),
            )
            Text(
                text = when {
                    hasText && speakerLabel != null -> speakerLabel
                    live -> "Listening…"
                    else -> connectionStatus
                },
                color = if (hasText) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Live text
        AnimatedVisibility(
            visible = hasText,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Text(
                text = liveText.orEmpty(),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = textSizeSp.sp,
                lineHeight = (textSizeSp + 9f).sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (!hasText && showIdleHint) {
            Text(
                text = if (live) "Start speaking…" else "Waiting for connection",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                fontSize = (textSizeSp - 2f).sp,
            )
        }
    }
}

// ── Flat icon button ──────────────────────────────────────────────────────────

@Composable
private fun FlatControlButton(
    onClick: () -> Unit,
    contentDescription: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(46.dp)
                .semantics { this.contentDescription = contentDescription },
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun FaceDownEmptyState(
    connectionStatus: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ) {
            Icon(
                imageVector = Icons.Outlined.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(16.dp)
                    .size(28.dp),
            )
        }
        Text(
            text = "Transcript will appear here",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ── Permission fallback ───────────────────────────────────────────────────────

@Composable
private fun FaceDownPermissionFallback(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Microphone access is required.",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Grant microphone access to start the live transcript.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            TextButton(onClick = onRequestPermission) {
                Text("Grant microphone")
            }
        }
    }
}

// ── Data builders ─────────────────────────────────────────────────────────────

private fun buildFinalizedTranscriptRows(
    captions: List<VisibleCaptionUi>,
    showOriginalLanguage: Boolean,
): List<FaceDownTranscriptRow> {
    return captions
        .filter { it.text.isNotBlank() }
        .sortedBy { it.timestamp }
        .map { caption ->
            val hasTranslation = caption.hasVisibleTranslation()
            FaceDownTranscriptRow(
                id = caption.id,
                speakerKey = caption.speakerKey,
                speakerLabel = caption.speakerLabel ?: fallbackSpeakerLabel(caption.speakerKey),
                text = if (hasTranslation && !showOriginalLanguage) {
                    caption.translatedText.orEmpty()
                } else {
                    caption.text
                },
                timestamp = caption.timestamp,
                lastTimestamp = caption.timestamp,
                metadataLabel = if (hasTranslation) {
                    if (showOriginalLanguage) "original" else "translated"
                } else {
                    null
                },
                intentLabels = caption.intentLabels,
                threadId = caption.threadId,
                isThreadHighlighted = caption.isThreadHighlighted,
            )
        }
        .mergeSpeakerTurns()
}

private fun List<FaceDownTranscriptRow>.mergeSpeakerTurns(): List<FaceDownTranscriptRow> {
    if (isEmpty()) return emptyList()
    val merged = mutableListOf<FaceDownTranscriptRow>()
    forEach { row ->
        val previous = merged.lastOrNull()
        if (previous != null && previous.canMergeWith(row)) {
            merged[merged.lastIndex] = previous.copy(
                id = "${previous.id}:${row.id}",
                text = mergeTranscriptText(previous.text, row.text),
                lastTimestamp = row.timestamp,
                intentLabels = (previous.intentLabels + row.intentLabels).distinct(),
                threadId = previous.threadId ?: row.threadId,
                isThreadHighlighted = previous.isThreadHighlighted || row.isThreadHighlighted,
            )
        } else {
            merged += row
        }
    }
    return merged
}

private fun FaceDownTranscriptRow.canMergeWith(other: FaceDownTranscriptRow): Boolean {
    return speakerKey == other.speakerKey &&
        metadataLabel == other.metadataLabel &&
        other.timestamp - lastTimestamp <= FACE_DOWN_TURN_MERGE_GAP_MS
}

private fun mergeTranscriptText(first: String, second: String): String {
    return listOf(first.trim(), second.trim())
        .filter { it.isNotBlank() }
        .joinToString(separator = " ")
}

// ── Extension helpers ─────────────────────────────────────────────────────────

private fun VisibleCaptionUi.hasVisibleTranslation(): Boolean {
    return translatedText?.isNotBlank() == true
}

private fun SpeechBubbleData.hasVisibleTranslation(): Boolean {
    return translatedText?.isNotBlank() == true
}

private fun fallbackSpeakerLabel(speakerKey: String): String {
    if (speakerKey == SpeechBubbleData.PENDING_SPEAKER_KEY) return "Speaker"
    val index = speakerKey.substringAfter("speaker-", "").toIntOrNull()
    return if (index != null) "Speaker ${index + 1}" else "Speaker"
}

private fun Long.toFaceDownTimestamp(): String {
    if (this <= 0L) return ""
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(this))
}

private fun speakerColorFor(speakerKey: String): Color {
    val index = (speakerKey.hashCode() and Int.MAX_VALUE) % FaceDownSpeakerColors.size
    return FaceDownSpeakerColors[index]
}

// ── Constants ─────────────────────────────────────────────────────────────────

private const val FACE_DOWN_TURN_MERGE_GAP_MS = 15_000L

private val FaceDownSpeakerColors = listOf(
    Color(0xFF4F7BFF),
    Color(0xFF17A86B),
    Color(0xFFE07A3F),
    Color(0xFFB85DE2),
    Color(0xFFE04F6F),
    Color(0xFF1D9FB8),
)
