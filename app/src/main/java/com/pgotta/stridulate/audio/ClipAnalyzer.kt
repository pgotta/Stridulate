package com.pgotta.stridulate.audio

import com.pgotta.stridulate.classifier.Candidate
import com.pgotta.stridulate.classifier.InsectClassifier

/**
 * Runs a fixed block of PCM through the identical feature/classifier pipeline
 * used by microphone recordings. Sound sensitivity is applied exactly once here
 * to both the displayed analysis and neural-model PCM; the source WAV stays raw.
 */
class ClipAnalyzer(
    private val classifier: InsectClassifier,
    private val fftSize: Int = 4096
) {
    data class Result(
        val signature: MeasuredSignature,
        val candidates: List<Candidate>,
        val spectrogram: List<FloatArray>,
        val quality: RecordingQuality
    )

    /** Analyze decoded audio. Returns null if the clip is essentially silent. */
    fun analyze(samples: FloatArray, sampleRate: Int): Result? {
        val analysisSamples = SoundSensitivity.apply(samples)
        val fft = Fft(fftSize)
        val extractor = FeatureExtractor(sampleRate, fftSize)
        val hop = fftSize / 2
        val frame = FloatArray(fftSize)
        val specHeight = 96
        val columns = ArrayList<FloatArray>()

        val hiBin = (16000.0 / (sampleRate / 2.0) * (fftSize / 2)).toInt()
            .coerceIn(1, fftSize / 2 - 1)

        var pos = 0
        while (pos + fftSize <= analysisSamples.size) {
            System.arraycopy(analysisSamples, pos, frame, 0, fftSize)
            val spectrum = fft.magnitudeSpectrum(frame)
            val t = pos.toDouble() / sampleRate
            extractor.addFrame(spectrum, t)

            val col = FloatArray(specHeight)
            for (r in 0 until specHeight) {
                val frac = 1f - r.toFloat() / specHeight
                val bin = (frac * hiBin).toInt().coerceIn(0, spectrum.size - 1)
                col[r] = spectrum[bin] / 255f
            }
            columns.add(col)
            pos += hop
        }

        val signature = extractor.aggregate() ?: return null
        if (signature.loudness < 45) return null

        val candidates = classifier.classify(analysisSamples, sampleRate, signature)
        val quality = RecordingQualityAssessor.assess(analysisSamples, sampleRate, signature)
        val display = if (columns.size > 200) {
            val step = columns.size / 200
            columns.filterIndexed { i, _ -> i % step == 0 }
        } else columns

        return Result(signature, candidates, display, quality)
    }
}
