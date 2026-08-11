package com.pgotta.stridulate.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pgotta.stridulate.audio.AudioFileDecoder
import com.pgotta.stridulate.audio.ClipAnalyzer
import com.pgotta.stridulate.audio.FeatureExtractor
import com.pgotta.stridulate.audio.InsectSignalAssessment
import com.pgotta.stridulate.audio.InsectSignalGate
import com.pgotta.stridulate.audio.MeasuredSignature
import com.pgotta.stridulate.audio.MicRecorder
import com.pgotta.stridulate.audio.PossibleMatchGate
import com.pgotta.stridulate.audio.RecordedSegmentPlayer
import com.pgotta.stridulate.audio.ReferenceSoundPlayer
import com.pgotta.stridulate.audio.RecordingQuality
import com.pgotta.stridulate.audio.SoundSensitivity
import com.pgotta.stridulate.classifier.Candidate
import com.pgotta.stridulate.classifier.ClassificationPolicy
import com.pgotta.stridulate.classifier.InsectClassifier
import com.pgotta.stridulate.classifier.OpenSetDecision
import com.pgotta.stridulate.classifier.OpenSetDecisionType
import com.pgotta.stridulate.classifier.TfLiteClassifier
import com.pgotta.stridulate.community.CommunityObservationRecord
import com.pgotta.stridulate.community.CommunityObservationRepository
import com.pgotta.stridulate.community.EvidenceAudio
import com.pgotta.stridulate.community.EvidenceAudioStore
import com.pgotta.stridulate.community.EvidenceSource
import com.pgotta.stridulate.community.INaturalistClient
import com.pgotta.stridulate.data.DetectionSettingsRepository
import com.pgotta.stridulate.data.DetectionTierSettings
import com.pgotta.stridulate.data.OpenSetSafetyPolicy
import com.pgotta.stridulate.data.ReliabilityInfo
import com.pgotta.stridulate.data.Species
import com.pgotta.stridulate.data.SpeciesPhoto
import com.pgotta.stridulate.data.SpeciesReliabilityRepository
import com.pgotta.stridulate.data.SpeciesRepository
import com.pgotta.stridulate.environment.ContextAssessment
import com.pgotta.stridulate.environment.ContextProfileRepository
import com.pgotta.stridulate.environment.ContextReranker
import com.pgotta.stridulate.environment.EnvironmentRepository
import com.pgotta.stridulate.environment.ObservationContext
import com.pgotta.stridulate.environment.SpeciesContextProfile
import com.pgotta.stridulate.log.DetectionLogRepository
import com.pgotta.stridulate.log.DetectionLogSession
import com.pgotta.stridulate.log.DetectionOccurrence
import com.pgotta.stridulate.log.LoggedSpeciesDetection
import com.pgotta.stridulate.qa.FeedbackVerdict
import com.pgotta.stridulate.qa.TestFeedbackRepository
import com.pgotta.stridulate.qa.TestFeedbackSnapshot
import java.io.File
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Detection(
    val species: Species,
    val confidencePct: Int,
    val peakConfidencePct: Int = confidencePct,
    val time: Date,
    val occurrences: List<DetectionOccurrence> = emptyList()
)

enum class IdentificationDecision {
    IDENTIFIED,
    POSSIBLE_MATCH,
    NO_CONFIDENT_MATCH
}

data class IdResult(
    val signature: MeasuredSignature,
    /** Top three supported species; Unknown/Unsupported remains in modelTopLabel for gate reporting. */
    val candidates: List<Candidate>,
    val spectrogram: List<FloatArray>,
    val decision: IdentificationDecision = IdentificationDecision.NO_CONFIDENT_MATCH,
    val decisionReason: String = "",
    val modelTopLabel: String = "",
    val modelTopConfidence: Double = 0.0,
    val modelMargin: Double = 0.0,
    val requiredConfidence: Double = 0.0,
    val requiredMargin: Double = 0.0,
    val acousticCheckPassed: Boolean = false,
    val acousticCheckSummary: String = "No acoustic profile check was available.",
    val observationContext: ObservationContext = ObservationContext(),
    val contextApplied: Boolean = false,
    val contextSummary: String = "Audio ranking only.",
    val recordingQuality: RecordingQuality? = null,
    /** Class-agnostic raw-audio front gate. J.1 scores remain available for QA even when this fails. */
    val signalAssessment: InsectSignalAssessment? = null,
    /** Temporary lossless evidence retained only until the result is dismissed or saved. */
    val evidenceAudio: EvidenceAudio? = null,
    /** Local archive record when the user explicitly saves this result for community review. */
    val communityRecordId: String? = null,
    /** Full original model ranking retained so a weather refresh can safely rerank the result. */
    val allAudioCandidates: List<Candidate> = emptyList()
)

sealed interface UiState {
    data object Idle : UiState
    data object Listening : UiState
    data class Analyzing(val label: String) : UiState
    data class Result(val result: IdResult) : UiState
    data class Error(val message: String) : UiState
}

sealed interface CommunityShareRequest {
    val recordId: String

    data class Identification(override val recordId: String) : CommunityShareRequest
    data class TrainingBundle(
        override val recordId: String,
        val filePath: String
    ) : CommunityShareRequest
}

class StridulateViewModel(app: Application) : AndroidViewModel(app) {

    val repo = SpeciesRepository(app)
    private val reliabilityRepository = SpeciesReliabilityRepository(app)
    private val detectionSettingsRepository = DetectionSettingsRepository(app)
    val tierSettings: StateFlow<DetectionTierSettings> = detectionSettingsRepository.tiers
    private val detectionLogRepository = DetectionLogRepository(app)
    val logSessions: StateFlow<List<DetectionLogSession>> = detectionLogRepository.sessions
    private val testFeedbackRepository = TestFeedbackRepository(app)
    val testFeedbackCount: StateFlow<Int> = testFeedbackRepository.count
    val testTargetKey: StateFlow<String?> = testFeedbackRepository.targetKey
    private val _testFeedbackExportRequest = MutableStateFlow<String?>(null)
    val testFeedbackExportRequest: StateFlow<String?> = _testFeedbackExportRequest
    private val contextProfiles = ContextProfileRepository(app)
    private val contextReranker = ContextReranker(contextProfiles)
    private val environmentRepository = EnvironmentRepository(app)
    val environment: StateFlow<ObservationContext> = environmentRepository.state

    val communityRepository = CommunityObservationRepository(app)
    val communityRecords: StateFlow<List<CommunityObservationRecord>> = communityRepository.records
    private val iNaturalistClient = INaturalistClient()
    private val _communityBusy = MutableStateFlow<String?>(null)
    val communityBusy: StateFlow<String?> = _communityBusy
    private val _communityNotice = MutableStateFlow<String?>(null)
    val communityNotice: StateFlow<String?> = _communityNotice
    private val _communityShareRequest = MutableStateFlow<CommunityShareRequest?>(null)
    val communityShareRequest: StateFlow<CommunityShareRequest?> = _communityShareRequest

    private fun normalizeLatin(value: String): String =
        value.lowercase().replace('_', ' ').trim().replace(Regex("\\s+"), " ")

    private fun modelLabel(species: Species): String = species.latin.replace(' ', '_')

    /** Field-guide entries supported by the frozen J.1 88-class label set, in label order. */
    val tier1Species: List<Species> = try {
        val byLatin = repo.species.associateBy { normalizeLatin(it.latin) }
        app.assets.open("labels.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && it != "Unknown_or_unsupported" }
                .mapNotNull { byLatin[normalizeLatin(it)] }
                .toList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun reliabilityFor(species: Species): ReliabilityInfo = reliabilityRepository.forSpecies(species)

    fun contextAssessmentFor(species: Species): ContextAssessment =
        contextReranker.assess(modelLabel(species), species, environment.value)

    fun contextCoverageFor(species: Species): String? =
        contextProfiles.forLabel(modelLabel(species))?.coverageNote

    fun contextProfileFor(species: Species): SpeciesContextProfile? =
        contextProfiles.forLabel(modelLabel(species))

    fun supportsRegion(species: Species, region: com.pgotta.stridulate.environment.ContextRegion): Boolean =
        contextProfiles.supportsRegion(modelLabel(species), region)

    private val unavailableClassifier = object : InsectClassifier {
        override fun classify(signature: MeasuredSignature): List<Candidate> = emptyList()
    }

    private data class ClassifierSetup(
        val classifier: InsectClassifier,
        val usingTrainedModel: Boolean,
        val status: String
    )

    private val classifierSetup: ClassifierSetup = run {
        fun assetExists(name: String): Boolean = try {
            app.assets.open(name).close()
            true
        } catch (_: Exception) {
            false
        }

        val requiredAssets = listOf(
            "j1_labels.txt",
            "labels.txt",
            "android_reliability.json",
            "context_profiles.json",
            "species.json"
        )
        val missing = requiredAssets.filterNot(::assetExists)
        if (missing.isNotEmpty()) {
            ClassifierSetup(
                classifier = unavailableClassifier,
                usingTrainedModel = false,
                status = "App resources unavailable · missing ${missing.joinToString()}"
            )
        } else {
            try {
                val trained = TfLiteClassifier(app, repo.species)
                val supportedSpecies = trained.classCount ?: tier1Species.size
                ClassifierSetup(
                    classifier = trained,
                    usingTrainedModel = true,
                    status = "Frozen J.1 model active · $supportedSpecies species · ${trained.backendName}"
                )
            } catch (e: Exception) {
                ClassifierSetup(
                    classifier = unavailableClassifier,
                    usingTrainedModel = false,
                    status = "Model unavailable · ${(e.message ?: e.javaClass.simpleName).replace(Regex("\\s+"), " ").take(220)}"
                )
            } catch (e: LinkageError) {
                ClassifierSetup(
                    classifier = unavailableClassifier,
                    usingTrainedModel = false,
                    status = "Model unavailable · ONNX Runtime unavailable"
                )
            }
        }
    }

    private val classifier: InsectClassifier = classifierSetup.classifier
    val usingTrainedModel: Boolean get() = classifierSetup.usingTrainedModel
    val modelStatus: String get() = classifierSetup.status

    private val fftSize = 4096
    private val mic = MicRecorder(fftSize)
    private val clipAnalyzer = ClipAnalyzer(classifier, fftSize)

    private val _ui = MutableStateFlow<UiState>(UiState.Idle)
    val ui: StateFlow<UiState> = _ui

    private val _liveDetections = MutableStateFlow<List<Detection>>(emptyList())
    val liveDetections: StateFlow<List<Detection>> = _liveDetections
    private val _recordingElapsedSeconds = MutableStateFlow(0.0)
    val recordingElapsedSeconds: StateFlow<Double> = _recordingElapsedSeconds

    val spectrogramColumn: StateFlow<FloatArray> get() = mic.spectrogramColumn
    val loudness: StateFlow<Float> get() = mic.loudness

    // Live discovery is intentionally separate from the acceptance gate. The Top 3
    // score-ranked candidates remain visible even when none crosses its J.1 threshold.
    private val _liveCandidates = MutableStateFlow<List<Candidate>>(emptyList())
    val liveCandidates: StateFlow<List<Candidate>> = _liveCandidates
    private val _liveSignalAssessment = MutableStateFlow<InsectSignalAssessment?>(null)
    val liveSignalAssessment: StateFlow<InsectSignalAssessment?> = _liveSignalAssessment
    private var lastLiveFeedbackSnapshot: TestFeedbackSnapshot? = null
    private val liveCandidateStreaks = mutableMapOf<String, Int>()
    private var previousRawLiveLabels: Set<String> = emptySet()
    private var lastLiveAnalysisResult: ClipAnalyzer.Result? = null
    private var lastLiveRawTopThree: List<Candidate> = emptyList()
    private var lastLiveRawPcm: FloatArray = FloatArray(0)
    private var lastLiveRawSampleRate: Int = 0
    private var liveGateRefreshJob: Job? = null

    private var liveExtractor: FeatureExtractor? = null
    private var workJob: Job? = null
    private var contextRefreshJob: Job? = null
    private var liveAnalysisJob: Job? = null
    private var elapsedJob: Job? = null
    private var photoPrefetchJob: Job? = null
    private var recordingStartedAtMillis: Long = 0L

    init {
        // Observation context is optional and must never delay microphone startup. Polling is
        // cheap; EnvironmentRepository only performs location/weather I/O after its ten-minute
        // freshness window has expired.
        viewModelScope.launch {
            while (isActive) {
                refreshContextInBackgroundIfNeeded()
                delay(CONTEXT_REFRESH_POLL_MILLIS)
            }
        }
    }

    private fun refreshContextInBackgroundIfNeeded() {
        if (contextRefreshJob?.isActive == true) return
        contextRefreshJob = viewModelScope.launch {
            try {
                environmentRepository.refreshIfStale()
            } finally {
                contextRefreshJob = null
            }
        }
    }

    fun useDeviceContext() {
        viewModelScope.launch { environmentRepository.useDeviceLocation() }
    }

    fun useManualContext(query: String) {
        viewModelScope.launch { environmentRepository.useManualLocation(query) }
    }

    fun refreshContext() {
        viewModelScope.launch { environmentRepository.refreshNow() }
    }

    /** Refresh current weather and reapply context to the result already on screen. */
    fun refreshResultContext() {
        val currentResult = (_ui.value as? UiState.Result)?.result ?: return
        _ui.value = UiState.Analyzing("Refreshing current location and weather…")
        launchWork {
            environmentRepository.refreshNow()
            currentCoroutineContext().ensureActive()
            _ui.value = UiState.Result(reapplyContext(currentResult, environment.value))
        }
    }

    fun disableContext() {
        environmentRepository.disable()
    }

    fun markLocationPermissionDenied() {
        environmentRepository.markPermissionDenied()
    }

    fun setTestTargetSpecies(species: Species) {
        testFeedbackRepository.setTargetKey(modelLabel(species))
    }

    fun setTestTargetNoise() {
        testFeedbackRepository.setTargetKey(TestFeedbackRepository.TARGET_NOISE)
    }

    fun clearTestTarget() {
        testFeedbackRepository.setTargetKey(null)
    }

    fun clearTestFeedback() {
        testFeedbackRepository.clear()
    }

    fun requestTestFeedbackExport() {
        viewModelScope.launch(Dispatchers.IO) {
            val file = testFeedbackRepository.exportBundle()
            _testFeedbackExportRequest.value = file.absolutePath
        }
    }

    fun clearTestFeedbackExportRequest() {
        _testFeedbackExportRequest.value = null
    }

    fun recordLiveTestFeedback(candidateLabel: String, verdict: FeedbackVerdict) {
        val snapshot = lastLiveFeedbackSnapshot ?: return
        testFeedbackRepository.record(verdict, candidateLabel, snapshot)
    }

    fun recordCurrentLiveWindowAsNoise() {
        val snapshot = lastLiveFeedbackSnapshot ?: return
        testFeedbackRepository.record(FeedbackVerdict.NOISE, null, snapshot)
    }

    fun recordResultTestFeedback(candidateLabel: String, verdict: FeedbackVerdict) {
        val result = (_ui.value as? UiState.Result)?.result ?: return
        val snapshot = TestFeedbackSnapshot(
            source = "result",
            sessionKey = result.communityRecordId ?: result.evidenceAudio?.filePath,
            candidates = result.candidates.take(3),
            quality = result.recordingQuality,
            signature = result.signature,
            signalAssessment = result.signalAssessment,
            observationContext = result.observationContext
        )
        testFeedbackRepository.record(verdict, candidateLabel, snapshot)
    }

    fun recordCurrentResultAsNoise() {
        val result = (_ui.value as? UiState.Result)?.result ?: return
        val snapshot = TestFeedbackSnapshot(
            source = "result",
            sessionKey = result.communityRecordId ?: result.evidenceAudio?.filePath,
            candidates = result.candidates.take(3),
            quality = result.recordingQuality,
            signature = result.signature,
            signalAssessment = result.signalAssessment,
            observationContext = result.observationContext
        )
        testFeedbackRepository.record(FeedbackVerdict.NOISE, null, snapshot)
    }

    /**
     * Re-evaluate only the cheap raw-audio/display gates for the most recent live window.
     * Frozen Perch/J.1 inference is intentionally not rerun while the user drags the gate.
     */
    fun setLivePossibleMatchSensitivity(value: Float) {
        PossibleMatchGate.set(getApplication<Application>(), value.coerceIn(0f, 1f))
        val result = lastLiveAnalysisResult ?: return
        val rawTopThree = lastLiveRawTopThree
        val rawPcm = lastLiveRawPcm
        val sampleRate = lastLiveRawSampleRate
        if (rawPcm.isEmpty() || sampleRate <= 0) return

        liveGateRefreshJob?.cancel()
        liveGateRefreshJob = viewModelScope.launch(Dispatchers.Default) {
            val assessment = InsectSignalGate.assess(
                rawSamples = rawPcm,
                sampleRate = sampleRate,
                signature = result.rawSignature,
                quality = result.rawQuality,
                sensitivityLevel = PossibleMatchGate.level
            )
            // A fresh rolling window wins over a stale slider refresh.
            if (lastLiveRawPcm !== rawPcm || lastLiveAnalysisResult !== result) return@launch
            _liveSignalAssessment.value = assessment
            _liveCandidates.value = filterLiveCandidates(rawTopThree, result, assessment)
        }
    }

    private fun filterLiveCandidates(
        rawTopThree: List<Candidate>,
        result: ClipAnalyzer.Result,
        assessment: InsectSignalAssessment
    ): List<Candidate> {
        if (!assessment.passed) return emptyList()
        return rawTopThree.filter { candidate ->
            candidate.evidenceAccepted == true ||
                (candidate.callCompatibilityPassed != false && PossibleMatchGate.allows(
                    candidate = candidate,
                    quality = result.quality,
                    signature = result.signature,
                    consecutiveWindows = liveCandidateStreaks[candidate.label] ?: 1
                ))
        }
    }

    fun startListening() {
        cancelAnalysis(silent = true)
        liveAnalysisJob?.cancel()
        elapsedJob?.cancel()
        mic.stop()
        // Recording always starts immediately. Optional location/weather refresh runs on its own
        // coroutine and can update the eventual observation context without blocking the mic.
        beginListening()
        refreshContextInBackgroundIfNeeded()
    }

    private fun beginListening() {
        _ui.value = UiState.Listening
        _liveCandidates.value = emptyList()
        _liveSignalAssessment.value = null
        lastLiveFeedbackSnapshot = null
        liveCandidateStreaks.clear()
        previousRawLiveLabels = emptySet()
        lastLiveAnalysisResult = null
        lastLiveRawTopThree = emptyList()
        lastLiveRawPcm = FloatArray(0)
        lastLiveRawSampleRate = 0
        liveGateRefreshJob?.cancel()
        liveGateRefreshJob = null
        PossibleMatchGate.initialize(getApplication<Application>())
        _liveDetections.value = emptyList()
        _recordingElapsedSeconds.value = 0.0
        recordingStartedAtMillis = System.currentTimeMillis()
        val ext = FeatureExtractor(48000, fftSize).also { liveExtractor = it }
        val rawFile = detectionLogRepository.newRawCaptureFile()
        mic.start(rawFile) { spectrum, t -> ext.addFrame(spectrum, t) }

        elapsedJob?.cancel()
        elapsedJob = viewModelScope.launch {
            while (isActive && _ui.value is UiState.Listening) {
                _recordingElapsedSeconds.value =
                    (System.currentTimeMillis() - recordingStartedAtMillis).coerceAtLeast(0L) / 1000.0
                delay(250L)
            }
        }

        liveAnalysisJob?.cancel()
        liveAnalysisJob = viewModelScope.launch {
            delay(LIVE_INITIAL_DELAY_MILLIS)
            while (isActive && _ui.value is UiState.Listening) {
                analyzeRollingWindow()
                delay(LIVE_ANALYSIS_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun analyzeRollingWindow() {
        if (!usingTrainedModel) return
        val (pcm, pcmSr) = mic.capturedPcm()
        if (pcm.size < pcmSr * 5) return
        val result = withContext(Dispatchers.Default) { clipAnalyzer.analyze(pcm, pcmSr) } ?: return
        lastLiveAnalysisResult = result
        lastLiveRawPcm = pcm
        lastLiveRawSampleRate = pcmSr
        _liveSignalAssessment.value = result.signalAssessment

        // Discovery view: preserve useful below-J.1 candidates, but do not force arbitrary
        // species onto silence/noise. The user-controlled PossibleMatchGate is deliberately
        // separate from frozen J.1 acceptance: it only controls what the live Top 3 displays.
        val rawTopThree = result.candidates
            .asSequence()
            .filter { it.species != null }
            .sortedByDescending { it.audioConfidence }
            .take(3)
            .toList()
        lastLiveRawTopThree = rawTopThree
        val rawLabels = rawTopThree.map { it.label }.toSet()
        rawTopThree.forEach { candidate ->
            liveCandidateStreaks[candidate.label] =
                if (candidate.label in previousRawLiveLabels) {
                    (liveCandidateStreaks[candidate.label] ?: 0) + 1
                } else {
                    1
                }
        }
        liveCandidateStreaks.keys.retainAll(rawLabels)
        previousRawLiveLabels = rawLabels

        _liveCandidates.value = filterLiveCandidates(rawTopThree, result, result.signalAssessment)

        val feedbackNow = _recordingElapsedSeconds.value
        lastLiveFeedbackSnapshot = TestFeedbackSnapshot(
            source = "live",
            sessionKey = recordingStartedAtMillis.toString(),
            windowStartSeconds = (feedbackNow - LIVE_WINDOW_SECONDS).coerceAtLeast(0.0),
            windowEndSeconds = feedbackNow,
            candidates = rawTopThree,
            quality = result.rawQuality,
            signature = result.rawSignature,
            signalAssessment = result.signalAssessment,
            observationContext = environment.value
        )

        // Decision/logging view: the raw-audio signal gate is mandatory. J.1 can still be
        // calculated and exported for QA on rejected noise windows, but no accepted/logged call
        // may originate from silence/noise that failed the class-agnostic front gate.
        if (!result.signalAssessment.passed) return
        val top = result.candidates.firstOrNull() ?: return
        val runnerUp = result.candidates.getOrNull(1)
        val policy = classifier.policy ?: return
        val gate = OpenSetDecision.evaluate(
            top = top,
            runnerUp = runnerUp,
            signature = result.signature,
            recordingQuality = result.quality,
            policy = policy
        )
        if (gate.type == OpenSetDecisionType.REJECTED) return
        val species = top.species ?: return
        if (!tierSettings.value.allows(top.reliability.tier)) return

        val nowSeconds = _recordingElapsedSeconds.value
        val occurrence = DetectionOccurrence(
            startSeconds = (nowSeconds - LIVE_WINDOW_SECONDS).coerceAtLeast(0.0),
            endSeconds = nowSeconds,
            confidencePct = (top.audioConfidence * 100.0).toInt().coerceIn(0, 100)
        )
        val current = _liveDetections.value
        val existing = current.firstOrNull { it.species.id == species.id }
        val updated = if (existing == null) {
            Detection(
                species = species,
                confidencePct = occurrence.confidencePct,
                peakConfidencePct = occurrence.confidencePct,
                time = Date(),
                occurrences = listOf(occurrence)
            )
        } else {
            existing.copy(
                confidencePct = occurrence.confidencePct,
                peakConfidencePct = maxOf(existing.peakConfidencePct, occurrence.confidencePct),
                time = Date(),
                occurrences = (existing.occurrences + occurrence).takeLast(MAX_OCCURRENCES_PER_SPECIES)
            )
        }
        _liveDetections.value = listOf(updated) + current.filterNot { it.species.id == species.id }
    }

    fun stopAndIdentify() = stopAndSaveLog()

    fun stopAndSaveLog() {
        liveAnalysisJob?.cancel()
        liveAnalysisJob = null
        elapsedJob?.cancel()
        elapsedJob = null
        mic.stop()
        val rawFile = mic.capturedRawFile()
        val started = recordingStartedAtMillis
        val ended = System.currentTimeMillis()
        val detections = _liveDetections.value.map { detection ->
            LoggedSpeciesDetection(
                speciesId = detection.species.id,
                latestConfidencePct = detection.confidencePct,
                peakConfidencePct = detection.peakConfidencePct,
                lastHeardAtMillis = detection.time.time,
                occurrences = detection.occurrences
            )
        }
        _ui.value = UiState.Analyzing("Saving recording to Log…")
        launchWork {
            withContext(Dispatchers.IO) {
                detectionLogRepository.saveSession(
                    startedAtMillis = started,
                    endedAtMillis = ended,
                    rawPcmFile = rawFile,
                    sampleRate = mic.sampleRate,
                    detections = detections,
                    observationContext = environment.value
                )
            }
            _recordingElapsedSeconds.value = 0.0
            _liveCandidates.value = emptyList()
            _liveSignalAssessment.value = null
            lastLiveFeedbackSnapshot = null
            liveCandidateStreaks.clear()
            previousRawLiveLabels = emptySet()
            lastLiveAnalysisResult = null
            lastLiveRawTopThree = emptyList()
            lastLiveRawPcm = FloatArray(0)
            lastLiveRawSampleRate = 0
            liveGateRefreshJob?.cancel()
            liveGateRefreshJob = null
            _ui.value = UiState.Idle
        }
    }

    fun cancelListening() {
        liveAnalysisJob?.cancel()
        liveAnalysisJob = null
        elapsedJob?.cancel()
        elapsedJob = null
        mic.discardCapture()
        _liveDetections.value = emptyList()
        _recordingElapsedSeconds.value = 0.0
        _liveCandidates.value = emptyList()
        _liveSignalAssessment.value = null
        lastLiveFeedbackSnapshot = null
        liveCandidateStreaks.clear()
        previousRawLiveLabels = emptySet()
        lastLiveAnalysisResult = null
        lastLiveRawTopThree = emptyList()
        lastLiveRawPcm = FloatArray(0)
        lastLiveRawSampleRate = 0
        liveGateRefreshJob?.cancel()
        liveGateRefreshJob = null
        _ui.value = UiState.Idle
    }

    /**
     * Analyze a saved file. Current conditions are opt-in because an imported recording may have
     * been captured at another time or place.
     */
    fun analyzeFile(uri: Uri, useCurrentContext: Boolean = false) {
        cancelListening()
        if (!usingTrainedModel) {
            _ui.value = UiState.Error(
                "The trained sound model is not available on this device. " +
                    "Stridulate will not substitute a heuristic guess. $modelStatus"
            )
            return
        }
        _ui.value = UiState.Analyzing(
            if (useCurrentContext) "Refreshing current conditions…" else "Preparing audio-only analysis…"
        )
        launchWork {
            val contextSnapshot = if (useCurrentContext) {
                // This is an explicit here-and-now choice, so force a fresh request even when the
                // previous snapshot is younger than the automatic ten-minute refresh window.
                if (environment.value.enabled) environmentRepository.refreshNow()
                else environmentRepository.useDeviceLocation(forceFreshLocation = true)
                currentCoroutineContext().ensureActive()
                environment.value
            } else {
                ObservationContext(
                    message = "Imported recording analyzed without current weather because its original time and place are unknown."
                )
            }
            _ui.value = UiState.Analyzing("Decoding audio…")
            val decoded = withContext(Dispatchers.IO) {
                AudioFileDecoder.decode(getApplication(), uri, maxSeconds = 30.0)
            }
            currentCoroutineContext().ensureActive()
            _ui.value = UiState.Analyzing("Checking quality and running frozen J.1 / Perch 2.0…")
            val res = withContext(Dispatchers.Default) {
                clipAnalyzer.analyze(decoded.samples, decoded.sampleRate)
            }
            currentCoroutineContext().ensureActive()
            if (res == null) {
                _ui.value = UiState.Error(
                    "No clear insect song found in that file. Try a clip with steady, close calling."
                )
            } else {
                val evidence = withContext(Dispatchers.IO) {
                    EvidenceAudioStore.writeTemp(
                        getApplication(),
                        decoded.samples,
                        decoded.sampleRate,
                        EvidenceSource.IMPORTED,
                        observedAtMillis = null
                    )
                }
                currentCoroutineContext().ensureActive()
                recordAndShow(
                    IdResult(
                        signature = res.signature,
                        candidates = res.candidates,
                        spectrogram = res.spectrogram,
                        recordingQuality = res.quality,
                        signalAssessment = res.signalAssessment,
                        evidenceAudio = evidence
                    ),
                    contextOverride = contextSnapshot
                )
            }
        }
    }

    fun cancelAnalysis(silent: Boolean = false) {
        workJob?.cancel()
        workJob = null
        if (!silent && _ui.value is UiState.Analyzing) _ui.value = UiState.Idle
    }

    private fun launchWork(block: suspend () -> Unit) {
        workJob?.cancel()
        lateinit var job: Job
        job = viewModelScope.launch {
            try {
                block()
            } catch (_: CancellationException) {
                if (_ui.value is UiState.Analyzing) _ui.value = UiState.Idle
            } catch (e: Exception) {
                _ui.value = UiState.Error(e.message ?: "Something went wrong.")
            } finally {
                if (workJob == job) workJob = null
            }
        }
        workJob = job
    }

    private fun recordAndShow(
        rawResult: IdResult,
        contextOverride: ObservationContext? = null
    ) {
        val rankedOutputs = rawResult.candidates
        val topOutput = rankedOutputs.firstOrNull()
        if (topOutput == null) {
            _ui.value = UiState.Error("The Tier 1 model returned no output scores.")
            return
        }

        val policy = classifier.policy ?: ClassificationPolicy(
            unknownLabel = "Unknown_or_unsupported",
            minimumConfidence = 1.0,
            minimumMargin = 1.0,
            reliabilityByLabel = emptyMap(),
            openSetSafetyPolicy = OpenSetSafetyPolicy.conservativeFallback()
        )
        val gate = OpenSetDecision.evaluate(
            top = topOutput,
            runnerUp = rankedOutputs.getOrNull(1),
            signature = rawResult.signature,
            recordingQuality = rawResult.recordingQuality,
            policy = policy
        )
        val signalRejected = rawResult.signalAssessment?.passed == false
        val decision = if (signalRejected) {
            IdentificationDecision.NO_CONFIDENT_MATCH
        } else {
            when (gate.type) {
                OpenSetDecisionType.STRONG_POSSIBLE -> IdentificationDecision.IDENTIFIED
                OpenSetDecisionType.POSSIBLE -> IdentificationDecision.POSSIBLE_MATCH
                OpenSetDecisionType.REJECTED -> IdentificationDecision.NO_CONFIDENT_MATCH
            }
        }
        val reason = if (signalRejected) {
            rawResult.signalAssessment?.reason ?: "No insect-like signal was detected in the raw audio."
        } else {
            gate.reason
        }

        val contextSnapshot = contextOverride ?: environment.value
        val baseResult = rawResult.copy(
            decision = decision,
            decisionReason = reason,
            modelTopLabel = topOutput.label,
            modelTopConfidence = topOutput.audioConfidence,
            modelMargin = gate.margin,
            requiredConfidence = gate.requiredConfidence,
            requiredMargin = gate.requiredMargin,
            acousticCheckPassed = gate.acousticCheck.passed,
            acousticCheckSummary = gate.acousticCheck.summary,
            allAudioCandidates = rankedOutputs
        )
        val result = reapplyContext(baseResult, contextSnapshot)

        _ui.value = UiState.Result(result)
    }

    private fun reapplyContext(
        result: IdResult,
        contextSnapshot: ObservationContext
    ): IdResult {
        val rankedOutputs = result.allAudioCandidates.ifEmpty { result.candidates }
        val reranked = contextReranker.rerank(rankedOutputs, contextSnapshot)
        val supported = reranked.candidates.filter { it.species != null }
        // The visible Top 3 is a discovery ranking, not a gate ranking. A below-threshold class
        // with the strongest J.1 evidence must remain visible instead of being displaced by a
        // lower-scoring class that happens to have an easier acceptance threshold.
        val supportedTopThree = if (reranked.applied) {
            supported.sortedByDescending { it.contextScore }.take(3)
        } else {
            supported.sortedByDescending { it.audioConfidence }.take(3)
        }
        return result.copy(
            candidates = supportedTopThree,
            observationContext = contextSnapshot,
            contextApplied = reranked.applied,
            contextSummary = reranked.summary
        )
    }

    fun saveCurrentForCommunity() {
        val current = (_ui.value as? UiState.Result)?.result ?: return
        if (current.communityRecordId != null) {
            _communityNotice.value = "Recording is already saved in Unknowns."
            return
        }
        viewModelScope.launch {
            _communityBusy.value = "Saving lossless WAV and result metadata…"
            try {
                val record = withContext(Dispatchers.IO) { communityRepository.saveResult(current) }
                val stillCurrent = (_ui.value as? UiState.Result)?.result
                if (stillCurrent != null) {
                    _ui.value = UiState.Result(stillCurrent.copy(communityRecordId = record.id))
                }
                _communityNotice.value = "Saved ${record.id}. Share it to iNaturalist when ready."
            } catch (e: Exception) {
                _communityNotice.value = e.message ?: "Could not save the recording."
            } finally {
                _communityBusy.value = null
            }
        }
    }

    fun requestIdentificationShare(recordId: String) {
        runCatching { communityRepository.markShared(recordId) }
            .onSuccess { _communityShareRequest.value = CommunityShareRequest.Identification(recordId) }
            .onFailure { _communityNotice.value = it.message ?: "Could not prepare the WAV." }
    }

    fun linkCommunityRecord(recordId: String, observationUrlOrId: String) {
        viewModelScope.launch {
            _communityBusy.value = "Linking and checking iNaturalist…"
            try {
                val linked = withContext(Dispatchers.IO) {
                    communityRepository.linkINaturalist(recordId, observationUrlOrId)
                }
                refreshCommunityRecordInternal(linked.id)
            } catch (e: Exception) {
                _communityNotice.value = e.message ?: "Could not link that observation."
            } finally {
                _communityBusy.value = null
            }
        }
    }

    fun refreshCommunityRecord(recordId: String) {
        viewModelScope.launch {
            _communityBusy.value = "Checking the latest iNaturalist identifications…"
            try {
                refreshCommunityRecordInternal(recordId)
            } catch (e: Exception) {
                _communityNotice.value = e.message ?: "Could not refresh iNaturalist."
            } finally {
                _communityBusy.value = null
            }
        }
    }

    fun refreshAllCommunityRecords() {
        val linked = communityRecords.value.filter { it.iNaturalistObservationId != null }
        if (linked.isEmpty()) {
            _communityNotice.value = "No iNaturalist observations are linked yet."
            return
        }
        viewModelScope.launch {
            _communityBusy.value = "Checking ${linked.size} linked observation${if (linked.size == 1) "" else "s"}…"
            var updated = 0
            var failed = 0
            linked.forEach { record ->
                try {
                    refreshCommunityRecordInternal(record.id, announce = false)
                    updated++
                } catch (_: Exception) {
                    failed++
                }
            }
            _communityNotice.value = buildString {
                append("Checked $updated observation")
                if (updated != 1) append('s')
                if (failed > 0) append("; $failed failed")
                append('.')
            }
            _communityBusy.value = null
        }
    }

    private suspend fun refreshCommunityRecordInternal(recordId: String, announce: Boolean = true) {
        val record = communityRecords.value.firstOrNull { it.id == recordId }
            ?: throw IllegalArgumentException("Saved recording not found.")
        val observationId = record.iNaturalistObservationId
            ?: throw IllegalArgumentException("Link an iNaturalist observation first.")
        val snapshot = withContext(Dispatchers.IO) { iNaturalistClient.fetchObservation(observationId) }
        val updated = withContext(Dispatchers.IO) {
            communityRepository.applyINaturalistSnapshot(recordId, snapshot)
        }
        if (announce) {
            _communityNotice.value = if (updated.hasCommunityIdentification) {
                "Community ID: ${updated.displayTaxon}. Review it before approving training use."
            } else {
                "Still awaiting a community identification (${updated.identificationsCount} IDs)."
            }
        }
    }

    fun approveCommunityRecord(
        recordId: String,
        label: String,
        contributorCredit: String,
        rightsConfirmed: Boolean
    ) {
        viewModelScope.launch {
            _communityBusy.value = "Preparing human-reviewed contribution…"
            try {
                val updated = withContext(Dispatchers.IO) {
                    communityRepository.approveForTraining(
                        recordId, label, contributorCredit, rightsConfirmed
                    )
                }
                _communityNotice.value = "Approved ${updated.id} as ${updated.approvedTrainingLabel} under ${updated.contributionLicense}."
            } catch (e: Exception) {
                _communityNotice.value = e.message ?: "Could not approve the recording."
            } finally {
                _communityBusy.value = null
            }
        }
    }

    fun requestTrainingBundle(recordId: String) {
        viewModelScope.launch {
            _communityBusy.value = "Building WAV + metadata contribution ZIP…"
            try {
                val file = withContext(Dispatchers.IO) { communityRepository.exportTrainingBundle(recordId) }
                _communityShareRequest.value = CommunityShareRequest.TrainingBundle(recordId, file.absolutePath)
            } catch (e: Exception) {
                _communityNotice.value = e.message ?: "Could not export the contribution bundle."
            } finally {
                _communityBusy.value = null
            }
        }
    }

    fun deleteCommunityRecord(recordId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { communityRepository.delete(recordId) }
            _communityNotice.value = "Deleted the saved recording."
        }
    }

    fun updateCommunityNote(recordId: String, note: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { communityRepository.updateNote(recordId, note) }
            _communityNotice.value = "Review note saved."
        }
    }

    fun deleteLogSession(sessionId: String) {
        viewModelScope.launch {
            RecordedSegmentPlayer.stop()
            val deleted = withContext(Dispatchers.IO) { detectionLogRepository.delete(sessionId) }
            if (!deleted) _communityNotice.value = "That Log recording was already removed."
        }
    }

    fun moveLogSessionToUnknowns(sessionId: String) {
        val session = logSessions.value.firstOrNull { it.id == sessionId }
        if (session == null) {
            _communityNotice.value = "That Log recording was not found."
            return
        }
        viewModelScope.launch {
            _communityBusy.value = "Moving recording into Unknowns…"
            try {
                RecordedSegmentPlayer.stop()
                val record = withContext(Dispatchers.IO) {
                    communityRepository.importLogSession(session, repo::byId).also {
                        check(detectionLogRepository.delete(sessionId)) {
                            "The recording was copied to Unknowns, but could not be removed from Log."
                        }
                    }
                }
                _communityNotice.value = "Moved ${record.id} to Unknowns. Open it to listen, add notes, or share it for identification."
            } catch (e: Exception) {
                _communityNotice.value = e.message ?: "Could not move the recording to Unknowns."
            } finally {
                _communityBusy.value = null
            }
        }
    }

    fun showCommunityNotice(message: String) {
        _communityNotice.value = message
    }

    fun clearCommunityNotice() {
        _communityNotice.value = null
    }

    fun clearCommunityShareRequest() {
        _communityShareRequest.value = null
    }

    fun clearSession() {
        viewModelScope.launch {
            RecordedSegmentPlayer.stop()
            withContext(Dispatchers.IO) { detectionLogRepository.clear() }
        }
    }

    fun setTierEnabled(tier: com.pgotta.stridulate.data.ReliabilityTier, enabled: Boolean) {
        detectionSettingsRepository.setEnabled(tier, enabled)
    }

    fun prefetchFieldGuidePhotos() {
        if (photoPrefetchJob?.isActive == true) return
        photoPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
            SpeciesPhoto.prefetch(getApplication(), tier1Species)
        }
    }

    fun dismissResult() {
        ReferenceSoundPlayer.stop()
        val evidence = (_ui.value as? UiState.Result)?.result?.evidenceAudio
        EvidenceAudioStore.deleteQuietly(evidence)
        _ui.value = UiState.Idle
    }

    override fun onCleared() {
        cancelAnalysis(silent = true)
        liveAnalysisJob?.cancel()
        elapsedJob?.cancel()
        photoPrefetchJob?.cancel()
        mic.stop()
        EvidenceAudioStore.deleteQuietly((_ui.value as? UiState.Result)?.result?.evidenceAudio)
        ReferenceSoundPlayer.stop()
        classifier.close()
        super.onCleared()
    }


    private companion object {
        const val CONTEXT_REFRESH_POLL_MILLIS = 60_000L
        const val LIVE_INITIAL_DELAY_MILLIS = 5_500L
        const val LIVE_ANALYSIS_INTERVAL_MILLIS = 2_500L
        const val LIVE_WINDOW_SECONDS = 5.0
        const val MAX_OCCURRENCES_PER_SPECIES = 100
    }
}
