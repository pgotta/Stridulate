package com.pgotta.stridulate.audio

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A compact acoustic signature used for recording-quality checks and explanatory
 * measurements in the UI. Species identification itself is performed by the
 * calibrated TensorFlow Lite model from the original PCM audio.
 */
data class MeasuredSignature(
    val peakFreqKHz: Double,
    val bandwidthKHz: Double,
    val pulseRate: Double?,        // null if no periodic pulsing detected
    val pulseRegularity: Double,   // 0..1
    val broadband: Boolean,
    val loudness: Double,          // 0..255, peak magnitude in the insect band
    val relBW: Double,
    val tonality: Double,          // 0..1: energy concentrated at peak (insect) vs spread (voice)
    val lowFreqRatio: Double,      // 0..1: fraction of energy below 2 kHz (high for speech)
    val peakStability: Double      // 0..1: how steady the dominant frequency is across frames
) {
    /**
     * A rough 0..1 estimate of "does this sound like a singing insect at all?"
     * Insects: strong tonal/structured energy, dominant freq usually >=2.5 kHz,
     * a steady peak, little low-frequency (speech) energy. Human voice, fans, and
     * general noise score low. This diagnostic value is not used as the release
     * identification gate; the trained Unknown/background class performs that job.
     */
    val insectLikelihood: Double
        get() {
            val freqOk = when {
                peakFreqKHz < 1.8 -> 0.15      // below typical insect song
                peakFreqKHz < 2.5 -> 0.55
                else -> 1.0
            }
            val voicePenalty = (1.0 - lowFreqRatio).coerceIn(0.0, 1.0)   // lots of low energy -> speech
            val structure = maxOf(tonality, if (broadband) peakStability else tonality)
            return (freqOk * 0.30 + voicePenalty * 0.30 + structure * 0.25 + peakStability * 0.15)
                .coerceIn(0.0, 1.0)
        }
}

private data class Frame(
    val peakFreq: Double,
    val centroid: Double,
    val bandwidth: Double,
    val loudness: Double,
    val energy: Double,
    val tonality: Double,      // peak energy / total band energy (concentration)
    val lowFreqRatio: Double   // energy below 2 kHz / total energy
)

/**
 * Turns a stream of FFT magnitude frames into a single [MeasuredSignature].
 *
 * Mirrors the web prototype:
 *  - per-frame: dominant frequency, spectral centroid, bandwidth, loudness
 *  - across frames: pulse rate + regularity via envelope autocorrelation
 *  - broadbandness from relative bandwidth (cicada buzz vs cricket tone)
 */
class FeatureExtractor(
    private val sampleRate: Int,
    private val fftSize: Int
) {
    private val frames = ArrayList<Frame>()
    private val envelope = ArrayList<Double>()
    private val envTimes = ArrayList<Double>()

    private val loBand = 1200.0
    private val hiBand = 18000.0

    private fun freqToBin(f: Double) = Math.round(f / (sampleRate / 2.0) * (fftSize / 2)).toInt()
    private fun binToFreq(b: Int) = b.toDouble() / (fftSize / 2) * (sampleRate / 2.0)

    /** Feed one magnitude spectrum (length fftSize/2), with a monotonic timestamp in seconds. */
    fun addFrame(spectrum: FloatArray, timeSec: Double) {
        val n = spectrum.size
        val loBin = freqToBin(loBand).coerceIn(0, n - 1)
        val hiBin = freqToBin(hiBand).coerceIn(0, n - 1)
        var peakVal = 0.0; var peakBin = loBin
        var sumF = 0.0; var sumF2 = 0.0; var energy = 0.0; var peakEnergy = 0.0
        for (b in loBin..hiBin) {
            val v = spectrum[b].toDouble()
            if (v > peakVal) { peakVal = v; peakBin = b }
            val f = binToFreq(b)
            val w = v * v
            energy += w; sumF += f * w; sumF2 += f * f * w
        }
        // energy concentrated in a small window around the peak (tonality)
        val half = 3
        for (b in (peakBin - half)..(peakBin + half)) {
            if (b in 0 until n) { val v = spectrum[b].toDouble(); peakEnergy += v * v }
        }
        val tonality = if (energy > 0) (peakEnergy / energy).coerceIn(0.0, 1.0) else 0.0

        // energy below 2 kHz vs the whole 0..hiBand range (speech sits here; insects don't)
        val lo2 = freqToBin(0.0).coerceIn(0, n - 1)
        val hi2 = freqToBin(2000.0).coerceIn(0, n - 1)
        var lowE = 0.0; var fullE = 0.0
        for (b in lo2..hiBin) {
            val v = spectrum[b].toDouble(); val w = v * v
            fullE += w
            if (b <= hi2) lowE += w
        }
        val lowFreqRatio = if (fullE > 0) (lowE / fullE).coerceIn(0.0, 1.0) else 0.0

        val centroid = if (energy > 0) sumF / energy else 0.0
        val variance = if (energy > 0) (sumF2 / energy - centroid * centroid).coerceAtLeast(0.0) else 0.0
        frames.add(
            Frame(binToFreq(peakBin), centroid, sqrt(variance), peakVal, energy, tonality, lowFreqRatio)
        )
        envelope.add(peakVal)
        envTimes.add(timeSec)
        // keep memory bounded for very long files
        if (frames.size > 4000) { frames.removeAt(0); envelope.removeAt(0); envTimes.removeAt(0) }
    }

    fun frameCount() = frames.size

    /** Aggregate everything fed so far into one signature, or null if too little signal. */
    fun aggregate(): MeasuredSignature? {
        if (frames.size < 8) return null
        val loud = frames.filter { it.loudness > 60 }
        val use = if (loud.size >= 6) loud else frames
        fun med(values: List<Double>): Double {
            val s = values.sorted(); return s[s.size / 2]
        }
        val peakFreq = med(use.map { it.peakFreq })
        val bandwidth = med(use.map { it.bandwidth })
        val loudness = med(use.map { it.loudness })
        val tonality = med(use.map { it.tonality })
        val lowFreqRatio = med(use.map { it.lowFreqRatio })
        val (rate, regularity) = estimatePulse()
        val relBW = if (peakFreq > 0) bandwidth / peakFreq else 0.0
        val broadband = relBW > 0.45 || bandwidth > 4000.0

        // peak stability: how tightly the per-frame dominant frequency clusters.
        // Insects hold a steady carrier; speech slews around constantly.
        val peaks = use.map { it.peakFreq }
        val meanPeak = peaks.average()
        val sd = if (peaks.size > 1)
            sqrt(peaks.sumOf { (it - meanPeak) * (it - meanPeak) } / peaks.size) else 0.0
        val peakStability = if (meanPeak > 0)
            (1.0 - (sd / meanPeak)).coerceIn(0.0, 1.0) else 0.0

        return MeasuredSignature(
            peakFreqKHz = peakFreq / 1000.0,
            bandwidthKHz = bandwidth / 1000.0,
            pulseRate = rate,
            pulseRegularity = regularity,
            broadband = broadband,
            loudness = loudness,
            relBW = relBW,
            tonality = tonality,
            lowFreqRatio = lowFreqRatio,
            peakStability = peakStability
        )
    }

    /** Autocorrelation of the loudness envelope → pulse rate (Hz) + regularity (0..1). */
    private fun estimatePulse(): Pair<Double?, Double> {
        if (envelope.size < 40) return null to 0.0
        val env = envelope
        val times = envTimes
        val dt = (times.last() - times.first()) / (times.size - 1)
        if (dt <= 0) return null to 0.0
        val mean = env.average()
        val x = DoubleArray(env.size) { env[it] - mean }
        val minRate = 1.0; val maxRate = 120.0
        val maxLag = minOf(env.size - 1, Math.floor(1.0 / (minRate * dt)).toInt())
        val minLag = maxOf(1, Math.floor(1.0 / (maxRate * dt)).toInt())
        var bestLag = 0; var bestVal = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            var s = 0.0
            var i = 0
            while (i + lag < x.size) { s += x[i] * x[i + lag]; i++ }
            if (s > bestVal) { bestVal = s; bestLag = lag }
        }
        var zero = 0.0
        for (v in x) zero += v * v
        if (bestLag == 0 || zero <= 0) return null to 0.0
        val strength = bestVal / zero
        if (strength < 0.10) return null to strength
        val rate = 1.0 / (bestLag * dt)
        if (rate < minRate || rate > maxRate) return null to strength.coerceIn(0.0, 1.0)
        return rate to strength.coerceIn(0.0, 1.0)
    }

    fun reset() { frames.clear(); envelope.clear(); envTimes.clear() }
}
