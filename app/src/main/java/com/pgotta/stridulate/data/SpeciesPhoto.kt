package com.pgotta.stridulate.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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

    /**
     * Resolve and permanently cache a correctly matched field-guide photo.
     * The network is used only until the image and attribution metadata have
     * been saved in the app's private files directory.
     */
    suspend fun photoFor(context: Context, species: Species): PhotoInfo? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val key = normalize(species.latin)
        synchronized(photoCache) {
            if (photoCache.containsKey(key)) return@withContext photoCache[key]
        }

        cachedPhoto(app, key)?.let { cached ->
            synchronized(photoCache) { photoCache[key] = cached }
            return@withContext cached
        }

        val remote = resolveTaxon(species.latin)?.photo ?: wikipediaPhoto(species.latin)
        val result = remote?.let { downloadPhoto(app, key, it) }
        if (result != null) synchronized(photoCache) { photoCache[key] = result }
        result
    }

    suspend fun prefetch(context: Context, species: List<Species>) = withContext(Dispatchers.IO) {
        species.forEach { photoFor(context, it) }
    }

    private fun cachedPhoto(context: Context, key: String): PhotoInfo? {
        val prefs = context.getSharedPreferences(PHOTO_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(key, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val file = File(json.getString("file"))
            if (!file.exists() || file.length() <= 0L) return null
            PhotoInfo(
                imageUrl = file.toURI().toString(),
                attribution = json.optString("attribution", "Community photo"),
                licenseCode = json.optString("license").ifBlank { null },
                sourceName = json.optString("source_name", "iNaturalist"),
                sourceUrl = json.optString("source_url").ifBlank { null }
            )
        }.getOrNull()
    }

    private fun downloadPhoto(context: Context, key: String, remote: PhotoInfo): PhotoInfo? {
        val directory = File(context.filesDir, "species_photos").apply { mkdirs() }
        val safe = key.replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val finalFile = File(directory, "$safe.image")
        val tempFile = File(directory, "$safe.tmp")
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(remote.imageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Stridulate/2.4 Android (field-guide photo cache)")
                setRequestProperty("Accept", "image/*")
            }
            if (connection.responseCode !in 200..299) return null
            val declared = connection.contentLengthLong
            if (declared > MAX_PHOTO_BYTES) return null
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_PHOTO_BYTES) throw IllegalStateException("Photo is too large")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (tempFile.length() <= 0L) return null
            if (finalFile.exists()) finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
            val local = remote.copy(imageUrl = finalFile.toURI().toString())
            val metadata = JSONObject()
                .put("file", finalFile.absolutePath)
                .put("attribution", remote.attribution)
                .put("license", remote.licenseCode ?: "")
                .put("source_name", remote.sourceName)
                .put("source_url", remote.sourceUrl ?: "")
            context.getSharedPreferences(PHOTO_PREFS, Context.MODE_PRIVATE)
                .edit().putString(key, metadata.toString()).apply()
            local
        } catch (_: Exception) {
            tempFile.delete()
            null
        } finally {
            connection?.disconnect()
        }
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

    private const val PHOTO_PREFS = "species_photo_cache_v1"
    private const val MAX_PHOTO_BYTES = 8L * 1024L * 1024L

    private fun JSONObject.firstNonBlank(vararg keys: String): String? {
        for (key in keys) {
            val value = optString(key)
            if (value.isNotBlank()) return value
        }
        return null
    }
}
