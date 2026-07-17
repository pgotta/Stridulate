package com.pgotta.stridulate.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Network-backed field-guide metadata.
 *
 * Photos are resolved taxonomically instead of guessing Wikimedia filenames:
 *  1. Exact scientific-name match from iNaturalist's taxa API.
 *  2. Wikipedia page-image fallback.
 *  3. The legacy URL in species.json as a last resort only.
 *
 * The same exact iNaturalist taxon is used to request a bounded sample of
 * research-grade observations for the occurrence map. Nothing here is used by
 * the classifier; identification remains fully on-device.
 */
object SpeciesPhoto {

    data class PhotoInfo(
        val imageUrl: String,
        val attribution: String,
        val licenseCode: String?,
        val sourceName: String,
        val sourceUrl: String?
    )

    data class ObservationPoint(
        val latitude: Double,
        val longitude: Double
    )

    data class ObservationSample(
        val points: List<ObservationPoint>,
        val totalResults: Int,
        val sourceUrl: String?
    )

    private data class TaxonInfo(
        val id: Int,
        val name: String,
        val photo: PhotoInfo?,
        val sourceUrl: String
    )

    private val taxonCache = HashMap<String, TaxonInfo?>()
    private val photoCache = HashMap<String, PhotoInfo?>()
    private val observationCache = HashMap<String, ObservationSample>()

    /** Resolve a correctly matched field-guide photo for a species. */
    suspend fun photoFor(species: Species): PhotoInfo? = withContext(Dispatchers.IO) {
        val key = normalize(species.latin)
        synchronized(photoCache) {
            if (photoCache.containsKey(key)) return@withContext photoCache[key]
        }

        val taxon = resolveTaxon(species.latin)
        val result = taxon?.photo ?: wikipediaPhoto(species.latin)

        if (result != null) synchronized(photoCache) { photoCache[key] = result }
        result
    }

    /**
     * Return up to 200 research-grade U.S. observation coordinates.
     *
     * These are occurrence records, not a complete range polygon. The UI labels
     * them as such so sparse records are never presented as a definitive range.
     */
    suspend fun observationsFor(latinName: String): ObservationSample? =
        withContext(Dispatchers.IO) {
            val key = normalize(latinName)
            synchronized(observationCache) {
                observationCache[key]?.let { return@withContext it }
            }

            val taxon = resolveTaxon(latinName) ?: return@withContext null
            val apiUrl = buildString {
                append("https://api.inaturalist.org/v1/observations")
                append("?taxon_id=").append(taxon.id)
                append("&quality_grade=research")
                append("&geo=true&verifiable=true")
                append("&swlat=24&swlng=-125&nelat=50&nelng=-66")
                append("&per_page=200")
                append("&order_by=votes&order=desc")
            }
            val json = getJson(apiUrl) ?: return@withContext null
            val results = json.optJSONArray("results") ?: JSONArray()
            val seen = HashSet<String>()
            val points = ArrayList<ObservationPoint>()

            for (i in 0 until results.length()) {
                val observation = results.optJSONObject(i) ?: continue
                val point = parseObservationPoint(observation) ?: continue

                // The bundled map is the contiguous U.S. Keep Alaska, Hawaii,
                // Canada and Mexico records from being plotted outside it.
                if (point.latitude !in 24.0..50.0 || point.longitude !in -125.0..-66.0) continue

                // Nearby records otherwise render as hundreds of identical dots.
                val cell = "${(point.latitude * 100).roundToInt()}:" +
                    "${(point.longitude * 100).roundToInt()}"
                if (seen.add(cell)) points.add(point)
            }

            val sample = ObservationSample(
                points = points,
                totalResults = json.optInt("total_results", points.size),
                sourceUrl = taxon.sourceUrl
            )
            synchronized(observationCache) { observationCache[key] = sample }
            sample
        }

    private fun resolveTaxon(latinName: String): TaxonInfo? {
        val key = normalize(latinName)
        synchronized(taxonCache) {
            if (taxonCache.containsKey(key)) return taxonCache[key]
        }

        val encoded = URLEncoder.encode(latinName.trim(), "UTF-8")
        val json = getJson(
            "https://api.inaturalist.org/v1/taxa?q=$encoded&rank=species&per_page=10"
        )
        val results = json?.optJSONArray("results")

        var exact: JSONObject? = null
        if (results != null) {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val accepted = normalize(item.optString("name"))
                val matched = normalize(item.optString("matched_term"))
                if (accepted == key || matched == key) {
                    exact = item
                    break
                }
            }
        }

        // Never use a merely similar first search result: a missing picture is
        // preferable to showing the wrong insect.
        val selected = exact
        val taxon = selected?.let { item ->
            val id = item.optInt("id", -1)
            if (id <= 0) return@let null
            val acceptedName = item.optString("name", latinName)
            val sourceUrl = "https://www.inaturalist.org/taxa/$id"
            val photoJson = item.optJSONObject("default_photo")
            val photoUrl = photoJson?.firstNonBlank(
                "medium_url",
                "large_url",
                "original_url",
                "url"
            )?.replace("square.", "medium.")

            val photo = photoUrl?.let {
                PhotoInfo(
                    imageUrl = it,
                    attribution = photoJson?.optString("attribution")
                        ?.ifBlank { "iNaturalist community photo" }
                        ?: "iNaturalist community photo",
                    licenseCode = photoJson?.optString("license_code")?.ifBlank { null },
                    sourceName = "iNaturalist",
                    sourceUrl = sourceUrl
                )
            }
            TaxonInfo(id, acceptedName, photo, sourceUrl)
        }

        // Cache successful taxon matches and true no-match responses. A network
        // error is not cached, allowing a later retry after connectivity returns.
        if (json != null) synchronized(taxonCache) { taxonCache[key] = taxon }
        return taxon
    }

    private fun wikipediaPhoto(latinName: String): PhotoInfo? {
        val title = URLEncoder.encode(latinName.trim(), "UTF-8")
        val url = buildString {
            append("https://en.wikipedia.org/w/api.php")
            append("?action=query&format=json&redirects=1")
            append("&prop=pageimages%7Cinfo&inprop=url")
            append("&piprop=thumbnail&pithumbsize=1200")
            append("&titles=").append(title)
        }
        val json = getJson(url) ?: return null
        val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val keys = pages.keys()
        while (keys.hasNext()) {
            val page = pages.optJSONObject(keys.next()) ?: continue
            val imageUrl = page.optJSONObject("thumbnail")?.optString("source")
                ?.takeIf { it.isNotBlank() }
                ?: continue
            return PhotoInfo(
                imageUrl = imageUrl,
                attribution = "Wikipedia / Wikimedia Commons",
                licenseCode = null,
                sourceName = "Wikipedia",
                sourceUrl = page.optString("fullurl").ifBlank { null }
            )
        }
        return null
    }

    private fun parseObservationPoint(observation: JSONObject): ObservationPoint? {
        val coords = observation.optJSONObject("geojson")?.optJSONArray("coordinates")
        if (coords != null && coords.length() >= 2) {
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isFinite() && lon.isFinite()) return ObservationPoint(lat, lon)
        }

        val location = observation.optString("location")
        val parts = location.split(',')
        if (parts.size >= 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lon = parts[1].trim().toDoubleOrNull()
            if (lat != null && lon != null) return ObservationPoint(lat, lon)
        }
        return null
    }

    private fun getJson(url: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 12_000
                setRequestProperty(
                    "User-Agent",
                    "Stridulate/1.0 Android (field-guide metadata; contact via app repository)"
                )
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun normalize(value: String): String =
        value.trim().lowercase().replace('_', ' ').replace(Regex("\\s+"), " ")

    private fun JSONObject.firstNonBlank(vararg keys: String): String? {
        for (key in keys) {
            val value = optString(key)
            if (value.isNotBlank()) return value
        }
        return null
    }
}
