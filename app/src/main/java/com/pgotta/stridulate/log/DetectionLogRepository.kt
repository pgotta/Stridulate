package com.pgotta.stridulate.log

import android.content.Context
import com.pgotta.stridulate.community.EvidenceAudioStore
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
    val detections: List<LoggedSpeciesDetection>
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

    fun saveSession(
        startedAtMillis: Long,
        endedAtMillis: Long,
        rawPcmFile: File?,
        sampleRate: Int,
        detections: List<LoggedSpeciesDetection>
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
            detections = detections
        )
        _sessions.value = (listOf(session) + _sessions.value).take(MAX_SESSIONS)
        persist()
        pruneAudio()
        return session
    }

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
            )
        }
        val temp = File(root, "index.tmp")
        temp.writeText(JSONObject().put("schema", 1).put("sessions", array).toString())
        if (indexFile.exists()) indexFile.delete()
        temp.renameTo(indexFile)
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
                        detections = detections
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val MAX_SESSIONS = 100
    }
}
