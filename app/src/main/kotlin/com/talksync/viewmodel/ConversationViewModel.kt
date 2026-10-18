package com.talksync.viewmodel

// giant coordinator for speech, vision, translation, summaries, and saving stuff. careful in here.

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.talksync.ai.ClaudeConfig
import com.talksync.ai.ClaudeEngine
import com.talksync.ai.ForeignTextTranslation
import com.talksync.audio.AudioAnalyzer
import com.talksync.audio.AudioDirection
import com.talksync.audio.AzureSpeechConfig
import com.talksync.audio.DeepgramConfig
import com.talksync.audio.SonioxConfig
import com.talksync.audio.DirectionalAudioService
import com.talksync.audio.SpeakingLoudnessMonitor
import com.talksync.audio.FinalizedSpeech
import com.talksync.audio.SpeechUpdate
import com.talksync.model.SpeakerBubbleState
import com.talksync.model.RecentCaptionRef
import com.talksync.model.RecentCaptionState
import com.talksync.audio.SpeechBackendKind
import com.talksync.audio.SpeechEngineHolder
import com.talksync.audio.StereoPanAnalyzer
import com.talksync.audio.UserVoiceActivityDetector
import com.talksync.audio.VolumeWordBuffer
import com.talksync.audio.WordVolume
import com.talksync.data.AppDatabase
import com.talksync.data.AppSettings
import com.talksync.data.ConversationSummaryStore
import com.talksync.data.SettingsStore
import com.talksync.emotion.HumeConfig
import com.talksync.emotion.EmotionState
import com.talksync.emotion.HumeEmotionEngine
import com.talksync.emotion.persistedLabel
import com.talksync.model.BubbleAnchorMode
import com.talksync.model.ConversationInputMode
import com.talksync.model.ConversationQuestionState
import com.talksync.model.ConversationSummaryRequest
import com.talksync.model.ExpandedToken
import com.talksync.model.FunctionalityCheck
import com.talksync.model.FunctionalityReport
import com.talksync.model.FunctionalityStatus
import com.talksync.model.IntentLabel
import com.talksync.model.IntentLabeler
import com.talksync.model.SpeechBubbleData
import com.talksync.model.TranscriptEntry
import com.talksync.audio.DeepLTranslationEngine
import com.talksync.audio.ElevenLabsTranscriptionEngine
import com.talksync.translation.AslTranslationEngine
import com.talksync.translation.LanguageIdentifier
import com.talksync.util.SlangExpander
import com.talksync.util.toHistoryTimestamp
import com.talksync.vision.AVHypothesis
import com.talksync.vision.CameraZoomState
import com.talksync.vision.CameraManager
import com.talksync.vision.FaceIdentity
import com.talksync.vision.FaceIdentityMatcher
import com.talksync.vision.FaceTracker
import com.talksync.vision.LipLandmarkAnalyzer
import com.talksync.vision.LipReadingCoordinator
import com.talksync.vision.NoiseEstimator
import com.talksync.vision.SpeakerFaceAssignmentStore
import com.talksync.vision.TrackedFace
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

data class VisibleCaptionUi(
    val id: String,
    val speakerKey: String = SpeechBubbleData.DEFAULT_SPEAKER_KEY,
    val speakerLabel: String? = null,
    val anchorFaceId: String? = null,
    val text: String,
    val timestamp: Long,
    val wordVolumes: List<WordVolume>,
    val emotionState: EmotionState,
    val detectedLanguage: String? = null,
    val translatedText: String? = null,
    val isTranslating: Boolean = false,
    val expandedTokens: List<ExpandedToken>,
    val intentLabels: List<IntentLabel> = emptyList(),
    val threadId: String? = null,
    val replyToSpeakerKey: String? = null,
    val threadTurnIndex: Int = 0,
    val isThreadHighlighted: Boolean = false,
    val isFading: Boolean = false,
)

data class VisualSpeakerCue(
    val faceId: String,
    val score: Float,
    val mouthScore: Float,
    val gazeAttentionScore: Float,
    val audioActivityScore: Float,
    val timestamp: Long,
)

private data class BubbleThreadMeta(
    val threadId: String? = null,
    val replyToSpeakerKey: String? = null,
    val threadTurnIndex: Int = 0,
    val isThreadHighlighted: Boolean = false,
)

private data class ThreadState(
    val id: String,
    val speakerKeys: Set<String>,
    val lastSpeakerKey: String,
    val lastTimestamp: Long,
    val turnCount: Int,
)

private data class RecentFinalTurn(
    val speakerKey: String,
    val timestamp: Long,
    val threadId: String?,
)

class ConversationViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val TAG = "ConversationViewModel"
        const val UI_CHROME_IDLE_DELAY_MS = 5_000L
        const val UI_CHROME_MANUAL_REVEAL_MS = 3_500L
        // After diarization reports a speaker change, the visual lip cue is
        // allowed to move a bubble onto a different face for this long. Outside
        // the window the anchor is held steady so lip flicker can't switch faces.
        const val DIARIZATION_ANCHOR_WINDOW_MS = 1_500L
        // Smart bubble lifetimes
        const val THREAD_REPLY_GAP_MS = 24_000L
        const val THREAD_SPEAKER_LIMIT = 2
        const val STALE_CONFIRMED_RESTORE_WINDOW_MS = 4_000L
        const val PERSISTENT_ASL_BUBBLE_ID = "asl-live"
        const val BUBBLE_VISIBLE_DURATION_MS = 5_000L
        const val TRANSLATED_BUBBLE_VISIBLE_DURATION_MS = 4_000L
        // Bluetooth mic echo suppression: window after user stops talking
        const val LOCAL_SPEECH_SUPPRESSION_WINDOW_MS = 1_200L
    }

    private val appDatabase = AppDatabase.getInstance(application)
    private val transcriptDao = appDatabase.transcriptDao()
    private val settingsStore = SettingsStore(application)
    private val conversationSummaryStore = ConversationSummaryStore(application)
    private val claudeEngine = ClaudeEngine()
    private val humeEngine = HumeEmotionEngine()
    private val aslTranslationEngine = AslTranslationEngine(application)
    private val deepLTranslationEngine = DeepLTranslationEngine()
    private val elevenLabsEngine = ElevenLabsTranscriptionEngine()
    private val userVoiceDetector = UserVoiceActivityDetector()
    private val loudnessMonitor = SpeakingLoudnessMonitor()
    private val audioAnalyzer = AudioAnalyzer()
    private val faceTracker = FaceTracker()
    private val cameraManager = CameraManager(application)
    private val deepgramConfigured = DeepgramConfig.API_KEY.isNotBlank() &&
        DeepgramConfig.API_KEY != DeepgramConfig.PLACEHOLDER_API_KEY
    private val azureConfigured = AzureSpeechConfig.isConfigured
    private val sonioxConfigured = SonioxConfig.isConfigured
    private val claudeConfigured = ClaudeConfig.isConfigured
    private val humeConfigured = HumeConfig.API_KEY.isNotBlank() &&
        HumeConfig.API_KEY != HumeConfig.PLACEHOLDER_API_KEY
    private val aslModelBundled = runCatching {
        application.assets.list("")?.contains("gesture_recognizer.task") == true
    }.getOrDefault(false)
    private val historyStoreReady = runCatching {
        appDatabase.openHelper.readableDatabase
    }.isSuccess
    val volumeWordBuffer = VolumeWordBuffer()

    val settings: StateFlow<AppSettings> = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings(),
    )

    val historyEntries = transcriptDao.getAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptyList(),
    )
    val conversationSummaries: StateFlow<Map<String, String>> = conversationSummaryStore.summaries
    private val _conversationQuestionStates = MutableStateFlow<Map<String, ConversationQuestionState>>(emptyMap())
    val conversationQuestionStates: StateFlow<Map<String, ConversationQuestionState>> =
        _conversationQuestionStates.asStateFlow()

    val volumeLevel = audioAnalyzer.volumeLevel
    val currentWordVolumes: StateFlow<List<WordVolume>> = volumeWordBuffer.wordVolumes
    val faceBoundingBox = faceTracker.faceBoundingBox
    val trackedFaces: StateFlow<List<TrackedFace>> = faceTracker.trackedFaces
    val emotionState: StateFlow<EmotionState> = humeEngine.emotionState
    val humeConnected: StateFlow<Boolean> = humeEngine.isConnected
    val cameraZoomState: StateFlow<CameraZoomState> = cameraManager.zoomState
    val emphasisEnabled: StateFlow<Boolean> = settings
        .map { it.visualEmphasisEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val emphasisColorEnabled: StateFlow<Boolean> = settings
        .map { it.emphasisColorIntensity > 0f }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val emphasisColorIntensity: StateFlow<Float> = settings
        .map { it.emphasisColorIntensity }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)
    val baseTextSize: StateFlow<Float> = settings
        .map { it.textSizeSp }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 18f)

    private val speechEngine = SpeechEngineHolder(application)

    private val _bubbleData = MutableStateFlow(SpeechBubbleData())
    val bubbleData: StateFlow<SpeechBubbleData> = _bubbleData.asStateFlow()

    private val _speakerBubbles = MutableStateFlow<List<SpeechBubbleData>>(emptyList())
    val speakerBubbles: StateFlow<List<SpeechBubbleData>> = _speakerBubbles.asStateFlow()

    private val _currentTranscript = MutableStateFlow<List<VisibleCaptionUi>>(emptyList())
    val currentTranscript: StateFlow<List<VisibleCaptionUi>> = _currentTranscript.asStateFlow()
    private val recentCaptionRefs = MutableStateFlow<List<RecentCaptionRef>>(emptyList())
    val recentSideCaptions: StateFlow<List<VisibleCaptionUi>> = combine(
        recentCaptionRefs,
        _currentTranscript,
    ) { refs, transcript ->
        val byId = transcript.associateBy { it.id }
        refs.mapNotNull { ref -> byId[ref.captionId]?.copy(isFading = ref.isFading) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val _overlayEnabled = MutableStateFlow(true)
    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled.asStateFlow()

    private val _historyLogExpanded = MutableStateFlow(false)
    val historyLogExpanded: StateFlow<Boolean> = _historyLogExpanded.asStateFlow()

    private val _cameraLensFacing = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val cameraLensFacing: StateFlow<Int> = _cameraLensFacing.asStateFlow()

    private val _inputMode = MutableStateFlow(ConversationInputMode.TRANSCRIPTION)
    val inputMode: StateFlow<ConversationInputMode> = _inputMode.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Ready")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _showOriginalLanguage = MutableStateFlow(false)
    val showOriginalLanguage: StateFlow<Boolean> = _showOriginalLanguage.asStateFlow()
    private val _uiChromeVisible = MutableStateFlow(true)
    val uiChromeVisible: StateFlow<Boolean> = _uiChromeVisible.asStateFlow()
    val isUserTalking: StateFlow<Boolean> = userVoiceDetector.isUserTalking
    val isSpeakingLoud: StateFlow<Boolean> = loudnessMonitor.isSpeakingLoud
    val activeSpeakerCount: StateFlow<Int> = speakerBubbles
        .map { bubbles ->
            bubbles
                .filter { it.text.isNotBlank() && it.isVisible }
                .map { it.speakerKey }
                .distinct()
                .size
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // Auto-scales bubbles based on how close faces are and how many speakers are active.
    // Larger face → closer speaker → bigger text. More speakers → smaller text to fit.
    val dynamicBubbleScale: StateFlow<Float> = combine(
        trackedFaces,
        activeSpeakerCount,
    ) { faces, speakerCount ->
        val avgFaceHeight = faces
            .map { it.boundingBox.height() }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toFloat()
        val proximityScale = when {
            avgFaceHeight == null  -> 1.00f
            avgFaceHeight > 0.42f -> 1.22f
            avgFaceHeight > 0.28f -> 1.10f
            avgFaceHeight > 0.14f -> 1.00f
            avgFaceHeight > 0.07f -> 0.90f
            else                  -> 0.82f
        }
        val speakerScale = when {
            speakerCount >= 3 -> 0.78f
            speakerCount == 2 -> 0.88f
            else              -> 1.00f
        }
        (proximityScale * speakerScale).coerceIn(0.60f, 1.30f)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)
    val functionalityReport: StateFlow<FunctionalityReport> = combine(
        speechEngine.isConnected,
        speechEngine.connectionError,
        humeConnected,
        emotionState,
        trackedFaces,
        settings,
        inputMode,
        aslTranslationEngine.statusText,
        speakerBubbles,
        historyEntries,
        volumeLevel,
        isUserTalking,
        isSpeakingLoud,
    ) { values: Array<Any?> ->
        val speechConnected = values[0] as Boolean
        val speechError = values[1] as String?
        val humeLive = values[2] as Boolean
        val emotion = values[3] as EmotionState
        val faces = values[4] as List<TrackedFace>
        val settingsValue = values[5] as AppSettings
        val activeInputMode = values[6] as ConversationInputMode
        val aslStatus = values[7] as String
        val bubbles = values[8] as List<SpeechBubbleData>
        val history = values[9] as List<TranscriptEntry>
        val liveVolume = values[10] as Float
        val userTalking = values[11] as Boolean
        val speakingLoud = values[12] as Boolean
        val activeBackendKind = SpeechBackendKind.fromValue(settingsValue.speechBackend)
        val backendConfigured = when (activeBackendKind) {
            SpeechBackendKind.DEEPGRAM -> deepgramConfigured
            SpeechBackendKind.SONIOX -> sonioxConfigured
            SpeechBackendKind.AZURE -> azureConfigured
        }
        val backendLabel = when (activeBackendKind) {
            SpeechBackendKind.DEEPGRAM -> "Deepgram"
            SpeechBackendKind.SONIOX -> "Soniox"
            SpeechBackendKind.AZURE -> "Azure Speech"
        }
        val backendSecretName = when (activeBackendKind) {
            SpeechBackendKind.DEEPGRAM -> "deepgramApiKey"
            SpeechBackendKind.SONIOX -> "sonioxApiKey"
            SpeechBackendKind.AZURE -> "azureSpeechKey"
        }
        val speechCheck = when {
            !backendConfigured -> FunctionalityCheck(
                title = "Speech backend",
                detail = "$backendLabel key missing. Add $backendSecretName to TalkSync/secrets.properties.",
                status = FunctionalityStatus.NEEDS_SETUP,
            )
            speechError != null -> FunctionalityCheck(
                title = "Speech backend",
                detail = speechError,
                status = FunctionalityStatus.WARNING,
            )
            speechConnected && activeInputMode == ConversationInputMode.TRANSCRIPTION -> FunctionalityCheck(
                title = "Speech backend",
                detail = if (liveVolume > 0.04f) {
                    "$backendLabel live transcription, diarization, and mic capture are active."
                } else {
                    "$backendLabel connected and waiting for speech."
                },
                status = FunctionalityStatus.LIVE,
            )
            else -> FunctionalityCheck(
                title = "Speech backend",
                detail = "$backendLabel configured and ready for live transcription.",
                status = FunctionalityStatus.READY,
            )
        }

        val emotionCheck = when {
            !humeConfigured -> FunctionalityCheck(
                title = "Emotion backend",
                detail = "Hume key missing. Add it in TalkSync/secrets.properties.",
                status = FunctionalityStatus.NEEDS_SETUP,
            )
            humeLive && activeInputMode == ConversationInputMode.TRANSCRIPTION -> FunctionalityCheck(
                title = "Emotion backend",
                detail = if (emotion.confidence > 0f) {
                    "Face + vocal tone fusion is live."
                } else {
                    "Connected and waiting for expressive input."
                },
                status = FunctionalityStatus.LIVE,
            )
            else -> FunctionalityCheck(
                title = "Emotion backend",
                detail = "Configured for face, prosody, and text tone cues.",
                status = FunctionalityStatus.READY,
            )
        }

        val faceCheck = when {
            settingsValue.bubbleAnchorMode == BubbleAnchorMode.FACE_DOWN.value -> FunctionalityCheck(
                title = "Face tracking",
                detail = "Camera anchoring is intentionally off in face-down mode.",
                status = FunctionalityStatus.WAITING,
            )
            faces.isNotEmpty() -> FunctionalityCheck(
                title = "Face tracking",
                detail = "${faces.size} face(s) tracked for bubble anchoring.",
                status = FunctionalityStatus.LIVE,
            )
            isConversationRunning -> FunctionalityCheck(
                title = "Face tracking",
                detail = "Camera is up, waiting to lock onto faces.",
                status = FunctionalityStatus.WAITING,
            )
            else -> FunctionalityCheck(
                title = "Face tracking",
                detail = "Ready to anchor bubbles when the camera opens.",
                status = FunctionalityStatus.READY,
            )
        }

        val aslCheck = when {
            !aslModelBundled -> FunctionalityCheck(
                title = "ASL beta",
                detail = "The gesture model asset is missing from the build.",
                status = FunctionalityStatus.WARNING,
            )
            activeInputMode == ConversationInputMode.ASL_TRANSLATION && aslStatus.contains("ASL beta") -> FunctionalityCheck(
                title = "ASL beta",
                detail = aslStatus,
                status = FunctionalityStatus.LIVE,
            )
            else -> FunctionalityCheck(
                title = "ASL beta",
                detail = "On-device sign recognition is bundled and ready.",
                status = FunctionalityStatus.READY,
            )
        }

        val awarenessCheck = when {
            speakingLoud -> FunctionalityCheck(
                title = "User awareness",
                detail = "Voice activity + loudness nudge are currently active.",
                status = FunctionalityStatus.LIVE,
            )
            userTalking -> FunctionalityCheck(
                title = "User awareness",
                detail = "The app can tell when the user starts talking.",
                status = FunctionalityStatus.LIVE,
            )
            else -> FunctionalityCheck(
                title = "User awareness",
                detail = "User-speaking and loudness awareness are ready.",
                status = FunctionalityStatus.READY,
            )
        }

        val historyCheck = when {
            !historyStoreReady -> FunctionalityCheck(
                title = "History",
                detail = "Transcript storage is unavailable right now.",
                status = FunctionalityStatus.WARNING,
            )
            history.isNotEmpty() -> FunctionalityCheck(
                title = "History",
                detail = "${history.size} saved captions are already available.",
                status = FunctionalityStatus.READY,
            )
            else -> FunctionalityCheck(
                title = "History",
                detail = "Conversation logging is enabled and ready to save sessions.",
                status = FunctionalityStatus.READY,
            )
        }

        FunctionalityReport(
            checks = listOf(
                speechCheck,
                emotionCheck,
                faceCheck,
                aslCheck,
                awarenessCheck,
                historyCheck,
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = FunctionalityReport(),
    )

    val aslStatusText: StateFlow<String> = aslTranslationEngine.statusText

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val lipLandmarkAnalyzer = LipLandmarkAnalyzer(getApplication())
    /** Exposed so the bubble overlay can anchor captions to the mouth center. */
    val lipSignals: StateFlow<Map<String, LipLandmarkAnalyzer.LipSignal>> =
        lipLandmarkAnalyzer.lipSignals
    private val noiseEstimator = NoiseEstimator()
    private val lipReadingCoordinator = LipReadingCoordinator(getApplication())
    val noiseLevel: StateFlow<Float> = noiseEstimator.noiseLevel
    val lipReadingResults: StateFlow<Map<String, AVHypothesis>> =
        lipReadingCoordinator.lipReadingResults
    private val faceIdentityDao = appDatabase.faceIdentityDao()
    private val faceIdentityMatcher = FaceIdentityMatcher(faceIdentityDao)
    /** Live feed of stored face identities, sorted by recency. */
    val faceIdentities: StateFlow<List<FaceIdentity>> = faceIdentityMatcher.identities

    /** Most recent identityId observed per live faceId. */
    private val faceIdentityIds = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Public read-only view for the debug overlay. */
    val faceIdentityIdsState: StateFlow<Map<String, String>> = faceIdentityIds.asStateFlow()

    /** speakerKey -> identityId, derived from current face assignments. */
    private val speakerIdentityIds = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Snapshot of [SpeakerFaceAssignmentStore] for the debug overlay.
     * Refreshed by [observeFaceSignal] on every face-state tick.
     */
    private val _speakerFaceAssignments =
        MutableStateFlow<Map<String, SpeakerFaceAssignmentStore.Assignment>>(emptyMap())
    val speakerFaceAssignments:
        StateFlow<Map<String, SpeakerFaceAssignmentStore.Assignment>> =
        _speakerFaceAssignments.asStateFlow()
    private val _visualSpeakerCue = MutableStateFlow<VisualSpeakerCue?>(null)
    val visualSpeakerCue: StateFlow<VisualSpeakerCue?> = _visualSpeakerCue.asStateFlow()
    private val stereoPanAnalyzer = StereoPanAnalyzer()
    private val directionalAudio = DirectionalAudioService()
    val audioDirection: StateFlow<AudioDirection> = directionalAudio.direction
    val isStereoCapture: StateFlow<Boolean> = directionalAudio.isStereoCapture
    private val speakerFaceStore = SpeakerFaceAssignmentStore()
    private val speakerLastSeenAt = mutableMapOf<String, Long>()

    private var activeSpeechSpeakerKey: String? = null
    // Diarization-gated anchor switching. Persists across idle/bubble resets
    // (unlike activeSpeechSpeakerKey) so A → (silence) → B still registers as a
    // speaker switch and opens the window for the visual cue to move the anchor.
    private var diarizationActiveSpeakerKey: String? = null
    private var lastDiarizationSwitchAt: Long = 0L
    private val persistentAslSegments = mutableListOf<String>()
    private var persistentAslPartial: String = ""

    private var activeThreadState: ThreadState? = null
    private var lastFinalTurn: RecentFinalTurn? = null
    private val summaryRequestsInFlight = ConcurrentHashMap.newKeySet<String>()
    private val questionRequestsInFlight = ConcurrentHashMap.newKeySet<String>()
    private var isConversationRunning = false
    private var faceDownModeActive = false
    private var noiseGatePaused = false
    private var noiseFloorStartedAt: Long? = null
    @Volatile
    private var lastConversationActivityAt: Long = 0L
    @Volatile
    private var chromeManualRevealUntil: Long = 0L
    private var chromeRevealJob: Job? = null
    private var elevenLabsJob: Job? = null
    private val speakerBubbleClearJobs = mutableMapOf<String, Job>()
    private val finalBubbleCaptionIds = mutableMapOf<String, String>()
    private var recentCaptionExpiryJob: Job? = null
    private val speechCompletionJobs = mutableSetOf<Job>()
    @Volatile private var lastLocalUserSpeechAt: Long = 0L

    // Audio buffer for ElevenLabs — holds raw PCM chunks from the current utterance.
    private val utteranceAudioChunks = ArrayList<ByteArray>(300)
    @Volatile private var isRecordingUtterance = false

    init {
        // main wiring spot for camera + speech + asl + emotion
        cameraManager.attachHumeEngine(humeEngine)
        cameraManager.attachAslTranslationEngine(aslTranslationEngine)
        audioAnalyzer.attachHumeEngine(humeEngine)
        audioAnalyzer.attachVoiceActivityDetector(userVoiceDetector)
        humeEngine.start(viewModelScope)
        observeConnectionState()
        observeSpeechAudioStream()
        observeSpeech()
        observeAslTranslation()
        observeHumeEmotion()
        observeFaceSignal()
        observeVisualSpeakerCue()
        observeWordVolumeSampling()
        observeSettings()
        observeSpeechBackendSetting()
        observeExtendedLanguagesSetting()
        observeVolumeAndNoiseGate()
        observeLipLandmarks()
        observeNoiseEstimator()
        lipLandmarkAnalyzer.initialize()
        observeFaceIdentities()
        observeStereoPan()
        observeLoudnessMonitor()
        observeLocalSpeechSuppression()
        viewModelScope.launch(Dispatchers.Default) {
            LanguageIdentifier.warmUp()
        }
    }

    fun bindCamera(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        lensFacing: Int = CameraSelector.LENS_FACING_BACK,
        inputMode: ConversationInputMode = _inputMode.value,
        useUltraWide: Boolean = false,
    ) {
        cameraManager.bind(
            previewView = previewView,
            lifecycleOwner = lifecycleOwner,
            faceTracker = faceTracker,
            lensFacing = lensFacing,
            inputMode = inputMode,
            useUltraWide = useUltraWide,
        )
    }

    fun unbindCamera() {
        cameraManager.unbind()
    }

    fun startConversation() {
        if (isConversationRunning) {
            return
        }
        isConversationRunning = true
        applyInputMode(_inputMode.value)
    }

    fun stopConversation() {
        isConversationRunning = false
        noiseGatePaused = false
        noiseFloorStartedAt = null
        chromeRevealJob?.cancel()
        _uiChromeVisible.value = true
        chromeManualRevealUntil = 0L
        aslTranslationEngine.stop()
        speechEngine.stop()
        audioAnalyzer.stop()
        humeEngine.updateTranscriptText("")
        volumeWordBuffer.clear()
        loudnessMonitor.reset()
        noiseEstimator.reset()
        clearLiveBubble()
        _connectionStatus.value = "Ready"
        _statusMessage.value = null
    }

    fun toggleOverlay() {
        _overlayEnabled.update { current -> !current }
        if (!_overlayEnabled.value) {
            _bubbleData.update { it.copy(isVisible = false) }
            _speakerBubbles.update { current -> current.map { it.copy(isVisible = false) } }
        } else {
            _speakerBubbles.update { current -> current.map { it.copy(isVisible = it.text.isNotBlank()) } }
        }
        if (_overlayEnabled.value && _bubbleData.value.text.isNotBlank()) {
            _bubbleData.update { it.copy(isVisible = true) }
        }
    }

    fun toggleHistoryLogExpanded() {
        _historyLogExpanded.update { current -> !current }
    }

    fun toggleShowOriginalLanguage() {
        _showOriginalLanguage.update { current -> !current }
    }

    fun toggleCameraLens() {
        val newLensFacing = if (_cameraLensFacing.value == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        _cameraLensFacing.value = newLensFacing
    }

    fun adjustZoomByScale(scaleFactor: Float) {
        cameraManager.multiplyZoom(scaleFactor)
    }

    fun nudgeZoom(deltaRatio: Float) {
        cameraManager.nudgeZoom(deltaRatio)
    }

    fun resetZoom() {
        cameraManager.resetZoom()
    }

    fun onArCameraJpeg(jpegBytes: ByteArray) {
        humeEngine.onCameraJpeg(jpegBytes)
    }

    fun onAslPreviewBitmap(bitmap: Bitmap) {
        if (_inputMode.value == ConversationInputMode.ASL_TRANSLATION && isConversationRunning) {
            aslTranslationEngine.onPreviewBitmap(bitmap)
        }
    }

    fun setInputMode(mode: ConversationInputMode) {
        if (_inputMode.value == mode) {
            return
        }
        _inputMode.value = mode
        clearLiveBubble()
        if (isConversationRunning) {
            applyInputMode(mode)
        }
    }

    fun deleteTranscript(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            transcriptDao.deleteById(id)
        }
    }

    fun deleteTranscripts(ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { id ->
                transcriptDao.deleteById(id)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            transcriptDao.deleteAll()
            conversationSummaryStore.clear()
            _conversationQuestionStates.value = emptyMap()
            summaryRequestsInFlight.clear()
            questionRequestsInFlight.clear()
        }
    }

    fun ensureConversationSummaries(requests: List<ConversationSummaryRequest>) {
        if (!claudeConfigured) {
            return
        }

        val cachedSummaries = conversationSummaries.value
        requests.forEach { request ->
            val conversationId = request.conversationId
            val transcript = request.transcript.trim()
            if (conversationId.isBlank() || transcript.isBlank()) {
                return@forEach
            }
            if (cachedSummaries.containsKey(conversationId)) {
                return@forEach
            }
            if (!summaryRequestsInFlight.add(conversationId)) {
                return@forEach
            }

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val summary = claudeEngine.summarizeConversation(transcript)
                    if (!summary.isNullOrBlank()) {
                        conversationSummaryStore.put(conversationId, summary)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Unable to summarize conversation $conversationId", t)
                } finally {
                    summaryRequestsInFlight.remove(conversationId)
                }
            }
        }
    }

    fun askConversationQuestion(
        conversationId: String,
        transcript: String,
        question: String,
        summaryHint: String? = null,
        toneHint: String? = null,
    ) {
        val cleanedConversationId = conversationId.trim()
        val cleanedTranscript = transcript.trim()
        val cleanedQuestion = question.trim()
        if (cleanedConversationId.isBlank() || cleanedTranscript.isBlank() || cleanedQuestion.isBlank()) {
            return
        }
        if (!claudeConfigured) {
            _conversationQuestionStates.update { current ->
                current + (
                    cleanedConversationId to ConversationQuestionState(
                        question = cleanedQuestion,
                        answer = null,
                        isLoading = false,
                        error = "Claude is not configured.",
                    )
                )
            }
            return
        }
        if (!questionRequestsInFlight.add(cleanedConversationId)) {
            return
        }

        _conversationQuestionStates.update { current ->
            current + (
                cleanedConversationId to ConversationQuestionState(
                    question = cleanedQuestion,
                    answer = current[cleanedConversationId]?.answer,
                    isLoading = true,
                    error = null,
                )
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val answer = claudeEngine.answerQuestionAboutConversation(
                    transcript = cleanedTranscript,
                    question = cleanedQuestion,
                    summaryHint = summaryHint,
                    toneHint = toneHint,
                )
                _conversationQuestionStates.update { current ->
                    current + (
                        cleanedConversationId to ConversationQuestionState(
                            question = cleanedQuestion,
                            answer = answer ?: "I couldn't answer that from the saved conversation.",
                            isLoading = false,
                            error = null,
                        )
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to answer conversation question for $cleanedConversationId", t)
                _conversationQuestionStates.update { current ->
                    current + (
                        cleanedConversationId to ConversationQuestionState(
                            question = cleanedQuestion,
                            answer = null,
                            isLoading = false,
                            error = "Couldn't get an answer right now.",
                        )
                    )
                }
            } finally {
                questionRequestsInFlight.remove(cleanedConversationId)
            }
        }
    }

    fun exportAllHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val entries = transcriptDao.getAllOnce().sortedBy { it.timestamp }
                val content = buildString {
                    entries.forEach { entry ->
                        val emotionSuffix = entry.emotionLabel?.let { " Â· $it" }.orEmpty()
                        appendLine("${entry.timestamp.toHistoryTimestamp()}$emotionSuffix")
                        appendLine(entry.text)
                        appendLine()
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = getApplication<Application>().contentResolver
                    val fileName = "bubbl-history-${System.currentTimeMillis()}.txt"
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("Could not create export destination.")
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(content.toByteArray())
                    } ?: error("Could not open export stream.")
                } else {
                    val downloadsDir = getApplication<Application>()
                        .getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: error("Downloads directory unavailable.")
                    val exportFile = File(downloadsDir, "bubbl-history-${System.currentTimeMillis()}.txt")
                    exportFile.writeText(content)
                    exportFile.toUri()
                }
            }.onSuccess {
                _messages.tryEmit("History exported.")
            }.onFailure {
                _messages.tryEmit("Unable to export history.")
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            speechEngine.speechStarted.collect {
                if (_inputMode.value != ConversationInputMode.TRANSCRIPTION) {
                    return@collect
                }
                noteConversationActivity()
            }
        }

        viewModelScope.launch {
            combine(
                speechEngine.isRunning,
                speechEngine.isConnected,
                speechEngine.connectionError,
                _inputMode,
            ) { running, connected, error, mode ->
                Quadruple(running, connected, error, mode)
            }.collect { (running, connected, error, mode) ->
                if (mode != ConversationInputMode.TRANSCRIPTION) {
                    return@collect
                }

                _connectionStatus.value = when {
                    connected -> "Live"
                    error != null -> speechConnectionStatus(error)
                    running -> "Connecting..."
                    else -> "Ready"
                }
                _statusMessage.value = if (connected) {
                    null
                } else {
                    error?.let { "Connection error: $it" }
                }
            }
        }
    }

    private fun speechConnectionStatus(error: String): String {
        return when {
            error.contains("api key", ignoreCase = true) ||
                error.contains("unauthorized", ignoreCase = true) ||
                error.contains("forbidden", ignoreCase = true) -> "Auth required"
            error.contains("AudioRecord", ignoreCase = true) ||
                error.contains("mic", ignoreCase = true) -> "Mic unavailable"
            error.contains("timeout", ignoreCase = true) -> "Connection timeout"
            error.contains("no internet", ignoreCase = true) -> "No internet"
            error.contains("reconnecting", ignoreCase = true) ||
                error.contains("connection lost", ignoreCase = true) -> "Reconnecting..."
            error.contains("tls", ignoreCase = true) ||
                error.contains("ssl", ignoreCase = true) -> "Secure connection failed"
            error.contains("network", ignoreCase = true) -> "Network error"
            else -> "Connection issue"
        }
    }

    private fun observeSpeech() {
        viewModelScope.launch {
            speechEngine.speechUpdates.collect { update ->
                if (!isConversationRunning || _inputMode.value != ConversationInputMode.TRANSCRIPTION ||
                    shouldSuppressLocalSpeech()
                ) {
                    return@collect
                }
                when (update) {
                    is SpeechUpdate.Partial -> {
                        val liveKeys = update.segments.map { it.speakerKey ?: SpeechBubbleData.PENDING_SPEAKER_KEY }.toSet()
                        // Provisional text can be revised or withdrawn by the recognizer.
                        _speakerBubbles.update { bubbles ->
                            bubbles.filterNot { it.isPartial && it.speakerKey !in liveKeys }
                        }
                        update.segments.forEach { segment ->
                            if (segment.text.isBlank()) return@forEach
                            if (isBubbleClearCommand(segment.text)) {
                                clearOnScreenBubbles(resetTranscript = true, resetSpatialMemory = true)
                                _messages.tryEmit("Bubbles cleared.")
                                return@collect
                            }
                            noteConversationActivity()
                            if (!isRecordingUtterance) {
                                synchronized(utteranceAudioChunks) { utteranceAudioChunks.clear() }
                                isRecordingUtterance = true
                            }
                            volumeWordBuffer.onPartialResult(segment.text)
                            showSpeechBubble(
                                text = segment.text,
                                speakerKey = segment.speakerKey ?: SpeechBubbleData.PENDING_SPEAKER_KEY,
                                detectedLanguage = segment.detectedLanguage,
                                isPartial = true,
                                promotePending = SpeechBubbleData.PENDING_SPEAKER_KEY !in liveKeys,
                            )
                        }
                    }
                    is SpeechUpdate.Final -> handleFinalSpeech(update.result)
                }
            }
        }
    }

    private fun showSpeechBubble(
        text: String,
        speakerKey: String,
        detectedLanguage: String?,
        isPartial: Boolean,
        timestamp: Long = System.currentTimeMillis(),
        promotePending: Boolean = true,
    ): SpeechBubbleData {
        speakerBubbleClearJobs.remove(speakerKey)?.cancel()
        // A fresh turn replaces this speaker's old bubble, so preserve that turn at the side.
        finalBubbleCaptionIds.remove(speakerKey)?.let(::moveCaptionToSide)
        if (speakerKey != SpeechBubbleData.PENDING_SPEAKER_KEY) {
            if (promotePending) promotePendingAssignment(speakerKey)
            noteDiarizationSpeaker(speakerKey)
            speakerLastSeenAt[speakerKey] = timestamp
        }
        activeSpeechSpeakerKey = speakerKey
        humeEngine.updateTranscriptText(text)
        val previous = _speakerBubbles.value.firstOrNull { it.speakerKey == speakerKey }
        val anchor = resolveFaceIdForSpeaker(speakerKey)
        val bubble = SpeechBubbleData(
            id = "bubble-$speakerKey",
            speakerKey = speakerKey,
            speakerLabel = speakerLabelFor(speakerKey),
            anchorFaceId = anchor,
            text = text,
            isPartial = isPartial,
            isVisible = _overlayEnabled.value,
            volumeLevel = calibrateEmphasisVolume(
                rawVolume = audioAnalyzer.averageVolume(200L),
                sensitivity = settings.value.volumeSensitivity,
                fixedOffset = settings.value.emphasisVolumeOffset,
            ),
            wordVolumes = if (isPartial) currentWordVolumes.value else volumeWordBuffer.onFinalResult(text),
            emotionState = humeEngine.emotionState.value,
            detectedLanguage = detectedLanguage,
            expandedTokens = expansionsFor(text, settings.value),
            intentLabels = IntentLabeler.labelsFor(text),
            // A later turn can repeat the same words within the same millisecond.
            timestamp = maxOf(timestamp, (previous?.timestamp ?: 0L) + 1L),
        )
        _speakerBubbles.update { SpeakerBubbleState.upsert(it, bubble, promotePending) }
        return bubble
    }

    private fun handleFinalSpeech(result: FinalizedSpeech) {
        if (result.text.isBlank()) return
        if (isBubbleClearCommand(result.text)) {
            clearOnScreenBubbles(resetTranscript = true, resetSpatialMemory = true)
            _messages.tryEmit("Bubbles cleared.")
            return
        }
        noteConversationActivity(result.timestamp)
        val speakerKey = result.speakerKey ?: SpeechBubbleData.PENDING_SPEAKER_KEY
        val bubble = showSpeechBubble(result.text, speakerKey, result.detectedLanguage, false, result.timestamp)
        val threadMeta = commitThreadForFinal(speakerKey, result.timestamp)
        lipReadingCoordinator.onAudioTranscript(result.text, confidence = 0.85f, anchorFaceId = bubble.anchorFaceId)
        val captionId = "${speakerKey}-${bubble.timestamp}-${result.text.hashCode()}"
        val caption = VisibleCaptionUi(
            id = captionId,
            speakerKey = speakerKey,
            speakerLabel = bubble.speakerLabel,
            anchorFaceId = bubble.anchorFaceId,
            text = result.text,
            timestamp = result.timestamp,
            wordVolumes = bubble.wordVolumes,
            emotionState = bubble.emotionState,
            detectedLanguage = result.detectedLanguage,
            expandedTokens = bubble.expandedTokens,
            intentLabels = bubble.intentLabels,
            threadId = threadMeta.threadId,
            replyToSpeakerKey = threadMeta.replyToSpeakerKey,
            threadTurnIndex = threadMeta.threadTurnIndex,
            isThreadHighlighted = threadMeta.isThreadHighlighted,
        )
        _currentTranscript.update { (it + caption).takeLast(150) }
        finalBubbleCaptionIds[speakerKey] = captionId
        val identityId = bubble.anchorFaceId?.let { faceIdentityIds.value[it] }
        val historyText = persistedTranscriptText(result.text, speakerKey)
        viewModelScope.launch(Dispatchers.IO) {
            transcriptDao.insert(
                TranscriptEntry(
                    text = historyText,
                    timestamp = result.timestamp,
                    volumeLevel = bubble.volumeLevel,
                    emotionLabel = bubble.emotionState.persistedLabel(),
                    speakerKey = speakerKey,
                    speakerLabel = bubble.speakerLabel,
                    faceIdentityId = identityId,
                ),
            )
        }

        volumeWordBuffer.clear()
        isRecordingUtterance = false
        val capturedAudio = synchronized(utteranceAudioChunks) {
            flattenAudioChunks(utteranceAudioChunks).also { utteranceAudioChunks.clear() }
        }
        // Translation runs outside the speech collector, so one slow request cannot
        // delay the next person's captions. Results can only patch this revision.
        scheduleSpeakerBubbleClear(
            bubble,
            if (settings.value.translationEnabled) 17_000L else BUBBLE_VISIBLE_DURATION_MS,
        )
        val job = viewModelScope.launch {
            var lang = result.detectedLanguage
            if (lang == null && settings.value.translationEnabled) {
                lang = runCatching { LanguageIdentifier.identify(result.text) }.getOrNull()
            }
            val translated = runCatching {
                resolvePreFadeTranslation(
                    text = result.text,
                    detectedLanguage = lang,
                    translateWholeUtterance = settings.value.translationEnabled &&
                        lang != null && lang.lowercase().take(2) != "en",
                )
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.w(TAG, "Caption translation failed", error)
            }.getOrNull()
            val language = translated?.detectedLanguage ?: lang
            _currentTranscript.update { captions ->
                captions.map { if (it.id == captionId) it.copy(
                    translatedText = translated?.translatedText,
                    detectedLanguage = language,
                ) else it }
            }
            _speakerBubbles.update { bubbles ->
                bubbles.map { if (SpeakerBubbleState.isSameRevision(it, bubble)) it.copy(
                    translatedText = translated?.translatedText,
                    detectedLanguage = language,
                    isTranslating = false,
                ) else it }
            }
            if (_speakerBubbles.value.any { it.isVisible && SpeakerBubbleState.isSameRevision(it, bubble) }) {
                scheduleSpeakerBubbleClear(
                    bubble,
                    if (translated?.translatedText != null) TRANSLATED_BUBBLE_VISIBLE_DURATION_MS else BUBBLE_VISIBLE_DURATION_MS,
                )
            }
        }
        speechCompletionJobs += job
        job.invokeOnCompletion { speechCompletionJobs.remove(job) }

        // The raw mic buffer is a mix once more than one speaker is present.
        // Keep refinement for a single speaker, where it can be attributed safely.
        if (!faceDownModeActive && speakerLastSeenAt.size <= 1 &&
            elevenLabsEngine.isConfigured && capturedAudio.isNotEmpty()
        ) {
            elevenLabsJob?.cancel()
            elevenLabsJob = viewModelScope.launch {
                val refined = elevenLabsEngine.transcribe(pcmBytes = capturedAudio, hintLanguage = result.detectedLanguage) ?: return@launch
                _currentTranscript.update { captions ->
                    captions.map { if (it.id == captionId) it.copy(
                        text = refined.text,
                        detectedLanguage = refined.languageCode ?: it.detectedLanguage,
                    ) else it }
                }
            }
        }
    }

    private fun scheduleSpeakerBubbleClear(bubble: SpeechBubbleData, delayMs: Long = BUBBLE_VISIBLE_DURATION_MS) {
        speakerBubbleClearJobs.remove(bubble.speakerKey)?.cancel()
        speakerBubbleClearJobs[bubble.speakerKey] = viewModelScope.launch {
            delay(delayMs)
            if (!_speakerBubbles.value.any { SpeakerBubbleState.isSameRevision(it, bubble) }) return@launch
            // Keep the composable mounted while its existing opacity animation runs.
            _speakerBubbles.update { bubbles ->
                bubbles.map { if (SpeakerBubbleState.isSameRevision(it, bubble)) it.copy(isVisible = false) else it }
            }
            delay(RecentCaptionState.BUBBLE_FADE_MS)
            if (_speakerBubbles.value.any { SpeakerBubbleState.isSameRevision(it, bubble) }) {
                finalBubbleCaptionIds.remove(bubble.speakerKey)?.let(::moveCaptionToSide)
                _speakerBubbles.update { SpeakerBubbleState.remove(it, bubble) }
            }
            speakerBubbleClearJobs.remove(bubble.speakerKey)
        }
    }

    private fun moveCaptionToSide(captionId: String) {
        if (_currentTranscript.value.none { it.id == captionId }) return
        val now = SystemClock.elapsedRealtime()
        recentCaptionRefs.update { RecentCaptionState.add(RecentCaptionState.advance(it, now), captionId, now) }
        recentCaptionExpiryJob?.cancel()
        recentCaptionExpiryJob = viewModelScope.launch {
            while (true) {
                val next = RecentCaptionState.nextTransitionAt(recentCaptionRefs.value) ?: break
                delay((next - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
                recentCaptionRefs.update { RecentCaptionState.advance(it, SystemClock.elapsedRealtime()) }
            }
        }
    }

    private fun observeAslTranslation() {
        viewModelScope.launch {
            aslTranslationEngine.partialTranslation.collect { partial ->
                if (_inputMode.value != ConversationInputMode.ASL_TRANSLATION) {
                    return@collect
                }
                if (partial.isBlank()) {
                    return@collect
                }
                noteConversationActivity()

                val settingsValue = settings.value
                val timestamp = System.currentTimeMillis()
                persistentAslPartial = partial
                renderPersistentAslBubble(
                    settingsValue = settingsValue,
                    timestamp = timestamp,
                )
            }
        }

        viewModelScope.launch {
            aslTranslationEngine.finalizedTranslation.collect { result ->
                if (_inputMode.value != ConversationInputMode.ASL_TRANSLATION) {
                    return@collect
                }
                noteConversationActivity(result.timestamp)

                val settingsValue = settings.value
                persistentAslPartial = ""
                appendPersistentSegment(persistentAslSegments, result.text)
                renderPersistentAslBubble(
                    settingsValue = settingsValue,
                    timestamp = result.timestamp,
                )

                viewModelScope.launch(Dispatchers.IO) {
                    transcriptDao.insert(
                        TranscriptEntry(
                            text = result.text,
                            timestamp = result.timestamp,
                            volumeLevel = 0f,
                            emotionLabel = null,
                        ),
                    )
                }
            }
        }
    }

    private fun observeSpeechAudioStream() {
        viewModelScope.launch(Dispatchers.Default) {
            speechEngine.audioPcmChunks.collect { chunk ->
                audioAnalyzer.onPcmChunk(chunk)
            }
        }
        // Independent collector — buffers audio for ElevenLabs refinement.
        // Runs on Default so it doesn't block the analysis path above.
        viewModelScope.launch(Dispatchers.Default) {
            speechEngine.audioPcmChunks.collect { chunk ->
                if (isRecordingUtterance) {
                    synchronized(utteranceAudioChunks) {
                        utteranceAudioChunks.add(chunk)
                    }
                }
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settings.collect { settingsValue ->
                _bubbleData.update { current ->
                    if (current.text.isBlank()) {
                        current
                    } else {
                        current.copy(
                            expandedTokens = expansionsFor(current.text, settingsValue),
                        )
                    }
                }
                _speakerBubbles.update { current ->
                    current.map { bubble ->
                        bubble.copy(
                            expandedTokens = expansionsFor(bubble.text, settingsValue),
                        )
                    }
                }
                _currentTranscript.update { current ->
                    current.map { caption ->
                        caption.copy(
                            expandedTokens = expansionsFor(caption.text, settingsValue),
                        )
                    }
                }
            }
        }
    }

    fun setFaceDownMode(active: Boolean) {
        faceDownModeActive = active
        if (active) {
            // Facedown: Soniox interim/prefinal (+ diarization) over the raw
            // UNPROCESSED far-field mic, with ElevenLabs Scribe as the
            // authoritative final. The holder builds the facedown Soniox engine
            // whenever facedown is active, regardless of the normal-mode choice.
            speechEngine.setFaceDownMode(true)
        } else {
            speechEngine.setFaceDownMode(false)
            speechEngine.setBackend(SpeechBackendKind.fromValue(settings.value.speechBackend))
        }
    }

    private fun observeSpeechBackendSetting() {
        viewModelScope.launch {
            settings
                .map { SpeechBackendKind.fromValue(it.speechBackend) }
                .distinctUntilChanged()
                .collect { kind ->
                    if (!faceDownModeActive) {
                        speechEngine.setBackend(kind)
                    }
                }
        }
    }

    private fun observeExtendedLanguagesSetting() {
        viewModelScope.launch {
            settings
                .map { it.extendedLanguagesEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    speechEngine.setExtendedLanguages(enabled)
                }
        }
    }

    private fun observeHumeEmotion() {
        viewModelScope.launch {
            humeEngine.emotionState.collect { emotion ->
                if (_inputMode.value != ConversationInputMode.TRANSCRIPTION) {
                    return@collect
                }
                _speakerBubbles.update { current ->
                    if (current.isEmpty() || detailedActiveSpeakerCount(excludingPending = true) > 1) {
                        current
                    } else {
                        val latestSpeakerKey = current
                            .filter { it.speakerKey != SpeechBubbleData.PENDING_SPEAKER_KEY }
                            .maxByOrNull { it.timestamp }
                            ?.speakerKey
                        current.map { bubble ->
                            if (bubble.speakerKey == latestSpeakerKey && bubble.text.isNotBlank()) {
                                bubble.copy(emotionState = emotion)
                            } else {
                                bubble
                            }
                        }
                    }
                }
            }
        }
    }

    private fun observeWordVolumeSampling() {
        viewModelScope.launch {
            combine(volumeLevel, settings) { volume, settingsValue ->
                calibrateEmphasisVolume(
                    rawVolume = volume,
                    sensitivity = settingsValue.volumeSensitivity,
                    fixedOffset = settingsValue.emphasisVolumeOffset,
                )
            }.collect { calibratedVolume ->
                volumeWordBuffer.addSample(calibratedVolume)
            }
        }
    }

    private fun observeFaceSignal() {
        viewModelScope.launch {
            faceBoundingBox.collect { faceBounds ->
                humeEngine.updateLocalFaceBoundingBox(faceBounds)
            }
        }

        viewModelScope.launch {
            trackedFaces.collect { faces ->
                lipLandmarkAnalyzer.updateTrackedFaces(faces)
                val visibleFaceIds = faces.map { it.trackId }.toSet()
                // drop dead face links so bubbles can re-anchor
                speakerFaceStore.pruneMissingFaces(visibleFaceIds)
                _speakerBubbles.update { current ->
                    current.map { bubble ->
                        bubble.copy(
                            anchorFaceId = bubble.anchorFaceId
                                ?.takeIf { it in visibleFaceIds }
                                ?: if (bubble.isPartial) resolveFaceIdForSpeaker(bubble.speakerKey) else null,
                            lipDrivenAnchorId = bubble.lipDrivenAnchorId?.takeIf { it in visibleFaceIds },
                        )
                    }
                }
                // Refresh the debug snapshot AFTER the prune+resolve loop so
                // the overlay shows whatever the layout actually saw.
                _speakerFaceAssignments.value = speakerFaceStore.snapshotAssignments()
            }
        }
    }

    private fun observeVisualSpeakerCue() {
        viewModelScope.launch {
            combine(
                trackedFaces,
                lipSignals,
                volumeLevel,
                audioDirection,
            ) { faces, lips, volume, audio ->
                if (_inputMode.value != ConversationInputMode.TRANSCRIPTION || faces.isEmpty()) {
                    null
                } else {
                    speakerFaceStore.activeSpeakerCandidate(
                        faces = faces,
                        lipSignals = lips,
                        audioDirection = audio,
                        audioActivityScore = volume,
                    )?.let { candidate ->
                        VisualSpeakerCue(
                            faceId = candidate.faceId,
                            score = candidate.score,
                            mouthScore = candidate.mouthScore,
                            gazeAttentionScore = candidate.gazeAttentionScore,
                            audioActivityScore = candidate.audioActivityScore,
                            timestamp = candidate.updatedAt,
                        )
                    }
                }
            }.collect { cue ->
                _visualSpeakerCue.value = cue
                if (cue != null) {
                    // Only let the lip cue move a bubble onto a *different* face
                    // shortly after diarization reported a speaker change. Within
                    // a single speaker's turn the window stays closed, so the
                    // anchor holds steady instead of flickering between faces.
                    val diarizationAllowsSwitch =
                        (System.currentTimeMillis() - lastDiarizationSwitchAt) <= DIARIZATION_ANCHOR_WINDOW_MS
                    _speakerBubbles.update { bubbles ->
                        bubbles.map { bubble ->
                            if (!bubble.isVisible || !bubble.isPartial ||
                                bubble.speakerKey != activeSpeechSpeakerKey
                            ) return@map bubble
                            // A visual cue must never move another person's caption.
                            if (bubbles.any { other -> other.speakerKey != bubble.speakerKey &&
                                    (other.lipDrivenAnchorId ?: other.anchorFaceId) == cue.faceId
                                }
                            ) return@map bubble
                            val assignment = speakerFaceStore.snapshotAssignments()[bubble.speakerKey]
                            if (assignment != null && (assignment.isPinned || assignment.confidence >= 0.65f)) return@map bubble

                            val currentAnchor = bubble.lipDrivenAnchorId ?: bubble.anchorFaceId
                            // Hold the existing anchor unless diarization just switched.
                            // First assignment (no anchor) and same-face refresh are allowed.
                            if (currentAnchor != null &&
                                currentAnchor != cue.faceId &&
                                !diarizationAllowsSwitch
                            ) {
                                return@map bubble
                            }
                            bubble.copy(
                                anchorFaceId = cue.faceId,
                                lipDrivenAnchorId = cue.faceId,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun observeLipLandmarks() {
        viewModelScope.launch(Dispatchers.Default) {
            faceTracker.latestBitmap.collect { bitmap ->
                val faces = trackedFaces.value
                if (faces.isEmpty()) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    return@collect
                }

                // Create a local copy for mouth-crop extraction. This copy is owned
                // exclusively by this coroutine call — no other thread ever sees it,
                // so we can safely recycle it at the end of this block with no races.
                val copy = runCatching {
                    if (bitmap.width <= 480) {
                        bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    } else {
                        Bitmap.createScaledBitmap(
                            bitmap,
                            480,
                            (480f * bitmap.height / bitmap.width).toInt(),
                            false,
                        )
                    }
                }.getOrNull()

                // Feed original frame to MediaPipe (async, takes ownership of bitmap).
                lipLandmarkAnalyzer.onFrame(bitmap, faces)

                // Process current lip signals with the copy, then recycle immediately.
                // Using lipSignals.value (snapshot) is equivalent to what the old two-
                // coroutine design did — signals lag one MediaPipe frame behind regardless.
                if (copy != null && !copy.isRecycled) {
                    val signals = lipLandmarkAnalyzer.lipSignals.value
                    val noise = noiseEstimator.noiseLevel.value
                    for ((faceId, signal) in signals) {
                        lipReadingCoordinator.onLipSignal(faceId, signal, copy, noise)
                    }
                    (lipReadingResults.value.keys - signals.keys).forEach {
                        lipReadingCoordinator.clearFace(it)
                    }
                    copy.recycle()
                }
            }
        }
    }

    private fun observeNoiseEstimator() {
        viewModelScope.launch(Dispatchers.Default) {
            combine(volumeLevel, isUserTalking) { v, talking -> v to talking }
                .collect { (v, talking) ->
                    noiseEstimator.onVolumeSample(v, talking)
                }
        }
    }

    /**
     * Throttled identity resolver: roughly twice a second, take the latest
     * geometric features for live faces and ask [FaceIdentityMatcher] to
     * cluster them. Updates [faceIdentityIds] which speakerLabelFor() reads
     * to surface user-named identities in bubbles + transcripts.
     */
    private fun observeFaceIdentities() {
        viewModelScope.launch(Dispatchers.IO) {
            // Hot-load any rows persisted from a previous session before
            // starting the resolution loop.
            faceIdentityMatcher.refresh()
        }
        viewModelScope.launch(Dispatchers.Default) {
            lipLandmarkAnalyzer.faceFeatures.collect { features ->
                if (features.isEmpty()) {
                    if (faceIdentityIds.value.isNotEmpty()) {
                        faceIdentityIds.value = emptyMap()
                    }
                    return@collect
                }
                val resolved = mutableMapOf<String, String>()
                for ((faceId, feature) in features) {
                    val identityId = faceIdentityMatcher.matchOrCreate(feature) ?: continue
                    resolved[faceId] = identityId
                }
                faceIdentityIds.value = resolved
                // Throttle: identity stays effectively stable inside a session,
                // so we don't need per-frame clustering. 400ms keeps centroid
                // adaptation responsive without thrashing the DB.
                delay(400L)
            }
        }
    }

    /**
     * Apply a user-supplied display name to a stored face identity. The new
     * name is reflected in current bubbles and persisted to future
     * transcript rows automatically because speakerLabelFor() consults the
     * matcher's cache.
     */
    fun renameFaceIdentity(identityId: String, displayName: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            faceIdentityMatcher.rename(identityId, displayName)
        }
    }

    private fun observeStereoPan() {
        viewModelScope.launch(Dispatchers.Default) {
            speechEngine.stereoAudioChunks.collect { stereoChunk ->
                stereoPanAnalyzer.onStereoPcmChunk(stereoChunk)
                directionalAudio.onStereoPcmChunk(stereoChunk)
            }
        }
    }

    private fun observeLoudnessMonitor() {
        viewModelScope.launch {
            combine(
                volumeLevel,
                isUserTalking,
            ) { volume, talking -> volume to talking }
                .collect { (volume, talking) ->
                    loudnessMonitor.onVolumeTick(
                        currentVolume = volume,
                        isUserTalking = talking,
                    )
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        volumeWordBuffer.clear()
        aslTranslationEngine.release()
        humeEngine.stop()
        speechEngine.release()
        audioAnalyzer.release()
        faceTracker.release()
        cameraManager.release()
        lipLandmarkAnalyzer.release()
        lipReadingCoordinator.release()
        userVoiceDetector.release()
        chromeRevealJob?.cancel()
    }

    private fun observeVolumeAndNoiseGate() {
        viewModelScope.launch {
            combine(volumeLevel, settings) { volume, settingsValue -> volume to settingsValue }
                .collect { (volume, settingsValue) ->
                    if (_inputMode.value == ConversationInputMode.TRANSCRIPTION && _speakerBubbles.value.isNotEmpty()) {
                        val latestSpeakerKey = _speakerBubbles.value.maxByOrNull { it.timestamp }?.speakerKey
                        if (latestSpeakerKey != null) {
                            _speakerBubbles.update { current ->
                                current.map { bubble ->
                                    if (bubble.speakerKey == latestSpeakerKey) {
                                        bubble.copy(volumeLevel = volume)
                                    } else {
                                        bubble
                                    }
                                }
                            }
                        }
                    } else if (_bubbleData.value.text.isNotBlank()) {
                        _bubbleData.update { current -> current.copy(volumeLevel = volume) }
                    }

                    if (!isConversationRunning) {
                        noiseFloorStartedAt = null
                        return@collect
                    }

                    // Facedown/tabletop: voices are quieter at table distance — skip the
                    // noise gate entirely so it doesn't mute valid speech.
                    if (!settingsValue.backgroundNoiseFiltering || faceDownModeActive) {
                        noiseFloorStartedAt = null
                        if (noiseGatePaused) {
                            noiseGatePaused = false
                            speechEngine.setAudioStreamingEnabled(true)
                        }
                        return@collect
                    }

                    if (volume < 0.15f) {
                        val startedAt = noiseFloorStartedAt ?: System.currentTimeMillis().also {
                            noiseFloorStartedAt = it
                        }
                        // wait a beat so tiny pauses don't kill audio
                        if (System.currentTimeMillis() - startedAt >= 500L && !noiseGatePaused) {
                            noiseGatePaused = true
                            speechEngine.setAudioStreamingEnabled(false)
                        }
                    } else {
                        noiseFloorStartedAt = null
                        if (noiseGatePaused) {
                            noiseGatePaused = false
                            speechEngine.setAudioStreamingEnabled(true)
                        }
                    }
                }
        }
    }

    private fun renderPersistentAslBubble(
        settingsValue: AppSettings,
        timestamp: Long,
    ) {
        val text = persistentText(persistentAslSegments, persistentAslPartial)
        _bubbleData.value = if (text.isBlank()) {
            SpeechBubbleData()
        } else {
            SpeechBubbleData(
                id = PERSISTENT_ASL_BUBBLE_ID,
                text = text,
                isPartial = persistentAslPartial.isNotBlank(),
                isVisible = _overlayEnabled.value,
                volumeLevel = 0f,
                wordVolumes = emptyList(),
                emotionState = EmotionState(),
                expandedTokens = expansionsFor(text, settingsValue),
                intentLabels = IntentLabeler.labelsFor(text),
                timestamp = timestamp,
            )
        }
    }

    /**
     * Races DeepL and Claude Haiku in parallel.
     * First non-null result wins. Claude retries internally (up to 3×) so this
     * should always produce a result as long as there is network.
     */
    private suspend fun raceTranslation(text: String, sourceLang: String): String? = coroutineScope {
        val channel = Channel<String>(capacity = Channel.UNLIMITED)

        val deeplJob = async(Dispatchers.IO) {
            deepLTranslationEngine.translate(text, sourceLang)
                ?.also { channel.trySend(it) }
        }
        // Claude has no inner timeout — OkHttp's 25s call timeout is the ceiling,
        // and it retries up to 3× on transient failures before giving up.
        val claudeJob = async(Dispatchers.IO) {
            claudeEngine.translateText(text, sourceLang)
                ?.also { channel.trySend(it) }
        }

        val result = withTimeoutOrNull(30_000L) { channel.receive() }
        deeplJob.cancel()
        claudeJob.cancel()
        channel.close()
        result
    }

    private suspend fun resolveTranslation(
        text: String,
        detectedLanguage: String?,
        translateWholeUtterance: Boolean,
    ): ForeignTextTranslation? {
        if (translateWholeUtterance && detectedLanguage != null) {
            raceTranslation(text, detectedLanguage)?.let { translated ->
                return ForeignTextTranslation(
                    translatedText = translated,
                    detectedLanguage = detectedLanguage,
                )
            }
        }
        return withContext(Dispatchers.IO) {
            claudeEngine.translateForeignTextIfPresent(text)
        }
    }

    private suspend fun resolvePreFadeTranslation(
        text: String,
        detectedLanguage: String?,
        translateWholeUtterance: Boolean,
    ): ForeignTextTranslation? = withTimeoutOrNull(12_000L) {
        if (settings.value.translationEnabled && claudeConfigured) {
            withContext(Dispatchers.IO) {
                claudeEngine.translateForeignTextIfPresent(text)
            }?.let { return@withTimeoutOrNull it }
        }
        if (settings.value.translationEnabled && translateWholeUtterance && detectedLanguage != null) {
            raceTranslation(text, detectedLanguage)?.let { translated ->
                ForeignTextTranslation(
                    translatedText = translated,
                    detectedLanguage = detectedLanguage,
                )
            }
        } else {
            null
        }
    }

    private fun flattenAudioChunks(chunks: List<ByteArray>): ByteArray {
        val totalSize = chunks.sumOf { it.size }
        val dest = ByteArray(totalSize)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(dest, offset)
            offset += chunk.size
        }
        return dest
    }

    private fun appendPersistentSegment(
        segments: MutableList<String>,
        text: String,
    ) {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return
        if (segments.lastOrNull() != cleaned) {
            segments += cleaned
        }
    }

    private fun persistentText(
        segments: List<String>,
        partial: String,
    ): String {
        return (segments + listOf(partial.trim()))
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun previewThreadFor(
        speakerKey: String,
        timestamp: Long,
    ): BubbleThreadMeta {
        if (speakerKey == SpeechBubbleData.PENDING_SPEAKER_KEY) {
            return BubbleThreadMeta()
        }

        activeThreadState?.let { thread ->
            if (thread.canAccept(speakerKey, timestamp)) {
                return BubbleThreadMeta(
                    threadId = thread.id,
                    replyToSpeakerKey = thread.lastSpeakerKey.takeIf { it != speakerKey },
                    threadTurnIndex = thread.turnCount,
                    isThreadHighlighted = true,
                )
            }
        }

        val previous = lastFinalTurn
        if (
            previous != null &&
            previous.speakerKey != speakerKey &&
            timestamp - previous.timestamp <= THREAD_REPLY_GAP_MS
        ) {
            val threadId = previous.threadId
                ?: buildThreadId(previous.timestamp, previous.speakerKey, speakerKey)
            return BubbleThreadMeta(
                threadId = threadId,
                replyToSpeakerKey = previous.speakerKey,
                threadTurnIndex = 1,
                isThreadHighlighted = true,
            )
        }

        return BubbleThreadMeta()
    }

    private fun commitThreadForFinal(
        speakerKey: String,
        timestamp: Long,
    ): BubbleThreadMeta {
        if (speakerKey == SpeechBubbleData.PENDING_SPEAKER_KEY) {
            return BubbleThreadMeta()
        }

        activeThreadState?.let { thread ->
            if (thread.canAccept(speakerKey, timestamp)) {
                val updatedThread = thread.copy(
                    speakerKeys = thread.speakerKeys + speakerKey,
                    lastSpeakerKey = speakerKey,
                    lastTimestamp = timestamp,
                    turnCount = thread.turnCount + 1,
                )
                activeThreadState = updatedThread
                val meta = BubbleThreadMeta(
                    threadId = thread.id,
                    replyToSpeakerKey = thread.lastSpeakerKey.takeIf { it != speakerKey },
                    threadTurnIndex = thread.turnCount,
                    isThreadHighlighted = true,
                )
                lastFinalTurn = RecentFinalTurn(
                    speakerKey = speakerKey,
                    timestamp = timestamp,
                    threadId = thread.id,
                )
                return meta
            }
        }

        val previous = lastFinalTurn
        if (
            previous != null &&
            previous.speakerKey != speakerKey &&
            timestamp - previous.timestamp <= THREAD_REPLY_GAP_MS
        ) {
            val threadId = previous.threadId
                ?: buildThreadId(previous.timestamp, previous.speakerKey, speakerKey)
            activeThreadState = ThreadState(
                id = threadId,
                speakerKeys = setOf(previous.speakerKey, speakerKey),
                lastSpeakerKey = speakerKey,
                lastTimestamp = timestamp,
                turnCount = 2,
            )
            tagPreviousBubbleForThread(previous, threadId)
            val meta = BubbleThreadMeta(
                threadId = threadId,
                replyToSpeakerKey = previous.speakerKey,
                threadTurnIndex = 1,
                isThreadHighlighted = true,
            )
            lastFinalTurn = RecentFinalTurn(
                speakerKey = speakerKey,
                timestamp = timestamp,
                threadId = threadId,
            )
            return meta
        }

        activeThreadState = null
        lastFinalTurn = RecentFinalTurn(
            speakerKey = speakerKey,
            timestamp = timestamp,
            threadId = null,
        )
        return BubbleThreadMeta()
    }

    private fun ThreadState.canAccept(
        speakerKey: String,
        timestamp: Long,
    ): Boolean {
        if (timestamp - lastTimestamp > THREAD_REPLY_GAP_MS) {
            return false
        }
        return speakerKey in speakerKeys ||
            (speakerKeys.size < THREAD_SPEAKER_LIMIT && lastSpeakerKey != speakerKey)
    }

    private fun tagPreviousBubbleForThread(
        previous: RecentFinalTurn,
        threadId: String,
    ) {
        _speakerBubbles.update { current ->
            current.map { bubble ->
                if (bubble.speakerKey == previous.speakerKey && bubble.timestamp == previous.timestamp) {
                    bubble.copy(
                        threadId = threadId,
                        threadTurnIndex = 0,
                        isThreadHighlighted = true,
                    )
                } else {
                    bubble
                }
            }
        }
        _currentTranscript.update { current ->
            current.map { caption ->
                if (caption.speakerKey == previous.speakerKey && caption.timestamp == previous.timestamp) {
                    caption.copy(
                        threadId = threadId,
                        threadTurnIndex = 0,
                        isThreadHighlighted = true,
                    )
                } else {
                    caption
                }
            }
        }
    }

    private fun buildThreadId(
        startedAt: Long,
        firstSpeakerKey: String,
        secondSpeakerKey: String,
    ): String {
        val pairKey = listOf(firstSpeakerKey, secondSpeakerKey)
            .sorted()
            .joinToString("/")
        val pairHash = (pairKey.hashCode() and Int.MAX_VALUE).toString(radix = 36)
        return "thread-$startedAt-$pairHash"
    }

    private fun speakerLabelFor(speakerKey: String): String {
        if (speakerKey == SpeechBubbleData.PENDING_SPEAKER_KEY) {
            return "Listening"
        }
        // Demo mode: map speaker-N → hard-coded presenter names.
        val s = settings.value
        if (s.demoSpeakersEnabled) {
            val names = s.demoSpeakerNames
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            val ordinal = speakerKey.substringAfter("speaker-", "0").toIntOrNull() ?: 0
            return names.getOrElse(ordinal) { "Speaker ${ordinal + 1}" }
        }
        // Prefer a user-named face identity when one is bound to this
        // speaker — that's the "Google Photos knows people" surface.
        val faceId = speakerFaceStore.faceIdFor(speakerKey)
        val identityId = faceId?.let { faceIdentityIds.value[it] }
        val displayName = identityId?.let { faceIdentityMatcher.displayName(it) }
        if (!displayName.isNullOrBlank()) {
            return displayName
        }
        val ordinal = speakerKey.substringAfter("speaker-", missingDelimiterValue = "0")
            .toIntOrNull()
            ?.plus(1)
            ?: 1
        return "Speaker $ordinal"
    }

    private fun detailedActiveSpeakerCount(
        excludingPending: Boolean,
        incomingSpeakerKey: String? = null,
    ): Int {
        val activeSpeakerKeys = LinkedHashSet<String>()
        if (incomingSpeakerKey != null) {
            activeSpeakerKeys += incomingSpeakerKey
        }
        _speakerBubbles.value.forEach { bubble ->
            if (bubble.text.isBlank()) {
                return@forEach
            }
            if (excludingPending && bubble.speakerKey == SpeechBubbleData.PENDING_SPEAKER_KEY) {
                return@forEach
            }
            activeSpeakerKeys += bubble.speakerKey
        }
        if (!excludingPending) {
            speakerLastSeenAt.keys.forEach(activeSpeakerKeys::add)
        }
        return activeSpeakerKeys.size
    }

    private fun resolveFaceIdForSpeaker(speakerKey: String): String? {
        if (speakerKey == SpeechBubbleData.PENDING_SPEAKER_KEY) {
            return resolvePendingFaceId()
        }
        val audioActivity = audioAnalyzer.averageVolume(180L)
        val resolved = speakerFaceStore.resolve(
            speakerKey = speakerKey,
            faces = trackedFaces.value,
            lipSignals = lipLandmarkAnalyzer.lipSignals.value,
            audioDirection = audioDirection.value,
            audioActivityScore = audioActivity,
        )
        return resolved
    }

    private fun resolvePendingFaceId(): String? {
        val faces = trackedFaces.value
        if (faces.isEmpty()) {
            return null
        }
        val lips = lipLandmarkAnalyzer.lipSignals.value
        val audio = audioDirection.value
        val audioActivity = audioAnalyzer.averageVolume(180L)

        speakerFaceStore.activeSpeakerCandidate(
            faces = faces,
            lipSignals = lips,
            audioDirection = audio,
            audioActivityScore = audioActivity,
        )?.let { candidate ->
            speakerFaceStore.bindPending(candidate.faceId, candidate.score)
            return candidate.faceId
        }

        return speakerFaceStore.resolve(
            speakerKey = SpeechBubbleData.PENDING_SPEAKER_KEY,
            faces = faces,
            lipSignals = lips,
            audioDirection = audio,
            audioActivityScore = audioActivity,
        )
    }

    private fun promotePendingAssignment(speakerKey: String) {
        if (speakerKey == SpeechBubbleData.PENDING_SPEAKER_KEY) return
        // Carry whichever face the pending slot was anchored to onto the real
        // speaker key at locked confidence. Without this, finals teleport away
        // from the pending anchor because the store had nothing for speaker-N.
        speakerFaceStore.adopt(
            fromSpeakerKey = SpeechBubbleData.PENDING_SPEAKER_KEY,
            toSpeakerKey = speakerKey,
        )
    }

    private fun shouldSuppressLocalSpeech(now: Long = System.currentTimeMillis()): Boolean {
        if (!speechEngine.isBluetoothInputActive.value) return false
        return userVoiceDetector.isUserTalking.value ||
            (now - lastLocalUserSpeechAt) <= LOCAL_SPEECH_SUPPRESSION_WINDOW_MS
    }

    private fun isUsbInputActive(): Boolean {
        return speechEngine.inputRouteLabel.value?.contains("USB", ignoreCase = true) == true
    }

    private fun observeLocalSpeechSuppression() {
        viewModelScope.launch {
            // Update on every transition so lastLocalUserSpeechAt tracks the STOP time,
            // not just the start time. The 1200ms window is measured from when talking ends.
            userVoiceDetector.isUserTalking.collect {
                lastLocalUserSpeechAt = System.currentTimeMillis()
            }
        }
    }

    private fun noteConversationActivity(now: Long = System.currentTimeMillis()) {
        lastConversationActivityAt = now
        recomputeUiChrome(now)
    }

    fun revealUiChromeTemporarily() {
        val now = System.currentTimeMillis()
        chromeManualRevealUntil = now + UI_CHROME_MANUAL_REVEAL_MS
        recomputeUiChrome(now)
    }

    fun dismissLiveSpeechBubble() {
        if (_inputMode.value == ConversationInputMode.ASL_TRANSLATION) {
            clearOnScreenBubbles(resetTranscript = false, resetSpatialMemory = false)
            return
        }
        _speakerBubbles.value.filterNot { it.isPartial }.forEach { scheduleSpeakerBubbleClear(it, delayMs = 0L) }
        _speakerBubbles.update { bubbles -> bubbles.filterNot { it.isPartial } }
    }

    /** Drops every voice-to-face lock so the next words pick their faces fresh. */
    fun resetSpeakerFaceLocks() {
        speakerFaceStore.clear()
    }

    private fun noteDiarizationSpeaker(speakerKey: String) {
        if (speakerKey == SpeechBubbleData.PENDING_SPEAKER_KEY) return
        if (diarizationActiveSpeakerKey != speakerKey) {
            lastDiarizationSwitchAt = System.currentTimeMillis()
        }
        diarizationActiveSpeakerKey = speakerKey
    }

    private fun normalizeTranscriptForDedupe(text: String): String {
        return text
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun recomputeUiChrome(now: Long = System.currentTimeMillis()) {
        chromeRevealJob?.cancel()

        val recentActivity = lastConversationActivityAt > 0L &&
            (now - lastConversationActivityAt) < UI_CHROME_IDLE_DELAY_MS
        val inactivityRemaining = if (recentActivity) {
            UI_CHROME_IDLE_DELAY_MS - (now - lastConversationActivityAt)
        } else {
            0L
        }.coerceAtLeast(0L)
        val manualRemaining = (chromeManualRevealUntil - now).coerceAtLeast(0L)

        _uiChromeVisible.value = !recentActivity || manualRemaining > 0L

        val nextDelay = when {
            recentActivity && manualRemaining > 0L -> minOf(inactivityRemaining, manualRemaining)
            recentActivity -> inactivityRemaining
            else -> 0L
        }

        if (nextDelay > 0L) {
            chromeRevealJob = viewModelScope.launch {
                delay(nextDelay)
                recomputeUiChrome()
            }
        }
    }

    private fun persistedTranscriptText(
        text: String,
        speakerKey: String,
    ): String {
        return if (speakerLastSeenAt.keys.size > 1) {
            "${speakerLabelFor(speakerKey)}: $text"
        } else {
            text
        }
    }

    private fun expansionsFor(text: String, settingsValue: AppSettings): List<ExpandedToken> {
        return if (settingsValue.showInferredText) SlangExpander.expand(text) else emptyList()
    }

    private fun clearLiveBubble() {
        clearOnScreenBubbles(resetTranscript = true, resetSpatialMemory = true)
    }

    private fun clearOnScreenBubbles(
        resetTranscript: Boolean,
        resetSpatialMemory: Boolean,
    ) {
        speakerBubbleClearJobs.values.forEach { it.cancel() }
        speakerBubbleClearJobs.clear()
        finalBubbleCaptionIds.clear()
        recentCaptionExpiryJob?.cancel()
        recentCaptionExpiryJob = null
        recentCaptionRefs.value = emptyList()
        speechCompletionJobs.toList().forEach { it.cancel() }
        speechCompletionJobs.clear()
        elevenLabsJob?.cancel()
        elevenLabsJob = null
        isRecordingUtterance = false
        synchronized(utteranceAudioChunks) { utteranceAudioChunks.clear() }
        lastLocalUserSpeechAt = 0L
        activeSpeechSpeakerKey = null
        persistentAslSegments.clear()
        persistentAslPartial = ""
        _bubbleData.update {
            it.copy(
                text = "",
                isVisible = false,
                isPartial = false,
                volumeLevel = 0f,
                wordVolumes = emptyList(),
                emotionState = EmotionState(),
                expandedTokens = emptyList(),
                intentLabels = emptyList(),
                threadId = null,
                replyToSpeakerKey = null,
                threadTurnIndex = 0,
                isThreadHighlighted = false,
            )
        }
        _speakerBubbles.value = emptyList()
        if (resetTranscript) {
            _currentTranscript.value = emptyList()
        }
        _visualSpeakerCue.value = null
        activeThreadState = null
        lastFinalTurn = null
        if (resetSpatialMemory) {
            speakerFaceStore.clear()
            diarizationActiveSpeakerKey = null
            lastDiarizationSwitchAt = 0L
        }
        stereoPanAnalyzer.reset()
        directionalAudio.reset()
        speakerLastSeenAt.clear()
        humeEngine.updateTranscriptText("")
        volumeWordBuffer.clear()
    }

    private fun isBubbleClearCommand(text: String): Boolean {
        val normalized = text
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        return normalized == "bubble clear" ||
            normalized == "clear bubbles" ||
            normalized == "clear bubble" ||
            normalized == "bubbles clear"
    }

    private fun applyInputMode(mode: ConversationInputMode) {
        clearLiveBubble()
        // Re-seed the side-panel transcript from the DB so entries survive crashes/restarts.
        // Runs after clearLiveBubble so the async set wins the race against the fresh empty state.
        viewModelScope.launch(Dispatchers.IO) {
            val recent = transcriptDao.getRecentEntries(60).reversed()
            if (recent.isEmpty()) return@launch
            val seeded = recent.map { entry ->
                VisibleCaptionUi(
                    id = "db-${entry.id}",
                    speakerKey = entry.speakerKey ?: SpeechBubbleData.DEFAULT_SPEAKER_KEY,
                    speakerLabel = entry.speakerLabel,
                    text = entry.text,
                    timestamp = entry.timestamp,
                    wordVolumes = emptyList(),
                    emotionState = EmotionState(),
                    expandedTokens = emptyList(),
                    intentLabels = emptyList(),
                )
            }
            _currentTranscript.update { current ->
                if (current.isEmpty()) seeded else current
            }
        }
        when (mode) {
            ConversationInputMode.TRANSCRIPTION -> {
                humeEngine.start(viewModelScope)
                aslTranslationEngine.stop()
                audioAnalyzer.start()
                speechEngine.setAudioStreamingEnabled(true)
                speechEngine.start(viewModelScope)
                _connectionStatus.value = if (speechEngine.isConnected.value) "Live" else "Connecting..."
                _statusMessage.value = null
            }

            ConversationInputMode.ASL_TRANSLATION -> {
                noiseGatePaused = false
                noiseFloorStartedAt = null
                speechEngine.stop()
                audioAnalyzer.stop()
                humeEngine.stop()
                aslTranslationEngine.start()
                _connectionStatus.value = "ASL beta"
                _statusMessage.value = null
            }
        }
    }

    private fun calibrateEmphasisVolume(
        rawVolume: Float,
        sensitivity: Float,
        fixedOffset: Float,
    ): Float {
        val safeRaw = rawVolume.coerceIn(0f, 1f)
        val adjustedSensitivity = sensitivity.coerceIn(0.5f, 2f)
        val adjustedOffset = fixedOffset.coerceIn(-0.30f, 0.30f)
        val centered = ((safeRaw - 0.10f) / 0.78f).coerceIn(0f, 1f)
        val responseExponent = if (adjustedSensitivity >= 1f) {
            (1f - ((adjustedSensitivity - 1f) * 0.35f)).coerceAtLeast(0.75f)
        } else {
            (1f + ((1f - adjustedSensitivity) * 0.60f)).coerceAtMost(1.30f)
        }
        val curved = centered.toDouble().pow(responseExponent.toDouble()).toFloat()
        val blended = (curved * 0.82f) + (centered * 0.18f)
        return ((blended * 0.90f) + 0.03f + adjustedOffset).coerceIn(0f, 1f)
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )
}
