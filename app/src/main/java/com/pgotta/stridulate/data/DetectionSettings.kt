package com.pgotta.stridulate.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DetectionTierSettings(
    val verified: Boolean = true,
    val good: Boolean = true,
    val experimental: Boolean = false
) {
    fun allows(tier: ReliabilityTier): Boolean = when (tier) {
        ReliabilityTier.VERIFIED -> verified
        ReliabilityTier.GOOD -> good
        ReliabilityTier.EXPERIMENTAL -> experimental
        ReliabilityTier.NOT_READY, ReliabilityTier.UNKNOWN_GATE -> false
    }
}

class DetectionSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("detection_settings", Context.MODE_PRIVATE)
    private val _tiers = MutableStateFlow(
        DetectionTierSettings(
            verified = prefs.getBoolean("verified", true),
            good = prefs.getBoolean("good", true),
            experimental = prefs.getBoolean("experimental", false)
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
            .apply()
    }
}
