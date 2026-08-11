package com.pgotta.stridulate.audio

import com.pgotta.stridulate.classifier.Candidate
import com.pgotta.stridulate.classifier.InsectClassifier

/**
 * Runs a fixed block of PCM through the microphone/file analysis pipeline.
 *
 * Open-world safety is deliberately split in two:
 *  1) [InsectSignalGate] inspects untouched RAW PCM and decides whether a credible
 *     insect-like acoustic event exists at all.
 *  2) Frozen J.1 / Perch identifies supported species from the optional gained
 *     analysis PCM. SoundSensitivity can therefore help quiet callers without
 *     being able to turn microphone self-noise into an insect signal.
 */
class ClipAnalyzer(
    private val classifier: InsectClassifier,
    private val fftSize: Int = 4096,
    private val comparisonClassifier: InsectClassifier? = null
) {
    data class Result(
        val signature: MeasuredSignature,
        val rawSignature: MeasuredSignature,
        val candidates: List<Candidate>,
        /** Old pre-v0.3 Stridulate shadow-model ranking. Diagnostic only. */
        val legacyCandidates: List<Candidate> = emptyList(),
        val spectrogram: List<FloatArray>,
        val quality: RecordingQuality,
        val rawQuality: RecordingQuality,
        val signalAssessment: InsectSignalAssessment
    )

    /** Analyze decoded audio. Returns null only when there is not enough waveform to measure. */
    fun analyze(samples: FloatArray, sampleRate: Int): Result? {
        if (samples.isEmpty() || sampleRate <= 0) return null
        PossibleMatchGate.level // ensure the front gate observes the persisted/current level
        val analysisSamples = SoundSensitivity.apply(samples)
        val fft = Fft(fftSize)
        val rawExtractor = FeatureExtractor(sampleRate, fftSize)
        val analysisExtractor = FeatureExtractor(sampleRate, fftSize)
        val hop = fftSize / 2
        val rawFrame = FloatArray(fftSize)
        val analysisFrame = FloatArray(fftSize)
        val specHeight = 96
        val columns = ArrayList<FloatArray>()

        val hiBin = (16000.0 / (sampleRate / 2.0) * (fftSize / 2)).toInt()
            .coerceIn(1, fftSize / 2 - 1)

        var pos = 0
        while (pos + fftSize <= samples.size) {
            System.arraycopy(samples, pos, rawFrame, 0, fftSize)
            System.arraycopy(analysisSamples, pos, analysisFrame, 0, fftSize)
            val rawSpectrum = fft.magnitudeSpectrum(rawFrame)
            val analysisSpectrum = fft.magnitudeSpectrum(analysisFrame)
            val t = pos.toDouble() / sampleRate
            rawExtractor.addFrame(rawSpectrum, t)
            analysisExtractor.addFrame(analysisSpectrum, t)

            val col = FloatArray(specHeight)
            for (r in 0 until specHeight) {
                val frac = 1f - r.toFloat() / specHeight
                val bin = (frac * hiBin).toInt().coerceIn(0, analysisSpectrum.size - 1)
                col[r] = analysisSpectrum[bin] / 255f
            }
            columns.add(col)
            pos += hop
        }

        val rawSignature = rawExtractor.aggregate() ?: return null
        val signature = analysisExtractor.aggregate() ?: return null
        val rawQuality = RecordingQualityAssessor.assess(samples, sampleRate, rawSignature)
        val quality = RecordingQualityAssessor.assess(analysisSamples, sampleRate, signature)
        val signalAssessment = InsectSignalGate.assess(
            rawSamples = samples,
            sampleRate = sampleRate,
            signature = rawSignature,
            quality = rawQuality
        )

        val candidates = classifier.classify(analysisSamples, sampleRate, signature).map { candidate ->
            val species = candidate.species
            if (species == null || !AcousticCompatibility.hasSpecificProfile(species)) {
                candidate
            } else {
                val compatibility = AcousticCompatibility.assess(species, signature)
                candidate.copy(
                    callCompatibilityPassed = compatibility.passed,
                    callCompatibilitySummary = compatibility.summary
                )
            }
        }
        // Comparison receives the exact same sensitivity-adjusted PCM as J.1. It is
        // diagnostic only; failures never block the production J.1 result.
        val legacyCandidates = comparisonClassifier?.let { legacy ->
            runCatching { legacy.classify(analysisSamples, sampleRate, signature) }.getOrDefault(emptyList())
        }.orEmpty()
        val display = if (columns.size > 200) {
            val step = columns.size / 200
            columns.filterIndexed { i, _ -> i % step == 0 }
        } else columns

        return Result(
            signature = signature,
            rawSignature = rawSignature,
            candidates = candidates,
            legacyCandidates = legacyCandidates,
            spectrogram = display,
            quality = quality,
            rawQuality = rawQuality,
            signalAssessment = signalAssessment
        )
    }
}
