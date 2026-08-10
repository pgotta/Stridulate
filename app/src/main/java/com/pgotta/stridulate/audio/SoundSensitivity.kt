package com.pgotta.stridulate.audio

import android.content.Context

/**
 * User-controlled analysis gain. Level 0 is exactly neutral/off.
 *
 * The gain is applied only to analysis PCM and the live visualization. Raw WAV
 * evidence is always recorded without this gain, so changing sensitivity never
 * rewrites or fabricates the original recording.
 */
object SoundSensitivity {
    private const val PREFS = "sound_sensitivity_v1"
    private const val KEY_LEVEL = "level"
    private const val MAX_EXTRA_GAIN = 3f // 0..1 maps to 1x..4x total analysis gain.

    @Volatile private var initialized = false
    @Volatile private var _level = 0f

    val level: Float get() = _level
    val gain: Float get() = 1f + _level * MAX_EXTRA_GAIN

    @Synchronized
    fun initialize(context: Context): Float {
        if (!initialized) {
            _level = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_LEVEL, 0f)
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

    fun applySample(sample: Float): Float = (sample * gain).coerceIn(-1f, 1f)

    fun apply(samples: FloatArray): FloatArray {
        val activeGain = gain
        if (activeGain <= 1.00001f) return samples.copyOf()
        return FloatArray(samples.size) { index ->
            (samples[index] * activeGain).coerceIn(-1f, 1f)
        }
    }
}
