package com.pgotta.stridulate.classifier

import com.pgotta.stridulate.audio.MeasuredSignature
import com.pgotta.stridulate.data.ReliabilityInfo
import com.pgotta.stridulate.data.ReliabilityTier
import com.pgotta.stridulate.data.Species

/** A single ranked model output. Unknown/unsupported has no field-guide species. */
data class Candidate(
    val label: String,
    val species: Species?,
    /** Calibrated audio-model probability. Context never overwrites this value. */
    val confidence: Double,
    val rawScore: Double,
    val reliability: ReliabilityInfo = ReliabilityInfo(ReliabilityTier.EXPERIMENTAL),
    /** Ranking score after optional small region/season/time adjustments. */
    val contextScore: Double = confidence,
    val contextMultiplier: Double = 1.0,
    val contextSummary: String? = null
) {
    val isUnknown: Boolean get() = species == null
    val audioConfidence: Double get() = confidence
}

/** Calibrated decision rules and V50 evaluation tiers shipped with the active model. */
data class ClassificationPolicy(
    val unknownLabel: String,
    val minimumConfidence: Double,
    val minimumMargin: Double,
    val reliabilityByLabel: Map<String, ReliabilityInfo>
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
