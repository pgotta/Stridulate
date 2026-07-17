package com.pgotta.stridulate.environment

import com.pgotta.stridulate.classifier.Candidate
import com.pgotta.stridulate.data.Species
import kotlin.math.abs

data class ContextAssessment(
    val multiplier: Double,
    val summary: String,
    val factors: List<String>,
    val temperatureUsed: Boolean
)

data class ContextRerankResult(
    val candidates: List<Candidate>,
    val applied: Boolean,
    val summary: String
)

/**
 * Adds small, transparent context adjustments after audio inference.
 * It never hard-excludes a species and never changes the calibrated audio confidence value.
 */
class ContextReranker(private val profiles: ContextProfileRepository) {

    fun rerank(candidates: List<Candidate>, context: ObservationContext): ContextRerankResult {
        if (!context.enabled || context.status !in setOf(ContextStatus.READY, ContextStatus.STALE)) {
            return ContextRerankResult(candidates, false, "Audio ranking only; observation context was unavailable or disabled.")
        }

        val adjusted = candidates.map { candidate ->
            val species = candidate.species
            if (species == null) candidate
            else {
                val assessment = assess(candidate.label, species, context)
                candidate.copy(
                    contextScore = candidate.audioConfidence * assessment.multiplier,
                    contextMultiplier = assessment.multiplier,
                    contextSummary = assessment.summary
                )
            }
        }.sortedByDescending { it.contextScore }

        val usedTemperature = adjusted.any { candidate ->
            candidate.contextSummary?.contains("temperature", ignoreCase = true) == true
        }
        val summary = buildString {
            append("Softly ranked with ")
            append(context.region.displayName)
            append(", ")
            append(context.seasonLabel.lowercase())
            append(" season and local time")
            if (usedTemperature) append(", plus a sourced species temperature profile")
            else if (context.temperatureF != null && context.isTemperatureCurrentForScoring) append(". Current temperature is recorded but not used without species-specific source data")
            else if (context.temperatureF != null) append(". Older cached temperature is displayed only and not used for scoring")
            append(". Context never rules a species out.")
        }
        return ContextRerankResult(adjusted, true, summary)
    }

    fun assess(label: String, species: Species, context: ObservationContext): ContextAssessment {
        if (!context.enabled || context.status !in setOf(ContextStatus.READY, ContextStatus.STALE)) {
            return ContextAssessment(1.0, "Context not applied", emptyList(), false)
        }

        val factors = mutableListOf<String>()
        var multiplier = 1.0

        val active = species.months.getOrElse(context.monthIndex) { 0 } == 1
        val previous = species.months.getOrElse((context.monthIndex + 11) % 12) { 0 } == 1
        val next = species.months.getOrElse((context.monthIndex + 1) % 12) { 0 } == 1
        when {
            active -> {
                multiplier *= 1.06
                factors += "active month"
            }
            previous || next -> {
                multiplier *= 1.01
                factors += "near active season"
            }
            else -> {
                multiplier *= 0.94
                factors += "outside typical active months"
            }
        }

        val night = context.dayPeriodLabel == "Night"
        when {
            species.nocturnal && night -> {
                multiplier *= 1.03
                factors += "night-active"
            }
            species.nocturnal && !night -> {
                multiplier *= 0.99
                factors += "usually heard at night"
            }
            !species.nocturnal && !night -> {
                multiplier *= 1.02
                factors += "day-active"
            }
            else -> {
                multiplier *= 0.99
                factors += "usually heard by day"
            }
        }

        val profile = profiles.forLabel(label)
        if (profile != null && context.region != ContextRegion.UNKNOWN) {
            val tags = context.region.profileTags
            when {
                "NATIONWIDE" in profile.regions -> {
                    multiplier *= 1.01
                    factors += "broad U.S. range"
                }
                profile.regions.any(tags::contains) -> {
                    multiplier *= 1.05
                    factors += "region supported"
                }
                else -> {
                    multiplier *= 0.96
                    factors += "outside broad model range profile"
                }
            }
        }

        var temperatureUsed = false
        val temperature = context.temperatureF.takeIf { context.isTemperatureCurrentForScoring }
        val minTemp = profile?.minimumTemperatureF
        val maxTemp = profile?.maximumTemperatureF
        if (temperature != null && minTemp != null && maxTemp != null && profile.temperatureSource != null) {
            temperatureUsed = true
            when {
                temperature in minTemp..maxTemp -> {
                    multiplier *= 1.03
                    factors += "temperature within sourced activity range"
                }
                temperature < minTemp && abs(temperature - minTemp) <= 8.0 ||
                    temperature > maxTemp && abs(temperature - maxTemp) <= 8.0 -> {
                    factors += "temperature near sourced activity range"
                }
                else -> {
                    multiplier *= 0.97
                    factors += "temperature outside sourced activity range"
                }
            }
        }

        multiplier = multiplier.coerceIn(0.85, 1.15)
        val direction = when {
            multiplier >= 1.025 -> "Context supports this match"
            multiplier <= 0.975 -> "Context weakens this match slightly"
            else -> "Context is neutral"
        }
        return ContextAssessment(multiplier, "$direction · ${factors.joinToString(" · ")}", factors, temperatureUsed)
    }
}
