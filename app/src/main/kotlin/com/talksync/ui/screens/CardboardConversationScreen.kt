package com.talksync.ui.screens

// cardboard split-eye view, tuned for phone screens and simple stereo overlays.

import android.content.pm.ActivityInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.talksync.data.AppSettings
import com.talksync.model.ConversationDistanceMode
import com.talksync.model.ConversationInputMode
import com.talksync.model.EmphasisToneMode
import com.talksync.ui.components.SpeechBubble
import com.talksync.ui.components.RecentCaptionSideBar
import com.talksync.viewmodel.VisibleCaptionUi
import com.talksync.util.PermissionUtil
import com.talksync.util.findActivity
import com.talksync.viewmodel.ConversationViewModel
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.isActive

// Pixel 10a hardware profile
// Display: ~6.3" OLED 2424×1080 @ 422 ppi (same geometry as Pixel 9a, refined in 10a)
// Standard Google Cardboard v2 IPD = 64 mm; screen ~145.9 mm wide in landscape → each eye ~72.9 mm
private const val PIXEL10A_CARDBOARD_TEXT_SCALE = 0.82f   // vs generic 0.78f — lenses magnify ~1.6× so text reads slightly smaller
private const val PIXEL10A_NOSE_BRIDGE_DP = 4             // ~1.5 mm physical; aligns with Cardboard housing cutout on this form factor
private const val PIXEL10A_DEFAULT_PARALLAX_PX = 55       // comfortable mid-depth cue at 422 ppi; 40 px was tuned for lower-density screens

/**
 * Side-by-side stereo overlay for Google Cardboard / generic VR holders.
 *
 * Layout:
 *   ┌─────────────┬─────────────┐
 *   │   left eye  │  right eye  │
 *   │ camera + ui │ snapshot+ui │
 *   └─────────────┴─────────────┘
 *
 * The phone is mounted in the headset with the back camera poking through a
 * cut-out. Both halves render the same camera frame so each eye sees the
 * world. CameraX can only attach to a single Preview surface, so the right
 * eye polls [PreviewView.getBitmap] at ~30Hz and renders the snapshot via a
 * Compose [Image] — that's good enough for a passthrough caption overlay
 * without the cost of a custom GLSurface fan-out.
 *
 * Speech bubbles are rendered independently in each half against the
 * half-width constraints, so the layout naturally fits the eye's viewport.
 *
 * Exits:
 *   - System back
 *   - Long-press anywhere (Cardboard's magnetic trigger maps to a regular
 *     touch, which on most phones registers as a tap; we accept any tap as
 *     "wake the chrome" and a long-press to bail).
 */
@Composable
fun CardboardConversationScreen(
    conversationViewModel: ConversationViewModel,
    settings: AppSettings,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }

    // Lock landscape while in cardboard mode; restore on exit.
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = previous
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // COMPATIBLE forces a TextureView backend. PreviewView.getBitmap() on a
            // TextureView reads directly from the GL texture without a blocking GPU→CPU
            // readback, which is what made the right-eye snapshot so laggy in PERFORMANCE
            // (SurfaceView) mode.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var hasPermissions by remember {
        mutableStateOf(
            PermissionUtil.hasPermissionsForMode(
                context,
                ConversationInputMode.TRANSCRIPTION,
            ),
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPermissions = PermissionUtil.hasPermissionsForMode(
            context,
            ConversationInputMode.TRANSCRIPTION,
        )
    }
    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            permissionLauncher.launch(
                PermissionUtil.permissionsForMode(ConversationInputMode.TRANSCRIPTION),
            )
        }
    }

    // Bind camera + start the conversation pipeline whenever permissions
    // are present. We always use the BACK camera (the one peeking through
    // the cardboard hole).
    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            conversationViewModel.bindCamera(
                previewView = previewView,
                lifecycleOwner = lifecycleOwner,
                lensFacing = CameraSelector.LENS_FACING_BACK,
                inputMode = ConversationInputMode.TRANSCRIPTION,
                useUltraWide = settings.useUltraWideCamera,
            )
            conversationViewModel.startConversation()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            conversationViewModel.stopConversation()
            conversationViewModel.unbindCamera()
        }
    }

    // Right-eye snapshot stream. withFrameMillis suspends until the next Choreographer
    // vsync pulse, so updates are locked to the display refresh rate (60/90/120 Hz) and
    // never produce redundant copies between frames. Combined with COMPATIBLE mode above,
    // getBitmap() reads from the TextureView's GL texture instead of doing a blocking
    // GPU→CPU pixel readback — this is why the right eye was so much laggier than the left.
    var rightEyeSnapshot by remember {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(hasPermissions) {
        if (!hasPermissions) return@LaunchedEffect
        while (isActive) {
            withFrameMillis { }
            val snap = runCatching { previewView.bitmap }.getOrNull()
            if (snap != null) {
                rightEyeSnapshot = snap.asImageBitmap()
            }
        }
    }

    val recentSideCaptions by conversationViewModel.recentSideCaptions.collectAsStateWithLifecycle()
    val speakerBubbles by conversationViewModel.speakerBubbles
        .collectAsStateWithLifecycle()
    val trackedFaces by conversationViewModel.trackedFaces
        .collectAsStateWithLifecycle()
    val lipSignals by conversationViewModel.lipSignals
        .collectAsStateWithLifecycle()
    val rawBaseTextSize by conversationViewModel.baseTextSize
        .collectAsStateWithLifecycle()
    val emphasisEnabled by conversationViewModel.emphasisEnabled
        .collectAsStateWithLifecycle()
    val emphasisColorIntensity by conversationViewModel.emphasisColorIntensity
        .collectAsStateWithLifecycle()

    val emphasisToneMode = EmphasisToneMode.fromValue(settings.emphasisToneMode)
    val distanceMode = ConversationDistanceMode.fromValue(settings.conversationDistanceMode)
    val dynamicBubbleScale by conversationViewModel.dynamicBubbleScale.collectAsStateWithLifecycle()
    val animatedDynamicScale by animateFloatAsState(
        targetValue = dynamicBubbleScale,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 60f),
        label = "cardboardDynamicScale",
    )
    // PIXEL10A_CARDBOARD_TEXT_SCALE replaces the old 0.78f — tuned for the Pixel 10a's
    // 422 ppi display + standard Cardboard v2 lens magnification (~1.6×).
    val baseTextSize = (rawBaseTextSize * distanceMode.textScale * PIXEL10A_CARDBOARD_TEXT_SCALE * animatedDynamicScale)
        .coerceAtLeast(11f)

    val bubblesToRender = remember(speakerBubbles) {
        speakerBubbles
            .filter { it.text.isNotBlank() }
            .take(3)
    }

    // Half of the total horizontal disparity per eye.  Left eye shifts right
    // (positive), right eye shifts left (negative) — crossed disparity makes
    // the captions float in front of the camera passthrough plane.
    // If the user has never changed the setting it stays at the DataStore default (40 px);
    // coerce to the Pixel 10a optimum so first-launch feels right on this device.
    val effectiveParallaxPx = settings.cardboardParallaxPx
        .coerceAtLeast(PIXEL10A_DEFAULT_PARALLAX_PX)
        .coerceAtMost(80)
    val halfParallaxPx = effectiveParallaxPx / 2

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            StereoEye(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                primaryEye = true,
                previewView = previewView,
                snapshot = null,
                bubblesToRender = bubblesToRender,
                recentSideCaptions = recentSideCaptions,
                trackedFaces = trackedFaces,
                lipSignals = lipSignals,
                baseTextSize = baseTextSize,
                bubbleOpacity = settings.bubbleOpacity,
                emphasisEnabled = emphasisEnabled,
                emphasisColorIntensity = emphasisColorIntensity,
                emphasisToneMode = emphasisToneMode,
                emphasisToneFadeSeconds = settings.emphasisToneFadeSeconds,
                emphasisToneResponse = settings.emphasisToneResponse,
                emphasisQuietFloor = settings.emphasisQuietFloor,
                emphasisLoudTrigger = settings.emphasisLoudTrigger,
                distanceMode = distanceMode,
                parallaxOffsetPx = +halfParallaxPx,
                onLongPressExit = onBack,
            )
            // Nose bridge gap — PIXEL10A_NOSE_BRIDGE_DP is sized to match the
            // physical housing cutout on the Pixel 10a's 6.3" form factor.
            Spacer(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(PIXEL10A_NOSE_BRIDGE_DP.dp)
                    .background(Color.Black),
            )
            StereoEye(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                primaryEye = false,
                previewView = null,
                snapshot = rightEyeSnapshot,
                bubblesToRender = bubblesToRender,
                recentSideCaptions = recentSideCaptions,
                trackedFaces = trackedFaces,
                lipSignals = lipSignals,
                baseTextSize = baseTextSize,
                bubbleOpacity = settings.bubbleOpacity,
                emphasisEnabled = emphasisEnabled,
                emphasisColorIntensity = emphasisColorIntensity,
                emphasisToneMode = emphasisToneMode,
                emphasisToneFadeSeconds = settings.emphasisToneFadeSeconds,
                emphasisToneResponse = settings.emphasisToneResponse,
                emphasisQuietFloor = settings.emphasisQuietFloor,
                emphasisLoudTrigger = settings.emphasisLoudTrigger,
                distanceMode = distanceMode,
                parallaxOffsetPx = -halfParallaxPx,
                onLongPressExit = onBack,
            )
        }
    }
}

@Composable
private fun StereoEye(
    modifier: Modifier,
    primaryEye: Boolean,
    previewView: PreviewView?,
    snapshot: androidx.compose.ui.graphics.ImageBitmap?,
    bubblesToRender: List<com.talksync.model.SpeechBubbleData>,
    recentSideCaptions: List<VisibleCaptionUi>,
    trackedFaces: List<com.talksync.vision.TrackedFace>,
    lipSignals: Map<String, com.talksync.vision.LipLandmarkAnalyzer.LipSignal>,
    baseTextSize: Float,
    bubbleOpacity: Float,
    emphasisEnabled: Boolean,
    emphasisColorIntensity: Float,
    emphasisToneMode: EmphasisToneMode,
    emphasisToneFadeSeconds: Float,
    emphasisToneResponse: Float,
    emphasisQuietFloor: Float,
    emphasisLoudTrigger: Float,
    distanceMode: ConversationDistanceMode,
    /** Horizontal pixel shift to apply to every bubble — positive shifts right,
     *  negative shifts left. Left eye receives +halfParallax, right eye -halfParallax
     *  so the captions appear to float in front of the camera passthrough plane. */
    parallaxOffsetPx: Int = 0,
    onLongPressExit: () -> Unit,
) {
    val density = LocalDensity.current
    val bubbleRowSizes = remember { mutableStateMapOf<String, IntSize>() }

    BoxWithConstraints(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onLongPressExit() },
                )
            },
    ) {
        // Camera passthrough.
        if (primaryEye && previewView != null) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (snapshot != null) {
            Image(
                painter = BitmapPainter(snapshot),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // Black frame until the first snapshot lands.
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        }

        // Bubble overlay. Layout independently per-eye so each half-width
        // gets a properly positioned set of bubbles.
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val marginPx = with(density) { 12.dp.toPx() }
        val minWidthPx = with(density) { 100.dp.toPx() }
        val topSpacingPx = with(density) { distanceMode.anchorLiftDp.dp.toPx() }
        val recentPanelWidth = (maxWidth * 0.38f).coerceIn(120.dp, 180.dp)
        val reservedSideWidth = if (recentSideCaptions.isNotEmpty()) recentPanelWidth + 16.dp else 0.dp
        val maxBubbleWidth = (maxWidth * distanceMode.maxBubbleWidthFraction)
            .coerceAtLeast(120.dp)
            .coerceAtMost((maxWidth - reservedSideWidth - 32.dp).coerceAtLeast(120.dp))
        val fallbackBubbleSize = remember(density) {
            with(density) {
                IntSize(
                    width = 160.dp.roundToPx(),
                    height = 64.dp.roundToPx(),
                )
            }
        }

        val bubbleLayouts = remember(
            reservedSideWidth,
            bubblesToRender,
            trackedFaces,
            lipSignals,
            bubbleRowSizes.toMap(),
            maxWidthPx,
            maxHeightPx,
        ) {
            val facesById = trackedFaces.associateBy { it.trackId }
            val renderModels = bubblesToRender.map { bubble ->
                val anchorFaceId = (bubble.lipDrivenAnchorId ?: bubble.anchorFaceId)
                val mouthSignal = anchorFaceId?.let { lipSignals[it] }
                BubbleRenderModel(
                    bubble = bubble,
                    anchorBounds = (bubble.lipDrivenAnchorId ?: bubble.anchorFaceId)?.let { facesById[it]?.boundingBox },
                    bubbleSize = bubbleRowSizes[bubble.id] ?: fallbackBubbleSize,
                    mouthAnchor = mouthSignal?.let {
                        MouthAnchor(it.mouthCenterX, it.mouthCenterY)
                    },
                )
            }
            resolveBubbleLayouts(
                models = renderModels,
                maxWidthPx = maxWidthPx,
                reservedRightPx = with(density) { reservedSideWidth.toPx() },
                maxHeightPx = maxHeightPx,
                marginPx = marginPx,
                minWidthPx = minWidthPx,
                defaultTopFraction = 0.22f,
                topSpacingPx = topSpacingPx,
            )
        }

        bubbleLayouts.forEach { layout ->
            Box(
                modifier = Modifier
                    .offset { IntOffset(layout.offset.x + parallaxOffsetPx, layout.offset.y) }
                    .onGloballyPositioned { coords ->
                        val current = bubbleRowSizes[layout.bubble.id]
                        val next = IntSize(coords.size.width, coords.size.height)
                        if (current == null ||
                            current.width != next.width ||
                            current.height != next.height
                        ) {
                            bubbleRowSizes[layout.bubble.id] = next
                        }
                    },
            ) {
                SpeechBubble(
                    bubbleData = layout.bubble,
                    baseTextSizeSp = baseTextSize,
                    bubbleOpacity = bubbleOpacity,
                    emphasisEnabled = emphasisEnabled,
                    emphasisColorIntensity = emphasisColorIntensity,
                    emphasisToneMode = emphasisToneMode,
                    emphasisToneFadeSeconds = emphasisToneFadeSeconds,
                    emphasisToneResponse = emphasisToneResponse,
                    emphasisQuietFloor = emphasisQuietFloor,
                    emphasisLoudTrigger = emphasisLoudTrigger,
                    maxBubbleWidth = maxBubbleWidth,
                )
            }
        }
        RecentCaptionSideBar(
            entries = recentSideCaptions,
            enabled = true,
            panelWidth = recentPanelWidth,
            maxPanelHeight = maxHeight * 0.60f,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
        )


        // Tiny "VR" pill so the user knows they're in the right mode when
        // they put on the headset for the first time. Long-press anywhere
        // to exit.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.55f),
            ) {
                Text(
                    text = if (primaryEye) "L" else "R",
                    color = Color.White,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}
