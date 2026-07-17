package com.pgotta.stridulate.data

import android.content.Context
import org.json.JSONObject

enum class ReliabilityTier(val displayName: String) {
    VERIFIED("Verified"),
    GOOD("Good"),
    EXPERIMENTAL("Experimental"),
    UNKNOWN_GATE("Unsupported gate");

    companion object {
        fun fromAsset(value: String?): ReliabilityTier = when (value?.uppercase()) {
            "VERIFIED" -> VERIFIED
            "GOOD" -> GOOD
            "UNKNOWN_GATE" -> UNKNOWN_GATE
            else -> EXPERIMENTAL
        }
    }
}

data class ReliabilityInfo(
    val tier: ReliabilityTier,
    val precision: Double? = null,
    val recall: Double? = null,
    val f1: Double? = null,
    val lockedRecordings: Int? = null
) {
    val conciseExplanation: String
        get() = when (tier) {
            ReliabilityTier.VERIFIED ->
                "Stronger support in the V50 locked-holdout evaluation. Still an acoustic estimate, not scientific confirmation."
            ReliabilityTier.GOOD ->
                "Promising locked-holdout support, but it has not met the app's Verified release tier. Confirm with season, range and call pattern."
            ReliabilityTier.EXPERIMENTAL ->
                "Limited or uneven evaluation support. Treat this as a possible match and compare the field-guide details carefully."
            ReliabilityTier.UNKNOWN_GATE ->
                "Used by the model to reject unsupported or uncertain recordings rather than assign a species."
        }
}

/** Loads the V50 model-evaluation tiers independently from the model runtime. */
class SpeciesReliabilityRepository(context: Context, assetName: String = "species_reliability.json") {
    private val infoByLabel: Map<String, ReliabilityInfo>

    init {
        val root = context.assets.open(assetName).bufferedReader().use { JSONObject(it.readText()) }
        val statuses = root.getJSONObject("status_by_label")
        val metrics = root.optJSONObject("locked_holdout_metrics")
        infoByLabel = buildMap {
            statuses.keys().forEach { label ->
                val metric = metrics?.optJSONObject(label)
                put(
                    label,
                    ReliabilityInfo(
                        tier = ReliabilityTier.fromAsset(statuses.optString(label)),
                        precision = metric?.optDoubleOrNull("precision"),
                        recall = metric?.optDoubleOrNull("recall"),
                        f1 = metric?.optDoubleOrNull("f1"),
                        lockedRecordings = metric?.optIntOrNull("locked_recordings")
                    )
                )
            }
        }
    }

    fun forLabel(label: String): ReliabilityInfo =
        infoByLabel[label] ?: ReliabilityInfo(ReliabilityTier.EXPERIMENTAL)

    fun forSpecies(species: Species): ReliabilityInfo = forLabel(species.latin.replace(' ', '_'))

    val verifiedLabels: Set<String> = infoByLabel
        .filterValues { it.tier == ReliabilityTier.VERIFIED }
        .keys

    val goodLabels: Set<String> = infoByLabel
        .filterValues { it.tier == ReliabilityTier.GOOD }
        .keys
}

private fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name).takeIf(Double::isFinite) else null

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null
