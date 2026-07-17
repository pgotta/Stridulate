package com.pgotta.stridulate.community

import org.json.JSONArray
import org.json.JSONObject

/** Human-review state for a locally saved unresolved recording. */
enum class CommunityRecordStatus(val displayName: String) {
    SAVED("Saved locally"),
    SHARED("Shared for ID"),
    LINKED("Linked to iNaturalist"),
    COMMUNITY_ID("Community ID available"),
    TRAINING_APPROVED("Approved for training")
}

data class SavedCandidate(
    val label: String,
    val scientificName: String?,
    val commonName: String?,
    val audioScore: Double,
    val reliabilityTier: String
)

data class INaturalistSnapshot(
    val observationId: Long,
    val url: String,
    val observerTaxonScientificName: String?,
    val observerTaxonCommonName: String?,
    val communityTaxonId: Long?,
    val communityTaxonScientificName: String?,
    val communityTaxonCommonName: String?,
    val communityTaxonRank: String?,
    val qualityGrade: String?,
    val identificationsCount: Int,
    val commentsCount: Int,
    val updatedAt: String?,
    val soundLicenseCode: String?,
    val soundAttribution: String?
)

data class CommunityObservationRecord(
    val id: String,
    val createdAtMillis: Long,
    val observedAtMillis: Long?,
    val source: EvidenceSource,
    val audioFileName: String,
    val sampleRate: Int,
    val durationSeconds: Double,
    val decision: String,
    val decisionReason: String,
    val modelTopLabel: String,
    val modelTopConfidence: Double,
    val candidates: List<SavedCandidate>,
    val qualityGrade: String?,
    val qualityScore: Int?,
    val locationLabel: String?,
    val latitude: Double?,
    val longitude: Double?,
    val region: String?,
    val temperatureF: Double?,
    val weatherObservedAtMillis: Long?,
    val status: CommunityRecordStatus = CommunityRecordStatus.SAVED,
    val iNaturalistObservationId: Long? = null,
    val iNaturalistUrl: String? = null,
    val observerTaxonScientificName: String? = null,
    val observerTaxonCommonName: String? = null,
    val communityTaxonId: Long? = null,
    val communityTaxonScientificName: String? = null,
    val communityTaxonCommonName: String? = null,
    val communityTaxonRank: String? = null,
    val iNaturalistQualityGrade: String? = null,
    val identificationsCount: Int = 0,
    val commentsCount: Int = 0,
    val iNaturalistUpdatedAt: String? = null,
    val lastCheckedAtMillis: Long? = null,
    val soundLicenseCode: String? = null,
    val soundAttribution: String? = null,
    val approvedTrainingLabel: String? = null,
    val contributorCredit: String? = null,
    val contributionLicense: String? = null,
    val rightsAttestedAtMillis: Long? = null,
    val note: String? = null
) {
    val displayTaxon: String?
        get() = communityTaxonCommonName ?: communityTaxonScientificName
            ?: observerTaxonCommonName ?: observerTaxonScientificName

    val hasCommunityIdentification: Boolean
        get() = !communityTaxonScientificName.isNullOrBlank()

    /** Species or a taxon below species; broad family/genus IDs still need refinement. */
    val hasSpeciesLevelCommunityIdentification: Boolean
        get() = communityTaxonRank?.lowercase() in setOf(
            "species", "subspecies", "variety", "form", "hybrid"
        )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("created_at_millis", createdAtMillis)
        putNullable("observed_at_millis", observedAtMillis)
        put("source", source.name)
        put("audio_file_name", audioFileName)
        put("sample_rate", sampleRate)
        put("duration_seconds", durationSeconds)
        put("decision", decision)
        put("decision_reason", decisionReason)
        put("model_top_label", modelTopLabel)
        put("model_top_confidence", modelTopConfidence)
        put("candidates", JSONArray().apply {
            candidates.forEach { candidate ->
                put(JSONObject().apply {
                    put("label", candidate.label)
                    putNullable("scientific_name", candidate.scientificName)
                    putNullable("common_name", candidate.commonName)
                    put("audio_score", candidate.audioScore)
                    put("reliability_tier", candidate.reliabilityTier)
                })
            }
        })
        putNullable("quality_grade", qualityGrade)
        putNullable("quality_score", qualityScore)
        putNullable("location_label", locationLabel)
        putNullable("latitude", latitude)
        putNullable("longitude", longitude)
        putNullable("region", region)
        putNullable("temperature_f", temperatureF)
        putNullable("weather_observed_at_millis", weatherObservedAtMillis)
        put("status", status.name)
        putNullable("inaturalist_observation_id", iNaturalistObservationId)
        putNullable("inaturalist_url", iNaturalistUrl)
        putNullable("observer_taxon_scientific_name", observerTaxonScientificName)
        putNullable("observer_taxon_common_name", observerTaxonCommonName)
        putNullable("community_taxon_id", communityTaxonId)
        putNullable("community_taxon_scientific_name", communityTaxonScientificName)
        putNullable("community_taxon_common_name", communityTaxonCommonName)
        putNullable("community_taxon_rank", communityTaxonRank)
        putNullable("inaturalist_quality_grade", iNaturalistQualityGrade)
        put("identifications_count", identificationsCount)
        put("comments_count", commentsCount)
        putNullable("inaturalist_updated_at", iNaturalistUpdatedAt)
        putNullable("last_checked_at_millis", lastCheckedAtMillis)
        putNullable("sound_license_code", soundLicenseCode)
        putNullable("sound_attribution", soundAttribution)
        putNullable("approved_training_label", approvedTrainingLabel)
        putNullable("contributor_credit", contributorCredit)
        putNullable("contribution_license", contributionLicense)
        putNullable("rights_attested_at_millis", rightsAttestedAtMillis)
        putNullable("note", note)
    }

    companion object {
        fun fromJson(root: JSONObject): CommunityObservationRecord {
            val candidatesJson = root.optJSONArray("candidates") ?: JSONArray()
            val candidates = buildList {
                for (i in 0 until candidatesJson.length()) {
                    val candidate = candidatesJson.optJSONObject(i) ?: continue
                    add(
                        SavedCandidate(
                            label = candidate.optString("label"),
                            scientificName = candidate.optNullableString("scientific_name"),
                            commonName = candidate.optNullableString("common_name"),
                            audioScore = candidate.optDouble("audio_score", 0.0),
                            reliabilityTier = candidate.optString("reliability_tier", "Experimental")
                        )
                    )
                }
            }
            return CommunityObservationRecord(
                id = root.getString("id"),
                createdAtMillis = root.optLong("created_at_millis", System.currentTimeMillis()),
                observedAtMillis = root.optNullableLong("observed_at_millis"),
                source = runCatching { EvidenceSource.valueOf(root.optString("source")) }
                    .getOrDefault(EvidenceSource.LIVE),
                audioFileName = root.getString("audio_file_name"),
                sampleRate = root.optInt("sample_rate", 44_100),
                durationSeconds = root.optDouble("duration_seconds", 0.0),
                decision = root.optString("decision", "NO_CONFIDENT_MATCH"),
                decisionReason = root.optString("decision_reason"),
                modelTopLabel = root.optString("model_top_label"),
                modelTopConfidence = root.optDouble("model_top_confidence", 0.0),
                candidates = candidates,
                qualityGrade = root.optNullableString("quality_grade"),
                qualityScore = root.optNullableInt("quality_score"),
                locationLabel = root.optNullableString("location_label"),
                latitude = root.optNullableDouble("latitude"),
                longitude = root.optNullableDouble("longitude"),
                region = root.optNullableString("region"),
                temperatureF = root.optNullableDouble("temperature_f"),
                weatherObservedAtMillis = root.optNullableLong("weather_observed_at_millis"),
                status = runCatching { CommunityRecordStatus.valueOf(root.optString("status")) }
                    .getOrDefault(CommunityRecordStatus.SAVED),
                iNaturalistObservationId = root.optNullableLong("inaturalist_observation_id"),
                iNaturalistUrl = root.optNullableString("inaturalist_url"),
                observerTaxonScientificName = root.optNullableString("observer_taxon_scientific_name"),
                observerTaxonCommonName = root.optNullableString("observer_taxon_common_name"),
                communityTaxonId = root.optNullableLong("community_taxon_id"),
                communityTaxonScientificName = root.optNullableString("community_taxon_scientific_name"),
                communityTaxonCommonName = root.optNullableString("community_taxon_common_name"),
                communityTaxonRank = root.optNullableString("community_taxon_rank"),
                iNaturalistQualityGrade = root.optNullableString("inaturalist_quality_grade"),
                identificationsCount = root.optInt("identifications_count", 0),
                commentsCount = root.optInt("comments_count", 0),
                iNaturalistUpdatedAt = root.optNullableString("inaturalist_updated_at"),
                lastCheckedAtMillis = root.optNullableLong("last_checked_at_millis"),
                soundLicenseCode = root.optNullableString("sound_license_code"),
                soundAttribution = root.optNullableString("sound_attribution"),
                approvedTrainingLabel = root.optNullableString("approved_training_label"),
                contributorCredit = root.optNullableString("contributor_credit"),
                contributionLicense = root.optNullableString("contribution_license"),
                rightsAttestedAtMillis = root.optNullableLong("rights_attested_at_millis"),
                note = root.optNullableString("note")
            )
        }
    }
}

internal fun JSONObject.putNullable(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}

internal fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

internal fun JSONObject.optNullableLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

internal fun JSONObject.optNullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

internal fun JSONObject.optNullableDouble(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key)
