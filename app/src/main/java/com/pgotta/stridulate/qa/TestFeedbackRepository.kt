package com.pgotta.stridulate.qa

import android.content.Context
import android.os.Build
import com.pgotta.stridulate.audio.MeasuredSignature
import com.pgotta.stridulate.audio.InsectSignalAssessment
import com.pgotta.stridulate.audio.PossibleMatchGate
import com.pgotta.stridulate.audio.RecordingQuality
import com.pgotta.stridulate.audio.SoundSensitivity
import com.pgotta.stridulate.classifier.Candidate
import com.pgotta.stridulate.environment.ObservationContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class FeedbackVerdict { CORRECT, INCORRECT, NOISE }


data class QaSpeciesProgress(
    val targetKey: String,
    val total: Int,
    val correct: Int,
    val incorrect: Int,
    val noise: Int
)

data class QaProgressSnapshot(
    val totalFeedback: Int,
    val correct: Int,
    val incorrect: Int,
    val noise: Int,
    val noiseTargetTests: Int,
    val exploratoryTests: Int,
    val byTarget: Map<String, QaSpeciesProgress>
)

data class TestFeedbackSnapshot(
    val source: String,
    val sessionKey: String? = null,
    val windowStartSeconds: Double? = null,
    val windowEndSeconds: Double? = null,
    val candidates: List<Candidate>,
    /** Exact pre-v0.3 Epoch-19 shadow ranking from the same gained PCM, when bundled. */
    val legacyCandidates: List<Candidate> = emptyList(),
    val quality: RecordingQuality? = null,
    val signature: MeasuredSignature? = null,
    val signalAssessment: InsectSignalAssessment? = null,
    val observationContext: ObservationContext? = null
)

/**
 * Human QA labels for beta field/lab testing.
 *
 * Each tap stores the complete visible Top 3 and the frozen J.1 gate state. The
 * selected test target is persisted separately so an INCORRECT tap still records
 * what species should have been present. Exact coordinates are intentionally not
 * exported; only coarse region/weather context is retained.
 */
class TestFeedbackRepository(private val context: Context) {
    private val root = File(context.filesDir, "test_feedback").apply { mkdirs() }
    private val eventFile = File(root, "feedback.jsonl")
    private val prefs = context.getSharedPreferences("test_feedback_v1", Context.MODE_PRIVATE)
    private val _count = MutableStateFlow(countLines())
    val count: StateFlow<Int> = _count
    private val _targetKey = MutableStateFlow(prefs.getString(KEY_TARGET, null))
    val targetKey: StateFlow<String?> = _targetKey
    private val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    private val appVersionName: String = packageInfo.versionName ?: "unknown"
    private val appVersionCode: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }

    fun setTargetKey(value: String?) {
        val clean = value?.takeIf(String::isNotBlank)
        _targetKey.value = clean
        prefs.edit().apply {
            if (clean == null) remove(KEY_TARGET) else putString(KEY_TARGET, clean)
        }.apply()
    }

    @Synchronized
    fun record(verdict: FeedbackVerdict, selectedLabel: String?, snapshot: TestFeedbackSnapshot): String {
        val visible = snapshot.candidates.filter { it.species != null }.take(3)
        val legacyVisible = snapshot.legacyCandidates.take(3)
        val selected = visible.firstOrNull { it.label == selectedLabel }
        val target = when {
            _targetKey.value == TARGET_NOISE -> JSONObject().put("type", "noise").put("label", "noise_or_non_insect")
            !_targetKey.value.isNullOrBlank() -> JSONObject().put("type", "species").put("label", _targetKey.value)
            verdict == FeedbackVerdict.CORRECT && selected != null -> JSONObject()
                .put("type", "species_inferred_from_correct_tap")
                .put("label", selected.label)
                .put("common", selected.species?.common)
            else -> JSONObject().put("type", "unspecified")
        }
        val event = JSONObject()
            .put("schema", 3)
            .put("event_id", "qa-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}")
            .put("recorded_at_ms", System.currentTimeMillis())
            .put("app_version", appVersionName)
            .put("app_version_code", appVersionCode)
            .put("model", "frozen_j1_perch_2_0")
            .put("comparison_model", if (legacyVisible.isEmpty()) JSONObject.NULL else "legacy_epoch19_67_tflite")
            .put("j1_score_meaning", "model score 0..1; not a literal probability of correctness")
            .put("source", snapshot.source)
            .put("verdict", verdict.name.lowercase(Locale.US))
            .put("expected_target", target)
            .put("selected_candidate", selected?.let(::candidateJson) ?: JSONObject.NULL)
            .put("top3", JSONArray().apply { visible.forEachIndexed { index, c -> put(candidateJson(c).put("rank", index + 1)) } })
            .put("legacy_top3", JSONArray().apply { legacyVisible.forEachIndexed { index, c -> put(legacyCandidateJson(c).put("rank", index + 1)) } })
            .putNullable("session_key", snapshot.sessionKey)
            .putNullable("window_start_seconds", snapshot.windowStartSeconds)
            .putNullable("window_end_seconds", snapshot.windowEndSeconds)
            .put("sound_sensitivity_level", SoundSensitivity.level.toDouble())
            .put("analysis_gain", SoundSensitivity.gain.toDouble())
            .put("possible_match_gate_level", PossibleMatchGate.level.toDouble())
            .put("possible_match_gate_profile", PossibleMatchGate.profile())
            .put("quality", qualityJson(snapshot.quality))
            .put("acoustic_signature", signatureJson(snapshot.signature))
            .put("signal_gate", signalGateJson(snapshot.signalAssessment))
            .put("context", contextJson(snapshot.observationContext))
        eventFile.appendText(event.toString() + "\n")
        _count.value = _count.value + 1
        return event.getString("event_id")
    }

    @Synchronized
    fun clear() {
        if (eventFile.exists()) eventFile.delete()
        _count.value = 0
    }

    @Synchronized
    fun progressSnapshot(): QaProgressSnapshot {
        val lines = if (eventFile.exists()) eventFile.readLines().filter(String::isNotBlank) else emptyList()
        var correct = 0
        var incorrect = 0
        var noise = 0
        var noiseTargetTests = 0
        var exploratoryTests = 0
        val speciesCounters = linkedMapOf<String, IntArray>()

        lines.forEach { line ->
            val event = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            when (event.optString("verdict")) {
                "correct" -> correct += 1
                "incorrect" -> incorrect += 1
                "noise" -> noise += 1
            }

            val target = event.optJSONObject("expected_target")
            val type = target?.optString("type").orEmpty()
            val label = target?.optString("label").orEmpty()
            when (type) {
                "species", "species_inferred_from_correct_tap" -> {
                    if (label.isBlank()) {
                        exploratoryTests += 1
                    } else {
                        val counts = speciesCounters.getOrPut(label) { IntArray(4) }
                        counts[0] += 1
                        when (event.optString("verdict")) {
                            "correct" -> counts[1] += 1
                            "incorrect" -> counts[2] += 1
                            "noise" -> counts[3] += 1
                        }
                    }
                }
                "noise" -> noiseTargetTests += 1
                else -> exploratoryTests += 1
            }
        }

        val byTarget = speciesCounters.mapValues { (label, c) ->
            QaSpeciesProgress(
                targetKey = label,
                total = c[0],
                correct = c[1],
                incorrect = c[2],
                noise = c[3]
            )
        }
        return QaProgressSnapshot(
            totalFeedback = lines.size,
            correct = correct,
            incorrect = incorrect,
            noise = noise,
            noiseTargetTests = noiseTargetTests,
            exploratoryTests = exploratoryTests,
            byTarget = byTarget
        )
    }

    @Synchronized
    fun exportBundle(): File {
        val exportDir = File(context.cacheDir, "test_feedback_exports").apply { mkdirs() }
        exportDir.listFiles()?.filter { it.name.startsWith("stridulate-test-feedback-") }?.forEach { it.delete() }
        val stamp = System.currentTimeMillis()
        val zip = File(exportDir, "stridulate-test-feedback-$stamp.zip")
        val lines = if (eventFile.exists()) eventFile.readLines().filter(String::isNotBlank) else emptyList()
        val csv = buildCsv(lines)
        val readme = buildString {
            appendLine("Stridulate beta QA feedback export")
            appendLine("App version: $appVersionName ($appVersionCode)")
            appendLine("Primary model: frozen J.1 / Perch 2.0")
            appendLine("Comparison model: old Stridulate Epoch-19 / 67-class TFLite when bundled")
            appendLine("Feedback rows: ${lines.size}")
            appendLine()
            appendLine("feedback.jsonl is the lossless machine-readable log.")
            appendLine("feedback.csv is a flattened view for spreadsheets.")
            appendLine("Exact GPS coordinates are not exported by this QA logger.")
            appendLine("J.1 values are model scores, not literal probabilities of correctness.")
            appendLine("The log includes raw Top 3 J.1 scores and, when available, the old Stridulate Top 3 from the SAME sensitivity-adjusted PCM.")
            appendLine("It also includes the class-agnostic raw-audio signal gate, possible-match gate, and acoustic diagnostics so silence/noise false positives can be analyzed later.")
            appendLine("CORRECT/INCORRECT refer to the selected candidate; expected_target records the test target when one was set.")
        }
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("feedback.jsonl"))
            lines.forEach { out.write((it + "\n").toByteArray()) }
            out.closeEntry()
            out.putNextEntry(ZipEntry("feedback.csv"))
            out.write(csv.toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("README.txt"))
            out.write(readme.toByteArray())
            out.closeEntry()
        }
        return zip
    }

    private fun candidateJson(candidate: Candidate): JSONObject = JSONObject()
        .put("label", candidate.label)
        .putNullable("common", candidate.species?.common)
        .putNullable("scientific", candidate.species?.latin)
        .put("score", candidate.audioConfidence)
        .put("score_kind", "j1_model_score_not_probability")
        .putNullable("threshold", candidate.acceptanceThreshold)
        .putNullable("gate_passed", candidate.evidenceAccepted)
        .putNullable("call_profile_passed", candidate.callCompatibilityPassed)
        .putNullable("call_profile_summary", candidate.callCompatibilitySummary)
        .put("tier", candidate.reliability.tier.name)

    private fun legacyCandidateJson(candidate: Candidate): JSONObject = JSONObject()
        .put("label", candidate.label)
        .putNullable("common", candidate.species?.common)
        .putNullable("scientific", candidate.species?.latin)
        .put("score", candidate.audioConfidence)
        .put("score_kind", "legacy_temperature_scaled_softmax")
        .putNullable("base_gate_passed", candidate.evidenceAccepted)
        .putNullable("threshold", candidate.acceptanceThreshold)
        .put("tier", candidate.reliability.tier.name)

    private fun qualityJson(quality: RecordingQuality?): Any = quality?.let {
        JSONObject()
            .put("grade", it.grade.name)
            .put("score", it.score)
            .put("duration_seconds", it.durationSeconds)
            .put("signal_clarity_score", it.signalClarityScore)
            .put("active_signal_percent", it.activeSignalPercent)
            .put("clipping_percent", it.clippingPercent)
            .put("possible_overlap", it.possibleOverlap)
            .put("warnings", JSONArray(it.warnings))
    } ?: JSONObject.NULL

    private fun signatureJson(signature: MeasuredSignature?): Any = signature?.let {
        JSONObject()
            .put("insect_likelihood", it.insectLikelihood)
            .put("peak_freq_khz", it.peakFreqKHz)
            .put("bandwidth_khz", it.bandwidthKHz)
            .put("tonality", it.tonality)
            .put("low_freq_ratio", it.lowFreqRatio)
            .put("peak_stability", it.peakStability)
            .put("broadband", it.broadband)
    } ?: JSONObject.NULL


    private fun signalGateJson(assessment: InsectSignalAssessment?): Any = assessment?.let {
        JSONObject()
            .put("passed", it.passed)
            .put("score", it.score)
            .put("reason", it.reason)
            .put("raw_rms", it.rawRms)
            .put("raw_peak", it.rawPeak)
            .put("temporal_contrast_db", it.temporalContrastDb)
            .put("insect_likelihood", it.insectLikelihood)
            .put("quality_score", it.qualityScore)
            .put("peak_freq_khz", it.peakFreqKHz)
            .put("tonality", it.tonality)
            .put("low_freq_ratio", it.lowFreqRatio)
            .put("peak_stability", it.peakStability)
            .put("pulse_regularity", it.pulseRegularity)
    } ?: JSONObject.NULL

    private fun contextJson(context: ObservationContext?): Any = context?.let {
        JSONObject()
            .put("enabled", it.enabled)
            .putNullable("region", it.region.displayName.takeUnless { name -> name == "Region unavailable" })
            .putNullable("temperature_f", it.temperatureF)
            .put("season", it.seasonLabel)
            .put("time_bucket", it.dayPeriodLabel)
    } ?: JSONObject.NULL

    private fun buildCsv(lines: List<String>): String {
        val header = listOf(
            "recorded_at_ms", "source", "verdict", "expected_target", "selected_label", "selected_common",
            "selected_score_pct", "selected_threshold_pct", "selected_gate_passed",
            "top1_label", "top1_score_pct", "top1_threshold_pct", "top1_gate_passed",
            "top2_label", "top2_score_pct", "top2_threshold_pct", "top2_gate_passed",
            "top3_label", "top3_score_pct", "top3_threshold_pct", "top3_gate_passed",
            "old1_label", "old1_score_pct", "old1_base_gate_passed",
            "old2_label", "old2_score_pct", "old2_base_gate_passed",
            "old3_label", "old3_score_pct", "old3_base_gate_passed",
            "window_start_seconds", "window_end_seconds", "analysis_gain", "possible_match_gate_level", "possible_match_gate_profile",
            "quality_grade", "quality_score", "insect_likelihood", "peak_freq_khz", "tonality", "low_freq_ratio", "peak_stability",
            "signal_gate_passed", "signal_gate_score", "signal_gate_reason", "raw_rms", "temporal_contrast_db",
            "region", "temperature_f", "session_key"
        )
        val rows = lines.mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }.map { event ->
            val target = event.optJSONObject("expected_target")?.optString("label").orEmpty()
            val selected = event.optJSONObject("selected_candidate")
            val top = event.optJSONArray("top3") ?: JSONArray()
            val legacy = event.optJSONArray("legacy_top3") ?: JSONArray()
            fun candidate(index: Int): JSONObject? = top.optJSONObject(index)
            fun legacyCandidate(index: Int): JSONObject? = legacy.optJSONObject(index)
            fun pct(value: Double): String = if (value.isFinite()) String.format(Locale.US, "%.1f", value * 100.0) else ""
            fun threshold(c: JSONObject?): String = c?.takeIf { it.has("threshold") && !it.isNull("threshold") }?.optDouble("threshold")?.let(::pct).orEmpty()
            fun passed(c: JSONObject?): String = c?.takeIf { it.has("gate_passed") && !it.isNull("gate_passed") }?.optBoolean("gate_passed")?.toString().orEmpty()
            val q = event.optJSONObject("quality")
            val sig = event.optJSONObject("acoustic_signature")
            val signalGate = event.optJSONObject("signal_gate")
            val ctx = event.optJSONObject("context")
            val values = mutableListOf(
                event.optLong("recorded_at_ms").toString(), event.optString("source"), event.optString("verdict"), target,
                selected?.optString("label").orEmpty(), selected?.optString("common").orEmpty(),
                selected?.optDouble("score")?.let(::pct).orEmpty(), threshold(selected), passed(selected)
            )
            for (i in 0..2) {
                val c = candidate(i)
                values += c?.optString("label").orEmpty()
                values += c?.optDouble("score")?.let(::pct).orEmpty()
                values += threshold(c)
                values += passed(c)
            }
            for (i in 0..2) {
                val c = legacyCandidate(i)
                values += c?.optString("label").orEmpty()
                values += c?.optDouble("score")?.let(::pct).orEmpty()
                values += c?.takeIf { it.has("base_gate_passed") && !it.isNull("base_gate_passed") }
                    ?.optBoolean("base_gate_passed")?.toString().orEmpty()
            }
            values += event.optNullableString("window_start_seconds")
            values += event.optNullableString("window_end_seconds")
            values += event.optDouble("analysis_gain", 1.0).toString()
            values += event.optDouble("possible_match_gate_level", PossibleMatchGate.DEFAULT_LEVEL.toDouble()).toString()
            values += event.optString("possible_match_gate_profile")
            values += q?.optString("grade").orEmpty()
            values += q?.optInt("score")?.toString().orEmpty()
            values += sig?.optDouble("insect_likelihood")?.toString().orEmpty()
            values += sig?.optDouble("peak_freq_khz")?.toString().orEmpty()
            values += sig?.optDouble("tonality")?.toString().orEmpty()
            values += sig?.optDouble("low_freq_ratio")?.toString().orEmpty()
            values += sig?.optDouble("peak_stability")?.toString().orEmpty()
            values += signalGate?.optBoolean("passed")?.toString().orEmpty()
            values += signalGate?.optInt("score")?.toString().orEmpty()
            values += signalGate?.optString("reason").orEmpty()
            values += signalGate?.optDouble("raw_rms")?.toString().orEmpty()
            values += signalGate?.optDouble("temporal_contrast_db")?.toString().orEmpty()
            values += ctx?.optString("region").orEmpty()
            values += ctx?.takeIf { it.has("temperature_f") && !it.isNull("temperature_f") }?.optDouble("temperature_f")?.toString().orEmpty()
            values += event.optString("session_key")
            values.joinToString(",", transform = ::csv)
        }
        return buildString {
            appendLine(header.joinToString(","))
            rows.forEach(::appendLine)
        }
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
    private fun countLines(): Int = runCatching { if (eventFile.exists()) eventFile.useLines { it.count() } else 0 }.getOrDefault(0)
    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = apply { put(key, value ?: JSONObject.NULL) }
    private fun JSONObject.optNullableString(key: String): String = if (!has(key) || isNull(key)) "" else optString(key)

    companion object {
        const val TARGET_NOISE = "__noise__"
        private const val KEY_TARGET = "target_key"
    }
}
