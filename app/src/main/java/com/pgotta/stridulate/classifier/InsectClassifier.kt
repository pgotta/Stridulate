package com.pgotta.stridulate.classifier

import com.pgotta.stridulate.audio.MeasuredSignature
import com.pgotta.stridulate.data.ReliabilityInfo
import com.pgotta.stridulate.data.OpenSetSafetyPolicy
import com.pgotta.stridulate.data.ReliabilityTier
import com.pgotta.stridulate.data.Species

/** A single ranked model output. Unknown/unsupported has no field-guide species. */
data class Candidate(
    val label: String,
    val species: Species?,
    /** Calibrated audio-model score. Context never overwrites this value. */
    val confidence: Double,
    val rawScore: Double,
    val reliability: ReliabilityInfo = ReliabilityInfo(ReliabilityTier.EXPERIMENTAL),
    /** Ranking score after optional small region/season/time adjustments. */
    val contextScore: Double = confidence,
    val contextMultiplier: Double = 1.0,
    val contextSummary: String? = null,
    /** Frozen J.1 acceptance threshold for this species, when this is a J.1 result. */
    val acceptanceThreshold: Double? = null,
    /** Conservative UI threshold for the "High confidence" evidence band. */
    val highConfidenceThreshold: Double? = null,
    /** Absolute J.1 evidence decision. Null for legacy/fallback classifiers. */
    val evidenceAccepted: Boolean? = null,
    /** Short model-specific evidence note for diagnostics and result explanations. */
    val evidenceSupport: String? = null
) {
    val isUnknown: Boolean get() = species == null
    val audioConfidence: Double get() = confidence
}

/** Calibrated decision rules and per-species evaluation tiers shipped with the active model. */
data class ClassificationPolicy(
    val unknownLabel: String,
    val minimumConfidence: Double,
    val minimumMargin: Double,
    val reliabilityByLabel: Map<String, ReliabilityInfo>,
    val openSetSafetyPolicy: OpenSetSafetyPolicy
) {
    val verifiedLabels: Set<String>
        get() = reliabilityByLabel.filterValues { it.tier == ReliabilityTier.VERIFIED }.keys
}

interface InsectClassifier : AutoCloseable {
    val policy: ClassificationPolicy? get() = null
    val classCount: Int? get() = null

    fun classify(signature: MeasuredSignature): List<Candidate>

    fun classify(pcm: FloatArray, sampleRate: Int, signature: MeasuredSignature): List<Candidate> =
        classify(signature)

    override fun close() = Unit
}
