package com.pgotta.stridulate.community

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Public, read-only iNaturalist lookup for observations explicitly linked by the user. */
class INaturalistClient {
    fun fetchObservation(observationId: Long): INaturalistSnapshot {
        require(observationId > 0) { "Enter a valid iNaturalist observation URL or ID." }
        val connection = URL("https://api.inaturalist.org/v1/observations/$observationId")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 12_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Stridulate/2.3.2 Android community-link")
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("iNaturalist returned HTTP $status.")
            val root = JSONObject(body)
            val observation = root.optJSONArray("results")?.optJSONObject(0)
                ?: root.optJSONObject("observation")
                ?: root.takeIf { it.has("id") }
                ?: throw IllegalStateException("iNaturalist did not return that observation.")

            val observerTaxon = observation.optJSONObject("taxon")
            val communityTaxon = observation.optJSONObject("community_taxon")
            val sounds = observation.optJSONArray("sounds")
            val firstSound = sounds?.optJSONObject(0)
            INaturalistSnapshot(
                observationId = observation.optLong("id", observationId),
                url = observation.optString("uri")
                    .takeIf { it.isNotBlank() }
                    ?: "https://www.inaturalist.org/observations/$observationId",
                observerTaxonScientificName = observerTaxon?.optString("name")?.takeIf { it.isNotBlank() },
                observerTaxonCommonName = observerTaxon?.optString("preferred_common_name")?.takeIf { it.isNotBlank() },
                communityTaxonId = communityTaxon?.optLong("id")?.takeIf { it > 0 },
                communityTaxonScientificName = communityTaxon?.optString("name")?.takeIf { it.isNotBlank() },
                communityTaxonCommonName = communityTaxon?.optString("preferred_common_name")?.takeIf { it.isNotBlank() },
                communityTaxonRank = communityTaxon?.optString("rank")?.takeIf { it.isNotBlank() },
                qualityGrade = observation.optString("quality_grade").takeIf { it.isNotBlank() },
                identificationsCount = observation.optInt("identifications_count", 0),
                commentsCount = observation.optInt("comments_count", 0),
                updatedAt = observation.optString("updated_at").takeIf { it.isNotBlank() },
                soundLicenseCode = firstSound?.optString("license_code")?.takeIf { it.isNotBlank() },
                soundAttribution = firstSound?.optString("attribution")?.takeIf { it.isNotBlank() }
            )
        } finally {
            connection.disconnect()
        }
    }
}
