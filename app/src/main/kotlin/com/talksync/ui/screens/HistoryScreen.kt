package com.talksync.ui.screens

// history browser. takes saved turns and rebuilds them into readable past conversations.

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.talksync.data.AppSettings
import com.talksync.emotion.ToneLabel
import com.talksync.model.ConversationQuestionState
import com.talksync.model.ConversationSummaryRequest
import com.talksync.model.TranscriptEntry
import com.talksync.ui.theme.BrandAccent
import com.talksync.ui.theme.HairlineWhite
import com.talksync.ui.theme.Radii
import com.talksync.ui.theme.Spacing
import com.talksync.util.toHistoryHeader
import com.talksync.viewmodel.ConversationViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

private data class HistoryConversationUi(
    val id: String,
    val entryIds: List<Long>,
    val latestTimestamp: Long,
    val oldestTimestamp: Long,
    val text: String,
    val transcriptSegments: List<HistoryTranscriptSegment>,
    val summary: String,
    val previewText: String,
    val emotionLabel: String?,
    val entryCount: Int,
)

private data class HistoryTranscriptSegment(
    val speakerLabel: String? = null,
    val text: String,
    val isPause: Boolean = false,
)

private const val INLINE_PAUSE_THRESHOLD_MS = 8_000L

private val summaryStopWords = setOf(
    "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can", "could",
    "did", "do", "does", "for", "from", "get", "got", "had", "has", "have", "hey",
    "how", "i", "i'm", "if", "in", "into", "is", "it", "it's", "its", "just", "kind",
    "know", "like", "me", "my", "of", "ok", "okay", "on", "or", "our", "really", "so",
    "speaker", "that", "the", "their", "them", "there", "they", "this", "to", "uh",
    "um", "want", "was", "we", "well", "were", "what", "when", "with", "would", "yeah",
    "you", "your",
)

private val speakerPrefixRegex = Regex("^Speaker\\s+\\d+:\\s*", RegexOption.IGNORE_CASE)
private val summaryTokenRegex = Regex("[A-Za-z][A-Za-z'-]*")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    conversationViewModel: ConversationViewModel,
    settings: AppSettings,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val historyEntries by conversationViewModel.historyEntries.collectAsStateWithLifecycle()
    val aiConversationSummaries by conversationViewModel.conversationSummaries.collectAsStateWithLifecycle()
    val conversationQuestionStates by conversationViewModel.conversationQuestionStates.collectAsStateWithLifecycle()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    var conversationPendingDelete by remember { mutableStateOf<HistoryConversationUi?>(null) }

    LaunchedEffect(Unit) {
        conversationViewModel.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val conversationGroups = remember(historyEntries, settings.conversationGapSeconds) {
        buildConversationGroups(
            entries = historyEntries,
            conversationGapSeconds = settings.conversationGapSeconds,
        )
    }
    LaunchedEffect(conversationGroups) {
        conversationViewModel.ensureConversationSummaries(
            conversationGroups.map { conversation ->
                ConversationSummaryRequest(
                    conversationId = conversation.id,
                    transcript = conversation.text,
                )
            },
        )
    }
    val filteredConversations = remember(conversationGroups, searchQuery, aiConversationSummaries) {
        conversationGroups.filter { conversation ->
            val displaySummary = aiConversationSummaries[conversation.id] ?: conversation.summary
            searchQuery.isBlank() ||
                conversation.text.contains(searchQuery, ignoreCase = true) ||
                displaySummary.contains(searchQuery, ignoreCase = true) ||
                conversation.previewText.contains(searchQuery, ignoreCase = true)
        }
    }
    val groupedConversations = remember(filteredConversations) {
        filteredConversations.groupBy { it.latestTimestamp.toHistoryHeader() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = Spacing.xl, vertical = Spacing.l),
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(
                    text = "History",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Search, share, and ask follow-up questions on past conversations.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(
                onClick = { conversationViewModel.exportAllHistory() },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileUpload,
                    contentDescription = "Export all history",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            IconButton(
                onClick = { showClearDialog = true },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Clear all history",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.m))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search history",
                )
            },
            label = { Text("Search conversations") },
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (filteredConversations.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(Radii.xl),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, HairlineWhite),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.l),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = null,
                            tint = BrandAccent,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = "No conversations yet",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Saved conversations will show up here once you start using live captions.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groupedConversations.forEach { (header, conversations) ->
                    item(key = "header-$header") {
                        Text(
                            text = header,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(
                        items = conversations,
                        key = { it.id },
                    ) { conversation ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value != SwipeToDismissBoxValue.Settled) {
                                    conversationViewModel.deleteTranscripts(conversation.entryIds)
                                    true
                                } else {
                                    false
                                }
                            },
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Text(
                                        text = "Delete conversation",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            },
                            enableDismissFromEndToStart = true,
                        ) {
                            HistoryConversationRow(
                                conversation = conversation,
                                summaryText = aiConversationSummaries[conversation.id] ?: conversation.summary,
                                questionState = conversationQuestionStates[conversation.id],
                                settings = settings,
                                onAskQuestion = { question ->
                                    conversationViewModel.askConversationQuestion(
                                        conversationId = conversation.id,
                                        transcript = conversation.text,
                                        question = question,
                                        summaryHint = aiConversationSummaries[conversation.id] ?: conversation.summary,
                                        toneHint = conversation.emotionLabel,
                                    )
                                },
                                onShare = {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, conversation.text)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(shareIntent, "Share conversation"),
                                    )
                                },
                                onLongPressDelete = {
                                    conversationPendingDelete = conversation
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear all history?") },
            text = { Text("This removes every saved conversation from the app history.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        conversationViewModel.clearHistory()
                    },
                ) {
                    Text("Clear all")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    conversationPendingDelete?.let { pendingConversation ->
        AlertDialog(
            onDismissRequest = { conversationPendingDelete = null },
            title = { Text("Delete conversation?") },
            text = { Text("This whole conversation group will be removed from history.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        conversationViewModel.deleteTranscripts(pendingConversation.entryIds)
                        conversationPendingDelete = null
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { conversationPendingDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryConversationRow(
    conversation: HistoryConversationUi,
    summaryText: String,
    questionState: ConversationQuestionState?,
    settings: AppSettings,
    onAskQuestion: (String) -> Unit,
    onShare: () -> Unit,
    onLongPressDelete: () -> Unit,
) {
    var expanded by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var questionDraft by rememberSaveable(conversation.id) { mutableStateOf("") }
    val emotionLabel = conversation.emotionLabel?.let(ToneLabel::fromPersistedLabel)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = onLongPressDelete,
            ),
        shape = RoundedCornerShape(Radii.l),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, HairlineWhite),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Text(
                        text = conversationWindowLabel(
                            latestTimestamp = conversation.latestTimestamp,
                            oldestTimestamp = conversation.oldestTimestamp,
                        ),
                        color = BrandAccent,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    emotionLabel
                        ?.takeIf { it != ToneLabel.NEUTRAL }
                        ?.let {
                            Text(
                                text = it.name.lowercase(),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    Text(
                        text = "${conversation.entryCount} ${if (conversation.entryCount == 1) "line" else "lines"}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                IconButton(
                    onClick = onShare,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.IosShare,
                        contentDescription = "Share conversation",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Text(
                text = summaryText,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (conversation.previewText.isNotBlank()) {
                Text(
                    text = conversation.previewText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontStyle = FontStyle.Italic,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (expanded) {
                ConversationTranscriptSegments(
                    transcriptSegments = conversation.transcriptSegments,
                    textSizeSp = settings.textSizeSp,
                )
                ConversationQuestionSection(
                    questionDraft = questionDraft,
                    onQuestionDraftChange = { questionDraft = it },
                    questionState = questionState,
                    onAsk = {
                        val cleaned = questionDraft.trim()
                        if (cleaned.isNotBlank()) {
                            onAskQuestion(cleaned)
                        }
                    },
                    onQuickAsk = { prompt ->
                        questionDraft = prompt
                        onAskQuestion(prompt)
                    },
                )
            }
        }
    }
}

private fun buildConversationGroups(
    entries: List<TranscriptEntry>,
    conversationGapSeconds: Float,
): List<HistoryConversationUi> {
    if (entries.isEmpty()) {
        return emptyList()
    }

    val gapMs = (conversationGapSeconds.coerceAtLeast(1f) * 1_000f).roundToLong()
    val sortedEntries = entries.sortedByDescending { it.timestamp }
    val sessions = mutableListOf<MutableList<TranscriptEntry>>()
    var currentSession = mutableListOf<TranscriptEntry>()

    sortedEntries.forEach { entry ->
        if (currentSession.isEmpty()) {
            currentSession += entry
            return@forEach
        }

        val previousOldest = currentSession.last()
        if (previousOldest.timestamp - entry.timestamp <= gapMs) {
            currentSession += entry
        } else {
            sessions += currentSession
            currentSession = mutableListOf(entry)
        }
    }

    if (currentSession.isNotEmpty()) {
        sessions += currentSession
    }

    return sessions.map { sessionEntries ->
        val chronologicalEntries = sessionEntries.sortedBy { it.timestamp }
        val newestEntry = sessionEntries.first()
        val oldestEntry = sessionEntries.last()
        val transcriptSegments = buildConversationTranscriptSegments(chronologicalEntries)
        val sessionText = buildConversationTranscript(transcriptSegments)
        val conversationEmotion = chronologicalEntries
            .lastOrNull { !it.emotionLabel.isNullOrBlank() }
            ?.emotionLabel
            ?: chronologicalEntries.firstOrNull { !it.emotionLabel.isNullOrBlank() }?.emotionLabel

        HistoryConversationUi(
            id = chronologicalEntries.first().id.toString(),
            entryIds = chronologicalEntries.map { it.id },
            latestTimestamp = newestEntry.timestamp,
            oldestTimestamp = oldestEntry.timestamp,
            text = sessionText,
            transcriptSegments = transcriptSegments,
            summary = buildConversationSummary(chronologicalEntries),
            previewText = buildConversationPreview(chronologicalEntries),
            emotionLabel = conversationEmotion,
            entryCount = chronologicalEntries.size,
        )
    }
}

private fun buildConversationTranscript(
    segments: List<HistoryTranscriptSegment>,
): String {
    if (segments.isEmpty()) {
        return ""
    }

    return buildString {
        segments.forEach { segment ->
            if (isNotEmpty()) {
                appendLine()
            }
            if (segment.isPause) {
                append(segment.text)
            } else {
                val renderedLine = listOfNotNull(segment.speakerLabel, segment.text)
                    .joinToString(separator = ": ")
                append(renderedLine)
            }
        }
    }.trim()
}

private fun buildConversationTranscriptSegments(
    entries: List<TranscriptEntry>,
): List<HistoryTranscriptSegment> {
    if (entries.isEmpty()) {
        return emptyList()
    }

    return buildList {
        entries.forEachIndexed { index, entry ->
            val rawLine = entry.text.trim()
            if (rawLine.isBlank()) {
                return@forEachIndexed
            }

            if (index > 0) {
                val gapMs = entry.timestamp - entries[index - 1].timestamp
                if (gapMs >= INLINE_PAUSE_THRESHOLD_MS) {
                    add(
                        HistoryTranscriptSegment(
                            text = formatPauseMarker(gapMs),
                            isPause = true,
                        ),
                    )
                }
            }

            val speakerLabel = extractSpeakerLabel(rawLine)
            val normalizedLine = normalizeTranscriptLine(rawLine).ifBlank { rawLine }
            add(
                HistoryTranscriptSegment(
                    speakerLabel = speakerLabel,
                    text = normalizedLine,
                ),
            )
        }
    }
}

private fun buildConversationPreview(
    entries: List<TranscriptEntry>,
): String {
    val previewLines = entries
        .map { entry ->
            listOfNotNull(
                extractSpeakerLabel(entry.text.trim()),
                normalizeTranscriptLine(entry.text.trim()).ifBlank { entry.text.trim() },
            ).joinToString(": ")
        }
        .filter { it.isNotBlank() }
        .take(2)
        .map { line -> trimForDisplay(line, maxChars = 92) }

    return when (previewLines.size) {
        0 -> ""
        1 -> "\"${previewLines.first()}\""
        else -> "\"${previewLines[0]}\"\n\"${previewLines[1]}\""
    }
}

@Composable
private fun ConversationTranscriptSegments(
    transcriptSegments: List<HistoryTranscriptSegment>,
    textSizeSp: Float,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        transcriptSegments.forEach { segment ->
            if (segment.isPause) {
                Text(
                    text = segment.text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = (textSizeSp - 2f).coerceAtLeast(12f).sp,
                    fontStyle = FontStyle.Italic,
                )
            } else if (segment.speakerLabel != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "${segment.speakerLabel}:",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = textSizeSp.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = segment.text,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = textSizeSp.sp,
                        lineHeight = (textSizeSp + 8f).sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Text(
                    text = segment.text,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = textSizeSp.sp,
                    lineHeight = (textSizeSp + 8f).sp,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConversationQuestionSection(
    questionDraft: String,
    onQuestionDraftChange: (String) -> Unit,
    questionState: ConversationQuestionState?,
    onAsk: () -> Unit,
    onQuickAsk: (String) -> Unit,
) {
    val quickPrompts = listOf(
        "What was this conversation about?",
        "What was the average tone?",
        "Any action items or next steps?",
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = BrandAccent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Ask about this conversation",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            quickPrompts.forEach { prompt ->
                SuggestionChip(
                    onClick = { onQuickAsk(prompt) },
                    label = { Text(prompt) },
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = questionDraft,
                onValueChange = onQuestionDraftChange,
                modifier = Modifier.weight(1f),
                label = { Text("Ask something") },
                singleLine = true,
            )
            IconButton(
                onClick = onAsk,
                enabled = questionDraft.trim().isNotBlank() && questionState?.isLoading != true,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "Ask about conversation",
                )
            }
        }

        when {
            questionState?.isLoading == true -> {
                Text(
                    text = "Thinking...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }

            !questionState?.error.isNullOrBlank() -> {
                Text(
                    text = questionState?.error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                )
            }

            !questionState?.answer.isNullOrBlank() -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(Radii.l),
                        )
                        .padding(Spacing.m),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    questionState?.question
                        ?.takeIf { it.isNotBlank() }
                        ?.let { prompt ->
                            Text(
                                text = prompt,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                fontStyle = FontStyle.Italic,
                            )
                        }
                    Text(
                        text = questionState?.answer.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}

private fun buildConversationSummary(
    entries: List<TranscriptEntry>,
): String {
    val normalizedLines = entries
        .map { entry -> normalizeTranscriptLine(entry.text) }
        .filter { it.isNotBlank() }

    if (normalizedLines.isEmpty()) {
        return "Conversation"
    }

    val keywords = normalizedLines
        .flatMap(::extractSummaryTokens)
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenByDescending { it.key.length },
        )
        .map { entry -> humanizeSummaryToken(entry.key) }
        .distinct()

    val speakerCount = entries
        .mapNotNull { entry -> extractSpeakerLabel(entry.text) }
        .distinct()
        .size

    val lead = when {
        speakerCount >= 2 && entries.size >= 4 -> "Back-and-forth about"
        speakerCount >= 2 -> "Conversation about"
        entries.size == 1 -> "Quick note about"
        else -> "Conversation about"
    }

    val summary = when {
        keywords.size >= 3 -> "$lead ${keywords[0]}, ${keywords[1]}, and ${keywords[2]}"
        keywords.size == 2 -> "$lead ${keywords[0]} and ${keywords[1]}"
        keywords.size == 1 -> "$lead ${keywords[0]}"
        else -> trimForDisplay(normalizedLines.first(), maxChars = 82)
    }

    return summary.replaceFirstChar { char ->
        if (char.isLowerCase()) {
            char.titlecase()
        } else {
            char.toString()
        }
    }
}

private fun extractSummaryTokens(
    text: String,
): List<String> {
    return summaryTokenRegex.findAll(text.lowercase())
        .map { match -> match.value.trim('\'') }
        .filter { token ->
            token.length >= 2 &&
                token !in summaryStopWords &&
                !token.all { it.isDigit() }
        }
        .toList()
}

private fun normalizeTranscriptLine(
    text: String,
): String {
    return text
        .replace(speakerPrefixRegex, "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun extractSpeakerLabel(
    text: String,
): String? {
    return Regex("^Speaker\\s+\\d+", RegexOption.IGNORE_CASE)
        .find(text.trim())
        ?.value
}

private fun humanizeSummaryToken(
    token: String,
): String {
    return when (token.lowercase()) {
        "ai" -> "AI"
        "ar" -> "AR"
        "asl" -> "ASL"
        "ui" -> "UI"
        else -> token
    }
}

private fun trimForDisplay(
    text: String,
    maxChars: Int,
): String {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.length <= maxChars) {
        return normalized
    }
    return normalized
        .take((maxChars - 3).coerceAtLeast(1))
        .trimEnd(' ', ',', '.', '!', '?', ':', ';')
        .plus("...")
}

private fun formatPauseMarker(
    gapMs: Long,
): String {
    val totalSeconds = ((gapMs + 500L) / 1_000L).coerceAtLeast(1L)
    return if (totalSeconds >= 60L) {
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        if (seconds == 0L) {
            "[pause ${minutes}m]"
        } else {
            "[pause ${minutes}m ${seconds}s]"
        }
    } else {
        "[pause ${totalSeconds}s]"
    }
}

private fun conversationWindowLabel(
    latestTimestamp: Long,
    oldestTimestamp: Long,
): String {
    val zoneId = ZoneId.systemDefault()
    val newest = Instant.ofEpochMilli(latestTimestamp).atZone(zoneId)
    val oldest = Instant.ofEpochMilli(oldestTimestamp).atZone(zoneId)
    val formatter = DateTimeFormatter.ofPattern("h:mm a")
    return if (latestTimestamp == oldestTimestamp) {
        newest.format(formatter)
    } else {
        "${oldest.format(formatter)} - ${newest.format(formatter)}"
    }
}
