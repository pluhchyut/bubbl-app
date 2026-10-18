package com.talksync.ui.screens

// ar convo screen. captions get pinned onto tracked faces instead of becoming a boring list.

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FaceRetouchingNatural
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LayersClear
import androidx.compose.material.icons.outlined.Translate
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.talksync.audio.toWordVolumes
import com.talksync.data.AppSettings
import com.talksync.model.ConversationInputMode
import com.talksync.model.EmphasisToneMode
import com.talksync.model.SpeechBubbleData
import com.talksync.ui.components.AslStatusPill
import com.talksync.ui.components.ConversationModeToggle
import com.talksync.ui.components.EmotionIndicator
import com.talksync.ui.components.SpeechBubble
import com.talksync.ui.components.RecentCaptionSideBar
import com.talksync.util.PermissionUtil
import com.talksync.util.findActivity
import com.talksync.viewmodel.ConversationViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt

@Composable
fun ArConversationScreen(
    conversationViewModel: ConversationViewModel,
    settings: AppSettings,
    onBack: () -> Unit,
    onHistory: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val bubbleData by conversationViewModel.bubbleData.collectAsStateWithLifecycle()
    val recentSideCaptions by conversationViewModel.recentSideCaptions.collectAsStateWithLifecycle()
    val speakerBubbles by conversationViewModel.speakerBubbles.collectAsStateWithLifecycle()
    val overlayEnabled by conversationViewModel.overlayEnabled.collectAsStateWithLifecycle()
    val faceBoundingBox by conversationViewModel.faceBoundingBox.collectAsStateWithLifecycle()
    val trackedFaces by conversationViewModel.trackedFaces.collectAsStateWithLifecycle()
    val cameraLensFacing by conversationViewModel.cameraLensFacing.collectAsStateWithLifecycle()
    val inputMode by conversationViewModel.inputMode.collectAsStateWithLifecycle()
    val zoomState by conversationViewModel.cameraZoomState.collectAsStateWithLifecycle()
    val connectionStatus by conversationViewModel.connectionStatus.collectAsStateWithLifecycle()
    val statusMessage by conversationViewModel.statusMessage.collectAsStateWithLifecycle()
    val aslStatusText by conversationViewModel.aslStatusText.collectAsStateWithLifecycle()
    val emphasisEnabled by conversationViewModel.emphasisEnabled.collectAsStateWithLifecycle()
    val emphasisColorEnabled by conversationViewModel.emphasisColorEnabled.collectAsStateWithLifecycle()
    val emphasisColorIntensity by conversationViewModel.emphasisColorIntensity.collectAsStateWithLifecycle()
    val baseTextSize by conversationViewModel.baseTextSize.collectAsStateWithLifecycle()
    val showOriginalLanguage by conversationViewModel.showOriginalLanguage.collectAsStateWithLifecycle()
    val uiChromeVisible by conversationViewModel.uiChromeVisible.collectAsStateWithLifecycle()
    val activeSpeakerCount by conversationViewModel.activeSpeakerCount.collectAsStateWithLifecycle()
    val debugModeEnabled = settings.debugModeEnabled
    val emphasisToneMode = EmphasisToneMode.fromValue(settings.emphasisToneMode)

    val previewView = remember { PreviewView(context) }
    val motionOffsetPx by rememberDeviceMotionOffset(enabled = overlayEnabled)

    var hasPermissions by rememberSaveable(inputMode) {
        mutableStateOf(PermissionUtil.hasPermissionsForMode(context, inputMode))
    }
    var showPermissionDialog by rememberSaveable { mutableStateOf(false) }
    var requestedOnce by rememberSaveable { mutableStateOf(false) }
    val bubbleRowSizes = remember { mutableStateMapOf<String, IntSize>() }
    val smoothedBubbleOffsets = remember { mutableStateMapOf<String, IntOffset>() }

    val scaleZoomState = rememberUpdatedState(newValue = conversationViewModel::adjustZoomByScale)
    val scaleGestureDetector = remember(context) {
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scaleZoomState.value(detector.scaleFactor)
                    return true
                }
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPermissions = PermissionUtil.hasPermissionsForMode(context, inputMode)
        showPermissionDialog = !hasPermissions
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermissions = PermissionUtil.hasPermissionsForMode(context, inputMode)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(inputMode) {
        hasPermissions = PermissionUtil.hasPermissionsForMode(context, inputMode)
    }

    LaunchedEffect(hasPermissions) {
        if (!hasPermissions && !requestedOnce) {
            requestedOnce = true
            permissionLauncher.launch(PermissionUtil.permissionsForMode(inputMode))
        }
    }

    DisposableEffect(previewView, scaleGestureDetector) {
        val touchListener = View.OnTouchListener { _, motionEvent ->
            scaleGestureDetector.onTouchEvent(motionEvent)
            if (motionEvent.action == MotionEvent.ACTION_UP) {
                conversationViewModel.dismissLiveSpeechBubble()
                conversationViewModel.revealUiChromeTemporarily()
            }
            true
        }
        previewView.setOnTouchListener(touchListener)
        onDispose {
            previewView.setOnTouchListener(null)
        }
    }

    DisposableEffect(hasPermissions, previewView, lifecycleOwner, cameraLensFacing, inputMode) {
        if (hasPermissions) {
            conversationViewModel.bindCamera(
                previewView = previewView,
                lifecycleOwner = lifecycleOwner,
                lensFacing = cameraLensFacing,
                inputMode = inputMode,
                useUltraWide = settings.useUltraWideCamera,
            )
        }
        onDispose {
            conversationViewModel.unbindCamera()
        }
    }

    DisposableEffect(hasPermissions) {
        if (hasPermissions) {
            conversationViewModel.startConversation()
        }
        onDispose {
            conversationViewModel.stopConversation()
        }
    }

    LaunchedEffect(previewView, hasPermissions, inputMode) {
        while (isActive && hasPermissions && inputMode == ConversationInputMode.ASL_TRANSLATION) {
            previewView.bitmap?.let(conversationViewModel::onAslPreviewBitmap)
            delay(180L)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val isLandscape = maxWidth > maxHeight
        val statusBarInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        val topBarPadding = statusBarInset + if (isLandscape) 12.dp else 20.dp
        val toggleTopPadding = when {
            isLandscape && debugModeEnabled -> statusBarInset + 80.dp
            isLandscape -> statusBarInset + 40.dp
            debugModeEnabled -> statusBarInset + 100.dp
            else -> statusBarInset + 58.dp
        }
        val debugStatusTopPadding = statusBarInset + if (isLandscape) 40.dp else 58.dp
        val aslTopPadding = when {
            isLandscape && debugModeEnabled -> statusBarInset + 122.dp
            isLandscape -> statusBarInset + 78.dp
            debugModeEnabled -> statusBarInset + 148.dp
            else -> statusBarInset + 106.dp
        }
        val activeSpeakerTopPadding = statusBarInset + if (isLandscape) 92.dp else 120.dp
        val bottomControlPadding = if (isLandscape) 12.dp else 20.dp
        val multiSpeakerBubbleWidth = if (isLandscape) {
            this@BoxWithConstraints.maxWidth * 0.38f
        } else {
            this@BoxWithConstraints.maxWidth * 0.38f
        }
        val singleSpeakerBubbleWidth = if (isLandscape) {
            this@BoxWithConstraints.maxWidth * 0.64f
        } else {
            this@BoxWithConstraints.maxWidth * 0.7f
        }

        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        if (!uiChromeVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            conversationViewModel.dismissLiveSpeechBubble()
                            conversationViewModel.revealUiChromeTemporarily()
                        }
                    },
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = uiChromeVisible,
            enter = androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(240),
            ) + androidx.compose.animation.slideInVertically(
                animationSpec = androidx.compose.animation.core.tween(240),
            ) { -it / 3 },
            exit = androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(220),
            ) + androidx.compose.animation.slideOutVertically(
                animationSpec = androidx.compose.animation.core.tween(220),
            ) { -it / 5 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topBarPadding),
        ) {
            ArModeBadge()
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = uiChromeVisible,
            enter = androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(240),
            ) + androidx.compose.animation.slideInVertically(
                animationSpec = androidx.compose.animation.core.tween(240),
            ) { -it / 3 },
            exit = androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(220),
            ) + androidx.compose.animation.slideOutVertically(
                animationSpec = androidx.compose.animation.core.tween(220),
            ) { -it / 5 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = toggleTopPadding),
        ) {
            ConversationModeToggle(
                selectedMode = inputMode,
                onModeSelected = conversationViewModel::setInputMode,
            )
        }

        if (debugModeEnabled) {
            androidx.compose.animation.AnimatedVisibility(
                visible = uiChromeVisible,
                enter = androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(240),
                ) + androidx.compose.animation.slideInVertically(
                    animationSpec = androidx.compose.animation.core.tween(240),
                ) { -it / 3 },
                exit = androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(220),
                ) + androidx.compose.animation.slideOutVertically(
                    animationSpec = androidx.compose.animation.core.tween(220),
                ) { -it / 5 },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = debugStatusTopPadding),
            ) {
                ArFaceDetectionDebugStatus(hasFace = trackedFaces.isNotEmpty())
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = uiChromeVisible,
                enter = androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(240),
                ) + androidx.compose.animation.slideInVertically(
                    animationSpec = androidx.compose.animation.core.tween(240),
                ) { -it / 3 },
                exit = androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(220),
                ) + androidx.compose.animation.slideOutVertically(
                    animationSpec = androidx.compose.animation.core.tween(220),
                ) { -it / 5 },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = topBarPadding, start = 16.dp),
            ) {
                ArConnectionStatusPill(
                    connectionStatus = connectionStatus,
                    detail = statusMessage,
                )
            }
        }

        if (inputMode == ConversationInputMode.ASL_TRANSLATION) {
            androidx.compose.animation.AnimatedVisibility(
                visible = uiChromeVisible,
                enter = androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(240),
                ) + androidx.compose.animation.slideInVertically(
                    animationSpec = androidx.compose.animation.core.tween(240),
                ) { -it / 3 },
                exit = androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(220),
                ) + androidx.compose.animation.slideOutVertically(
                    animationSpec = androidx.compose.animation.core.tween(220),
                ) { -it / 5 },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = aslTopPadding),
            ) {
                AslStatusPill(text = aslStatusText)
            }
        }

        if (activeSpeakerCount > 1) {
            androidx.compose.animation.AnimatedVisibility(
                visible = uiChromeVisible,
                enter = androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(240),
                ) + androidx.compose.animation.slideInVertically(
                    animationSpec = androidx.compose.animation.core.tween(240),
                ) { -it / 3 },
                exit = androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(220),
                ) + androidx.compose.animation.slideOutVertically(
                    animationSpec = androidx.compose.animation.core.tween(220),
                ) { -it / 5 },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = activeSpeakerTopPadding),
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                ) {
                    Text(
                        text = "$activeSpeakerCount people speaking",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = uiChromeVisible,
            enter = androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(240),
            ) + androidx.compose.animation.slideInVertically(
                animationSpec = androidx.compose.animation.core.tween(240),
            ) { -it / 3 },
            exit = androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(220),
            ) + androidx.compose.animation.slideOutVertically(
                animationSpec = androidx.compose.animation.core.tween(220),
            ) { -it / 5 },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = topBarPadding, end = 16.dp),
        ) {
            ArZoomControlStrip(
                zoomRatio = zoomState.zoomRatio,
                maxZoomRatio = zoomState.maxZoomRatio,
                onZoomOut = { conversationViewModel.nudgeZoom(-0.25f) },
                onZoomIn = { conversationViewModel.nudgeZoom(0.25f) },
                onReset = conversationViewModel::resetZoom,
            )
        }

        if (debugModeEnabled) {
            trackedFaces.forEach { trackedFace ->
                val box = trackedFace.boundingBox
                val maxWidthPx = constraints.maxWidth.toFloat()
                val maxHeightPx = constraints.maxHeight.toFloat()
                val faceLeftPx = box.left * maxWidthPx
                val faceTopPx = box.top * maxHeightPx
                val faceWidthDp = with(density) { (box.width() * maxWidthPx).toDp() }
                val faceHeightDp = with(density) { (box.height() * maxHeightPx).toDp() }

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = faceLeftPx.roundToInt(),
                                y = faceTopPx.roundToInt(),
                            )
                        }
                        .size(width = faceWidthDp, height = faceHeightDp)
                        .border(
                            width = 3.dp,
                            color = Color(0xFF7CFF8A),
                            shape = RoundedCornerShape(18.dp),
                        )
                        .background(
                            color = Color(0x227CFF8A),
                            shape = RoundedCornerShape(18.dp),
                        ),
                )
            }
        }

        if (!hasPermissions) {
            ArPermissionFallback(
                inputMode = inputMode,
                onRequestPermissions = {
                    permissionLauncher.launch(PermissionUtil.permissionsForMode(inputMode))
                },
            )
        }

        val debugPlaceholderBubble = remember(
            trackedFaces,
            overlayEnabled,
            speakerBubbles,
            bubbleData,
            debugModeEnabled,
            inputMode,
        ) {
            if (
                debugModeEnabled &&
                overlayEnabled &&
                inputMode == ConversationInputMode.TRANSCRIPTION &&
                speakerBubbles.isEmpty() &&
                bubbleData.isVisible &&
                trackedFaces.isNotEmpty()
            ) {
                SpeechBubbleData(
                    id = "debug-placeholder",
                    speakerKey = SpeechBubbleData.DEFAULT_SPEAKER_KEY,
                    speakerLabel = "Speaker 1",
                    anchorFaceId = trackedFaces.first().trackId,
                    text = "...",
                    isPartial = false,
                    isVisible = true,
                    volumeLevel = bubbleData.volumeLevel,
                    wordVolumes = "...".toWordVolumes(
                        volumes = listOf(bubbleData.volumeLevel),
                        isFinal = true,
                    ),
                    emotionState = bubbleData.emotionState,
                    timestamp = System.currentTimeMillis(),
                )
            } else {
                null
            }
        }

        val bubblesToRender = remember(inputMode, speakerBubbles, bubbleData, debugPlaceholderBubble) {
            when (inputMode) {
                ConversationInputMode.TRANSCRIPTION -> {
                    if (speakerBubbles.isNotEmpty()) {
                        speakerBubbles
                    } else {
                        listOfNotNull(debugPlaceholderBubble)
                    }
                }

                ConversationInputMode.ASL_TRANSLATION -> {
                    listOfNotNull(
                        bubbleData.takeIf { it.text.isNotBlank() || (debugModeEnabled && it.isVisible) },
                    )
                }
            }
        }

        val recentPanelWidth = (maxWidth * 0.38f).coerceIn(140.dp, 220.dp)
        val reservedSideWidth = if (overlayEnabled && recentSideCaptions.isNotEmpty()) recentPanelWidth + 24.dp else 0.dp
        val maxLiveBubbleWidth = (maxWidth - reservedSideWidth - 48.dp).coerceAtLeast(120.dp)
        val multiSpeakerActive = inputMode == ConversationInputMode.TRANSCRIPTION &&
            bubblesToRender.map { it.speakerKey }.distinct().size > 1
        val anyForeignLanguageDetected = remember(speakerBubbles, bubbleData) {
            (speakerBubbles + listOfNotNull(bubbleData))
                .any { bubble ->
                    bubble.translatedText?.isNotBlank() == true
                }
        }
        val facesById = remember(trackedFaces) { trackedFaces.associateBy { it.trackId } }

        val bubbleLayouts = remember(
            reservedSideWidth,
            bubblesToRender,
            trackedFaces,
            bubbleRowSizes.toMap(),
            constraints.maxWidth,
            constraints.maxHeight,
            inputMode,
            motionOffsetPx,
            multiSpeakerActive,
        ) {
            val fallbackBubbleSize = IntSize(
                width = with(density) { 200.dp.roundToPx() },
                height = with(density) { 72.dp.roundToPx() },
            )
            val renderModels = bubblesToRender.map { bubble ->
                BubbleRenderModel(
                    bubble = bubble,
                    anchorBounds = (bubble.lipDrivenAnchorId ?: bubble.anchorFaceId)?.let { facesById[it]?.boundingBox }
                        ?: faceBoundingBox.takeIf { inputMode == ConversationInputMode.ASL_TRANSLATION },
                    bubbleSize = bubbleRowSizes[bubble.id] ?: fallbackBubbleSize,
                )
            }
            resolveBubbleLayouts(
                models = renderModels,
                maxWidthPx = constraints.maxWidth.toFloat(),
                reservedRightPx = with(density) { reservedSideWidth.toPx() },
                maxHeightPx = constraints.maxHeight.toFloat(),
                marginPx = with(density) { 16.dp.toPx() },
                minWidthPx = with(density) { 120.dp.toPx() },
                defaultTopFraction = 0.22f,
                topSpacingPx = with(density) { 18.dp.toPx() },
                sideLeadPx = with(density) { 26.dp.toPx() },
                motionOffsetPx = if (multiSpeakerActive) {
                    IntOffset.Zero
                } else {
                    IntOffset(
                        x = (motionOffsetPx.x * if (trackedFaces.isNotEmpty()) 1f else 0.65f).roundToInt(),
                        y = (motionOffsetPx.y * if (trackedFaces.isNotEmpty()) 1f else 0.65f).roundToInt(),
                    )
                },
            )
        }

        LaunchedEffect(overlayEnabled, bubbleLayouts, multiSpeakerActive) {
            if (!overlayEnabled) {
                smoothedBubbleOffsets.clear()
                return@LaunchedEffect
            }

            val activeBubbleIds = bubbleLayouts.map { it.bubble.id }.toSet()
            smoothedBubbleOffsets.keys.retainAll(activeBubbleIds)

            bubbleLayouts.forEach { layout ->
                val previousOffset = smoothedBubbleOffsets[layout.bubble.id]
                smoothedBubbleOffsets[layout.bubble.id] = if (previousOffset == null) {
                    layout.offset
                } else if (multiSpeakerActive) {
                    IntOffset(
                        x = smoothRecoilPosition(
                            current = previousOffset.x.toFloat(),
                            target = layout.offset.x.toFloat(),
                        ).roundToInt(),
                        y = smoothRecoilPosition(
                            current = previousOffset.y.toFloat(),
                            target = layout.offset.y.toFloat(),
                        ).roundToInt(),
                    )
                } else {
                    IntOffset(
                        x = smoothRecoilPosition(
                            current = previousOffset.x.toFloat(),
                            target = layout.offset.x.toFloat(),
                        ).roundToInt(),
                        y = smoothRecoilPosition(
                            current = previousOffset.y.toFloat(),
                            target = layout.offset.y.toFloat(),
                        ).roundToInt(),
                    )
                }
            }
        }

        if (overlayEnabled && bubbleLayouts.isNotEmpty()) {
            bubbleLayouts.forEach { layout ->
                val anchorBounds = (layout.bubble.lipDrivenAnchorId ?: layout.bubble.anchorFaceId)?.let { facesById[it]?.boundingBox } ?: faceBoundingBox.takeIf { inputMode == ConversationInputMode.ASL_TRANSLATION }
                val animatedBubbleX by animateFloatAsState(
                    targetValue = (smoothedBubbleOffsets[layout.bubble.id] ?: layout.offset).x.toFloat(),
                    animationSpec = spring(
                        dampingRatio = if (multiSpeakerActive) 0.98f else 0.94f,
                        stiffness = if (multiSpeakerActive) 82f else 110f,
                    ),
                    label = "arBubbleOffsetX-${layout.bubble.id}",
                )
                val animatedBubbleY by animateFloatAsState(
                    targetValue = (smoothedBubbleOffsets[layout.bubble.id] ?: layout.offset).y.toFloat(),
                    animationSpec = spring(
                        dampingRatio = if (multiSpeakerActive) 0.99f else 0.96f,
                        stiffness = if (multiSpeakerActive) 78f else 105f,
                    ),
                    label = "arBubbleOffsetY-${layout.bubble.id}",
                )
                val bubbleScaleTarget = anchorBounds?.let { bounds ->
                    val normalized = ((bounds.height() - 0.18f) / 0.24f).coerceIn(0f, 1f)
                    0.96f + (normalized * 0.12f)
                } ?: 0.98f
                val bubbleScale by animateFloatAsState(
                    targetValue = bubbleScaleTarget,
                    animationSpec = spring(
                        dampingRatio = 0.9f,
                        stiffness = 120f,
                    ),
                    label = "arBubbleScale-${layout.bubble.id}",
                )

                val bubbleHeightPx = (bubbleRowSizes[layout.bubble.id]?.height
                    ?: with(density) { 72.dp.roundToPx() }).toFloat()
                val bubbleWidthPx = (bubbleRowSizes[layout.bubble.id]?.width ?: 0)
                    .toFloat()
                    .coerceAtLeast(with(density) { 120.dp.toPx() })
                val bubbleBottomCenter = Offset(
                    x = animatedBubbleX + (bubbleWidthPx / 2f),
                    y = animatedBubbleY + bubbleHeightPx,
                )
                val reticlePoint = anchorBounds?.let { bounds ->
                    Offset(
                        x = (bounds.centerX() * constraints.maxWidth) + (motionOffsetPx.x * 0.18f),
                        y = ((bounds.top + (bounds.height() * 0.34f)) * constraints.maxHeight) + (motionOffsetPx.y * 0.12f),
                    )
                }

                ArAnchorGuide(
                    faceAnchorPoint = reticlePoint,
                    bubbleBottomCenter = bubbleBottomCenter,
                    visible = reticlePoint != null,
                    modifier = Modifier.fillMaxSize(),
                )

                Column(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = animatedBubbleX.roundToInt(),
                                y = animatedBubbleY.roundToInt(),
                            )
                        }
                        .graphicsLayer {
                            scaleX = bubbleScale
                            scaleY = bubbleScale
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                        .animateContentSize(
                            animationSpec = spring(
                                dampingRatio = 0.96f,
                                stiffness = 180f,
                            ),
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.onGloballyPositioned {
                            bubbleRowSizes[layout.bubble.id] = it.size
                        },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        SpeechBubble(
                            bubbleData = layout.bubble,
                            wordVolumes = layout.bubble.wordVolumes,
                            baseTextSizeSp = baseTextSize,
                            bubbleOpacity = settings.bubbleOpacity,
                            emphasisEnabled = emphasisEnabled && !multiSpeakerActive,
                            emphasisColorIntensity = if (emphasisColorEnabled && !multiSpeakerActive) {
                                emphasisColorIntensity
                            } else {
                                0f
                            },
                            emphasisToneMode = emphasisToneMode,
                            emphasisToneFadeSeconds = settings.emphasisToneFadeSeconds,
                            emphasisToneResponse = settings.emphasisToneResponse,
                            emphasisQuietFloor = settings.emphasisQuietFloor,
                            emphasisLoudTrigger = settings.emphasisLoudTrigger,
                            showOriginalLanguage = showOriginalLanguage,
                            maxBubbleWidth = (if (multiSpeakerActive) {
                                multiSpeakerBubbleWidth
                            } else {
                                singleSpeakerBubbleWidth
                            }).coerceAtMost(maxLiveBubbleWidth),
                        )
                        EmotionIndicator(
                            emotionState = layout.bubble.emotionState,
                            enabled = (settings.showEmotionIndicators || debugModeEnabled) && !multiSpeakerActive,
                            showNeutralDebug = debugModeEnabled && !multiSpeakerActive,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        RecentCaptionSideBar(
            entries = recentSideCaptions,
            enabled = overlayEnabled && inputMode == ConversationInputMode.TRANSCRIPTION,
            panelWidth = recentPanelWidth,
            maxPanelHeight = maxHeight * 0.55f,
            showOriginalLanguage = showOriginalLanguage,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
        )

        androidx.compose.animation.AnimatedVisibility(
            visible = uiChromeVisible,
            enter = androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(260),
            ) + androidx.compose.animation.slideInVertically(
                animationSpec = androidx.compose.animation.core.tween(260),
            ) { it / 2 },
            exit = androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(200),
            ) + androidx.compose.animation.slideOutVertically(
                animationSpec = androidx.compose.animation.core.tween(200),
            ) { it / 3 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomControlPadding),
        ) {
            Surface(
                shape = RoundedCornerShape(30.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                tonalElevation = 10.dp,
                shadowElevation = 18.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArControlPillButton(
                        onClick = onBack,
                        contentDescription = "Back",
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    )
                    ArControlPillButton(
                        onClick = conversationViewModel::toggleCameraLens,
                        contentDescription = if (cameraLensFacing == CameraSelector.LENS_FACING_BACK) {
                            "Switch to front camera"
                        } else {
                            "Switch to back camera"
                        },
                        icon = Icons.Outlined.Cameraswitch,
                    )
                    ArControlPillButton(
                        onClick = conversationViewModel::toggleOverlay,
                        contentDescription = if (overlayEnabled) "Hide overlays" else "Show overlays",
                        icon = if (overlayEnabled) Icons.Outlined.Layers else Icons.Outlined.LayersClear,
                    )
                    if (anyForeignLanguageDetected) {
                        ArControlPillButton(
                            onClick = conversationViewModel::toggleShowOriginalLanguage,
                            contentDescription = if (showOriginalLanguage) {
                                "Show translation"
                            } else {
                                "Show original language"
                            },
                            icon = if (showOriginalLanguage) {
                                Icons.Outlined.Translate
                            } else {
                                Icons.Outlined.Language
                            },
                        )
                    }
                    ArControlPillButton(
                        onClick = onHistory,
                        contentDescription = "Open history",
                        icon = Icons.Outlined.History,
                    )
                }
            }
        }
    }

    if (showPermissionDialog) {
        val activity = context.findActivity()
        val useSettings = activity?.let { PermissionUtil.shouldShowPermissionRationale(it, inputMode) } == false
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Permissions required") },
            text = {
                Text(
                    when (inputMode) {
                        ConversationInputMode.TRANSCRIPTION ->
                            "bubbl! needs camera and microphone access to keep the AR-style caption bubble near the speaker."

                        ConversationInputMode.ASL_TRANSLATION ->
                            "bubbl! needs camera access to keep the AR-style bubble live while it translates visible hand signs."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermissionDialog = false
                        if (useSettings) {
                            PermissionUtil.openAppSettings(context)
                        } else {
                            permissionLauncher.launch(PermissionUtil.permissionsForMode(inputMode))
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

@Composable
private fun ArModeBadge(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            com.talksync.ui.theme.HairlineWhite,
        ),
    ) {
        Text(
            text = "AR experimental",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun ArPermissionFallback(
    inputMode: ConversationInputMode,
    onRequestPermissions: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.82f))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = when (inputMode) {
                ConversationInputMode.TRANSCRIPTION -> "Camera and microphone access are required."
                ConversationInputMode.ASL_TRANSLATION -> "Camera access is required."
            },
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = when (inputMode) {
                ConversationInputMode.TRANSCRIPTION ->
                    "Grant access to place the AR-style caption bubble near the speaker."

                ConversationInputMode.ASL_TRANSLATION ->
                    "Grant camera access to translate visible hand signs while the AR-style bubble stays locked in view."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(20.dp))
        TextButton(onClick = onRequestPermissions) {
            Text("Grant permissions")
        }
    }
}

@Composable
private fun ArControlPillButton(
    onClick: () -> Unit,
    contentDescription: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(46.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ArFaceDetectionDebugStatus(
    hasFace: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            com.talksync.ui.theme.HairlineWhite,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = if (hasFace) {
                            com.talksync.ui.theme.StatusLiveGreen
                        } else {
                            com.talksync.ui.theme.StatusWarnAmber
                        },
                        shape = CircleShape,
                    ),
            )
            Icon(
                imageVector = Icons.Outlined.FaceRetouchingNatural,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = if (hasFace) "AR anchor locked" else "Looking for face",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ArConnectionStatusPill(
    connectionStatus: String,
    detail: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            com.talksync.ui.theme.HairlineWhite,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(arConnectionStatusColor(connectionStatus), CircleShape),
            )
            Column {
                Text(
                    text = connectionStatus,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                )
                detail?.takeIf { connectionStatus != "Live" }?.let {
                    Text(
                        text = it.removePrefix("Connection error: "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun arConnectionStatusColor(connectionStatus: String): Color {
    return when (connectionStatus) {
        "Live" -> com.talksync.ui.theme.StatusLiveGreen
        "Ready", "Connecting...", "ASL beta" -> com.talksync.ui.theme.BrandAccent
        else -> com.talksync.ui.theme.StatusAlertCoral
    }
}

@Composable
private fun ArZoomControlStrip(
    zoomRatio: Float,
    maxZoomRatio: Float,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (maxZoomRatio <= 1.05f) {
        return
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            com.talksync.ui.theme.HairlineWhite,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onZoomOut,
                enabled = zoomRatio > 1.02f,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Zoom out" },
            ) {
                Text(
                    text = "-",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            TextButton(
                onClick = onReset,
                modifier = Modifier
                    .height(48.dp)
                    .semantics { contentDescription = "Reset zoom" },
            ) {
                Text(
                    text = String.format(Locale.US, "%.1fx", zoomRatio),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            IconButton(
                onClick = onZoomIn,
                enabled = zoomRatio < (maxZoomRatio - 0.02f),
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Zoom in" },
            ) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ArAnchorGuide(
    faceAnchorPoint: Offset?,
    bubbleBottomCenter: Offset?,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible || faceAnchorPoint == null || bubbleBottomCenter == null) {
        return
    }

    val transition = rememberInfiniteTransition(label = "arReticlePulse")
    val pulseRadius by transition.animateFloat(
        initialValue = 16f,
        targetValue = 28f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "arReticleRadius",
    )

    Canvas(modifier = modifier) {
        drawLine(
            color = Color.White.copy(alpha = 0.30f),
            start = bubbleBottomCenter,
            end = faceAnchorPoint,
            strokeWidth = 3f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f), 0f),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.18f),
            center = faceAnchorPoint,
            radius = pulseRadius,
            style = Stroke(width = 2.5f),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.55f),
            center = faceAnchorPoint,
            radius = 14f,
            style = Stroke(width = 3f),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.18f),
            center = faceAnchorPoint,
            radius = 5f,
        )
    }
}

@Composable
private fun rememberDeviceMotionOffset(
    enabled: Boolean,
): State<Offset> {
    val context = LocalContext.current
    val density = LocalDensity.current
    val maxTranslationPx = with(density) { 22.dp.toPx() }
    val motionOffset = remember { mutableStateOf(Offset.Zero) }
    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val rotationSensor = remember(sensorManager) {
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    DisposableEffect(enabled, sensorManager, rotationSensor, maxTranslationPx) {
        if (!enabled || rotationSensor == null) {
            motionOffset.value = Offset.Zero
            return@DisposableEffect onDispose { }
        }

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        var baselinePitch: Float? = null
        var baselineRoll: Float? = null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)

                val pitch = orientation[1]
                val roll = orientation[2]
                val currentBasePitch = baselinePitch ?: pitch.also { baselinePitch = it }
                val currentBaseRoll = baselineRoll ?: roll.also { baselineRoll = it }

                val deltaPitch = wrapAngle(pitch - currentBasePitch)
                val deltaRoll = wrapAngle(roll - currentBaseRoll)
                val target = Offset(
                    x = (-deltaRoll * maxTranslationPx * 1.9f).coerceIn(-maxTranslationPx, maxTranslationPx),
                    y = (deltaPitch * maxTranslationPx * 2.1f).coerceIn(-maxTranslationPx, maxTranslationPx),
                )

                val current = motionOffset.value
                motionOffset.value = Offset(
                    x = current.x + ((target.x - current.x) * 0.16f),
                    y = current.y + ((target.y - current.y) * 0.16f),
                )

                baselinePitch = currentBasePitch + (wrapAngle(pitch - currentBasePitch) * 0.015f)
                baselineRoll = currentBaseRoll + (wrapAngle(roll - currentBaseRoll) * 0.015f)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            sensorManager.unregisterListener(listener)
            motionOffset.value = Offset.Zero
        }
    }

    return motionOffset
}

private fun wrapAngle(angle: Float): Float {
    val pi = PI.toFloat()
    val twoPi = (PI * 2.0).toFloat()
    var wrapped = angle
    while (wrapped > pi) {
        wrapped -= twoPi
    }
    while (wrapped < -pi) {
        wrapped += twoPi
    }
    return wrapped
}

private fun smoothRecoilPosition(
    current: Float,
    target: Float,
): Float {
    val distance = kotlin.math.abs(target - current)
    val followAmount = 0.14f + (distance / 320f).coerceIn(0f, 1f) * 0.18f
    return current + ((target - current) * followAmount)
}
