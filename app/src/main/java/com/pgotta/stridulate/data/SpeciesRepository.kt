package com.pgotta.stridulate.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlin.math.max

/**
 * Loads the bundled species database from assets/species.json.
 * The database ships inside the app, so identification works fully offline.
 *
 * Some generated databases omit optional classifier fields. Those records are
 * normalized here so a data-file mismatch cannot crash the app at startup.
 */
class SpeciesRepository(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val decoded: SpeciesDatabase by lazy {
        val text = context.assets.open("species.json")
            .bufferedReader()
            .use { it.readText() }
        json.decodeFromString(SpeciesDatabase.serializer(), text)
    }

    val database: SpeciesDatabase by lazy {
        decoded.copy(species = decoded.species.map(::normalizeSpecies))
    }

    val species: List<Species> get() = database.species
    val packs: List<SoundPack> get() = database.packs

    fun byId(id: String): Species? = species.firstOrNull { it.id == id }

    private fun normalizeSpecies(species: Species): Species {
        val rangeWidth = if (species.freqRange.size >= 2) {
            (species.freqRange[1] - species.freqRange[0]).coerceAtLeast(0.2)
        } else {
            if (species.signature.broadband) 4.0 else 0.8
        }

        val normalizedBandwidth = species.signature.bandwidthKHz
            .takeIf { it.isFinite() && it > 0.0 }
            ?: rangeWidth

        val normalizedRegularity = species.signature.pulseRegularity
            .takeIf { it.isFinite() && it in 0.0..1.0 }
            ?: when {
                species.signature.broadband -> 0.35
                species.signature.pulseRate != null || species.pulseRate != null -> 0.80
                else -> 0.55
            }

        val normalizedPulseRate = species.signature.pulseRate ?: species.pulseRate

        return species.copy(
            signature = species.signature.copy(
                pulseRate = normalizedPulseRate,
                pulseRegularity = normalizedRegularity,
                bandwidthKHz = max(0.2, normalizedBandwidth)
            )
        )
    }
}
