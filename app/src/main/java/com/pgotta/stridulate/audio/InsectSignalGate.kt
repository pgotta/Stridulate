package com.pgotta.stridulate.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Class-agnostic front gate for open-world audio.
 *
 * This deliberately runs on RAW microphone/file PCM before SoundSensitivity gain.
 * It answers only "is there a credible singing-insect-like acoustic event here?";
 * it never assigns a species and never changes the frozen J.1 classifier scores.
 */
data class InsectSignalAssessment(
    val passed: Boolean,
    val score: Int,
    val reason: String,
    val rawRms: Double,
    val rawPeak: Double,
    val temporalContrastDb: Double,
    val insectLikelihood: Double,
    val qualityScore: Int,
    val peakFreqKHz: Double,
    val tonality: Double,
    val lowFreqRatio: Double,
    val peakStability: Double,
    val pulseRegularity: Double
)

object InsectSignalGate {
    fun assess(
        rawSamples: FloatArray,
        sampleRate: Int,
        signature: MeasuredSignature,
        quality: RecordingQuality,
        sensitivityLevel: Float = PossibleMatchGate.level
    ): InsectSignalAssessment {
        val level = sensitivityLevel.coerceIn(0f, 1f)
        val x = level.toDouble()
        if (rawSamples.isEmpty() || sampleRate <= 0) {
            return failed("No usable raw audio was available.", signature, quality)
        }

        var sumSq = 0.0
        var peak = 0.0
        rawSamples.forEach { sample ->
            val value = kotlin.math.abs(sample.toDouble())
            sumSq += value * value
            if (value > peak) peak = value
        }
        val rms = sqrt(sumSq / rawSamples.size.coerceAtLeast(1))

        val frameSize = (sampleRate / 10).coerceAtLeast(256)
        val frameRms = ArrayList<Double>()
        var offset = 0
        while (offset < rawSamples.size) {
            val end = minOf(rawSamples.size, offset + frameSize)
            var energy = 0.0
            for (i in offset until end) {
                val v = rawSamples[i].toDouble()
                energy += v * v
            }
            frameRms += sqrt(energy / (end - offset).coerceAtLeast(1))
            offset = end
        }
        val sorted = frameRms.sorted()
        fun percentile(fraction: Double): Double {
            if (sorted.isEmpty()) return 0.0
            val index = ((sorted.lastIndex) * fraction).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }
        val lowFrame = percentile(0.20).coerceAtLeast(1e-7)
        val highFrame = percentile(0.80).coerceAtLeast(lowFrame)
        val temporalContrastDb = (20.0 * log10(highFrame / lowFrame)).coerceIn(0.0, 40.0)

        // Tunable beta operating point. Moving toward SENSITIVE relaxes these only;
        // SoundSensitivity does not affect them because all measurements are raw.
        val minimumRms = 0.0016 - 0.0011 * x
        val minimumLikelihood = 0.62 - 0.22 * x
        val minimumQuality = (48.0 - 24.0 * x).toInt()
        val maximumLowFreqRatio = 0.62 + 0.18 * x
        val minimumStructure = 0.34 - 0.14 * x
        val requiredScore = 58.0 - 18.0 * x

        if (rms < minimumRms) {
            return assessment(
                false,
                0,
                "No insect-like signal: raw audio is at or near the microphone noise floor.",
                rms, peak, temporalContrastDb, signature, quality
            )
        }

        // Wind, speech, HVAC and brown-noise-like energy often pile up below 2 kHz.
        if (signature.lowFreqRatio > maximumLowFreqRatio &&
            signature.tonality < 0.055 &&
            signature.pulseRegularity < 0.45) {
            return assessment(
                false,
                8,
                "No insect-like signal: low-frequency broadband/noise energy dominates the window.",
                rms, peak, temporalContrastDb, signature, quality
            )
        }

        // Flat/random broadband energy has little stable carrier or repeatable temporal structure.
        val structure = maxOf(
            signature.peakStability.coerceIn(0.0, 1.0),
            signature.pulseRegularity.coerceIn(0.0, 1.0),
            (signature.tonality * 12.0).coerceIn(0.0, 1.0)
        )
        val randomBroadband = signature.broadband &&
            signature.tonality < (0.014 + 0.012 * x) &&
            signature.peakStability < (0.34 + 0.18 * x) &&
            signature.pulseRegularity < (0.24 + 0.18 * x) &&
            temporalContrastDb < (2.5 + 2.5 * x)
        if (randomBroadband) {
            return assessment(
                false,
                10,
                "No insect-like signal: the window is broadband and unstructured, like steady noise.",
                rms, peak, temporalContrastDb, signature, quality
            )
        }

        // Stable low-frequency hums can look highly tonal without being singing insects.
        if (signature.peakFreqKHz < (1.55 + 0.20 * (1.0 - x)) &&
            signature.lowFreqRatio > 0.45 &&
            signature.pulseRegularity < 0.25) {
            return assessment(
                false,
                12,
                "No insect-like signal: the dominant energy is a low-frequency hum/noise pattern.",
                rms, peak, temporalContrastDb, signature, quality
            )
        }

        val rmsScore = ((rms - minimumRms) / (0.012 - minimumRms).coerceAtLeast(0.002)).coerceIn(0.0, 1.0)
        val temporalScore = (temporalContrastDb / 10.0).coerceIn(0.0, 1.0)
        val qualityScore = (quality.score / 100.0).coerceIn(0.0, 1.0)
        val lowNoiseScore = (1.0 - signature.lowFreqRatio).coerceIn(0.0, 1.0)
        val likelihood = signature.insectLikelihood.coerceIn(0.0, 1.0)

        val score = (
            likelihood * 34.0 +
                structure * 24.0 +
                lowNoiseScore * 14.0 +
                qualityScore * 12.0 +
                temporalScore * 8.0 +
                rmsScore * 8.0
            ).toInt().coerceIn(0, 100)

        val weakStructure = structure < minimumStructure && signature.pulseRegularity < 0.25
        val passed = score >= requiredScore &&
            signature.insectLikelihood >= minimumLikelihood &&
            quality.score >= minimumQuality &&
            !weakStructure

        val reason = if (passed) {
            "Insect-like signal detected in raw audio before analysis gain."
        } else {
            buildString {
                append("No insect-like signal above the current gate")
                val details = mutableListOf<String>()
                if (signature.insectLikelihood < minimumLikelihood) details += "weak insect-like structure"
                if (quality.score < minimumQuality) details += "poor signal quality"
                if (weakStructure) details += "no stable carrier or repeating pattern"
                if (score < requiredScore) details += "front-gate score $score < ${requiredScore.toInt()}"
                if (details.isNotEmpty()) append(": ${details.joinToString(", ")}")
                append('.')
            }
        }
        return assessment(passed, score, reason, rms, peak, temporalContrastDb, signature, quality)
    }

    private fun failed(
        reason: String,
        signature: MeasuredSignature,
        quality: RecordingQuality
    ) = assessment(false, 0, reason, 0.0, 0.0, 0.0, signature, quality)

    private fun assessment(
        passed: Boolean,
        score: Int,
        reason: String,
        rms: Double,
        peak: Double,
        temporalContrastDb: Double,
        signature: MeasuredSignature,
        quality: RecordingQuality
    ) = InsectSignalAssessment(
        passed = passed,
        score = score,
        reason = reason,
        rawRms = rms,
        rawPeak = peak,
        temporalContrastDb = temporalContrastDb,
        insectLikelihood = signature.insectLikelihood,
        qualityScore = quality.score,
        peakFreqKHz = signature.peakFreqKHz,
        tonality = signature.tonality,
        lowFreqRatio = signature.lowFreqRatio,
        peakStability = signature.peakStability,
        pulseRegularity = signature.pulseRegularity
    )
}
