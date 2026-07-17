package com.pgotta.stridulate.data

import kotlinx.serialization.Serializable

/**
 * A single insect species and its published acoustic signature.
 *
 * The [signature] block is what the [com.pgotta.stridulate.classifier.InsectClassifier]
 * scores a measured recording against. Values are drawn from entomological
 * literature (dominant frequency, pulse/chirp rate, call structure) for North
 * American singing insects.
 */
@Serializable
data class Species(
    val id: String,
    val common: String,
    val latin: String,
    val authority: String,
    val family: String,
    val familyLatin: String,
    val group: String,                 // "cricket" | "katydid" | "cicada"
    val sizeMm: List<Int>,             // [min, max]
    val freqKHz: Double,               // dominant/carrier frequency
    val freqRange: List<Double>,       // [lo, hi] spectral spread
    val pulseRate: Double? = null,     // pulses/chirps per second; null if broadband
    val callType: String,
    val tempSensitive: Boolean,
    val months: List<Int>,             // 12 ints (0/1), Jan..Dec
    val nocturnal: Boolean,
    val habitat: String,
    val range: String,
    val blurb: String,
    val songDesc: String,
    val photoUrl: String? = null,
    val signature: AcousticSignature
)

@Serializable
data class AcousticSignature(
    val freqKHz: Double,
    val pulseRate: Double? = null,
    val broadband: Boolean,
    // Defaults keep older/generated species databases loadable. The repository
    // normalizes missing sentinel values from the species-level frequency range.
    val pulseRegularity: Double = -1.0, // 0..1  (1 = metronomic trill)
    val bandwidthKHz: Double = -1.0
)

@Serializable
data class SoundPack(
    val id: String,
    val flag: String,
    val name: String,
    val note: String,
    val installed: Boolean = false,
    val size: String? = null
)

@Serializable
data class SpeciesDatabase(
    val species: List<Species> = emptyList(),
    val packs: List<SoundPack> = emptyList()
)
