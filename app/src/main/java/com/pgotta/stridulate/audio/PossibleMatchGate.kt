package com.pgotta.stridulate.audio

import android.content.Context
import com.pgotta.stridulate.classifier.Candidate

/**
 * User-controlled filter for *visible* below-threshold possible matches.
 *
 * This does NOT change frozen J.1 acceptance thresholds or accepted-call logging.
 * It prevents the live research UI from being forced to show arbitrary species for
 * silence, broadband noise, speech, HVAC, and other weak/non-insect windows.
 *
 * Level 0 = strict, level 1 = sensitive. The default is deliberately balanced and
 * requires an insect-like acoustic signature plus recurrence across two windows.
 */
object PossibleMatchGate {
    private const val PREFS = "possible_match_gate_v1"
    private const val KEY_LEVEL = "level"
    const val DEFAULT_LEVEL = 0.35f

    @Volatile private var initialized = false
    @Volatile private var _level = DEFAULT_LEVEL

    val level: Float get() = _level

    @Synchronized
    fun initialize(context: Context): Float {
        if (!initialized) {
            _level = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_LEVEL, DEFAULT_LEVEL)
                .coerceIn(0f, 1f)
            initialized = true
        }
        return _level
    }

    @Synchronized
    fun set(context: Context, value: Float) {
        val clean = value.coerceIn(0f, 1f)
        _level = clean
        initialized = true
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_LEVEL, clean).apply()
    }

    fun profile(level: Float = _level): String = when {
        level < 0.25f -> "STRICT"
        level < 0.65f -> "BALANCED"
        else -> "SENSITIVE"
    }

    fun requiredConsecutiveWindows(level: Float = _level): Int = when {
        level < 0.25f -> 3
        level < 0.75f -> 2
        else -> 1
    }

    fun minimumInsectLikelihood(level: Float = _level): Double =
        (0.68 - 0.28 * level.coerceIn(0f, 1f)).coerceIn(0.40, 0.68)

    fun minimumQualityScore(level: Float = _level): Int =
        (60.0 - 30.0 * level.coerceIn(0f, 1f)).toInt().coerceIn(30, 60)

    fun minimumEvidenceFraction(level: Float = _level): Double =
        (0.72 - 0.30 * level.coerceIn(0f, 1f)).coerceIn(0.42, 0.72)

    fun minimumAbsoluteScore(level: Float = _level): Double =
        (0.30 - 0.18 * level.coerceIn(0f, 1f)).coerceIn(0.12, 0.30)

    /**
     * True when this candidate is useful enough to show in the live research UI.
     * A frozen J.1 accepted species always remains visible; this user control only
     * suppresses *below-threshold possible* candidates and never overrides J.1.
     */
    fun allows(
        candidate: Candidate,
        quality: RecordingQuality,
        signature: MeasuredSignature,
        consecutiveWindows: Int,
        level: Float = _level
    ): Boolean {
        if (candidate.species == null) return false

        // The display gate must never hide a species already accepted by frozen J.1.
        // Accepted/logged-call semantics continue to come from OpenSetDecision.
        if (candidate.evidenceAccepted == true) return true

        if (quality.blockingReason != null) return false
        if (quality.score < minimumQualityScore(level)) return false
        if (signature.insectLikelihood < minimumInsectLikelihood(level)) return false
        if (consecutiveWindows < requiredConsecutiveWindows(level)) return false

        val score = candidate.audioConfidence.coerceIn(0.0, 1.0)
        if (score < minimumAbsoluteScore(level)) return false

        val referenceThreshold = candidate.acceptanceThreshold?.coerceIn(0.20, 1.0) ?: 0.70
        val evidenceFraction = score / referenceThreshold
        if (evidenceFraction < minimumEvidenceFraction(level)) return false

        return true
    }
}
