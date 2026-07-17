package com.pgotta.stridulate.audio

import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 Cooley–Tukey FFT.
 * [size] must be a power of two. Reused across frames to avoid allocation.
 *
 * Web Audio gave us AnalyserNode.getByteFrequencyData for free; on Android we
 * compute the magnitude spectrum ourselves. This is the equivalent building block.
 */
class Fft(private val size: Int, periodicHann: Boolean = false) {

    init {
        require(size > 0 && (size and (size - 1)) == 0) { "FFT size must be a power of two" }
    }

    private val cosTable = DoubleArray(size / 2)
    private val sinTable = DoubleArray(size / 2)
    private val hann = DoubleArray(size)

    init {
        for (i in 0 until size / 2) {
            val ang = -2.0 * Math.PI * i / size
            cosTable[i] = cos(ang)
            sinTable[i] = sin(ang)
        }
        // Hann window reduces spectral leakage so frequency peaks are cleaner
        for (i in 0 until size) {
            val denominator = if (periodicHann) size.toDouble() else (size - 1).toDouble()
            hann[i] = 0.5 * (1 - cos(2.0 * Math.PI * i / denominator))
        }
    }

    private val re = DoubleArray(size)
    private val im = DoubleArray(size)

    /**
     * Compute the magnitude spectrum of one windowed frame of PCM samples.
     * Returns an array of length size/2 (real spectrum) with values normalized 0..255
     * to mirror the Web Audio byte-frequency scale the classifier was tuned against.
     */
    fun magnitudeSpectrum(samples: FloatArray): FloatArray {
        val n = size
        for (i in 0 until n) {
            re[i] = (if (i < samples.size) samples[i].toDouble() else 0.0) * hann[i]
            im[i] = 0.0
        }
        transform()
        val out = FloatArray(n / 2)
        // dB-like scaling to match AnalyserNode's getByteFrequencyData behaviour
        for (i in 0 until n / 2) {
            val mag = Math.hypot(re[i], im[i]) / n
            // convert to dB, clamp to [-100, -30] dB window, map to 0..255
            val db = 20.0 * Math.log10(mag + 1e-9)
            val clamped = ((db + 100.0) / 70.0).coerceIn(0.0, 1.0)
            out[i] = (clamped * 255.0).toFloat()
        }
        return out
    }

    /**
     * Raw power spectrum |X|^2 for one Hann-windowed frame, length nFft/2+1.
     * Used by MelSpectrogram to build the model's input exactly like training
     * (torchaudio.MelSpectrogram operates on power, power=2.0 by default).
     */
    fun powerSpectrum(samples: FloatArray): DoubleArray {
        val n = size
        for (i in 0 until n) {
            re[i] = (if (i < samples.size) samples[i].toDouble() else 0.0) * hann[i]
            im[i] = 0.0
        }
        transform()
        val out = DoubleArray(n / 2 + 1)
        for (i in 0..n / 2) {
            val mag = Math.hypot(re[i], im[i])
            out[i] = mag * mag
        }
        return out
    }

    private fun transform() {
        val n = size
        // bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        // butterflies
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (jj in i until i + half) {
                    val c = cosTable[k]
                    val s = sinTable[k]
                    val tre = re[jj + half] * c - im[jj + half] * s
                    val tim = re[jj + half] * s + im[jj + half] * c
                    re[jj + half] = re[jj] - tre
                    im[jj + half] = im[jj] - tim
                    re[jj] += tre
                    im[jj] += tim
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
