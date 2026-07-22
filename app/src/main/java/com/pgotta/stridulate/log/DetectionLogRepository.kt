package com.pgotta.stridulate.log

import android.content.Context
import com.pgotta.stridulate.community.EvidenceAudioStore
import com.pgotta.stridulate.environment.ObservationContext
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class DetectionOccurrence(
    val startSeconds: Double,
    val endSeconds: Double,
    val confidencePct: Int
)

data class LoggedSpeciesDetection(
    val speciesId: String,
    val latestConfidencePct: Int,
    val peakConfidencePct: Int,
    val lastHeardAtMillis: Long,
    val occurrences: List<DetectionOccurrence>
)

data class DetectionLogSession(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val audioFilePath: String,
    val sampleRate: Int,
    val durationSeconds: Double,
    val detections: List<LoggedSpeciesDetection>,
    val locationLabel: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val region: String? = null,
    val temperatureF: Double? = null,
    val weatherObservedAtMillis: Long? = null
)

/** Persistent recording log. Audio and metadata remain private in app storage. */
class DetectionLogRepository(private val context: Context) {
    private val root = File(context.filesDir, "detection_log").apply { mkdirs() }
    private val audioDir = File(root, "audio").apply { mkdirs() }
    private val tempDir = File(context.cacheDir, "live_capture").apply { mkdirs() }
    private val indexFile = File(root, "index.json")

    private val _sessions = MutableStateFlow(load())
    val sessions: StateFlow<List<DetectionLogSession>> = _sessions

    fun newRawCaptureFile(): File = File(tempDir, "capture-${UUID.randomUUID()}.pcm")

    @Synchronized
    fun saveSession(
        startedAtMillis: Long,
        endedAtMillis: Long,
        rawPcmFile: File?,
        sampleRate: Int,
        detections: List<LoggedSpeciesDetection>,
        observationContext: ObservationContext
    ): DetectionLogSession? {
        if (rawPcmFile == null || !rawPcmFile.exists() || rawPcmFile.length() <= 0L) return null
        val id = "log-${startedAtMillis}-${UUID.randomUUID().toString().take(8)}"
        val wav = File(audioDir, "$id.wav")
        EvidenceAudioStore.wrapRawPcm16AsWav(rawPcmFile, wav, sampleRate)
        rawPcmFile.delete()
        val duration = ((wav.length() - 44L).coerceAtLeast(0L) / 2.0) / sampleRate.toDouble()
        val session = DetectionLogSession(
            id = id,
            startedAtMillis = startedAtMillis,
            endedAtMillis = endedAtMillis,
            audioFilePath = wav.absolutePath,
            sampleRate = sampleRate,
            durationSeconds = duration,
            detections = detections,
            locationLabel = observationContext.locationLabel,
            latitude = observationContext.latitude,
            longitude = observationContext.longitude,
            region = observationContext.region.displayName.takeUnless { it == "Region unavailable" },
            temperatureF = observationContext.temperatureF,
            weatherObservedAtMillis = observationContext.temperatureObservedAtMillis
        )
        _sessions.value = (listOf(session) + _sessions.value).take(MAX_SESSIONS)
        persist()
        pruneAudio()
        return session
    }

    @Synchronized
    fun delete(sessionId: String): Boolean {
        val target = _sessions.value.firstOrNull { it.id == sessionId } ?: return false
        runCatching { File(target.audioFilePath).delete() }
        _sessions.value = _sessions.value.filterNot { it.id == sessionId }
        persist()
        return true
    }

    @Synchronized
    fun clear() {
        _sessions.value.forEach { runCatching { File(it.audioFilePath).delete() } }
        _sessions.value = emptyList()
        persist()
    }

    private fun pruneAudio() {
        val keep = _sessions.value.map { it.audioFilePath }.toSet()
        audioDir.listFiles()?.forEach { if (it.absolutePath !in keep) it.delete() }
    }

    private fun persist() {
        val array = JSONArray()
        _sessions.value.forEach { session ->
            val detections = JSONArray()
            session.detections.forEach { detection ->
                val occurrences = JSONArray()
                detection.occurrences.forEach { occurrence ->
                    occurrences.put(
                        JSONObject()
                            .put("start", occurrence.startSeconds)
                            .put("end", occurrence.endSeconds)
                            .put("confidence", occurrence.confidencePct)
                    )
                }
                detections.put(
                    JSONObject()
                        .put("species_id", detection.speciesId)
                        .put("latest_confidence", detection.latestConfidencePct)
                        .put("peak_confidence", detection.peakConfidencePct)
                        .put("last_heard", detection.lastHeardAtMillis)
                        .put("occurrences", occurrences)
                )
            }
            array.put(
                JSONObject()
                    .put("id", session.id)
                    .put("started_at", session.startedAtMillis)
                    .put("ended_at", session.endedAtMillis)
                    .put("audio_path", session.audioFilePath)
                    .put("sample_rate", session.sampleRate)
                    .put("duration", session.durationSeconds)
                    .put("detections", detections)
                    .putNullable("location_label", session.locationLabel)
                    .putNullable("latitude", session.latitude)
                    .putNullable("longitude", session.longitude)
                    .putNullable("region", session.region)
                    .putNullable("temperature_f", session.temperatureF)
                    .putNullable("weather_observed_at", session.weatherObservedAtMillis)
            )
        }
        val temp = File(root, "index.tmp")
        temp.writeText(JSONObject().put("schema", 2).put("sessions", array).toString())
        if (indexFile.exists()) indexFile.delete()
        if (!temp.renameTo(indexFile)) {
            temp.copyTo(indexFile, overwrite = true)
            temp.delete()
        }
    }

    private fun load(): List<DetectionLogSession> = runCatching {
        if (!indexFile.exists()) return@runCatching emptyList()
        val rootJson = JSONObject(indexFile.readText())
        val sessions = rootJson.optJSONArray("sessions") ?: JSONArray()
        buildList {
            for (i in 0 until sessions.length()) {
                val item = sessions.optJSONObject(i) ?: continue
                val audio = item.optString("audio_path")
                if (audio.isBlank() || !File(audio).exists()) continue
                val detectionArray = item.optJSONArray("detections") ?: JSONArray()
                val detections = buildList {
                    for (j in 0 until detectionArray.length()) {
                        val d = detectionArray.optJSONObject(j) ?: continue
                        val occurrenceArray = d.optJSONArray("occurrences") ?: JSONArray()
                        val occurrences = buildList {
                            for (k in 0 until occurrenceArray.length()) {
                                val o = occurrenceArray.optJSONObject(k) ?: continue
                                add(
                                    DetectionOccurrence(
                                        o.optDouble("start", 0.0),
                                        o.optDouble("end", 0.0),
                                        o.optInt("confidence", 0)
                                    )
                                )
                            }
                        }
                        add(
                            LoggedSpeciesDetection(
                                speciesId = d.optString("species_id"),
                                latestConfidencePct = d.optInt("latest_confidence", 0),
                                peakConfidencePct = d.optInt("peak_confidence", 0),
                                lastHeardAtMillis = d.optLong("last_heard", 0L),
                                occurrences = occurrences
                            )
                        )
                    }
                }
                add(
                    DetectionLogSession(
                        id = item.optString("id"),
                        startedAtMillis = item.optLong("started_at"),
                        endedAtMillis = item.optLong("ended_at"),
                        audioFilePath = audio,
                        sampleRate = item.optInt("sample_rate", 48_000),
                        durationSeconds = item.optDouble("duration", 0.0),
                        detections = detections,
                        locationLabel = item.optNullableString("location_label"),
                        latitude = item.optNullableDouble("latitude"),
                        longitude = item.optNullableDouble("longitude"),
                        region = item.optNullableString("region"),
                        temperatureF = item.optNullableDouble("temperature_f"),
                        weatherObservedAtMillis = item.optNullableLong("weather_observed_at")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = apply {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf(Double::isFinite)

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    companion object {
        private const val MAX_SESSIONS = 100
    }
}
