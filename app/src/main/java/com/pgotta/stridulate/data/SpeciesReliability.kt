package com.pgotta.stridulate.data

import android.content.Context
import org.json.JSONObject

enum class ReliabilityTier(val displayName: String) {
    VERIFIED("Verified"),
    GOOD("Good"),
    EXPERIMENTAL("Experimental"),
    NOT_READY("Not Ready"),
    UNKNOWN_GATE("Unsupported gate");

    companion object {
        fun fromAsset(value: String?): ReliabilityTier = when (value?.uppercase()) {
            "VERIFIED" -> VERIFIED
            "GOOD" -> GOOD
            "NOT_READY" -> NOT_READY
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
    val lockedRecordings: Int? = null,
    val independentSessions: Int? = null,
    val primaryResultAllowed: Boolean = tier !in setOf(ReliabilityTier.NOT_READY, ReliabilityTier.UNKNOWN_GATE),
    val uiWording: String? = null,
    val evidenceSource: String? = null
) {
    val conciseExplanation: String
        get() = when (tier) {
            ReliabilityTier.VERIFIED ->
                "Strong independent support for this model release, but field recordings outside the test set can still be confused. Treat it as a candidate, not confirmation."
            ReliabilityTier.GOOD ->
                "Useful independent support, but not enough for a Verified claim. Confirm with season, range and call pattern."
            ReliabilityTier.EXPERIMENTAL ->
                "Limited or uneven independent support. Treat this only as a possible match and compare the field-guide details carefully."
            ReliabilityTier.NOT_READY ->
                "This class did not meet the release reliability floor. It may appear as a nearby alternative, but the app will not use it as the primary identification."
            ReliabilityTier.UNKNOWN_GATE ->
                "Used by the model to reject unsupported, non-insect or uncertain recordings rather than assign a species."
        }
}

data class AcceptanceRule(
    val minimumConfidence: Double,
    val minimumMargin: Double,
    val requireAcousticProfile: Boolean = true,
    val note: String? = null
) {
    init {
        require(minimumConfidence in 0.0..1.0 && minimumMargin in 0.0..1.0) {
            "Invalid open-set acceptance rule."
        }
    }
}

data class OpenSetSafetyPolicy(
    val enabled: Boolean,
    val fieldTestMode: Boolean,
    val strongMinimumConfidence: Double,
    val strongMinimumMargin: Double,
    val strongVerifiedOnly: Boolean,
    val strongRequiresGoodQuality: Boolean,
    val strongRequiresAcousticProfile: Boolean,
    val rulesByTier: Map<ReliabilityTier, AcceptanceRule>,
    val speciesOverrides: Map<String, AcceptanceRule>
) {
    fun ruleFor(label: String, tier: ReliabilityTier): AcceptanceRule =
        speciesOverrides[label] ?: rulesByTier[tier] ?: AcceptanceRule(1.0, 1.0)

    companion object {
        fun conservativeFallback() = OpenSetSafetyPolicy(
            enabled = true,
            fieldTestMode = true,
            strongMinimumConfidence = 0.95,
            strongMinimumMargin = 0.40,
            strongVerifiedOnly = true,
            strongRequiresGoodQuality = true,
            strongRequiresAcousticProfile = true,
            rulesByTier = mapOf(
                ReliabilityTier.VERIFIED to AcceptanceRule(0.85, 0.25),
                ReliabilityTier.GOOD to AcceptanceRule(0.90, 0.30),
                ReliabilityTier.EXPERIMENTAL to AcceptanceRule(0.93, 0.35),
                ReliabilityTier.NOT_READY to AcceptanceRule(1.0, 1.0),
                ReliabilityTier.UNKNOWN_GATE to AcceptanceRule(1.0, 1.0)
            ),
            speciesOverrides = emptyMap()
        )
    }
}

/** Loads per-species evaluation tiers and the precision-first open-set runtime overlay. */
class SpeciesReliabilityRepository(context: Context, assetName: String = "android_reliability.json") {
    private val infoByLabel: Map<String, ReliabilityInfo>
    val modelLabelsSha256: String
    val openSetSafetyPolicy: OpenSetSafetyPolicy

    init {
        val root = context.assets.open(assetName).bufferedReader().use { JSONObject(it.readText()) }
        require(root.getInt("schema_version") == 2) { "Unsupported Android reliability schema." }
        modelLabelsSha256 = root.getString("model_labels_sha256")
        openSetSafetyPolicy = parseOpenSetPolicy(root.getJSONObject("open_set_safety_gate"))
        val species = root.getJSONArray("species")
        infoByLabel = buildMap {
            for (index in 0 until species.length()) {
                val item = species.getJSONObject(index)
                val label = item.getString("label")
                require(item.getInt("index") == index) { "Reliability label order is invalid at $label." }
                val tier = ReliabilityTier.fromAsset(item.getString("tier"))
                val primaryAllowed = item.getBoolean("primary_result_allowed_after_global_gate")
                require(primaryAllowed == (tier !in setOf(ReliabilityTier.NOT_READY, ReliabilityTier.UNKNOWN_GATE))) {
                    "Reliability primary-result rule is inconsistent for $label."
                }
                put(
                    label,
                    ReliabilityInfo(
                        tier = tier,
                        precision = item.optDoubleOrNull("precision"),
                        recall = item.optDoubleOrNull("recall"),
                        f1 = item.optDoubleOrNull("f1"),
                        lockedRecordings = item.optIntOrNull("recordings"),
                        independentSessions = item.optIntOrNull("independent_sessions"),
                        primaryResultAllowed = primaryAllowed,
                        uiWording = item.optString("ui_wording").takeIf { it.isNotBlank() },
                        evidenceSource = item.optString("evidence_source").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }

    private fun parseOpenSetPolicy(root: JSONObject): OpenSetSafetyPolicy {
        val strong = root.getJSONObject("strong_possible_match")
        val tierRoot = root.getJSONObject("tier_rules")
        val tierRules = ReliabilityTier.entries.associateWith { tier ->
            parseRule(tierRoot.getJSONObject(tier.name))
        }
        val overrides = buildMap {
            val objectRoot = root.optJSONObject("species_overrides") ?: JSONObject()
            val keys = objectRoot.keys()
            while (keys.hasNext()) {
                val label = keys.next()
                put(label, parseRule(objectRoot.getJSONObject(label)))
            }
        }
        return OpenSetSafetyPolicy(
            enabled = root.optBoolean("enabled", true),
            fieldTestMode = root.optBoolean("field_test_mode", true),
            strongMinimumConfidence = strong.getDouble("minimum_confidence"),
            strongMinimumMargin = strong.getDouble("minimum_top1_top2_margin"),
            strongVerifiedOnly = strong.optBoolean("verified_tier_only", true),
            strongRequiresGoodQuality = strong.optBoolean("requires_good_recording_quality", true),
            strongRequiresAcousticProfile = strong.optBoolean("requires_acoustic_profile_match", true),
            rulesByTier = tierRules,
            speciesOverrides = overrides
        )
    }

    private fun parseRule(root: JSONObject) = AcceptanceRule(
        minimumConfidence = root.getDouble("minimum_confidence"),
        minimumMargin = root.getDouble("minimum_top1_top2_margin"),
        requireAcousticProfile = root.optBoolean("require_acoustic_profile", true),
        note = root.optString("note").takeIf { it.isNotBlank() }
    )

    fun forLabel(label: String): ReliabilityInfo =
        infoByLabel[label] ?: ReliabilityInfo(
            tier = ReliabilityTier.NOT_READY,
            primaryResultAllowed = false,
            uiWording = "Do not use as the primary identification"
        )

    fun forSpecies(species: Species): ReliabilityInfo = forLabel(species.latin.replace(' ', '_'))

    val labels: Set<String> get() = infoByLabel.keys
    val verifiedLabels: Set<String> = infoByLabel.filterValues { it.tier == ReliabilityTier.VERIFIED }.keys
    val goodLabels: Set<String> = infoByLabel.filterValues { it.tier == ReliabilityTier.GOOD }.keys
    val notReadyLabels: Set<String> = infoByLabel.filterValues { it.tier == ReliabilityTier.NOT_READY }.keys
}

private fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name).takeIf(Double::isFinite) else null

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null
