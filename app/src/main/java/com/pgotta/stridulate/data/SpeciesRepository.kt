package com.pgotta.stridulate.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlin.math.max

/**
 * Loads the bundled field guide and adds conservative placeholder guide entries
 * for frozen J.1 labels that are not yet in the older hand-authored database.
 *
 * Placeholder entries do not invent detailed range, season, frequency or call
 * claims. They exist so every one of the 88 model labels is navigable and can be
 * displayed common-name-first when a well-established English name is bundled.
 */
class SpeciesRepository(private val context: Context) {

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
        val normalized = decoded.species.map(::normalizeSpecies)
        val byLatin = normalized.associateBy { normalizeLabel(it.latin) }
        val additions = j1Labels()
            .filterNot { it in byLatin }
            .map(::placeholderSpecies)
        decoded.copy(species = normalized + additions)
    }

    val species: List<Species> get() = database.species
    val packs: List<SoundPack> get() = database.packs

    fun byId(id: String): Species? = species.firstOrNull { it.id == id }

    private fun j1Labels(): List<String> = runCatching {
        context.assets.open("j1_labels.txt").bufferedReader().useLines { lines ->
            lines.map(String::trim).filter(String::isNotBlank).map(::normalizeLabel).toList()
        }
    }.getOrDefault(emptyList())

    private fun normalizeLabel(value: String): String =
        value.trim().lowercase().replace('_', ' ').replace(Regex("\\s+"), " ")

    private fun placeholderSpecies(normalizedLatin: String): Species {
        val parts = normalizedLatin.split(' ').filter { it.isNotBlank() }
        val latin = parts.mapIndexed { index, token ->
            if (index == 0) token.replaceFirstChar { it.titlecase() } else token.lowercase()
        }.joinToString(" ")
        val genus = latin.substringBefore(' ')
        val common = KNOWN_COMMON_NAMES[normalizedLatin] ?: latin
        val profile = genusProfile(genus)
        return Species(
            id = normalizedLatin.replace(' ', '-'),
            common = common,
            latin = latin,
            authority = "",
            family = profile.family,
            familyLatin = profile.familyLatin,
            group = profile.group,
            sizeMm = profile.sizeMm,
            freqKHz = 6.0,
            freqRange = listOf(1.0, 16.0),
            pulseRate = null,
            callType = "Species-specific acoustic call",
            tempSensitive = false,
            months = List(12) { 0 },
            nocturnal = false,
            habitat = "Species-specific habitat; detailed guide data not yet bundled",
            range = "Species-specific range; detailed guide data not yet bundled",
            blurb = buildString {
                append(common)
                if (!common.equals(latin, ignoreCase = true)) append(" ($latin)")
                append(" is one of the 88 acoustic classes supported by the frozen J.1 model. ")
                append("This placeholder intentionally omits unverified detailed range, season and frequency claims.")
            },
            songDesc = "Use the model result as a candidate and compare it with a taxon-matched reference recording. Detailed species-specific call notes are not yet bundled for this expanded class.",
            signature = AcousticSignature(
                freqKHz = 6.0,
                pulseRate = null,
                broadband = true,
                pulseRegularity = 0.5,
                bandwidthKHz = 15.0
            )
        )
    }

    private data class GenusProfile(
        val family: String,
        val familyLatin: String,
        val group: String,
        val sizeMm: List<Int>
    )

    private fun genusProfile(genus: String): GenusProfile = when (genus.lowercase()) {
        "acanthoventris", "aleeta", "amphipsalta", "arunta", "atrapsalta", "cacama",
        "diceroprocta", "hadoa", "magicicada", "megatibicen", "neocicada", "neotibicen",
        "okanagana" -> GenusProfile("Cicada", "Cicadidae", "cicada", listOf(15, 55))
        "platypedia" -> GenusProfile("Cicada", "Tibicinidae", "cicada", listOf(15, 40))
        "bicolorana", "caedicia", "conocephalus", "microcentrum", "neoconocephalus",
        "neoxabea", "orchelimum", "paracyrtophyllus", "pterophylla", "scudderia" ->
            GenusProfile("Katydid", "Tettigoniidae", "katydid", listOf(10, 65))
        "chorthippus" -> GenusProfile("Grasshopper", "Acrididae", "grasshopper", listOf(10, 35))
        "neocurtilla" -> GenusProfile("Mole Cricket", "Gryllotalpidae", "cricket", listOf(20, 40))
        else -> GenusProfile("Cricket", "Gryllidae", "cricket", listOf(8, 35))
    }

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

    companion object {
        private val KNOWN_COMMON_NAMES = mapOf(
            "aleeta curvicosta" to "Floury Baker Cicada",
            "amphipsalta cingulata" to "Clapping Cicada",
            "amphipsalta zelandica" to "Chorus Cicada",
            "arunta perulata" to "White Drummer Cicada",
            "caedicia simplex" to "Common Garden Katydid",
            "conocephalus fuscus" to "Long-winged Conehead",
            "gryllus bimaculatus" to "Two-spotted Cricket",
            "gryllus campestris" to "European Field Cricket",
            "gryllus rubens" to "Southeastern Field Cricket",
            "oecanthus pellucens" to "Italian Tree Cricket"
        )
    }
}
