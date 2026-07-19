package com.pgotta.stridulate.classifier

import com.pgotta.stridulate.audio.Fft
import com.pgotta.stridulate.audio.SincResampler
import kotlin.math.ln
import kotlin.math.max

/**
 * Active model frontend, read from model_meta.json:
 * 44.1 kHz, 5-second windows, 2,048-point centered STFT with reflect padding,
 * periodic Hann, 512 hop, 128 HTK mel bands (400–22,050 Hz), 80 dB clipping,
 * and the training-set global z-score normalization.
 */
class MelSpectrogram(private val metadata: ModelMetadata) {
    private val sampleRate = metadata.sampleRate
    private val nFft = metadata.nFft
    private val hop = metadata.hopLength
    private val nMels = metadata.nMels
    private val fmin = metadata.fmin
    private val fmax = metadata.fmax
    private val topDb = metadata.topDb
    private val clipLen = (metadata.clipSeconds * sampleRate).toInt()
    val frames: Int = 1 + clipLen / hop

    private val fft = Fft(nFft, periodicHann = true)
    private val melFilters = buildMelFilterbank()

    init {
        require(metadata.center && metadata.padMode == "reflect")
        require(metadata.melScale == "htk")
        require(metadata.winLength == nFft)
        require(frames == metadata.inputShape[2]) {
            "Preprocessing produces $frames frames, but metadata expects ${metadata.inputShape[2]}."
        }
    }

    /** All 50%-overlapping five-second windows, matching the v5 pooling contract. */
    fun fromPcmWindows(pcm: FloatArray, srcRate: Int): List<Array<FloatArray>> {
        val resampled = preparePcm(pcm, srcRate)
        if (resampled.size <= clipLen) return listOf(buildSpectrogram(padToClip(resampled)))

        val overlap = metadata.windowOverlap.coerceIn(0.0, 0.99)
        val step = max(1, (clipLen * (1.0 - overlap)).toInt())
        val starts = ArrayList<Int>()
        var start = 0
        while (start <= resampled.size - clipLen) {
            starts += start
            start += step
        }
        val finalStart = resampled.size - clipLen
        if (starts.lastOrNull() != finalStart) starts += finalStart

        return starts.map { windowStart ->
            buildSpectrogram(resampled.copyOfRange(windowStart, windowStart + clipLen))
        }
    }

    private fun preparePcm(pcm: FloatArray, srcRate: Int): FloatArray =
        if (srcRate == sampleRate) pcm.copyOf()
        else SincResampler.resample(pcm, srcRate, sampleRate)

    private fun padToClip(wav: FloatArray): FloatArray =
        if (wav.size == clipLen) wav.copyOf() else wav.copyOf(clipLen)

    private fun buildSpectrogram(clip: FloatArray): Array<FloatArray> {
        val logMel = Array(frames) { DoubleArray(nMels) }
        val frame = FloatArray(nFft)
        val centerPad = nFft / 2
        var globalMax = -Double.MAX_VALUE

        for (time in 0 until frames) {
            val frameStart = time * hop - centerPad
            for (i in 0 until nFft) {
                frame[i] = clip[reflectIndex(frameStart + i, clip.size)]
            }

            val power = fft.powerSpectrum(frame)
            for (melIndex in 0 until nMels) {
                var energy = 0.0
                val filter = melFilters[melIndex]
                for (bin in filter.indices) energy += filter[bin] * power[bin]
                val db = 10.0 * ln(max(energy, 1e-10)) / LN_10
                logMel[time][melIndex] = db
                if (db > globalMax) globalMax = db
            }
        }

        // Matches torchaudio AmplitudeToDB(stype="power", top_db=80): retain absolute dB, then clamp to the per-window maximum minus 80 dB.
        val floor = globalMax - topDb
        val output = Array(nMels) { FloatArray(frames) }
        for (time in 0 until frames) {
            for (melIndex in 0 until nMels) {
                val clampedDb = max(logMel[time][melIndex], floor)
                output[melIndex][time] =
                    ((clampedDb - metadata.normalizationMean) / metadata.normalizationStd).toFloat()
            }
        }
        return output
    }

    /** PyTorch/librosa-style reflect padding: -1 -> 1, length -> length-2. */
    private fun reflectIndex(rawIndex: Int, length: Int): Int {
        if (length <= 1) return 0
        var index = rawIndex
        while (index < 0 || index >= length) {
            index = if (index < 0) -index else 2 * length - 2 - index
        }
        return index
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * (ln(1.0 + hz / 700.0) / LN_10)
    private fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    /** HTK triangles with no Slaney area normalization (mel_norm=null). */
    private fun buildMelFilterbank(): Array<DoubleArray> {
        val nBins = nFft / 2 + 1
        val melLow = hzToMel(fmin)
        val melHigh = hzToMel(fmax)
        val points = DoubleArray(nMels + 2) { index ->
            melToHz(melLow + (melHigh - melLow) * index / (nMels + 1))
        }
        val binFrequencies = DoubleArray(nBins) { it * sampleRate.toDouble() / nFft }
        val filters = Array(nMels) { DoubleArray(nBins) }

        for (melIndex in 1..nMels) {
            val low = points[melIndex - 1]
            val center = points[melIndex]
            val high = points[melIndex + 1]
            for (bin in 0 until nBins) {
                val frequency = binFrequencies[bin]
                filters[melIndex - 1][bin] = when {
                    frequency < low || frequency > high -> 0.0
                    frequency <= center -> (frequency - low) / (center - low)
                    else -> (high - frequency) / (high - center)
                }.coerceAtLeast(0.0)
            }
        }
        return filters
    }

    private companion object {
        val LN_10: Double = ln(10.0)
    }
}
