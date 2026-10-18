package com.talksync.ui.screens

// main live convo screen. camera, captions, controls, and debug bits all crash here.

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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.talksync.audio.toWordVolumes
import com.talksync.data.AppSettings
import com.talksync.model.BubbleAnchorMode
import com.talksync.model.ConversationInputMode
import com.talksync.model.ConversationDistanceMode
import com.talksync.model.EmphasisToneMode
import com.talksync.model.SpeechBubbleData
import com.talksync.ui.components.AslStatusPill
import com.talksync.ui.components.ConversationModeToggle
import com.talksync.ui.components.EmotionIndicator
import com.talksync.ui.components.SpeechBubble
import com.talksync.ui.components.RecentCaptionSideBar
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.talksync.util.PermissionUtil
import com.talksync.util.findActivity
import com.talksync.viewmodel.ConversationViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun ConversationScreen(
    conversationViewModel: ConversationViewModel,
    settings: AppSettings,
    onBack: () -> Unit,
    onHistory: () -> Unit,
) {
    val selectedAnchorMode = BubbleAnchorMode.fromValue(settings.bubbleAnchorMode)
    val autoTabletopMode = rememberAutoTabletopMode(
        enabled = selectedAnchorMode != BubbleAnchorMode.FACE_DOWN,
    )
    val effectiveAnchorMode = if (autoTabletopMode) {
        BubbleAnchorMode.FACE_DOWN
    } else {
        selectedAnchorMode
    }

    when (effectiveAnchorMode) {
        BubbleAnchorMode.AR_EXPERIMENTAL -> {
            ArConversationScreen(
                conversationViewModel = conversationViewModel,
                settings = settings,
                onBack = onBack,
                onHistory = onHistory,
            )
            return
        }

        BubbleAnchorMode.FACE_DOWN -> {
            FacedownConversationScreen(
                conversationViewModel = conversationViewModel,
                settings = settings,
                autoTabletopMode = autoTabletopMode,
                onBack = onBack,
                onHistory = onHistory,
            )
            return
        }

        BubbleAnchorMode.CLASSIC -> Unit
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val bubbleData by conversationViewModel.bubbleData.collectAsStateWithLifecycle()
    val speakerBubbles by conversationViewModel.speakerBubbles.collectAsStateWithLifecycle()
    val recentSideCaptions by conversationViewModel.recentSideCaptions.collectAsStateWithLifecycle()
    val overlayEnabled by conversationViewModel.overlayEnabled.collectAsStateWithLifecycle()
    val faceBoundingBox by conversationViewModel.faceBoundingBox.collectAsStateWithLifecycle()
    val trackedFaces by conversationViewModel.trackedFaces.collectAsStateWithLifecycle()
    val lipSignals by conversationViewModel.lipSignals.collectAsStateWithLifecycle()
    val cameraLensFacing by conversationViewModel.cameraLensFacing.collectAsStateWithLifecycle()
    val inputMode by conversationViewModel.inputMode.collectAsStateWithLifecycle()
    val zoomState by conversationViewModel.cameraZoomState.collectAsStateWithLifecycle()
    val connectionStatus by conversationViewModel.connectionStatus.collectAsStateWithLifecycle()
    val statusMessage by conversationViewModel.statusMessage.collectAsStateWithLifecycle()
    val aslStatusText by conversationViewModel.aslStatusText.collectAsStateWithLifecycle()
    val emphasisEnabled by conversationViewModel.emphasisEnabled.collectAsStateWithLifecycle()
    val emphasisColorEnabled by conversationViewModel.emphasisColorEnabled.collectAsStateWithLifecycle()
    val emphasisColorIntensity by conversationViewModel.emphasisColorIntensity.collectAsStateWithLifecycle()
    val rawBaseTextSize by conversationViewModel.baseTextSize.collectAsStateWithLifecycle()
    val showOriginalLanguage by conversationViewModel.showOriginalLanguage.collectAsStateWithLifecycle()
    val uiChromeVisible by conversationViewModel.uiChromeVisible.collectAsStateWithLifecycle()
    val activeSpeakerCount by conversationViewModel.activeSpeakerCount.collectAsStateWithLifecycle()
    val debugModeEnabled = settings.debugModeEnabled
    val audioDirection by conversationViewModel.audioDirection.collectAsStateWithLifecycle()
    val isStereoCapture by conversationViewModel.isStereoCapture.collectAsStateWithLifecycle()
    val speakerFaceAssignments by conversationViewModel.speakerFaceAssignments
        .collectAsStateWithLifecycle()
    val visualSpeakerCue by conversationViewModel.visualSpeakerCue
        .collectAsStateWithLifecycle()
    val faceIdentityIds by conversationViewModel.faceIdentityIdsState
        .collectAsStateWithLifecycle()
    val faceIdentities by conversationViewModel.faceIdentities
        .collectAsStateWithLifecycle()
    val emphasisToneMode = EmphasisToneMode.fromValue(settings.emphasisToneMode)
    val distanceMode = ConversationDistanceMode.fromValue(settings.conversationDistanceMode)
    val dynamicBubbleScale by conversationViewModel.dynamicBubbleScale.collectAsStateWithLifecycle()
    val humeConnected by conversationViewModel.humeConnected.collectAsStateWithLifecycle()
    val emotionState by conversationViewModel.emotionState.collectAsStateWithLifecycle()
    val isUserTalking by conversationViewModel.isUserTalking.collectAsStateWithLifecycle()
    val isSpeakingLoud by conversationViewModel.isSpeakingLoud.collectAsStateWithLifecycle()
    val volumeLevel by conversationViewModel.volumeLevel.collectAsStateWithLifecycle(initialValue = 0f)
    val functionalityReport by conversationViewModel.functionalityReport.collectAsStateWithLifecycle()
    val animatedDynamicScale by animateFloatAsState(
        targetValue = dynamicBubbleScale,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 60f),
        label = "dynamicBubbleScale",
    )
    val baseTextSize = (rawBaseTextSize * distanceMode.textScale * animatedDynamicScale).coerceAtLeast(12f)

    val previewView = remember { PreviewView(context) }

    var hasPermissions by rememberSaveable(inputMode) {
        mutableStateOf(PermissionUtil.hasPermissionsForMode(context, inputMode))
    }
    var showPermissionDialog by rememberSaveable { mutableStateOf(false) }
    var requestedOnce by rememberSaveable { mutableStateOf(false) }
    val bubbleRowSizes = remember { mutableStateMapOf<String, IntSize>() }
    // Stable home positions — only updated when a bubble would collide or leave the screen.
    val stableOffsets = remember { mutableStateMapOf<String, IntOffset>() }

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
                conversationViewModel.resetSpeakerFaceLocks()
                conversationViewModel.revealUiChromeTemporarily()
            }
            true
        }
        previewView.setOnTouchListener(touchListener)
        onDispose {
            previewView.setOnTouchListener(null)
        }
    }

    DisposableEffect(hasPermissions, previewView, lifecycleOwner, cameraLensFacing, inputMode, settings.useUltraWideCamera) {
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
            isLandscape && debugModeEnabled -> statusBarInset + 48.dp
            isLandscape -> statusBarInset + 12.dp
            debugModeEnabled -> statusBarInset + 64.dp
            else -> statusBarInset + 20.dp
        }
        val aslTopPadding = when {
            isLandscape && debugModeEnabled -> statusBarInset + 96.dp
            isLandscape -> statusBarInset + 56.dp
            debugModeEnabled -> statusBarInset + 112.dp
            else -> statusBarInset + 68.dp
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
                            conversationViewModel.resetSpeakerFaceLocks()
                            conversationViewModel.revealUiChromeTemporarily()
                        }
                    },
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
                    .padding(top = topBarPadding),
            ) {
                FaceDetectionDebugStatus(hasFace = trackedFaces.isNotEmpty())
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
                ConnectionStatusPill(
                    connectionStatus = connectionStatus,
                    detail = statusMessage,
                )
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
                .align(Alignment.TopCenter)
                .padding(top = toggleTopPadding),
        ) {
            ConversationModeToggle(
                selectedMode = inputMode,
                onModeSelected = conversationViewModel::setInputMode,
            )
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
            ZoomControlStrip(
                zoomRatio = zoomState.zoomRatio,
                maxZoomRatio = zoomState.maxZoomRatio,
                onZoomOut = { conversationViewModel.nudgeZoom(-0.25f) },
                onZoomIn = { conversationViewModel.nudgeZoom(0.25f) },
                onReset = conversationViewModel::resetZoom,
            )
        }

        if (overlayEnabled && inputMode == ConversationInputMode.TRANSCRIPTION) {
            val cue = visualSpeakerCue
            val activeFace = cue?.let { activeCue ->
                trackedFaces.firstOrNull { it.trackId == activeCue.faceId }
            }
            if (activeFace != null) {
                ActiveSpeakerFaceRing(
                    trackedFace = activeFace,
                    strength = cue.score,
                    maxWidthPx = constraints.maxWidth.toFloat(),
                    maxHeightPx = constraints.maxHeight.toFloat(),
                    density = density,
                )
            }
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
                            width = 2.dp,
                            color = com.talksync.ui.theme.BrandAccent,
                            shape = RoundedCornerShape(18.dp),
                        )
                        .background(
                            color = com.talksync.ui.theme.BrandGlow,
                            shape = RoundedCornerShape(18.dp),
                        ),
                )

                val faceAngle = com.talksync.vision.FaceAngleMapping
                    .rectCenterToAngleDegrees(box)
                val delta = faceAngle - audioDirection.angleDegrees
                // Multi-line per-face debug card, anchored just under the
                // face box. Surfaces every input that drives speaker
                // assignment so misbehavior (no lip signal, identity
                // missing, locked to wrong speakerKey, etc.) is visible
                // without log spelunking.
                val lipSignal = lipSignals[trackedFace.trackId]
                val identityId = faceIdentityIds[trackedFace.trackId]
                val identityName = identityId?.let { id ->
                    faceIdentities.firstOrNull { it.id == id }?.displayName
                }
                val boundSpeaker = speakerFaceAssignments.entries
                    .firstOrNull { (_, a) -> a.faceId == trackedFace.trackId }
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = faceLeftPx.roundToInt(),
                                y = (faceTopPx + box.height() * maxHeightPx + 6f).roundToInt(),
                            )
                        },
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = "face ${trackedFace.trackId}",
                            color = com.talksync.ui.theme.BrandAccent,
                            fontSize = 10.sp,
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "ang %+.0f\u00B0 \u0394 %+.0f\u00B0",
                                faceAngle,
                                delta,
                            ),
                            color = Color.White,
                            fontSize = 10.sp,
                        )
                        Text(
                            text = if (lipSignal != null) {
                                String.format(
                                    Locale.US,
                                    "lip ap %.2f vel %.2f mix %.2f",
                                    lipSignal.apertureScore,
                                    lipSignal.velocityScore,
                                    lipSignal.combinedScore,
                                )
                            } else {
                                "lip: no MediaPipe signal"
                            },
                            color = if (lipSignal != null && lipSignal.combinedScore > 0.15f) {
                                com.talksync.ui.theme.StatusLiveGreen
                            } else {
                                Color.White.copy(alpha = 0.7f)
                            },
                            fontSize = 10.sp,
                        )
                        if (lipSignal != null) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "mouth (%.2f, %.2f)",
                                    lipSignal.mouthCenterX,
                                    lipSignal.mouthCenterY,
                                ),
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 10.sp,
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "pose y%+.0f p%+.0f r%+.0f gaze %.2f",
                                    lipSignal.headPose.yawDegrees,
                                    lipSignal.headPose.pitchDegrees,
                                    lipSignal.headPose.rollDegrees,
                                    lipSignal.visualAttentionScore,
                                ),
                                color = if (lipSignal.visualAttentionScore > 0.55f) {
                                    com.talksync.ui.theme.StatusLiveGreen
                                } else {
                                    Color.White.copy(alpha = 0.7f)
                                },
                                fontSize = 10.sp,
                            )
                        }
                        Text(
                            text = if (boundSpeaker != null) {
                                String.format(
                                    Locale.US,
                                    "\u2192 %s  conf %.2f",
                                    boundSpeaker.key,
                                    boundSpeaker.value.confidence,
                                )
                            } else {
                                "\u2192 unbound"
                            },
                            color = if (boundSpeaker != null) {
                                com.talksync.ui.theme.BrandAccent
                            } else {
                                com.talksync.ui.theme.StatusWarnAmber
                            },
                            fontSize = 10.sp,
                        )
                        Text(
                            text = when {
                                identityName != null -> "id: $identityName"
                                identityId != null -> "id: ${identityId.take(6)}\u2026"
                                else -> "id: not matched"
                            },
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                        )
                    }
                }

                // Mouth-anchor crosshair + full lip landmark overlay so
                // the user can verify the bubble is locked near the lips
                // and that MediaPipe is tracking the correct face mesh.
                if (lipSignal != null) {
                    val mouthPx = IntOffset(
                        x = (lipSignal.mouthCenterX * maxWidthPx - 6f).roundToInt(),
                        y = (lipSignal.mouthCenterY * maxHeightPx - 6f).roundToInt(),
                    )
                    Box(
                        modifier = Modifier
                            .offset { mouthPx }
                            .size(12.dp)
                            .background(
                                color = com.talksync.ui.theme.EmphasisRed,
                                shape = CircleShape,
                            ),
                    )

                    // Lip landmark polylines. Canvas fills the whole preview
                    // area; we convert normalized MediaPipe coords to pixels
                    // inline. Outer contour = green, inner contour = cyan,
                    // individual nodes = small white dots.
                    if (!lipSignal.contour.isEmpty) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height

                            fun toOffset(p: Pair<Float, Float>) =
                                Offset(p.first * w, p.second * h)

                            fun drawPolyline(
                                pts: List<Pair<Float, Float>>,
                                color: Color,
                                strokeWidth: Float = 2.5f,
                            ) {
                                for (i in 0 until pts.size - 1) {
                                    drawLine(
                                        color = color,
                                        start = toOffset(pts[i]),
                                        end = toOffset(pts[i + 1]),
                                        strokeWidth = strokeWidth,
                                    )
                                }
                            }

                            fun drawNodes(
                                pts: List<Pair<Float, Float>>,
                                color: Color,
                                radius: Float = 3.5f,
                            ) {
                                for (p in pts) {
                                    drawCircle(
                                        color = color,
                                        radius = radius,
                                        center = toOffset(p),
                                    )
                                }
                            }

                            val outerColor = Color(0xFF00FF88) // bright green
                            val innerColor = Color(0xFF00CFFF) // cyan
                            val nodeColor = Color.White.copy(alpha = 0.85f)

                            // Outer lip ring
                            drawPolyline(lipSignal.contour.outerUpper, outerColor)
                            drawPolyline(lipSignal.contour.outerLower, outerColor)

                            // Inner mouth opening
                            drawPolyline(lipSignal.contour.innerUpper, innerColor)
                            drawPolyline(lipSignal.contour.innerLower, innerColor)

                            // Individual landmark nodes
                            val allNodes = lipSignal.contour.outerUpper +
                                lipSignal.contour.outerLower +
                                lipSignal.contour.innerUpper +
                                lipSignal.contour.innerLower
                            drawNodes(allNodes, nodeColor)
                        }
                    }
                }
            }

            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = topBarPadding),
            ) {
                val audioLabel = if (isStereoCapture) {
                    String.format(
                        Locale.US,
                        "audio %+.0f\u00B0  conf %.2f",
                        audioDirection.angleDegrees,
                        audioDirection.confidence,
                    )
                } else {
                    "audio: mono (no direction)"
                }
                Text(
                    text = audioLabel,
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            // Bottom-left debug HUD — full pipeline telemetry.
            Surface(
                color = Color.Black.copy(alpha = 0.80f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 8.dp)
                    .fillMaxWidth(0.62f)
                    .heightIn(max = 420.dp),
            ) {
                val scrollState = androidx.compose.foundation.rememberScrollState()
                Column(
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    // ── header ──────────────────────────────────────────
                    Text("◆ DEBUG", color = com.talksync.ui.theme.BrandAccent, fontSize = 9.sp)

                    // ── system health ────────────────────────────────────
                    DebugSection("SYSTEM")
                    Text(
                        text = functionalityReport.headline,
                        color = if (functionalityReport.blockingCount > 0) com.talksync.ui.theme.StatusWarnAmber else com.talksync.ui.theme.StatusLiveGreen,
                        fontSize = 10.sp,
                    )
                    functionalityReport.checks.forEach { check ->
                        val color = when (check.status) {
                            com.talksync.model.FunctionalityStatus.LIVE -> com.talksync.ui.theme.StatusLiveGreen
                            com.talksync.model.FunctionalityStatus.NEEDS_SETUP -> com.talksync.ui.theme.StatusWarnAmber
                            com.talksync.model.FunctionalityStatus.WARNING -> Color(0xFFFF6B6B)
                            else -> Color.White.copy(alpha = 0.55f)
                        }
                        val icon = when (check.status) {
                            com.talksync.model.FunctionalityStatus.LIVE -> "▶"
                            com.talksync.model.FunctionalityStatus.NEEDS_SETUP -> "!"
                            com.talksync.model.FunctionalityStatus.WARNING -> "✕"
                            com.talksync.model.FunctionalityStatus.READY -> "✓"
                            else -> "…"
                        }
                        Text("$icon ${check.title}: ${check.detail}", color = color, fontSize = 9.sp)
                    }

                    // ── speech ───────────────────────────────────────────
                    DebugSection("SPEECH")
                    Text(
                        text = "${settings.speechBackend.uppercase()}  ${connectionStatus}${statusMessage?.let { "  $it" } ?: ""}",
                        color = if (connectionStatus.contains("live", ignoreCase = true) || connectionStatus.contains("connected", ignoreCase = true)) com.talksync.ui.theme.StatusLiveGreen else Color.White,
                        fontSize = 10.sp,
                    )

                    // ── audio ─────────────────────────────────────────────
                    DebugSection("AUDIO")
                    val volBars = (volumeLevel * 20).toInt().coerceIn(0, 20)
                    Text(
                        text = String.format(Locale.US, "vol %.2f  [%s%s]  talking=%s  loud=%s",
                            volumeLevel,
                            "█".repeat(volBars),
                            "░".repeat(20 - volBars),
                            if (isUserTalking) "Y" else "n",
                            if (isSpeakingLoud) "Y" else "n",
                        ),
                        color = if (isUserTalking) com.talksync.ui.theme.StatusLiveGreen else Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                    )
                    if (isStereoCapture) {
                        Text(
                            text = String.format(Locale.US, "stereo  dir %+.0f°  conf %.2f",
                                audioDirection.angleDegrees, audioDirection.confidence),
                            color = if (audioDirection.isSpeech) com.talksync.ui.theme.StatusLiveGreen else Color.White.copy(alpha = 0.55f),
                            fontSize = 10.sp,
                        )
                    } else {
                        Text("mono capture — no direction", color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp)
                    }

                    // ── camera ────────────────────────────────────────────
                    DebugSection("CAMERA")
                    val lensLabel = if (cameraLensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT) "FRONT" else "BACK"
                    val uwLabel = if (settings.useUltraWideCamera) "UW ON" else "UW off"
                    Text(
                        text = String.format(Locale.US, "%s  %s  zoom %.2f×  (%.2f – %.2f)",
                            lensLabel, uwLabel,
                            zoomState.zoomRatio, zoomState.minZoomRatio, zoomState.maxZoomRatio),
                        color = Color.White,
                        fontSize = 10.sp,
                    )
                    Text(
                        text = String.format(Locale.US, "bubbleScale %.2f×  dist=%s  anchor=%s",
                            dynamicBubbleScale, settings.conversationDistanceMode, settings.bubbleAnchorMode),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                    )

                    // ── face tracking ─────────────────────────────────────
                    DebugSection("FACES")
                    Text(
                        text = String.format(Locale.US, "tracked %d  lipSig %d  bubbles %d  active %d  ids %d/%d named",
                            trackedFaces.size, lipSignals.size, speakerBubbles.size, activeSpeakerCount,
                            faceIdentities.count { !it.displayName.isNullOrBlank() }, faceIdentities.size),
                        color = Color.White,
                        fontSize = 10.sp,
                    )
                    Text(
                        text = String.format(Locale.US,
                            "fusion w  mouth %.2f  gaze %.2f  audio %.2f  consist %.2f  vis %.2f",
                            com.talksync.vision.SpeakerFusionConfig.MOUTH_MOTION_WEIGHT,
                            com.talksync.vision.SpeakerFusionConfig.GAZE_ATTENTION_WEIGHT,
                            com.talksync.vision.SpeakerFusionConfig.DIRECTIONAL_AUDIO_WEIGHT,
                            com.talksync.vision.SpeakerFusionConfig.CONSISTENCY_WEIGHT,
                            com.talksync.vision.SpeakerFusionConfig.VISIBILITY_WEIGHT,
                        ),
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 9.sp,
                    )
                    val activeCue = visualSpeakerCue
                    if (activeCue != null) {
                        Text(
                            text = String.format(Locale.US, "▶ visual spk %s  total=%.2f  mouth=%.2f  gaze=%.2f  aud=%.2f",
                                activeCue.faceId, activeCue.score, activeCue.mouthScore,
                                activeCue.gazeAttentionScore, activeCue.audioActivityScore),
                            color = com.talksync.ui.theme.StatusLiveGreen,
                            fontSize = 10.sp,
                        )
                    }

                    // ── hume emotion ──────────────────────────────────────
                    DebugSection("HUME")
                    val humeStatus = if (humeConnected) "connected" else "disconnected"
                    Text(
                        text = String.format(Locale.US, "%s  fused=%s  conf=%.0f%%%s",
                            humeStatus, emotionState.label.name,
                            emotionState.confidence * 100f,
                            if (emotionState.isLowConfidence) "  LOW" else ""),
                        color = if (humeConnected) com.talksync.ui.theme.StatusLiveGreen else Color.White.copy(alpha = 0.45f),
                        fontSize = 10.sp,
                    )
                    val emoDetails = listOfNotNull(
                        emotionState.dominantFaceEmotion?.let { "face=$it" },
                        emotionState.dominantProsodyEmotion?.let { "voice=$it" },
                        emotionState.dominantBurstEmotion?.let { "burst=$it" },
                        emotionState.dominantTextEmotion?.let { "text=$it" },
                    )
                    if (emoDetails.isNotEmpty()) {
                        Text(
                            text = emoDetails.joinToString("  "),
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 9.sp,
                        )
                    }

                    // ── speaker bubbles ───────────────────────────────────
                    DebugSection("BUBBLES")
                    if (speakerBubbles.isEmpty()) {
                        Text("no speaker bubbles", color = com.talksync.ui.theme.StatusWarnAmber, fontSize = 10.sp)
                    } else {
                        speakerBubbles.forEach { bubble ->
                            val face = bubble.anchorFaceId ?: "–"
                            val state = buildString {
                                append(if (bubble.isVisible) "VIS" else "hid")
                                bubble.detectedLanguage?.let { append(" lang=$it") }
                                if (bubble.translatedText != null) append(" XLAT")
                                if (bubble.intentLabels.isNotEmpty()) {
                                    append(" intent=${bubble.intentLabels.joinToString("/") { it.displayText }}")
                                }
                                bubble.threadId?.let { append(" thread=${it.takeLast(6)}") }
                                append(" emo=${bubble.emotionState.label.name}")
                            }
                            Text(
                                text = String.format(Locale.US, "%s [%s]  %s  len=%d  →%s",
                                    bubble.speakerKey, bubble.speakerLabel ?: "?", state,
                                    bubble.text.length, face.take(10)),
                                color = if (bubble.isVisible) Color.White else Color.White.copy(alpha = 0.45f),
                                fontSize = 9.sp,
                            )
                        }
                    }
                }
            }
        }

        if (!hasPermissions) {
            PermissionFallback(
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

        val bubbleLayouts = remember(
            reservedSideWidth,
            bubblesToRender,
            trackedFaces,
            lipSignals,
            bubbleRowSizes.toMap(),
            constraints.maxWidth,
            constraints.maxHeight,
            inputMode,
        ) {
            val facesById = trackedFaces.associateBy { it.trackId }
            val fallbackBubbleSize = IntSize(
                width = with(density) { 200.dp.roundToPx() },
                height = with(density) { 72.dp.roundToPx() },
            )
            val demoSpeakersOn = settings.demoSpeakersEnabled
            val demoSpeakerCount = if (demoSpeakersOn) {
                settings.demoSpeakerNames.split(",").count { it.isNotBlank() }.coerceAtLeast(1)
            } else {
                0
            }
            val renderModels = bubblesToRender.mapIndexed { index, bubble ->
                val anchorFaceId = (bubble.lipDrivenAnchorId ?: bubble.anchorFaceId)
                val mouthSignal = anchorFaceId?.let { lipSignals[it] }
                val resolvedAnchor = (bubble.lipDrivenAnchorId ?: bubble.anchorFaceId)?.let { facesById[it]?.boundingBox }
                // In demo mode with no face anchor, pin each speaker to a distinct
                // horizontal slot so bubbles never pile on top of each other.
                val demoAnchor: android.graphics.RectF? = if (demoSpeakersOn && resolvedAnchor == null) {
                    val ordinal = bubble.speakerKey.substringAfter("speaker-", "0").toIntOrNull() ?: index
                    val slotCount = demoSpeakerCount.coerceAtLeast(2)
                    val slotFraction = (ordinal % slotCount + 0.5f) / slotCount
                    android.graphics.RectF(
                        slotFraction - 0.08f,
                        0.25f,
                        slotFraction + 0.08f,
                        0.55f,
                    )
                } else {
                    null
                }
                BubbleRenderModel(
                    bubble = bubble,
                    anchorBounds = demoAnchor ?: resolvedAnchor
                        ?: faceBoundingBox.takeIf { inputMode == ConversationInputMode.ASL_TRANSLATION },
                    bubbleSize = bubbleRowSizes[bubble.id] ?: fallbackBubbleSize,
                    mouthAnchor = mouthSignal?.let {
                        MouthAnchor(it.mouthCenterX, it.mouthCenterY)
                    },
                )
            }
            resolveBubbleLayouts(
                models = renderModels,
                maxWidthPx = constraints.maxWidth.toFloat(),
                reservedRightPx = with(density) { reservedSideWidth.toPx() },
                maxHeightPx = constraints.maxHeight.toFloat(),
                marginPx = with(density) { 16.dp.toPx() },
                minWidthPx = with(density) { 120.dp.toPx() },
                defaultTopFraction = 0.76f,
                topSpacingPx = with(density) { distanceMode.anchorLiftDp.dp.toPx() },
            )
        }

        if (overlayEnabled && bubbleLayouts.isNotEmpty()) {
            ThreadConnectorLayer(
                layouts = bubbleLayouts,
                bubbleSizes = bubbleRowSizes.toMap(),
                modifier = Modifier.fillMaxSize(),
            )

            // Keep stable home positions current. Runs after every composition so it's
            // synchronous — no frame lag. Only writes to stableOffsets when something
            // actually needs to move, so it won't loop.
            SideEffect {
                val marginPx = with(density) { 16.dp.roundToPx() }
                val gapPx   = with(density) {  8.dp.roundToPx() }
                val screenW = constraints.maxWidth - with(density) { reservedSideWidth.roundToPx() }
                val screenH = constraints.maxHeight

                // Seed new bubbles; prune gone ones.
                val activeIds = bubbleLayouts.map { it.bubble.id }.toSet()
                stableOffsets.keys.toList().forEach { if (it !in activeIds) stableOffsets.remove(it) }
                bubbleLayouts.forEach { layout ->
                    if (layout.bubble.id !in stableOffsets) stableOffsets[layout.bubble.id] = layout.offset
                }

                // For each bubble, check whether its stable position violates constraints.
                // If it does, snap the stable target to the layout-resolved position (which
                // already has collision + bounds handled) so the animated spring does the rest.
                bubbleLayouts.forEach { layout ->
                    val id   = layout.bubble.id
                    val pos  = stableOffsets[id]        ?: return@forEach
                    val size = bubbleRowSizes[id]       ?: return@forEach
                    if (layout.offset == pos) return@forEach  // nothing changed, skip

                    val offScreen = pos.x < marginPx ||
                        pos.y < marginPx ||
                        pos.x + size.width  > screenW - marginPx ||
                        pos.y + size.height > screenH - marginPx

                    val colliding = !offScreen && bubbleLayouts.any { other ->
                        if (other.bubble.id == id) return@any false
                        val oPos  = stableOffsets[other.bubble.id] ?: return@any false
                        val oSize = bubbleRowSizes[other.bubble.id] ?: return@any false
                        pos.x              < oPos.x + oSize.width  + gapPx &&
                            pos.x + size.width  + gapPx > oPos.x &&
                            pos.y              < oPos.y + oSize.height + gapPx &&
                            pos.y + size.height + gapPx > oPos.y
                    }

                    if (offScreen || colliding) stableOffsets[id] = layout.offset
                }
            }

            bubbleLayouts.forEach { layout ->
                key(layout.bubble.id) {
                // Bubble is sticky — snapped to its stable home, moves only when forced.
                // Spring is fast so the correction feels deliberate rather than drifty.
                val stableTarget = stableOffsets[layout.bubble.id] ?: layout.offset
                val animatedBubbleX by animateFloatAsState(
                    targetValue = stableTarget.x.toFloat(),
                    animationSpec = spring(dampingRatio = 1.0f, stiffness = 350f),
                    label = "bubbleOffsetX",
                )
                val animatedBubbleY by animateFloatAsState(
                    targetValue = stableTarget.y.toFloat(),
                    animationSpec = spring(dampingRatio = 1.0f, stiffness = 350f),
                    label = "bubbleOffsetY",
                )

                // Tether — stable bubble connected to the live face/mouth by an animated line.
                // animateFloatAsState MUST be unconditional (Compose slot-table rule).
                val tetherFaceId = layout.bubble.lipDrivenAnchorId ?: layout.bubble.anchorFaceId
                val tetherLipSignal = tetherFaceId?.let { lipSignals[it] }
                val tetherFace = tetherFaceId?.let { id -> trackedFaces.firstOrNull { it.trackId == id } }
                val tetherBubbleSize = bubbleRowSizes[layout.bubble.id]
                val maxWPx = constraints.maxWidth.toFloat()
                val maxHPx = constraints.maxHeight.toFloat()
                val faceTargetX = (tetherLipSignal?.mouthCenterX
                    ?: tetherFace?.boundingBox?.centerX() ?: 0.5f) * maxWPx
                val faceTargetY = (tetherLipSignal?.mouthCenterY
                    ?: tetherFace?.boundingBox?.top ?: 0.5f) * maxHPx
                // Unconditional — always in the slot table regardless of face visibility.
                val animTetherFaceX by animateFloatAsState(
                    targetValue = faceTargetX,
                    animationSpec = spring(dampingRatio = 1.0f, stiffness = 180f),
                    label = "tetherFaceX",
                )
                val animTetherFaceY by animateFloatAsState(
                    targetValue = faceTargetY,
                    animationSpec = spring(dampingRatio = 1.0f, stiffness = 150f),
                    label = "tetherFaceY",
                )
                // Only draw when we have something useful to connect to.
                val shouldDrawTether = layout.bubble.isVisible &&
                    tetherBubbleSize != null &&
                    tetherFaceId != null &&
                    (tetherLipSignal != null || tetherFace != null)
                if (shouldDrawTether && tetherBubbleSize != null) {
                    val bubbleCenterX = animatedBubbleX + tetherBubbleSize.width / 2f
                    val bubbleBottomY = animatedBubbleY + tetherBubbleSize.height.toFloat()
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val path = Path()
                        path.moveTo(bubbleCenterX, bubbleBottomY)
                        val ctrlX = bubbleCenterX * 0.35f + animTetherFaceX * 0.65f
                        val ctrlY = bubbleBottomY * 0.25f + animTetherFaceY * 0.75f
                        path.quadraticBezierTo(ctrlX, ctrlY, animTetherFaceX, animTetherFaceY)
                        drawPath(
                            path = path,
                            color = Color.White.copy(alpha = 0.55f),
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.75f),
                            radius = 5.dp.toPx(),
                            center = Offset(animTetherFaceX, animTetherFaceY),
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = animatedBubbleX.roundToInt(),
                                y = animatedBubbleY.roundToInt(),
                            )
                        }
                        .animateContentSize(
                            animationSpec = spring(
                                dampingRatio = 1f,
                                stiffness = 220f,
                            ),
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.onGloballyPositioned { coords ->
                            val q = IntSize(
                                ((coords.size.width + 7) / 8) * 8,
                                ((coords.size.height + 7) / 8) * 8,
                            )
                            if (bubbleRowSizes[layout.bubble.id] != q) {
                                bubbleRowSizes[layout.bubble.id] = q
                            }
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
                            visualHighlightStrength = if (layout.bubble.anchorFaceId == visualSpeakerCue?.faceId) {
                                visualSpeakerCue?.score ?: 0f
                            } else {
                                0f
                            },
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
                } // key(layout.bubble.id)
            }
        }

        // Off-screen awareness cue — speech detected but no face on screen.
        // In landscape the top/bottom mics become left/right, so direction is meaningful.
        // ROTATION_270 (left-side-up landscape) swaps the mics, so we negate the angle.
        val displayRotation = (context.getSystemService(android.view.WindowManager::class.java))
            ?.defaultDisplay?.rotation ?: android.view.Surface.ROTATION_0
        val effectiveAudioAngle = if (displayRotation == android.view.Surface.ROTATION_270) {
            -audioDirection.angleDegrees
        } else {
            audioDirection.angleDegrees
        }
        val showDirectionCue = settings.directionalAudioCuesEnabled &&
            overlayEnabled &&
            audioDirection.isSpeech &&
            trackedFaces.isEmpty()
        androidx.compose.animation.AnimatedVisibility(
            visible = showDirectionCue,
            enter = androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(350),
            ) + androidx.compose.animation.slideInVertically(
                animationSpec = androidx.compose.animation.core.tween(350),
            ) { it / 2 },
            exit = androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(250),
            ) + androidx.compose.animation.slideOutVertically(
                animationSpec = androidx.compose.animation.core.tween(250),
            ) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 90.dp),
        ) {
            OffScreenDirectionCue(
                angleDegrees = effectiveAudioAngle,
                hasDirection = isLandscape && isStereoCapture && audioDirection.confidence > 0.18f,
            )
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
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                tonalElevation = 10.dp,
                shadowElevation = 16.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ControlPillButton(
                        onClick = onBack,
                        contentDescription = "Back",
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    )
                    ControlPillButton(
                        onClick = conversationViewModel::toggleCameraLens,
                        contentDescription = if (cameraLensFacing == CameraSelector.LENS_FACING_BACK) {
                            "Switch to front camera"
                        } else {
                            "Switch to back camera"
                        },
                        icon = Icons.Outlined.Cameraswitch,
                    )
                    ControlPillButton(
                        onClick = conversationViewModel::toggleOverlay,
                        contentDescription = if (overlayEnabled) "Hide overlays" else "Show overlays",
                        icon = if (overlayEnabled) Icons.Outlined.Layers else Icons.Outlined.LayersClear,
                    )
                    if (anyForeignLanguageDetected) {
                        ControlPillButton(
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
                    ControlPillButton(
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
                            "bubbl! needs camera and microphone access to place live captions near the speaker."

                        ConversationInputMode.ASL_TRANSLATION ->
                            "bubbl! needs camera access to translate visible hand signs into live on-screen text."
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
private fun rememberAutoTabletopMode(
    enabled: Boolean,
): Boolean {
    val context = LocalContext.current
    val sensorManager = remember(context) {
        context.getSystemService(SensorManager::class.java)
    }
    val gravitySensor = remember(sensorManager) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }
    val isTabletop = remember { mutableStateOf(false) }

    DisposableEffect(enabled, sensorManager, gravitySensor) {
        if (!enabled || sensorManager == null || gravitySensor == null) {
            isTabletop.value = false
            return@DisposableEffect onDispose { }
        }

        val enterDelayMs = 900L
        val exitDelayMs = 500L
        var flatCandidateSince: Long? = null
        var nonFlatCandidateSince: Long? = null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val horizontalMagnitude = sqrt((x * x) + (y * y))
                val absoluteZ = abs(z)
                val flatNow = absoluteZ >= 8.6f && horizontalMagnitude <= 2.4f
                val now = System.currentTimeMillis()

                if (flatNow) {
                    nonFlatCandidateSince = null
                    val since = flatCandidateSince ?: now.also { flatCandidateSince = it }
                    if (now - since >= enterDelayMs) {
                        isTabletop.value = true
                    }
                } else {
                    flatCandidateSince = null
                    val since = nonFlatCandidateSince ?: now.also { nonFlatCandidateSince = it }
                    if (now - since >= exitDelayMs) {
                        isTabletop.value = false
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, gravitySensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose {
            sensorManager.unregisterListener(listener)
            isTabletop.value = false
        }
    }

    return isTabletop.value
}

@Composable
private fun PermissionFallback(
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
                    "Grant access to place live captions near the speaker on screen."

                ConversationInputMode.ASL_TRANSLATION ->
                    "Grant camera access to read visible hand signs and translate them into text."
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
private fun ControlPillButton(
    onClick: () -> Unit,
    contentDescription: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
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
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun FaceDetectionDebugStatus(
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
                text = if (hasFace) "Face detected" else "Looking for face",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ActiveSpeakerFaceRing(
    trackedFace: com.talksync.vision.TrackedFace,
    strength: Float,
    maxWidthPx: Float,
    maxHeightPx: Float,
    density: androidx.compose.ui.unit.Density,
) {
    val box = trackedFace.boundingBox
    val clampedStrength = strength.coerceIn(0f, 1f)
    val faceLeftPx = box.left * maxWidthPx
    val faceTopPx = box.top * maxHeightPx
    val faceWidthDp = with(density) { (box.width() * maxWidthPx).toDp() }
    val faceHeightDp = with(density) { (box.height() * maxHeightPx).toDp() }
    val shape = RoundedCornerShape(20.dp)
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
                width = (2f + clampedStrength * 2f).dp,
                color = com.talksync.ui.theme.StatusLiveGreen.copy(alpha = 0.52f + clampedStrength * 0.38f),
                shape = shape,
            )
            .background(
                color = com.talksync.ui.theme.StatusLiveGreen.copy(alpha = 0.06f + clampedStrength * 0.08f),
                shape = shape,
            ),
    )
}

@Composable
private fun ThreadConnectorLayer(
    layouts: List<ResolvedBubbleLayout>,
    bubbleSizes: Map<String, IntSize>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        layouts
            .filter { layout ->
                layout.bubble.isVisible &&
                    layout.bubble.isThreadHighlighted &&
                    layout.bubble.threadId != null
            }
            .groupBy { layout -> layout.bubble.threadId }
            .values
            .forEach { threadLayouts ->
                val ordered = threadLayouts.sortedBy { layout -> layout.bubble.threadTurnIndex }
                ordered.zipWithNext().forEach connection@{ (startLayout, endLayout) ->
                    if (startLayout.bubble.speakerKey == endLayout.bubble.speakerKey) {
                        return@connection
                    }
                    val startSize = bubbleSizes[startLayout.bubble.id] ?: return@connection
                    val endSize = bubbleSizes[endLayout.bubble.id] ?: return@connection
                    if (startSize == IntSize.Zero || endSize == IntSize.Zero) {
                        return@connection
                    }
                    val start = Offset(
                        x = startLayout.offset.x + (startSize.width * 0.5f),
                        y = startLayout.offset.y + (startSize.height * 0.5f),
                    )
                    val end = Offset(
                        x = endLayout.offset.x + (endSize.width * 0.5f),
                        y = endLayout.offset.y + (endSize.height * 0.5f),
                    )
                    drawLine(
                        color = Color(0xFF73C7FF).copy(alpha = 0.54f),
                        start = start,
                        end = end,
                        strokeWidth = 3.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
    }
}

@Composable
private fun ConnectionStatusPill(
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
                    .background(connectionStatusColor(connectionStatus), CircleShape),
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

private fun connectionStatusColor(connectionStatus: String): Color {
    return when (connectionStatus) {
        "Live" -> com.talksync.ui.theme.StatusLiveGreen
        "Ready", "Connecting...", "ASL beta" -> com.talksync.ui.theme.BrandAccent
        else -> com.talksync.ui.theme.StatusAlertCoral
    }
}

@Composable
private fun ZoomControlStrip(
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
private fun DebugSection(label: String) {
    androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(3.dp))
    Text(
        text = "── $label",
        color = com.talksync.ui.theme.BrandAccent.copy(alpha = 0.75f),
        fontSize = 8.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
    )
}

/**
 * Pill shown when stereo audio detects speech but no face is on-screen.
 * Gives the user a left/right/ahead hint before they visually locate the speaker.
 */
@Composable
private fun OffScreenDirectionCue(
    angleDegrees: Float,
    hasDirection: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = when {
        hasDirection && angleDegrees < -8f -> "← Voice on the left"
        hasDirection && angleDegrees > 8f -> "Voice on the right →"
        else -> "Voice nearby"
    }

    Surface(
        shape = RoundedCornerShape(50.dp),
        color = Color.Black.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.GraphicEq,
                contentDescription = null,
                tint = com.talksync.ui.theme.BrandAccent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
