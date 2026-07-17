package com.pgotta.stridulate.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pgotta.stridulate.audio.AudioFileDecoder
import com.pgotta.stridulate.audio.ClipAnalyzer
import com.pgotta.stridulate.audio.FeatureExtractor
import com.pgotta.stridulate.audio.MeasuredSignature
import com.pgotta.stridulate.audio.MicRecorder
import com.pgotta.stridulate.audio.ReferenceSoundPlayer
import com.pgotta.stridulate.audio.RecordingQuality
import com.pgotta.stridulate.audio.RecordingQualityAssessor
import com.pgotta.stridulate.classifier.Candidate
import com.pgotta.stridulate.classifier.ClassificationPolicy
import com.pgotta.stridulate.classifier.InsectClassifier
import com.pgotta.stridulate.classifier.TfLiteClassifier
import com.pgotta.stridulate.community.CommunityObservationRecord
import com.pgotta.stridulate.community.CommunityObservationRepository
import com.pgotta.stridulate.community.EvidenceAudio
import com.pgotta.stridulate.community.EvidenceAudioStore
import com.pgotta.stridulate.community.EvidenceSource
import com.pgotta.stridulate.community.INaturalistClient
import com.pgotta.stridulate.data.ReliabilityInfo
import com.pgotta.stridulate.data.ReliabilityTier
import com.pgotta.stridulate.data.Species
import com.pgotta.stridulate.data.SpeciesReliabilityRepository
import com.pgotta.stridulate.data.SpeciesRepository
import com.pgotta.stridulate.environment.ContextAssessment
import com.pgotta.stridulate.environment.ContextProfileRepository
import com.pgotta.stridulate.environment.ContextReranker
import com.pgotta.stridulate.environment.EnvironmentRepository
import com.pgotta.stridulate.environment.ObservationContext
import java.io.File
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Detection(
    val species: Species,
    val confidencePct: Int,
    val time: Date
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
    val observationContext: ObservationContext = ObservationContext(),
    val contextApplied: Boolean = false,
    val contextSummary: String = "Audio ranking only.",
    val recordingQuality: RecordingQuality? = null,
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

    /** Field-guide entries supported by the bundled Tier 1 v5 labels, in label order. */
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
            "insect_model.tflite",
            "labels.txt",
            "model_meta.json",
            "normalization.json",
            "species_reliability.json",
            "context_profiles.json"
        )
        val missing = requiredAssets.filterNot(::assetExists)
        if (missing.isNotEmpty()) {
            ClassifierSetup(
                classifier = unavailableClassifier,
                usingTrainedModel = false,
                status = "Model unavailable · missing ${missing.joinToString()}"
            )
        } else {
            try {
                val trained = TfLiteClassifier(app, repo.species)
                val supportedSpecies = (trained.classCount ?: 1) - 1
                ClassifierSetup(
                    classifier = trained,
                    usingTrainedModel = true,
                    status = "Tier 1 v5 active · $supportedSpecies species + unsupported · ${trained.backendName}"
                )
            } catch (e: Exception) {
                ClassifierSetup(
                    classifier = unavailableClassifier,
                    usingTrainedModel = false,
                    status = "Model failed · ${(e.message ?: e.javaClass.simpleName).replace(Regex("\\s+"), " ").take(180)}"
                )
            } catch (e: LinkageError) {
                ClassifierSetup(
                    classifier = unavailableClassifier,
                    usingTrainedModel = false,
                    status = "Model unavailable · TensorFlow runtime unavailable"
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

    private val _session = MutableStateFlow<List<Detection>>(emptyList())
    val session: StateFlow<List<Detection>> = _session

    val spectrogramColumn: StateFlow<FloatArray> get() = mic.spectrogramColumn
    val loudness: StateFlow<Float> get() = mic.loudness

    private val _liveCandidate = MutableStateFlow<Candidate?>(null)
    val liveCandidate: StateFlow<Candidate?> = _liveCandidate

    private var liveExtractor: FeatureExtractor? = null
    private var workJob: Job? = null

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

    fun startListening() {
        cancelAnalysis(silent = true)
        mic.stop()
        if (environment.value.enabled && !environment.value.isFresh) {
            _ui.value = UiState.Analyzing("Refreshing current location and weather…")
            launchWork {
                environmentRepository.refreshIfStale()
                currentCoroutineContext().ensureActive()
                beginListening()
            }
        } else {
            beginListening()
        }
    }

    private fun beginListening() {
        _ui.value = UiState.Listening
        _liveCandidate.value = null
        val ext = FeatureExtractor(48000, fftSize).also { liveExtractor = it }
        mic.start { spectrum, t ->
            ext.addFrame(spectrum, t)
            // The neural model needs the complete raw recording, so ID waits for Stop.
            if (ext.frameCount() % 8 == 0) _liveCandidate.value = null
        }
    }

    fun stopAndIdentify() {
        mic.stop()
        if (!usingTrainedModel) {
            _ui.value = UiState.Error(
                "The trained sound model is not available on this device. " +
                    "Stridulate will not substitute a heuristic guess. $modelStatus"
            )
            return
        }
        val sig = liveExtractor?.aggregate()
        if (sig == null || sig.loudness < 45) {
            _ui.value = UiState.Error("Too quiet — get closer to the caller and try again.")
            return
        }
        if (sig.insectLikelihood < INSECT_THRESHOLD) {
            _ui.value = UiState.Error(
                "That didn't sound like a singing insect. Voices, wind, traffic and other " +
                    "noise can't be identified. Try again near a clear, steady call."
            )
            return
        }
        _ui.value = UiState.Analyzing("Checking recording quality and matching the Tier 1 model…")
        val (pcm, pcmSr) = mic.capturedPcm()
        launchWork {
            val (candidates, quality) = withContext(Dispatchers.Default) {
                val ranked = if (pcm.isNotEmpty()) classifier.classify(pcm, pcmSr, sig)
                else classifier.classify(sig)
                val assessed = if (pcm.isNotEmpty()) RecordingQualityAssessor.assess(pcm, pcmSr, sig)
                else null
                ranked to assessed
            }
            currentCoroutineContext().ensureActive()
            val evidence = if (pcm.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    EvidenceAudioStore.writeTemp(
                        getApplication(),
                        pcm,
                        pcmSr,
                        EvidenceSource.LIVE,
                        System.currentTimeMillis()
                    )
                }
            } else null
            currentCoroutineContext().ensureActive()
            recordAndShow(
                IdResult(
                    signature = sig,
                    candidates = candidates,
                    spectrogram = emptyList(),
                    recordingQuality = quality,
                    evidenceAudio = evidence
                )
            )
        }
    }

    fun cancelListening() {
        mic.stop()
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
            _ui.value = UiState.Analyzing("Checking quality and reading the 44.1 kHz mel spectrogram…")
            val res = withContext(Dispatchers.Default) {
                clipAnalyzer.analyze(decoded.samples, decoded.sampleRate)
            }
            currentCoroutineContext().ensureActive()
            if (res == null) {
                _ui.value = UiState.Error(
                    "No clear insect song found in that file. Try a clip with steady, close calling."
                )
            } else if (res.signature.insectLikelihood < INSECT_THRESHOLD) {
                _ui.value = UiState.Error(
                    "That recording didn't sound like a singing insect. Voices, music, wind " +
                        "and traffic can't be identified — try a clear cricket, katydid or cicada call."
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
            reliabilityByLabel = emptyMap()
        )
        val secondOutput = rankedOutputs.getOrNull(1)
        val margin = topOutput.audioConfidence - (secondOutput?.audioConfidence ?: 0.0)
        val topIsUnknown = topOutput.label == policy.unknownLabel || topOutput.isUnknown
        val passesThresholds = topOutput.audioConfidence >= policy.minimumConfidence &&
            margin >= policy.minimumMargin
        val topTier = policy.reliabilityByLabel[topOutput.label]?.tier ?: topOutput.reliability.tier
        val qualityBlock = rawResult.recordingQuality?.blockingReason

        val decision = when {
            qualityBlock != null -> IdentificationDecision.NO_CONFIDENT_MATCH
            topIsUnknown || !passesThresholds -> IdentificationDecision.NO_CONFIDENT_MATCH
            topTier == ReliabilityTier.VERIFIED -> IdentificationDecision.IDENTIFIED
            else -> IdentificationDecision.POSSIBLE_MATCH
        }
        val reason = when {
            qualityBlock != null -> qualityBlock
            topIsUnknown ->
                "The model favored Unknown/Unsupported, so it is not assigning a species."
            topOutput.audioConfidence < policy.minimumConfidence ->
                "The best audio score did not meet the model's calibrated confidence threshold."
            margin < policy.minimumMargin ->
                "The two leading audio outputs were too close for a confident species match."
            topTier == ReliabilityTier.VERIFIED ->
                "Verified tier: stronger V50 locked-holdout support. This remains an acoustic estimate, not scientific confirmation."
            topTier == ReliabilityTier.GOOD ->
                "Good tier: promising V50 evaluation support, but not enough for the app's direct-identification tier. Treat it as a possible match."
            else ->
                "Experimental tier: limited or uneven evaluation support. Treat it as a possible acoustic match and compare the field guide."
        }

        val contextSnapshot = contextOverride ?: environment.value
        val baseResult = rawResult.copy(
            decision = decision,
            decisionReason = reason,
            modelTopLabel = topOutput.label,
            modelTopConfidence = topOutput.audioConfidence,
            modelMargin = margin,
            requiredConfidence = policy.minimumConfidence,
            requiredMargin = policy.minimumMargin,
            allAudioCandidates = rankedOutputs
        )
        val result = reapplyContext(baseResult, contextSnapshot)

        if (decision != IdentificationDecision.NO_CONFIDENT_MATCH) {
            topOutput.species?.let { species ->
                val detection = Detection(species, (topOutput.audioConfidence * 100).toInt(), Date())
                _session.value = listOf(detection) + _session.value
            }
        }
        _ui.value = UiState.Result(result)
    }

    private fun reapplyContext(
        result: IdResult,
        contextSnapshot: ObservationContext
    ): IdResult {
        val rankedOutputs = result.allAudioCandidates.ifEmpty { result.candidates }
        val reranked = contextReranker.rerank(rankedOutputs, contextSnapshot)
        val supported = reranked.candidates.filter { it.species != null }
        val supportedTopThree = if (result.decision == IdentificationDecision.NO_CONFIDENT_MATCH) {
            supported.take(3)
        } else {
            val accepted = supported.firstOrNull { it.label == result.modelTopLabel }
                ?: rankedOutputs.firstOrNull { it.label == result.modelTopLabel }
            if (accepted == null) supported.take(3)
            else listOf(accepted) + supported.filterNot { it.label == accepted.label }.take(2)
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
        _session.value = emptyList()
    }

    fun dismissResult() {
        ReferenceSoundPlayer.stop()
        val evidence = (_ui.value as? UiState.Result)?.result?.evidenceAudio
        EvidenceAudioStore.deleteQuietly(evidence)
        _ui.value = UiState.Idle
    }

    override fun onCleared() {
        cancelAnalysis(silent = true)
        mic.stop()
        EvidenceAudioStore.deleteQuietly((_ui.value as? UiState.Result)?.result?.evidenceAudio)
        ReferenceSoundPlayer.stop()
        classifier.close()
        super.onCleared()
    }

    companion object {
        const val INSECT_THRESHOLD = 0.55
    }
}
