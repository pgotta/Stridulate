package com.pgotta.stridulate.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.pgotta.stridulate.data.Species
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Plays a real community-recorded insect call instead of generating a sine wave
 * or white noise. Recordings are resolved by exact scientific taxon through
 * iNaturalist observations with sounds, preferring research-grade records.
 */
object ReferenceSoundPlayer {

    private data class SoundInfo(
        val fileUrl: String,
        val attribution: String,
        val licenseCode: String?,
        val observationUrl: String?
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val requestId = AtomicLong(0)
    private val cache = HashMap<String, SoundInfo>()

    @Volatile
    private var player: MediaPlayer? = null

    fun play(context: Context, species: Species) {
        val app = context.applicationContext
        stop()
        val request = requestId.incrementAndGet()
        Toast.makeText(app, "Finding a real ${species.common} recording…", Toast.LENGTH_SHORT).show()

        executor.execute {
            val key = normalize(species.latin)
            val info = synchronized(cache) { cache[key] } ?: findSound(species.latin)?.also {
                synchronized(cache) { cache[key] = it }
            }

            main.post {
                if (request != requestId.get()) return@post
                if (info == null) {
                    Toast.makeText(
                        app,
                        "No taxon-verified recording was available for ${species.common}.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@post
                }
                prepareAndPlay(app, species, info, request)
            }
        }
    }

    private fun prepareAndPlay(
        context: Context,
        species: Species,
        info: SoundInfo,
        request: Long
    ) {
        val mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(info.fileUrl)
            setOnPreparedListener { prepared ->
                if (request != requestId.get()) {
                    prepared.release()
                    return@setOnPreparedListener
                }
                prepared.start()
                val credit = buildString {
                    append("Real ").append(species.common).append(" recording")
                    if (info.attribution.isNotBlank()) append(" · ").append(info.attribution)
                    info.licenseCode?.takeIf(String::isNotBlank)?.let {
                        append(" · ").append(it.uppercase())
                    }
                }
                Toast.makeText(context, credit, Toast.LENGTH_LONG).show()
            }
            setOnCompletionListener { completed ->
                if (player === completed) player = null
                completed.release()
            }
            setOnErrorListener { failed, _, _ ->
                if (player === failed) player = null
                failed.release()
                Toast.makeText(
                    context,
                    "The real recording could not be played. Check your connection.",
                    Toast.LENGTH_LONG
                ).show()
                true
            }
        }
        player = mediaPlayer
        try {
            mediaPlayer.prepareAsync()
        } catch (_: Exception) {
            if (player === mediaPlayer) player = null
            mediaPlayer.release()
            Toast.makeText(context, "The recording could not be opened.", Toast.LENGTH_LONG).show()
        }
    }

    fun stop() {
        requestId.incrementAndGet()
        val active = player
        player = null
        if (active != null) {
            try {
                active.stop()
            } catch (_: Exception) {
                // It may still be preparing.
            }
            try {
                active.reset()
                active.release()
            } catch (_: Exception) {
                // Already released by a callback.
            }
        }
    }

    private fun findSound(latinName: String): SoundInfo? {
        val taxonId = exactTaxonId(latinName) ?: return null
        val research = observationSound(taxonId, researchGrade = true)
        return research ?: observationSound(taxonId, researchGrade = false)
    }

    private fun exactTaxonId(latinName: String): Int? {
        val encoded = URLEncoder.encode(latinName.trim(), "UTF-8")
        val json = getJson(
            "https://api.inaturalist.org/v1/taxa?q=$encoded&rank=species&per_page=10"
        ) ?: return null
        val wanted = normalize(latinName)
        val results = json.optJSONArray("results") ?: JSONArray()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val accepted = normalize(item.optString("name"))
            val matched = normalize(item.optString("matched_term"))
            if (accepted == wanted || matched == wanted) {
                return item.optInt("id", -1).takeIf { it > 0 }
            }
        }
        return null
    }

    private fun observationSound(taxonId: Int, researchGrade: Boolean): SoundInfo? {
        val url = buildString {
            append("https://api.inaturalist.org/v1/observations")
            append("?taxon_id=").append(taxonId)
            append("&sounds=true&verifiable=true")
            append("&sound_license=cc0,cc-by,cc-by-nc,cc-by-nd,cc-by-sa,cc-by-nc-nd,cc-by-nc-sa")
            if (researchGrade) append("&quality_grade=research")
            append("&per_page=30&order_by=votes&order=desc")
        }
        val json = getJson(url) ?: return null
        val results = json.optJSONArray("results") ?: JSONArray()
        for (i in 0 until results.length()) {
            val observation = results.optJSONObject(i) ?: continue
            val sounds = observation.optJSONArray("sounds") ?: continue
            for (j in 0 until sounds.length()) {
                val sound = sounds.optJSONObject(j) ?: continue
                val flags = sound.optJSONArray("flags")
                if (flags != null && flags.length() > 0) continue
                val rawUrl = sound.optString("file_url").trim()
                if (rawUrl.isBlank()) continue
                val fileUrl = when {
                    rawUrl.startsWith("//") -> "https:$rawUrl"
                    rawUrl.startsWith("http://") -> "https://${rawUrl.removePrefix("http://")}" 
                    else -> rawUrl
                }
                val observationId = observation.optLong("id", -1L)
                return SoundInfo(
                    fileUrl = fileUrl,
                    attribution = sound.optString("attribution").ifBlank {
                        observation.optJSONObject("user")?.optString("login")
                            ?.takeIf(String::isNotBlank)
                            ?.let { "iNaturalist user $it" }
                            ?: "iNaturalist community recording"
                    },
                    licenseCode = sound.optString("license_code").trim().takeIf { it.isNotEmpty() },
                    observationUrl = observationId.takeIf { it > 0 }
                        ?.let { "https://www.inaturalist.org/observations/$it" }
                )
            }
        }
        return null
    }

    private fun getJson(url: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty(
                    "User-Agent",
                    "Stridulate/2.2.1 Android (taxon-matched reference audio)"
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
}
