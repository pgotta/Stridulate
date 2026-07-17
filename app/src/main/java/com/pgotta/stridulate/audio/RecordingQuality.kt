package com.pgotta.stridulate.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class RecordingQualityGrade(val displayName: String) {
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Poor")
}

/** Transparent, on-device diagnostics; this is not a second species classifier. */
data class RecordingQuality(
    val grade: RecordingQualityGrade,
    val score: Int,
    val durationSeconds: Double,
    val signalClarityScore: Int,
    val clippingPercent: Double,
    val activeSignalPercent: Double,
    val possibleOverlap: Boolean,
    val warnings: List<String>,
    val blockingReason: String? = null
) {
    val summary: String
        get() = when (grade) {
            RecordingQualityGrade.GOOD -> "Clear enough for the model; still compare the call and field guide."
            RecordingQualityGrade.FAIR -> "Usable, but one or more audio issues may reduce confidence."
            RecordingQualityGrade.POOR -> "The recording may be too weak, noisy, clipped or mixed for a dependable result."
        }
}

/**
 * Lightweight waveform/spectrogram quality checks that run entirely on the phone.
 * Values are intentionally advisory and conservative rather than biological claims.
 */
object RecordingQualityAssessor {
    fun assess(
        samples: FloatArray,
        sampleRate: Int,
        signature: MeasuredSignature
    ): RecordingQuality {
        if (samples.isEmpty() || sampleRate <= 0) {
            return RecordingQuality(
                grade = RecordingQualityGrade.POOR,
                score = 0,
                durationSeconds = 0.0,
                signalClarityScore = 0,
                clippingPercent = 0.0,
                activeSignalPercent = 0.0,
                possibleOverlap = false,
                warnings = listOf("No decoded waveform was available for quality checks."),
                blockingReason = "No usable audio waveform was available."
            )
        }

        val duration = samples.size.toDouble() / sampleRate
        val clippingRatio = samples.count { abs(it) >= 0.985f }.toDouble() / samples.size
        val wholeRms = sqrt(samples.sumOf { it.toDouble() * it.toDouble() } / samples.size)

        val frameSize = (sampleRate / 10).coerceAtLeast(256) // 100 ms
        val frameRms = ArrayList<Double>()
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(samples.size, offset + frameSize)
            var energy = 0.0
            for (index in offset until end) {
                val value = samples[index].toDouble()
                energy += value * value
            }
            frameRms += sqrt(energy / (end - offset).coerceAtLeast(1))
            offset = end
        }

        val sortedRms = frameRms.sorted()
        fun percentile(fraction: Double): Double {
            if (sortedRms.isEmpty()) return 0.0
            val index = ((sortedRms.lastIndex) * fraction).toInt().coerceIn(0, sortedRms.lastIndex)
            return sortedRms[index]
        }

        val noiseFloor = percentile(0.20).coerceAtLeast(1e-6)
        val signalLevel = percentile(0.80).coerceAtLeast(noiseFloor)
        val temporalContrastDb = (20.0 * log10(signalLevel / noiseFloor)).coerceIn(0.0, 40.0)
        val temporalClarity = temporalContrastDb / 40.0 * 100.0
        // Continuous insect songs may have no quiet frames. Spectral structure therefore provides
        // a second clarity estimate instead of pretending temporal contrast alone is true SNR.
        val structuralClarity = (
            signature.tonality.coerceIn(0.0, 1.0) * 45.0 +
                signature.peakStability.coerceIn(0.0, 1.0) * 35.0 +
                (1.0 - signature.lowFreqRatio.coerceIn(0.0, 1.0)) * 20.0
            )
        val clarityScore = maxOf(temporalClarity, structuralClarity).roundToInt().coerceIn(0, 100)
        val activeThreshold = maxOf(noiseFloor + (signalLevel - noiseFloor) * 0.25, 0.004)
        val activeRatio = if (frameRms.isEmpty()) 0.0 else
            frameRms.count { it >= activeThreshold }.toDouble() / frameRms.size

        val possibleOverlap = duration >= 3.0 &&
            activeRatio >= 0.30 &&
            signature.tonality >= 0.015 &&
            signature.peakStability < 0.58

        val warnings = mutableListOf<String>()
        var score = 100

        when {
            duration < 2.0 -> {
                score -= 45
                warnings += "Very short clip; capture at least one full repeated phrase."
            }
            duration < 4.0 -> {
                score -= 18
                warnings += "Short clip; a longer steady call may improve the result."
            }
        }

        when {
            wholeRms < 0.003 -> {
                score -= 40
                warnings += "The caller is extremely quiet relative to the recording level."
            }
            wholeRms < 0.010 -> {
                score -= 18
                warnings += "The caller is quiet; move closer or shield the microphone."
            }
        }

        when {
            clarityScore < 25 -> {
                score -= 35
                warnings += "The caller has little tonal or temporal separation from the background."
            }
            clarityScore < 50 -> {
                score -= 18
                warnings += "Background sound may be competing with the insect call."
            }
        }

        when {
            clippingRatio >= 0.05 -> {
                score -= 55
                warnings += "Severe clipping detected; move farther away or reduce recording gain."
            }
            clippingRatio >= 0.005 -> {
                score -= 16
                warnings += "Some clipping detected; the loudest parts may be distorted."
            }
        }

        if (activeRatio < 0.18) {
            score -= 18
            warnings += "Only a small part of the clip contains a strong repeating signal."
        }
        if (signature.lowFreqRatio > 0.55) {
            score -= 15
            warnings += "Low-frequency noise, speech or wind is prominent."
        }
        if (signature.peakStability < 0.45) {
            score -= 12
            warnings += "The dominant frequency changes substantially across the clip."
        }
        if (possibleOverlap) {
            score -= 8
            warnings += "The signal may contain overlapping callers or a changing chorus."
        }

        score = score.coerceIn(0, 100)
        val grade = when {
            score >= 80 -> RecordingQualityGrade.GOOD
            score >= 55 -> RecordingQualityGrade.FAIR
            else -> RecordingQualityGrade.POOR
        }
        val blockingReason = when {
            duration < 1.5 -> "The recording is too short for a dependable identification."
            wholeRms < 0.0015 -> "The recording is too quiet for a dependable identification."
            clippingRatio >= 0.10 -> "The recording is too heavily clipped for a dependable identification."
            else -> null
        }

        return RecordingQuality(
            grade = grade,
            score = score,
            durationSeconds = duration,
            signalClarityScore = clarityScore,
            clippingPercent = clippingRatio * 100.0,
            activeSignalPercent = activeRatio * 100.0,
            possibleOverlap = possibleOverlap,
            warnings = warnings.distinct().take(4),
            blockingReason = blockingReason
        )
    }
}
