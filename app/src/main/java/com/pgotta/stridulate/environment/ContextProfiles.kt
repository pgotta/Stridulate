package com.pgotta.stridulate.environment

import android.content.Context
import org.json.JSONObject

data class SpeciesContextProfile(
    val label: String,
    val regions: Set<String>,
    val coverageNote: String,
    val minimumTemperatureF: Double? = null,
    val maximumTemperatureF: Double? = null,
    val temperatureSource: String? = null
)

class ContextProfileRepository(context: Context, assetName: String = "context_profiles.json") {
    private val profiles: Map<String, SpeciesContextProfile>

    init {
        val root = context.assets.open(assetName).bufferedReader().use { JSONObject(it.readText()) }
        val jsonProfiles = root.getJSONObject("profiles")
        profiles = buildMap {
            jsonProfiles.keys().forEach { label ->
                val item = jsonProfiles.getJSONObject(label)
                val regionsJson = item.getJSONArray("regions")
                val regionSet = buildSet {
                    for (index in 0 until regionsJson.length()) add(regionsJson.getString(index))
                }
                val temperature = item.optJSONObject("temperature_f")
                put(
                    label,
                    SpeciesContextProfile(
                        label = label,
                        regions = regionSet,
                        coverageNote = item.optString("coverage_note", "Broad U.S. context profile"),
                        minimumTemperatureF = temperature?.optDoubleOrNull("min"),
                        maximumTemperatureF = temperature?.optDoubleOrNull("max"),
                        temperatureSource = temperature?.optString("source")?.takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }

    fun forLabel(label: String): SpeciesContextProfile? = profiles[label]

    fun allProfiles(): Collection<SpeciesContextProfile> = profiles.values

    fun supportsRegion(label: String, region: ContextRegion): Boolean {
        val profile = profiles[label] ?: return false
        if (region == ContextRegion.UNKNOWN) return false
        return "NATIONWIDE" in profile.regions || profile.regions.any(region.profileTags::contains)
    }
}

private fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name).takeIf(Double::isFinite) else null
