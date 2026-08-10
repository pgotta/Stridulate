package com.pgotta.stridulate.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DetectionTierSettings(
    val verified: Boolean = true,
    val good: Boolean = true,
    val experimental: Boolean = true
) {
    fun allows(tier: ReliabilityTier): Boolean = when (tier) {
        ReliabilityTier.VERIFIED -> verified
        ReliabilityTier.GOOD -> good
        ReliabilityTier.EXPERIMENTAL -> experimental
        ReliabilityTier.NOT_READY, ReliabilityTier.UNKNOWN_GATE -> false
    }
}

/**
 * Detection-tier preferences. v3 migrates the previous default-disabled
 * Experimental tier to enabled once so all 88 frozen J.1 species are available
 * out of the box. A user's choices made after the migration remain persistent.
 */
class DetectionSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("detection_settings", Context.MODE_PRIVATE)

    init {
        val schema = prefs.getInt("schema_version", 1)
        if (schema < 2) {
            prefs.edit()
                .putBoolean("verified", prefs.getBoolean("verified", true))
                .putBoolean("good", prefs.getBoolean("good", true))
                .putBoolean("experimental", true)
                .putInt("schema_version", 2)
                .apply()
        }
    }

    private val _tiers = MutableStateFlow(
        DetectionTierSettings(
            verified = prefs.getBoolean("verified", true),
            good = prefs.getBoolean("good", true),
            experimental = prefs.getBoolean("experimental", true)
        )
    )
    val tiers: StateFlow<DetectionTierSettings> = _tiers

    fun setEnabled(tier: ReliabilityTier, enabled: Boolean) {
        val current = _tiers.value
        val updated = when (tier) {
            ReliabilityTier.VERIFIED -> current.copy(verified = enabled)
            ReliabilityTier.GOOD -> current.copy(good = enabled)
            ReliabilityTier.EXPERIMENTAL -> current.copy(experimental = enabled)
            else -> current
        }
        _tiers.value = updated
        prefs.edit()
            .putBoolean("verified", updated.verified)
            .putBoolean("good", updated.good)
            .putBoolean("experimental", updated.experimental)
            .putInt("schema_version", 2)
            .apply()
    }
}
