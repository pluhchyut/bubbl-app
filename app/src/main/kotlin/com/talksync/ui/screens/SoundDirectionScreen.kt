package com.talksync.ui.screens

// radar-ish debug view for voice direction and face alignment. super useful when fusion lies.

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.talksync.audio.AudioDirection
import com.talksync.model.ConversationInputMode
import com.talksync.model.SpeechBubbleData
import com.talksync.ui.theme.BrandAccent
import com.talksync.ui.theme.BrandGlow
import com.talksync.ui.theme.HairlineWhite
import com.talksync.ui.theme.Radii
import com.talksync.ui.theme.Spacing
import com.talksync.ui.theme.StatusLiveGreen
import com.talksync.ui.theme.StatusLiveGreenSoft
import com.talksync.ui.theme.StatusWarnAmber
import com.talksync.util.PermissionUtil
import com.talksync.util.findActivity
import com.talksync.viewmodel.ConversationViewModel
import com.talksync.vision.FaceAngleMapping
import com.talksync.vision.TrackedFace
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun SoundDirectionScreen(
    conversationViewModel: ConversationViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val trackedFaces by conversationViewModel.trackedFaces.collectAsStateWithLifecycle()
    val speakerBubbles by conversationViewModel.speakerBubbles.collectAsStateWithLifecycle()
    val audioDirection by conversationViewModel.audioDirection.collectAsStateWithLifecycle()
    val isStereoCapture by conversationViewModel.isStereoCapture.collectAsStateWithLifecycle()
    val connectionStatus by conversationViewModel.connectionStatus.collectAsStateWithLifecycle()
    val overlayEnabled by conversationViewModel.overlayEnabled.collectAsStateWithLifecycle()
    val previewView = remember { PreviewView(context) }

    var hasPermissions by rememberSaveable {
        mutableStateOf(PermissionUtil.hasPermissionsForMode(context, ConversationInputMode.TRANSCRIPTION))
    }
    var showPermissionDialog by rememberSaveable { mutableStateOf(false) }
    var requestedOnce by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPermissions = PermissionUtil.hasPermissionsForMode(context, ConversationInputMode.TRANSCRIPTION)
        showPermissionDialog = !hasPermissions
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermissions = PermissionUtil.hasPermissionsForMode(
                    context,
                    ConversationInputMode.TRANSCRIPTION,
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasPermissions) {
        if (!hasPermissions && !requestedOnce) {
            requestedOnce = true
            permissionLauncher.launch(PermissionUtil.permissionsForMode(ConversationInputMode.TRANSCRIPTION))
        }
    }

    DisposableEffect(hasPermissions, previewView, lifecycleOwner) {
        if (hasPermissions) {
            conversationViewModel.setInputMode(ConversationInputMode.TRANSCRIPTION)
            conversationViewModel.bindCamera(
                previewView = previewView,
                lifecycleOwner = lifecycleOwner,
                lensFacing = CameraSelector.LENS_FACING_BACK,
                inputMode = ConversationInputMode.TRANSCRIPTION,
            )
            conversationViewModel.startConversation()
        }

        onDispose {
            conversationViewModel.stopConversation()
            conversationViewModel.unbindCamera()
        }
    }

    val faceEntries = remember(trackedFaces, speakerBubbles) {
        buildFaceEntries(
            trackedFaces = trackedFaces,
            speakerBubbles = speakerBubbles,
        )
    }
    val liveSpeakerCount = remember(faceEntries) { faceEntries.count { it.isTalking } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            ),
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.01f),
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(
                start = Spacing.xl,
                end = Spacing.xl,
                top = Spacing.xl,
                bottom = Spacing.xxxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            item {
                SoundDirectionTopBar(
                    connectionStatus = connectionStatus,
                    isStereoCapture = isStereoCapture,
                    onBack = onBack,
                )
            }

            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Text(
                        text = "Sound direction",
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Text(
                        text = "Radar view of tracked faces and the live audio pull. Face arrows widen as people move closer.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(Radii.xl),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    tonalElevation = 12.dp,
                    shadowElevation = 18.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, HairlineWhite),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.l),
                        contentAlignment = Alignment.Center,
                    ) {
                        SoundRadar(
                            faceEntries = faceEntries,
                            audioDirection = audioDirection,
                            isStereoCapture = isStereoCapture,
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 520.dp)
                                .height(360.dp),
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    DirectionStatCard(
                        title = "Tracked faces",
                        value = trackedFaces.size.toString(),
                        detail = if (trackedFaces.isEmpty()) {
                            "Point the camera at people"
                        } else {
                            "Live face anchors"
                        },
                        accent = BrandAccent,
                        modifier = Modifier.weight(1f),
                    )
                    DirectionStatCard(
                        title = "Talking now",
                        value = liveSpeakerCount.toString(),
                        detail = if (liveSpeakerCount > 0) {
                            "Anchored speakers"
                        } else {
                            "Waiting for speech"
                        },
                        accent = StatusLiveGreen,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                DirectionStatusCard(
                    audioDirection = audioDirection,
                    isStereoCapture = isStereoCapture,
                    overlayEnabled = overlayEnabled,
                )
            }

            if (faceEntries.isEmpty()) {
                item {
                    EmptyDirectionState()
                }
            } else {
                items(
                    items = faceEntries,
                    key = { it.faceId },
                ) { entry ->
                    FaceDirectionRow(entry = entry)
                }
            }
        }
    }

    if (showPermissionDialog) {
        val activity = context.findActivity()
        val useSettings = activity?.let {
            PermissionUtil.shouldShowPermissionRationale(it, ConversationInputMode.TRANSCRIPTION)
        } == false
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Permissions required") },
            text = {
                Text("Sound direction needs camera and microphone access to map faces against live speech.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermissionDialog = false
                        if (useSettings) {
                            PermissionUtil.openAppSettings(context)
                        } else {
                            permissionLauncher.launch(
                                PermissionUtil.permissionsForMode(ConversationInputMode.TRANSCRIPTION),
                            )
                        }
                    },
                ) {
                    Text(if (useSettings) "Open Settings" else "Grant permissions")
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

private data class FaceDirectionEntry(
    val faceId: String,
    val label: String,
    val snippet: String?,
    val angleDegrees: Float,
    val closeness: Float,
    val isTalking: Boolean,
)

private fun buildFaceEntries(
    trackedFaces: List<TrackedFace>,
    speakerBubbles: List<SpeechBubbleData>,
): List<FaceDirectionEntry> {
    if (trackedFaces.isEmpty()) return emptyList()

    val latestBubbleByFace = speakerBubbles
        .filter { it.anchorFaceId != null && it.text.isNotBlank() }
        .groupBy { it.anchorFaceId!! }
        .mapValues { (_, bubbles) -> bubbles.maxByOrNull { it.timestamp } }

    val maxArea = trackedFaces.maxOfOrNull { it.area }?.coerceAtLeast(0.0001f) ?: 1f

    return trackedFaces
        .sortedBy { it.boundingBox.centerX() }
        .mapIndexed { index, face ->
            val bubble = latestBubbleByFace[face.trackId]
            val normalizedArea = (face.area / maxArea).coerceIn(0.15f, 1f)
            FaceDirectionEntry(
                faceId = face.trackId,
                label = bubble?.speakerLabel ?: "Face ${index + 1}",
                snippet = bubble?.translatedText ?: bubble?.text,
                angleDegrees = FaceAngleMapping.rectCenterToAngleDegrees(face.boundingBox),
                closeness = sqrt(normalizedArea),
                isTalking = bubble?.isVisible == true,
            )
        }
}

@Composable
private fun SoundDirectionTopBar(
    connectionStatus: String,
    isStereoCapture: Boolean,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(46.dp)
                .border(1.dp, HairlineWhite, CircleShape)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        Surface(
            shape = RoundedCornerShape(Radii.pill),
            color = if (isStereoCapture) StatusLiveGreenSoft else MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, HairlineWhite),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (isStereoCapture) StatusLiveGreen else StatusWarnAmber,
                            CircleShape,
                        ),
                )
                Text(
                    text = if (isStereoCapture) {
                        "Stereo direction live"
                    } else {
                        "Mono fallback"
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(Radii.pill),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, HairlineWhite),
        ) {
            Text(
                text = connectionStatus,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun SoundRadar(
    faceEntries: List<FaceDirectionEntry>,
    audioDirection: AudioDirection,
    isStereoCapture: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "soundRadar")
    val sweepDegrees by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "radarSweep",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "radarPulse",
    )

    Canvas(modifier = modifier) {
        val center = center
        val radius = size.minDimension * 0.39f
        val midRadius = radius * 0.68f
        val innerRadius = radius * 0.38f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    BrandGlow.copy(alpha = 0.55f * pulse),
                    Color.Transparent,
                ),
                center = center,
                radius = radius * 1.2f,
            ),
            radius = radius * 1.15f,
            center = center,
        )

        drawCircle(
            color = Color.White.copy(alpha = 0.05f),
            radius = radius,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.06f),
            radius = midRadius,
            center = center,
            style = Stroke(width = 1.5.dp.toPx()),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.07f),
            radius = innerRadius,
            center = center,
            style = Stroke(width = 1.5.dp.toPx()),
        )

        drawLine(
            color = BrandAccent.copy(alpha = 0.18f),
            start = center,
            end = center + polarOffset(angleDegrees = sweepDegrees - 90f, radius = radius),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = BrandAccent.copy(alpha = 0.85f),
            radius = 7.dp.toPx() * pulse,
            center = center,
        )

        for (entry in faceEntries) {
            val radarAngle = displayAngleForRadar(entry.angleDegrees)
            val thickness = lerp(3.dp.toPx(), 8.dp.toPx(), entry.closeness)
            val arrowLength = lerp(innerRadius * 0.85f, radius * 0.96f, entry.closeness)
            val arrowStart = center + polarOffset(radarAngle, innerRadius * 0.58f)
            val arrowEnd = center + polarOffset(radarAngle, arrowLength)
            val faceColor = if (entry.isTalking) StatusLiveGreen else Color.White.copy(alpha = 0.82f)

            drawArrow(
                start = arrowStart,
                end = arrowEnd,
                color = faceColor,
                strokeWidth = thickness,
                headSize = lerp(10.dp.toPx(), 18.dp.toPx(), entry.closeness),
                alpha = if (entry.isTalking) 1f else 0.76f,
            )
            drawCircle(
                color = faceColor.copy(alpha = 0.22f),
                radius = lerp(8.dp.toPx(), 16.dp.toPx(), entry.closeness),
                center = arrowEnd,
            )
        }

        if (isStereoCapture && audioDirection.confidence > 0.08f) {
            val audioAngle = displayAngleForRadar(audioDirection.angleDegrees)
            val audioConfidence = audioDirection.confidence.coerceIn(0f, 1f)
            drawArrow(
                start = center + polarOffset(audioAngle, innerRadius * 0.28f),
                end = center + polarOffset(audioAngle, radius * (0.82f + 0.16f * audioConfidence)),
                color = BrandAccent,
                strokeWidth = lerp(3.dp.toPx(), 9.dp.toPx(), audioConfidence),
                headSize = lerp(12.dp.toPx(), 22.dp.toPx(), audioConfidence),
                alpha = 0.92f,
            )
        }

        drawArc(
            color = Color.White.copy(alpha = 0.10f),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f),
            ),
        )
    }
}

@Composable
private fun DirectionStatCard(
    title: String,
    value: String,
    detail: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radii.l),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, HairlineWhite),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(accent, CircleShape),
                )
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
            Text(
                text = detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DirectionStatusCard(
    audioDirection: AudioDirection,
    isStereoCapture: Boolean,
    overlayEnabled: Boolean,
) {
    val accentColor by animateColorAsState(
        targetValue = if (audioDirection.isSpeech && isStereoCapture) BrandAccent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(240),
        label = "directionStatusAccent",
    )
    val angleText = remember(audioDirection.angleDegrees) {
        val rounded = audioDirection.angleDegrees.toInt()
        when {
            rounded > 4 -> "$rounded° right"
            rounded < -4 -> "${-rounded}° left"
            else -> "centered"
        }
    }

    Surface(
        shape = RoundedCornerShape(Radii.l),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, HairlineWhite),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Icon(
                    imageVector = Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Live audio pull",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                )
            }
            Text(
                text = if (!isStereoCapture) {
                    "This route is running in mono fallback, so face arrows stay active but the live audio arrow is paused."
                } else {
                    "Audio energy is leaning $angleText with ${(audioDirection.confidence * 100).toInt()}% confidence."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (overlayEnabled) {
                    "Conversation overlays are still enabled, so the radar and the regular anchored captions can agree."
                } else {
                    "Overlays are hidden, but the radar still tracks face position and directional pull."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun FaceDirectionRow(entry: FaceDirectionEntry) {
    val chipColor by animateColorAsState(
        targetValue = if (entry.isTalking) StatusLiveGreenSoft else MaterialTheme.colorScheme.surface,
        animationSpec = tween(220),
        label = "faceDirectionChip",
    )
    val emphasis by animateFloatAsState(
        targetValue = if (entry.isTalking) 1f else 0.72f,
        animationSpec = spring(stiffness = 240f, dampingRatio = 0.92f),
        label = "faceDirectionEmphasis",
    )

    Surface(
        shape = RoundedCornerShape(Radii.l),
        color = chipColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, HairlineWhite),
        tonalElevation = if (entry.isTalking) 6.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size((14.dp + (10.dp * entry.closeness)).coerceAtLeast(14.dp))
                    .background(
                        if (entry.isTalking) StatusLiveGreen.copy(alpha = emphasis) else BrandAccent.copy(alpha = emphasis),
                        CircleShape,
                    ),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = entry.label,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Surface(
                        shape = RoundedCornerShape(Radii.pill),
                        color = MaterialTheme.colorScheme.background.copy(alpha = 0.82f),
                    ) {
                        Text(
                            text = angleLabel(entry.angleDegrees),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                Text(
                    text = entry.snippet ?: if (entry.isTalking) "Speaking now" else "Tracked and ready",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = "${(entry.closeness * 100).toInt()}%",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

@Composable
private fun EmptyDirectionState() {
    Surface(
        shape = RoundedCornerShape(Radii.xl),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, HairlineWhite),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text(
                text = "No faces locked yet",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = "Point the back camera at the group and start talking. Face arrows will appear first, then the audio pull will settle on top.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

private fun DrawScope.drawArrow(
    start: Offset,
    end: Offset,
    color: Color,
    strokeWidth: Float,
    headSize: Float,
    alpha: Float,
) {
    drawLine(
        color = color.copy(alpha = alpha),
        start = start,
        end = end,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )

    val angle = kotlin.math.atan2(end.y - start.y, end.x - start.x)
    val left = Offset(
        x = end.x - headSize * cos(angle - PI / 6).toFloat(),
        y = end.y - headSize * sin(angle - PI / 6).toFloat(),
    )
    val right = Offset(
        x = end.x - headSize * cos(angle + PI / 6).toFloat(),
        y = end.y - headSize * sin(angle + PI / 6).toFloat(),
    )
    val path = Path().apply {
        moveTo(end.x, end.y)
        lineTo(left.x, left.y)
        lineTo(right.x, right.y)
        close()
    }
    drawPath(
        path = path,
        color = color.copy(alpha = alpha),
    )
}

private fun polarOffset(angleDegrees: Float, radius: Float): Offset {
    val radians = Math.toRadians(angleDegrees.toDouble())
    return Offset(
        x = (cos(radians) * radius).toFloat(),
        y = (sin(radians) * radius).toFloat(),
    )
}

private fun displayAngleForRadar(faceAngleDegrees: Float): Float {
    val normalized = (faceAngleDegrees / (FaceAngleMapping.DEFAULT_HORIZONTAL_FOV_DEGREES / 2f))
        .coerceIn(-1f, 1f)
    return -90f + (normalized * 84f)
}

private fun lerp(start: Float, end: Float, fraction: Float): Float {
    return start + ((end - start) * fraction.coerceIn(0f, 1f))
}

private fun angleLabel(angleDegrees: Float): String {
    val rounded = angleDegrees.toInt()
    return when {
        rounded > 4 -> "$rounded° right"
        rounded < -4 -> "${-rounded}° left"
        else -> "front center"
    }
}
