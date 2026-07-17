package com.pgotta.stridulate.audio

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Band-limited sample-rate conversion matching the v5 training/evaluation frontend's torchaudio
 * sinc_interp_hann settings (filter width 32, rolloff 0.9475937167).
 *
 * The previous linear interpolator did not low-pass before downsampling. Audio
 * above the destination Nyquist frequency therefore folded into the insect-call
 * band and completely changed the model's mel spectrogram.
 */
object SincResampler {
    private const val LOWPASS_FILTER_WIDTH = 32
    private const val ROLLOFF = 0.9475937167
    private const val EPSILON = 1e-12

    private data class PhaseKernel(
        val offsets: IntArray,
        val weights: DoubleArray
    )

    private data class Kernel(
        val originalReduced: Int,
        val targetReduced: Int,
        val width: Int,
        val phases: Array<PhaseKernel>
    )

    private val cache = ConcurrentHashMap<Long, Kernel>()

    fun resample(input: FloatArray, originalRate: Int, targetRate: Int): FloatArray {
        require(originalRate > 0 && targetRate > 0) { "Sample rates must be positive." }
        if (input.isEmpty() || originalRate == targetRate) return input.copyOf()

        val key = (originalRate.toLong() shl 32) xor targetRate.toLong()
        val kernel = cache.getOrPut(key) { buildKernel(originalRate, targetRate) }
        val original = kernel.originalReduced
        val target = kernel.targetReduced
        val outputLength = ceil(target.toDouble() * input.size / original).toInt()
        val output = FloatArray(outputLength)

        for (outputIndex in 0 until outputLength) {
            val block = outputIndex / target
            val phase = outputIndex % target
            val phaseKernel = kernel.phases[phase]
            val base = block * original - kernel.width
            var sum = 0.0
            for (i in phaseKernel.weights.indices) {
                val sourceIndex = base + phaseKernel.offsets[i]
                if (sourceIndex in input.indices) {
                    sum += input[sourceIndex] * phaseKernel.weights[i]
                }
            }
            output[outputIndex] = sum.toFloat()
        }
        return output
    }

    private fun buildKernel(originalRate: Int, targetRate: Int): Kernel {
        val divisor = gcd(originalRate, targetRate)
        val original = originalRate / divisor
        val target = targetRate / divisor
        val baseFrequency = min(original, target) * ROLLOFF
        val width = ceil(LOWPASS_FILTER_WIDTH * original / baseFrequency).toInt()
        val fullKernelLength = width * 2 + original
        val scale = baseFrequency / original

        val phases = Array(target) { phase ->
            val offsets = ArrayList<Int>(width * 2 + 4)
            val weights = ArrayList<Double>(width * 2 + 4)

            for (offset in 0 until fullKernelLength) {
                val indexPosition = (offset - width).toDouble() / original
                var t = (-phase.toDouble() / target + indexPosition) * baseFrequency
                t = t.coerceIn(-LOWPASS_FILTER_WIDTH.toDouble(), LOWPASS_FILTER_WIDTH.toDouble())

                val window = cos(t * PI / LOWPASS_FILTER_WIDTH / 2.0).let { it * it }
                val angle = t * PI
                val sinc = if (abs(angle) < 1e-14) 1.0 else sin(angle) / angle
                val weight = sinc * window * scale
                if (abs(weight) > EPSILON) {
                    offsets += offset
                    weights += weight
                }
            }

            PhaseKernel(offsets.toIntArray(), weights.toDoubleArray())
        }

        return Kernel(original, target, width, phases)
    }

    private tailrec fun gcd(a: Int, b: Int): Int =
        if (b == 0) kotlin.math.abs(a) else gcd(b, a % b)
}
