package com.pgotta.stridulate.classifier

import com.pgotta.stridulate.audio.AcousticCompatibility
import com.pgotta.stridulate.audio.AcousticCompatibilityResult
import com.pgotta.stridulate.audio.MeasuredSignature
import com.pgotta.stridulate.audio.RecordingQuality
import com.pgotta.stridulate.audio.RecordingQualityGrade
import com.pgotta.stridulate.data.ReliabilityTier

enum class OpenSetDecisionType {
    STRONG_POSSIBLE,
    POSSIBLE,
    REJECTED
}

data class OpenSetDecisionResult(
    val type: OpenSetDecisionType,
    val reason: String,
    val requiredConfidence: Double,
    val requiredMargin: Double,
    val margin: Double,
    val acousticCheck: AcousticCompatibilityResult
)

/**
 * Runtime decision overlay.
 *
 * Frozen J.1 candidates carry their own independently calibrated per-species
 * acceptance threshold. For those candidates we do not stack the legacy epoch-19
 * margin/acoustic veto on top of J.1, because that would change the validated J.1
 * decision. Recording-quality blockers still fail closed. Legacy candidates keep
 * the previous open-set behavior unchanged.
 */
object OpenSetDecision {
    fun evaluate(
        top: Candidate,
        runnerUp: Candidate?,
        signature: MeasuredSignature,
        recordingQuality: RecordingQuality?,
        policy: ClassificationPolicy
    ): OpenSetDecisionResult {
        val margin = top.audioConfidence - (runnerUp?.audioConfidence ?: 0.0)
        val topIsUnknown = top.label == policy.unknownLabel || top.isUnknown
        val reliability = policy.reliabilityByLabel[top.label] ?: top.reliability
        val tier = reliability.tier
        val qualityBlock = recordingQuality?.blockingReason
        val isFrozenJ1 = top.acceptanceThreshold != null && top.evidenceAccepted != null

        if (isFrozenJ1) {
            val requiredConfidence = top.acceptanceThreshold!!.coerceIn(0.0, 1.0)
            val highThreshold = (top.highConfidenceThreshold ?: 0.95).coerceIn(requiredConfidence, 1.0)
            val acousticCheck = AcousticCompatibilityResult(
                true,
                "Frozen J.1 uses its calibrated Perch evidence directly; the legacy hand-authored acoustic veto is not applied."
            )
            val highConfidence = top.evidenceAccepted == true &&
                top.audioConfidence >= highThreshold &&
                margin >= 0.10 &&
                recordingQuality?.grade == RecordingQualityGrade.GOOD
            val type = when {
                qualityBlock != null -> OpenSetDecisionType.REJECTED
                topIsUnknown -> OpenSetDecisionType.REJECTED
                top.evidenceAccepted != true -> OpenSetDecisionType.REJECTED
                highConfidence -> OpenSetDecisionType.STRONG_POSSIBLE
                else -> OpenSetDecisionType.POSSIBLE
            }
            val reason = when {
                qualityBlock != null -> qualityBlock
                topIsUnknown -> "The model did not produce a supported species candidate."
                top.evidenceAccepted != true ->
                    "No frozen J.1 species crossed its calibrated evidence threshold. The closest score was ${(top.audioConfidence * 100).toInt()}%; ${top.label.replace('_', ' ')} requires ${(requiredConfidence * 100).toInt()}%."
                highConfidence ->
                    "High-confidence evidence band: frozen J.1 crossed this species' calibrated threshold by a wide margin on a good-quality recording. Confirm the call in the field guide before treating it as identified."
                else ->
                    "Likely match: frozen J.1 crossed this species' calibrated acceptance threshold. Confirm the call pattern and field-guide details; the score is model evidence, not certainty."
            }
            return OpenSetDecisionResult(
                type = type,
                reason = reason,
                requiredConfidence = requiredConfidence,
                requiredMargin = 0.0,
                margin = margin,
                acousticCheck = acousticCheck
            )
        }

        val safetyRule = policy.openSetSafetyPolicy.ruleFor(top.label, tier)
        val confusableCicadaPair = runnerUp != null &&
            setOf(top.label, runnerUp.label) == DOG_DAY_LINNE_PAIR
        val requiredConfidence = maxOf(
            policy.minimumConfidence,
            safetyRule.minimumConfidence,
            if (confusableCicadaPair) CONFUSABLE_CICADA_MIN_CONFIDENCE else 0.0
        )
        val requiredMargin = maxOf(
            policy.minimumMargin,
            safetyRule.minimumMargin,
            if (confusableCicadaPair) CONFUSABLE_CICADA_MIN_MARGIN else 0.0
        )
        val acousticCheck = if (safetyRule.requireAcousticProfile && top.species != null) {
            AcousticCompatibility.assess(top.species, signature)
        } else {
            AcousticCompatibilityResult(true, "Acoustic profile check was not required for this output.")
        }
        val strongPolicy = policy.openSetSafetyPolicy
        val strongPossible = tier == ReliabilityTier.VERIFIED &&
            top.audioConfidence >= strongPolicy.strongMinimumConfidence &&
            margin >= strongPolicy.strongMinimumMargin &&
            (!strongPolicy.strongRequiresGoodQuality || recordingQuality?.grade == RecordingQualityGrade.GOOD) &&
            (!strongPolicy.strongRequiresAcousticProfile || acousticCheck.passed)

        val type = when {
            qualityBlock != null -> OpenSetDecisionType.REJECTED
            topIsUnknown -> OpenSetDecisionType.REJECTED
            !reliability.primaryResultAllowed -> OpenSetDecisionType.REJECTED
            top.audioConfidence < requiredConfidence -> OpenSetDecisionType.REJECTED
            margin < requiredMargin -> OpenSetDecisionType.REJECTED
            !acousticCheck.passed -> OpenSetDecisionType.REJECTED
            strongPossible -> OpenSetDecisionType.STRONG_POSSIBLE
            else -> OpenSetDecisionType.POSSIBLE
        }
        val reason = when {
            qualityBlock != null -> qualityBlock
            topIsUnknown ->
                "The model favored Unknown/Unsupported, so it is not assigning a species."
            !reliability.primaryResultAllowed ->
                "Not Ready tier: this class did not meet the release reliability floor, so it is shown only as a nearby alternative."
            top.audioConfidence < requiredConfidence && confusableCicadaPair ->
                "Dog-day and Linne's Cicadas are a known look-alike pair for this model. A result is hidden unless the leading score reaches ${(requiredConfidence * 100).toInt()}%; this score was ${(top.audioConfidence * 100).toInt()}%."
            margin < requiredMargin && confusableCicadaPair ->
                "Dog-day and Linne's Cicadas are too close to separate safely here. A ${(requiredMargin * 100).toInt()}-point lead is required; this lead was ${(margin * 100).toInt()} points."
            top.audioConfidence < requiredConfidence ->
                "Open-set safety mode requires at least ${(requiredConfidence * 100).toInt()}% for this class and tier; the closest model score was ${(top.audioConfidence * 100).toInt()}%."
            margin < requiredMargin ->
                "Open-set safety mode requires a ${(requiredMargin * 100).toInt()}-point lead over the runner-up; this lead was ${(margin * 100).toInt()} points."
            !acousticCheck.passed ->
                "The neural model favored this species, but a basic acoustic sanity check found a conflict: ${acousticCheck.summary}"
            strongPossible ->
                "Strong possible match: this Verified class passed the stricter score, margin, recording-quality and acoustic-profile checks. Confirm the call pattern before treating it as identified."
            tier == ReliabilityTier.VERIFIED ->
                "Possible match with stronger evaluation support. Field recordings can still resemble a different known or unsupported species."
            tier == ReliabilityTier.GOOD ->
                "Possible match with useful independent support. Confirm with season, range and the community recording."
            else ->
                "Experimental possible match with limited or uneven independent support. Compare the field-guide details carefully."
        }
        return OpenSetDecisionResult(type, reason, requiredConfidence, requiredMargin, margin, acousticCheck)
    }

    private val DOG_DAY_LINNE_PAIR = setOf("Neotibicen_canicularis", "Neotibicen_linnei")
    private const val CONFUSABLE_CICADA_MIN_CONFIDENCE = 0.93
    private const val CONFUSABLE_CICADA_MIN_MARGIN = 0.35
}
