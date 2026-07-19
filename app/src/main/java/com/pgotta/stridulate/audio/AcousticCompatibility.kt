package com.pgotta.stridulate.audio

import com.pgotta.stridulate.data.Species
import kotlin.math.max

/**
 * Conservative explanatory guard applied after the neural model.
 * It rejects gross conflicts with the field-guide acoustic profile; it never
 * promotes a candidate and is deliberately tolerant of phone microphones.
 */
data class AcousticCompatibilityResult(
    val passed: Boolean,
    val summary: String
)

object AcousticCompatibility {
    fun assess(species: Species, measured: MeasuredSignature): AcousticCompatibilityResult {
        val conflicts = mutableListOf<String>()
        val range = species.freqRange
        if (range.size >= 2) {
            val span = (range[1] - range[0]).coerceAtLeast(0.5)
            val low = (range[0] - max(1.0, span * 0.50)).coerceAtLeast(0.4)
            val high = range[1] + max(1.5, span * 0.50)
            if (measured.peakFreqKHz !in low..high) {
                conflicts += "measured peak ${"%.1f".format(measured.peakFreqKHz)} kHz is outside the broad expected band"
            }
        }

        val expectedBandwidth = species.signature.bandwidthKHz.takeIf { it > 0.0 }
            ?: (range.getOrNull(1)?.minus(range.getOrNull(0) ?: 0.0) ?: 2.0)
        if (species.signature.broadband && !measured.broadband &&
            measured.bandwidthKHz < max(2.5, expectedBandwidth * 0.45)) {
            conflicts += "the recording is narrowband but this species normally has a broader tick, buzz or rasp"
        }
        if (!species.signature.broadband && measured.broadband &&
            measured.bandwidthKHz > max(4.5, expectedBandwidth * 2.5)) {
            conflicts += "the recording is much broader than this species' usual tonal call"
        }

        val expectedPulse = species.signature.pulseRate
        val measuredPulse = measured.pulseRate
        if (expectedPulse != null && expectedPulse > 0.0 && measuredPulse != null && measuredPulse > 0.0) {
            val ratio = measuredPulse / expectedPulse
            if (ratio !in 0.33..3.0) conflicts += "the measured pulse rate differs greatly from the reference profile"
        }
        if (expectedPulse == null && species.signature.broadband && measuredPulse != null &&
            measured.pulseRegularity >= 0.55 && !measured.broadband) {
            conflicts += "a regular narrowband chirp pattern conflicts with this species' broader call profile"
        }

        return if (conflicts.isEmpty()) {
            AcousticCompatibilityResult(true, "The basic frequency, bandwidth and rhythm checks did not find a gross conflict.")
        } else {
            AcousticCompatibilityResult(false, conflicts.joinToString("; ").replaceFirstChar { it.uppercase() } + ".")
        }
    }
}
