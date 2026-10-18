package com.talksync.vision

// camerax preview/analyzer/zoom manager. camera lifecycle bugs love this file.

import android.content.Context
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.talksync.emotion.HumeEmotionEngine
import com.talksync.model.ConversationInputMode
import com.talksync.translation.AslTranslationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

data class CameraZoomState(
    val zoomRatio: Float = 1f,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
)

class CameraManager(private val context: Context) {
    private data class UseCaseCandidate(
        val label: String,
        val useCases: List<UseCase>,
    )

    private var cameraProvider: ProcessCameraProvider? = null
    private var currentCamera: Camera? = null
    private var zoomObserverOwner: LifecycleOwner? = null
    private var zoomObserver: Observer<ZoomState>? = null
    private var bindGeneration: Long = 0L
    private var requestedZoomRatio: Float = 1f
    private var humeEngine: HumeEmotionEngine? = null
    private var aslTranslationEngine: AslTranslationEngine? = null
    private val humeAnalysisExecutor = Executors.newSingleThreadExecutor()

    private val _zoomState = MutableStateFlow(CameraZoomState())
    val zoomState: StateFlow<CameraZoomState> = _zoomState.asStateFlow()

    fun attachHumeEngine(engine: HumeEmotionEngine) {
        humeEngine = engine
    }

    fun attachAslTranslationEngine(engine: AslTranslationEngine) {
        aslTranslationEngine = engine
    }

    fun bind(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        faceTracker: FaceTracker? = null,
        lensFacing: Int = CameraSelector.LENS_FACING_BACK,
        inputMode: ConversationInputMode = ConversationInputMode.TRANSCRIPTION,
        useUltraWide: Boolean = false,
    ) {
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        val generation = ++bindGeneration
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                if (generation != bindGeneration) {
                    return@addListener
                }

                val provider = try {
                    cameraProviderFuture.get()
                } catch (e: Exception) {
                    Log.e("CameraManager", "Unable to obtain camera provider", e)
                    return@addListener
                }

                cameraProvider = provider
                val resolvedLensFacing = resolveLensFacing(provider, lensFacing) ?: return@addListener
                if (generation != bindGeneration) {
                    return@addListener
                }

                bindUseCases(
                    provider = provider,
                    previewView = previewView,
                    lifecycleOwner = lifecycleOwner,
                    faceTracker = faceTracker,
                    lensFacing = resolvedLensFacing,
                    inputMode = inputMode,
                    useUltraWide = useUltraWide,
                )
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun unbind() {
        bindGeneration += 1
        removeZoomObserver()
        currentCamera = null
        cameraProvider?.unbindAll()
        _zoomState.value = CameraZoomState(
            zoomRatio = requestedZoomRatio.coerceAtLeast(1f),
            minZoomRatio = 1f,
            maxZoomRatio = 1f,
        )
    }

    fun setZoomRatio(zoomRatio: Float) {
        requestedZoomRatio = zoomRatio
        val currentZoomState = _zoomState.value
        val clampedZoom = zoomRatio.coerceIn(
            currentZoomState.minZoomRatio,
            currentZoomState.maxZoomRatio,
        )
        _zoomState.value = currentZoomState.copy(zoomRatio = clampedZoom)
        currentCamera?.cameraControl?.setZoomRatio(clampedZoom)
    }

    fun multiplyZoom(scaleFactor: Float) {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) {
            return
        }
        setZoomRatio(_zoomState.value.zoomRatio * scaleFactor)
    }

    fun nudgeZoom(deltaRatio: Float) {
        setZoomRatio(_zoomState.value.zoomRatio + deltaRatio)
    }

    fun resetZoom() {
        setZoomRatio(1f)
    }

    fun release() {
        unbind()
        humeAnalysisExecutor.shutdown()
    }

    private fun bindUseCases(
        provider: ProcessCameraProvider,
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        faceTracker: FaceTracker?,
        lensFacing: Int,
        inputMode: ConversationInputMode,
        useUltraWide: Boolean = false,
    ) {
        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val previewResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(previewResolutionSelector)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            previewBuilder.setTargetFrameRate(Range(30, 30))
        }
        val preview = previewBuilder.build()
            .apply { setSurfaceProvider(previewView.surfaceProvider) }

        // 1280x720 analysis (up from 640x480) gives MediaPipe ~3x the pixels so
        // it resolves lip landmarks on smaller/farther faces. STRATEGY_KEEP_ONLY_LATEST
        // + the analyzer's in-flight guard drop frames gracefully if a device
        // can't keep cadence at this resolution.
        val analysisResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
            )
            .build()

        // start w preview, add only analyzers this mode needs
        val baseUseCases = mutableListOf<UseCase>(preview)

        if (faceTracker != null) {
            faceTracker.setLensFacing(lensFacing)
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisResolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { imageAnalysis ->
                    imageAnalysis.setAnalyzer(faceTracker.analysisExecutor, faceTracker)
                }
            baseUseCases += analysis
        }

        var humeAnalysis: UseCase? = null
        if (inputMode == ConversationInputMode.TRANSCRIPTION) {
            humeEngine?.let { engine ->
                engine.updateLensFacing(lensFacing)
                val humeResolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(480, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build()
                humeAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(humeResolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(humeAnalysisExecutor) { imageProxy ->
                            engine.onCameraFrame(imageProxy)
                        }
                    }
            }
        } else {
            // asl path lighter, no extra hume analyzer here
            aslTranslationEngine?.updateLensFacing(lensFacing)
        }

        val candidates = buildList {
            if (humeAnalysis != null) {
                add(
                    UseCaseCandidate(
                        label = "preview + face tracker + hume",
                        useCases = baseUseCases + humeAnalysis!!,
                    ),
                )
            }
            add(
                UseCaseCandidate(
                    label = "preview + face tracker",
                    useCases = baseUseCases.toList(),
                ),
            )
            if (baseUseCases.size > 1) {
                add(
                    UseCaseCandidate(
                        label = "preview only",
                        useCases = listOf(preview),
                    ),
                )
            }
        }

        currentCamera = bindWithFallbacks(
            provider = provider,
            lifecycleOwner = lifecycleOwner,
            selector = selector,
            candidates = candidates,
        )
        if (currentCamera == null) {
            _zoomState.value = CameraZoomState(
                zoomRatio = requestedZoomRatio.coerceAtLeast(1f),
                minZoomRatio = 1f,
                maxZoomRatio = 1f,
            )
            return
        }
        observeZoomState(lifecycleOwner)
        if (useUltraWide) {
            val minZoom = currentCamera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
            setZoomRatio(if (minZoom < 1f) minZoom else requestedZoomRatio)
        } else {
            setZoomRatio(requestedZoomRatio)
        }
    }

    private fun bindWithFallbacks(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        selector: CameraSelector,
        candidates: List<UseCaseCandidate>,
    ): Camera? {
        var lastError: Throwable? = null
        candidates.forEachIndexed { index, candidate ->
            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    *candidate.useCases.toTypedArray(),
                )
                if (index > 0) {
                    Log.w("CameraManager", "Recovered camera bind using ${candidate.label}")
                }
                return camera
            } catch (t: Throwable) {
                lastError = t
                Log.w("CameraManager", "Camera bind failed for ${candidate.label}", t)
            }
        }

        provider.unbindAll()
        Log.e("CameraManager", "Unable to bind any camera use case combination", lastError)
        return null
    }

    private fun resolveLensFacing(
        provider: ProcessCameraProvider,
        lensFacing: Int,
    ): Int? {
        val requestedSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
        if (provider.hasCameraSafely(requestedSelector)) {
            return lensFacing
        }

        val fallbackLensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val fallbackSelector = CameraSelector.Builder()
            .requireLensFacing(fallbackLensFacing)
            .build()
        return if (provider.hasCameraSafely(fallbackSelector)) {
            Log.w("CameraManager", "Falling back to lens facing $fallbackLensFacing")
            fallbackLensFacing
        } else {
            Log.e("CameraManager", "No available camera for requested lens facing $lensFacing")
            null
        }
    }

    private fun observeZoomState(lifecycleOwner: LifecycleOwner) {
        removeZoomObserver()

        val camera = currentCamera ?: return
        val observer = Observer<ZoomState> { zoomState ->
            val nextState = CameraZoomState(
                zoomRatio = zoomState.zoomRatio,
                minZoomRatio = zoomState.minZoomRatio,
                maxZoomRatio = zoomState.maxZoomRatio,
            )
            _zoomState.value = nextState
            // clamp remembered zoom if hardware range shifts
            requestedZoomRatio = requestedZoomRatio.coerceIn(
                nextState.minZoomRatio,
                nextState.maxZoomRatio,
            )
        }

        camera.cameraInfo.zoomState.observe(lifecycleOwner, observer)
        zoomObserverOwner = lifecycleOwner
        zoomObserver = observer

        camera.cameraInfo.zoomState.value?.let { zoomState ->
            _zoomState.value = CameraZoomState(
                zoomRatio = zoomState.zoomRatio,
                minZoomRatio = zoomState.minZoomRatio,
                maxZoomRatio = zoomState.maxZoomRatio,
            )
        }
    }

    private fun removeZoomObserver() {
        val camera = currentCamera
        val owner = zoomObserverOwner
        val observer = zoomObserver
        if (camera != null && owner != null && observer != null) {
            camera.cameraInfo.zoomState.removeObserver(observer)
            camera.cameraInfo.zoomState.removeObservers(owner)
        }
        zoomObserverOwner = null
        zoomObserver = null
    }

    private fun ProcessCameraProvider.hasCameraSafely(selector: CameraSelector): Boolean {
        return try {
            hasCamera(selector)
        } catch (e: Exception) {
            false
        }
    }

}
