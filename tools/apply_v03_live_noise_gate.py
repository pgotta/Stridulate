from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if old not in text:
        raise SystemExit(f"expected block not found in {path}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1))


# -----------------------------------------------------------------------------
# New persistent user-controlled possible-match display gate.
# -----------------------------------------------------------------------------
gate_path = ROOT / "app/src/main/java/com/pgotta/stridulate/audio/PossibleMatchGate.kt"
gate_path.write_text('''package com.pgotta.stridulate.audio

import android.content.Context
import com.pgotta.stridulate.classifier.Candidate

/**
 * User-controlled filter for *visible* below-threshold possible matches.
 *
 * This does NOT change frozen J.1 acceptance thresholds or accepted-call logging.
 * It prevents the live research UI from being forced to show arbitrary species for
 * silence, broadband noise, speech, HVAC, and other weak/non-insect windows.
 *
 * Level 0 = strict, level 1 = sensitive. The default is deliberately balanced and
 * requires an insect-like acoustic signature plus recurrence across two windows.
 */
object PossibleMatchGate {
    private const val PREFS = "possible_match_gate_v1"
    private const val KEY_LEVEL = "level"
    const val DEFAULT_LEVEL = 0.35f

    @Volatile private var initialized = false
    @Volatile private var _level = DEFAULT_LEVEL

    val level: Float get() = _level

    @Synchronized
    fun initialize(context: Context): Float {
        if (!initialized) {
            _level = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_LEVEL, DEFAULT_LEVEL)
                .coerceIn(0f, 1f)
            initialized = true
        }
        return _level
    }

    @Synchronized
    fun set(context: Context, value: Float) {
        val clean = value.coerceIn(0f, 1f)
        _level = clean
        initialized = true
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_LEVEL, clean).apply()
    }

    fun profile(level: Float = _level): String = when {
        level < 0.25f -> "STRICT"
        level < 0.65f -> "BALANCED"
        else -> "SENSITIVE"
    }

    fun requiredConsecutiveWindows(level: Float = _level): Int = when {
        level < 0.25f -> 3
        level < 0.75f -> 2
        else -> 1
    }

    fun minimumInsectLikelihood(level: Float = _level): Double =
        (0.68 - 0.28 * level.coerceIn(0f, 1f)).coerceIn(0.40, 0.68)

    fun minimumQualityScore(level: Float = _level): Int =
        (60.0 - 30.0 * level.coerceIn(0f, 1f)).toInt().coerceIn(30, 60)

    fun minimumEvidenceFraction(level: Float = _level): Double =
        (0.72 - 0.30 * level.coerceIn(0f, 1f)).coerceIn(0.42, 0.72)

    fun minimumAbsoluteScore(level: Float = _level): Double =
        (0.30 - 0.18 * level.coerceIn(0f, 1f)).coerceIn(0.12, 0.30)

    /** True when this candidate is useful enough to show as a live *possible* match. */
    fun allows(
        candidate: Candidate,
        quality: RecordingQuality,
        signature: MeasuredSignature,
        consecutiveWindows: Int,
        level: Float = _level
    ): Boolean {
        if (candidate.species == null) return false
        if (quality.blockingReason != null) return false
        if (quality.score < minimumQualityScore(level)) return false
        if (signature.insectLikelihood < minimumInsectLikelihood(level)) return false
        if (consecutiveWindows < requiredConsecutiveWindows(level)) return false

        val score = candidate.audioConfidence.coerceIn(0.0, 1.0)
        if (score < minimumAbsoluteScore(level)) return false

        val referenceThreshold = candidate.acceptanceThreshold?.coerceIn(0.20, 1.0) ?: 0.70
        val evidenceFraction = score / referenceThreshold
        if (evidenceFraction < minimumEvidenceFraction(level)) return false

        return true
    }
}
''')

# -----------------------------------------------------------------------------
# ViewModel: maintain recurrence and keep raw Top 3 for QA while filtering display.
# -----------------------------------------------------------------------------
vm = ROOT / "app/src/main/java/com/pgotta/stridulate/ui/StridulateViewModel.kt"
replace_once(vm,
    "import com.pgotta.stridulate.audio.MicRecorder\n",
    "import com.pgotta.stridulate.audio.MicRecorder\nimport com.pgotta.stridulate.audio.PossibleMatchGate\n"
)
replace_once(vm,
    "    private var lastLiveFeedbackSnapshot: TestFeedbackSnapshot? = null\n",
    "    private var lastLiveFeedbackSnapshot: TestFeedbackSnapshot? = null\n"
    "    private val liveCandidateStreaks = mutableMapOf<String, Int>()\n"
    "    private var previousRawLiveLabels: Set<String> = emptySet()\n"
)
replace_once(vm,
    "            candidates = result.candidates.take(3),\n"
    "            quality = result.recordingQuality,\n"
    "            observationContext = result.observationContext\n",
    "            candidates = result.candidates.take(3),\n"
    "            quality = result.recordingQuality,\n"
    "            signature = result.signature,\n"
    "            observationContext = result.observationContext\n"
)
replace_once(vm,
    "        _liveCandidates.value = emptyList()\n"
    "        lastLiveFeedbackSnapshot = null\n"
    "        _liveDetections.value = emptyList()\n",
    "        _liveCandidates.value = emptyList()\n"
    "        lastLiveFeedbackSnapshot = null\n"
    "        liveCandidateStreaks.clear()\n"
    "        previousRawLiveLabels = emptySet()\n"
    "        PossibleMatchGate.initialize(getApplication<Application>())\n"
    "        _liveDetections.value = emptyList()\n"
)
replace_once(vm,
'''        // Discovery view: always expose the three strongest supported J.1 evidence scores.
        // The validated acceptance gate still controls logging/"likely" wording, but it never
        // erases a useful candidate from the live screen. This is especially important while
        // field-testing difficult classes such as Columbian Trig and Oblong-winged Katydid.
        _liveCandidates.value = result.candidates
            .asSequence()
            .filter { it.species != null }
            .sortedByDescending { it.audioConfidence }
            .take(3)
            .toList()
        val feedbackNow = _recordingElapsedSeconds.value
        lastLiveFeedbackSnapshot = TestFeedbackSnapshot(
            source = "live",
            sessionKey = recordingStartedAtMillis.toString(),
            windowStartSeconds = (feedbackNow - LIVE_WINDOW_SECONDS).coerceAtLeast(0.0),
            windowEndSeconds = feedbackNow,
            candidates = _liveCandidates.value,
            quality = result.quality,
            observationContext = environment.value
        )
''',
'''        // Discovery view: preserve useful below-J.1 candidates, but do not force arbitrary
        // species onto silence/noise. The user-controlled PossibleMatchGate is deliberately
        // separate from frozen J.1 acceptance: it only controls what the live Top 3 displays.
        val rawTopThree = result.candidates
            .asSequence()
            .filter { it.species != null }
            .sortedByDescending { it.audioConfidence }
            .take(3)
            .toList()
        val rawLabels = rawTopThree.map { it.label }.toSet()
        rawTopThree.forEach { candidate ->
            liveCandidateStreaks[candidate.label] =
                if (candidate.label in previousRawLiveLabels) {
                    (liveCandidateStreaks[candidate.label] ?: 0) + 1
                } else {
                    1
                }
        }
        liveCandidateStreaks.keys.retainAll(rawLabels)
        previousRawLiveLabels = rawLabels

        _liveCandidates.value = rawTopThree.filter { candidate ->
            PossibleMatchGate.allows(
                candidate = candidate,
                quality = result.quality,
                signature = result.signature,
                consecutiveWindows = liveCandidateStreaks[candidate.label] ?: 1
            )
        }

        val feedbackNow = _recordingElapsedSeconds.value
        lastLiveFeedbackSnapshot = TestFeedbackSnapshot(
            source = "live",
            sessionKey = recordingStartedAtMillis.toString(),
            windowStartSeconds = (feedbackNow - LIVE_WINDOW_SECONDS).coerceAtLeast(0.0),
            windowEndSeconds = feedbackNow,
            candidates = rawTopThree,
            quality = result.quality,
            signature = result.signature,
            observationContext = environment.value
        )
''')
replace_once(vm,
    "            _liveCandidates.value = emptyList()\n"
    "            lastLiveFeedbackSnapshot = null\n"
    "            _ui.value = UiState.Idle\n",
    "            _liveCandidates.value = emptyList()\n"
    "            lastLiveFeedbackSnapshot = null\n"
    "            liveCandidateStreaks.clear()\n"
    "            previousRawLiveLabels = emptySet()\n"
    "            _ui.value = UiState.Idle\n"
)
replace_once(vm,
    "        _liveCandidates.value = emptyList()\n"
    "        lastLiveFeedbackSnapshot = null\n"
    "        _ui.value = UiState.Idle\n",
    "        _liveCandidates.value = emptyList()\n"
    "        lastLiveFeedbackSnapshot = null\n"
    "        liveCandidateStreaks.clear()\n"
    "        previousRawLiveLabels = emptySet()\n"
    "        _ui.value = UiState.Idle\n"
)

# -----------------------------------------------------------------------------
# Listen screen: separate display gate slider from analysis gain.
# -----------------------------------------------------------------------------
listen = ROOT / "app/src/main/java/com/pgotta/stridulate/ui/screens/ListenScreen.kt"
replace_once(listen,
    "import com.pgotta.stridulate.audio.SoundSensitivity\n",
    "import com.pgotta.stridulate.audio.PossibleMatchGate\nimport com.pgotta.stridulate.audio.SoundSensitivity\n"
)
replace_once(listen,
    "    val context = LocalContext.current\n"
    "    var sensitivity by remember { mutableStateOf(SoundSensitivity.initialize(context)) }\n",
    "    val context = LocalContext.current\n"
    "    var sensitivity by remember { mutableStateOf(SoundSensitivity.initialize(context)) }\n"
    "    var possibleMatchSensitivity by remember { mutableStateOf(PossibleMatchGate.initialize(context)) }\n"
)
replace_once(listen,
    '                    "FIRST TOP 3 AFTER ~5 SEC · ${elapsedSeconds.toInt()} SEC",\n',
    '                    "FIRST CHECK AFTER ~5 SEC · ${elapsedSeconds.toInt()} SEC",\n'
)
replace_once(listen,
'''            Text(
                "Boosts analysis and visualization for quiet callers. The saved WAV remains original microphone audio.",
                fontFamily = Inter,
                fontSize = 10.sp,
                color = Mute,
                lineHeight = 14.sp
            )
        }
''',
'''            Text(
                "Boosts analysis and visualization for quiet callers. The saved WAV remains original microphone audio.",
                fontFamily = Inter,
                fontSize = 10.sp,
                color = Mute,
                lineHeight = 14.sp
            )

            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Possible-match gate", fontFamily = Fraunces, fontSize = 14.sp, color = Parch)
                Spacer(Modifier.weight(1f))
                Text(
                    PossibleMatchGate.profile(possibleMatchSensitivity),
                    fontFamily = JetBrainsMono,
                    fontSize = 9.5.sp,
                    color = Amber
                )
            }
            Slider(
                value = possibleMatchSensitivity,
                onValueChange = { value ->
                    possibleMatchSensitivity = value.coerceIn(0f, 1f)
                    PossibleMatchGate.set(context, possibleMatchSensitivity)
                },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth()) {
                Text("STRICT", fontFamily = JetBrainsMono, fontSize = 8.sp, color = Mute)
                Spacer(Modifier.weight(1f))
                Text("SENSITIVE", fontFamily = JetBrainsMono, fontSize = 8.sp, color = Mute)
            }
            Text(
                "Filters silence/noise and unstable guesses from Live Possible Matches. It does not change the frozen J.1 accepted-call thresholds.",
                fontFamily = Inter,
                fontSize = 9.5.sp,
                color = Mute,
                lineHeight = 13.sp
            )
        }
''')
replace_once(listen,
'''        Text(
            "Top 3 J.1 evidence scores are always shown. The gate only controls accepted/logged calls.",
            fontFamily = Inter,
            fontSize = 10.5.sp,
            color = Mute,
            lineHeight = 14.sp
        )
''',
'''        Text(
            "Possible matches are shown only when the current window clears your display gate; frozen J.1 still controls accepted/logged calls.",
            fontFamily = Inter,
            fontSize = 10.5.sp,
            color = Mute,
            lineHeight = 14.sp
        )
''')
replace_once(listen,
'''                    if (elapsedSeconds < 5.0) {
                        "Listening…\\nTop 3 appears after the first ~5 seconds."
                    } else {
                        "Analyzing the current rolling window…"
                    },
''',
'''                    if (elapsedSeconds < 5.0) {
                        "Listening…\\nFirst analysis starts after ~5 seconds."
                    } else {
                        "No insect-like match above the current gate.\\nMove the Possible-match gate toward SENSITIVE to inspect weaker candidates."
                    },
''')

# -----------------------------------------------------------------------------
# QA panel: collapsed one-line default.
# -----------------------------------------------------------------------------
qa_controls = ROOT / "app/src/main/java/com/pgotta/stridulate/ui/components/TestFeedbackControls.kt"
replace_once(qa_controls,
    "import androidx.compose.runtime.remember\n",
    "import androidx.compose.runtime.remember\nimport androidx.compose.runtime.saveable.rememberSaveable\n"
)
replace_once(qa_controls,
    "import androidx.compose.ui.text.font.FontWeight\n",
    "import androidx.compose.ui.text.font.FontWeight\nimport androidx.compose.ui.text.style.TextOverflow\n"
)
replace_once(qa_controls,
    "    var showTargets by remember { mutableStateOf(false) }\n"
    "    var confirmClear by remember { mutableStateOf(false) }\n",
    "    var expanded by rememberSaveable { mutableStateOf(false) }\n"
    "    var showTargets by remember { mutableStateOf(false) }\n"
    "    var confirmClear by remember { mutableStateOf(false) }\n"
)
replace_once(qa_controls,
'''    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, Amber.copy(alpha = 0.45f)), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("QA TEST FEEDBACK", fontFamily = JetBrainsMono, fontSize = 9.5.sp, color = Amber, letterSpacing = 1.2.sp)
            Spacer(Modifier.weight(1f))
            Text("$feedbackCount saved", fontFamily = JetBrainsMono, fontSize = 8.5.sp, color = Mute)
        }
        Spacer(Modifier.height(5.dp))
        Text(
            "Set the insect you are intentionally testing. Then tap Correct / Incorrect / Noise on a visible candidate. Every tap saves the full Top 3 and J.1 gate values.",
            fontFamily = Inter, fontSize = 10.5.sp, color = ParchDim, lineHeight = 14.sp
        )
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction("Target: $targetName", Amber, Modifier.weight(1f)) { showTargets = true }
            SmallAction("Export", Biolume) { onExport() }
            if (feedbackCount > 0) SmallAction("Clear", Danger) { confirmClear = true }
        }
        if (targetKey == null) {
            Spacer(Modifier.height(5.dp))
            Text("Tip: set a target before marking Incorrect, so the log records what it should have been.", fontFamily = Inter, fontSize = 9.5.sp, color = Mute)
        }
    }
''',
'''    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp))
            .border(BorderStroke(1.dp, Amber.copy(alpha = 0.38f)), RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("QA", fontFamily = JetBrainsMono, fontSize = 9.sp, color = Amber, letterSpacing = 1.sp)
            Text(
                " · $targetName",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = Inter,
                fontSize = 10.sp,
                color = ParchDim
            )
            Text(
                "$feedbackCount saved  ${if (expanded) "▲" else "▼"}",
                fontFamily = JetBrainsMono,
                fontSize = 8.sp,
                color = Mute
            )
        }

        if (expanded) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                Text(
                    "Set the insect you are intentionally testing. Then tap Correct / Incorrect / Noise on a visible candidate. Every tap saves the raw Top 3, J.1 values, audio diagnostics and possible-match gate setting.",
                    fontFamily = Inter, fontSize = 10.5.sp, color = ParchDim, lineHeight = 14.sp
                )
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallAction("Target: $targetName", Amber, Modifier.weight(1f)) { showTargets = true }
                    SmallAction("Export", Biolume) { onExport() }
                    if (feedbackCount > 0) SmallAction("Clear", Danger) { confirmClear = true }
                }
                if (targetKey == null) {
                    Spacer(Modifier.height(5.dp))
                    Text("Tip: set a target before marking Incorrect, so the log records what it should have been.", fontFamily = Inter, fontSize = 9.5.sp, color = Mute)
                }
            }
        }
    }
''')

# -----------------------------------------------------------------------------
# QA repository: save display-gate setting and acoustic diagnostics.
# -----------------------------------------------------------------------------
qa_repo = ROOT / "app/src/main/java/com/pgotta/stridulate/qa/TestFeedbackRepository.kt"
replace_once(qa_repo,
    "import com.pgotta.stridulate.audio.RecordingQuality\n",
    "import com.pgotta.stridulate.audio.MeasuredSignature\n"
    "import com.pgotta.stridulate.audio.PossibleMatchGate\n"
    "import com.pgotta.stridulate.audio.RecordingQuality\n"
)
replace_once(qa_repo,
    "    val candidates: List<Candidate>,\n"
    "    val quality: RecordingQuality? = null,\n"
    "    val observationContext: ObservationContext? = null\n",
    "    val candidates: List<Candidate>,\n"
    "    val quality: RecordingQuality? = null,\n"
    "    val signature: MeasuredSignature? = null,\n"
    "    val observationContext: ObservationContext? = null\n"
)
replace_once(qa_repo,
    '            .put("analysis_gain", SoundSensitivity.gain.toDouble())\n'
    '            .put("quality", qualityJson(snapshot.quality))\n'
    '            .put("context", contextJson(snapshot.observationContext))\n',
    '            .put("analysis_gain", SoundSensitivity.gain.toDouble())\n'
    '            .put("possible_match_gate_level", PossibleMatchGate.level.toDouble())\n'
    '            .put("possible_match_gate_profile", PossibleMatchGate.profile())\n'
    '            .put("quality", qualityJson(snapshot.quality))\n'
    '            .put("acoustic_signature", signatureJson(snapshot.signature))\n'
    '            .put("context", contextJson(snapshot.observationContext))\n'
)
replace_once(qa_repo,
    '            appendLine("Exact GPS coordinates are not exported by this QA logger.")\n'
    '            appendLine("CORRECT/INCORRECT refer to the selected candidate; expected_target records the test target when one was set.")\n',
    '            appendLine("Exact GPS coordinates are not exported by this QA logger.")\n'
    '            appendLine("The log includes raw Top 3 scores plus the live possible-match gate and acoustic diagnostics so false positives from silence/noise can be analyzed later.")\n'
    '            appendLine("CORRECT/INCORRECT refer to the selected candidate; expected_target records the test target when one was set.")\n'
)
replace_once(qa_repo,
    "    private fun contextJson(context: ObservationContext?): Any = context?.let {\n",
'''    private fun signatureJson(signature: MeasuredSignature?): Any = signature?.let {
        JSONObject()
            .put("insect_likelihood", it.insectLikelihood)
            .put("peak_freq_khz", it.peakFreqKHz)
            .put("bandwidth_khz", it.bandwidthKHz)
            .put("tonality", it.tonality)
            .put("low_freq_ratio", it.lowFreqRatio)
            .put("peak_stability", it.peakStability)
            .put("broadband", it.broadband)
    } ?: JSONObject.NULL

    private fun contextJson(context: ObservationContext?): Any = context?.let {
''')
replace_once(qa_repo,
    '            "window_start_seconds", "window_end_seconds", "analysis_gain", "quality_grade", "quality_score",\n'
    '            "region", "temperature_f", "session_key"\n',
    '            "window_start_seconds", "window_end_seconds", "analysis_gain", "possible_match_gate_level", "possible_match_gate_profile",\n'
    '            "quality_grade", "quality_score", "insect_likelihood", "peak_freq_khz", "tonality", "low_freq_ratio", "peak_stability",\n'
    '            "region", "temperature_f", "session_key"\n'
)
replace_once(qa_repo,
    '            val q = event.optJSONObject("quality")\n'
    '            val ctx = event.optJSONObject("context")\n',
    '            val q = event.optJSONObject("quality")\n'
    '            val sig = event.optJSONObject("acoustic_signature")\n'
    '            val ctx = event.optJSONObject("context")\n'
)
replace_once(qa_repo,
    '            values += event.optDouble("analysis_gain", 1.0).toString()\n'
    '            values += q?.optString("grade").orEmpty()\n'
    '            values += q?.optInt("score")?.toString().orEmpty()\n'
    '            values += ctx?.optString("region").orEmpty()\n',
    '            values += event.optDouble("analysis_gain", 1.0).toString()\n'
    '            values += event.optDouble("possible_match_gate_level", PossibleMatchGate.DEFAULT_LEVEL.toDouble()).toString()\n'
    '            values += event.optString("possible_match_gate_profile")\n'
    '            values += q?.optString("grade").orEmpty()\n'
    '            values += q?.optInt("score")?.toString().orEmpty()\n'
    '            values += sig?.optDouble("insect_likelihood")?.toString().orEmpty()\n'
    '            values += sig?.optDouble("peak_freq_khz")?.toString().orEmpty()\n'
    '            values += sig?.optDouble("tonality")?.toString().orEmpty()\n'
    '            values += sig?.optDouble("low_freq_ratio")?.toString().orEmpty()\n'
    '            values += sig?.optDouble("peak_stability")?.toString().orEmpty()\n'
    '            values += ctx?.optString("region").orEmpty()\n'
)

# -----------------------------------------------------------------------------
# Documentation and verification.
# -----------------------------------------------------------------------------
qa_doc = ROOT / "QA_TEST_FEEDBACK.md"
qa_doc.write_text(qa_doc.read_text() + '''

## Live possible-match gate

The live Top 3 is no longer forced to display a species for every rolling window. A separate **Possible-match gate** filters weak/non-insect windows using acoustic insect-likelihood, recording quality, recurrence, and evidence strength. The slider runs from **STRICT** to **SENSITIVE** and does not alter the frozen J.1 accepted-call thresholds.

The QA panel is collapsed to one thin row by default. Tap the row to set a target, export, or clear feedback. QA exports now include the possible-match gate setting and acoustic diagnostics (insect-likelihood, peak frequency, tonality, low-frequency ratio, and peak stability).
''')

verifier = ROOT / "verification/verify_final_j_android.py"
replace_once(verifier,
    "for token in ['LIVE POSSIBLE MATCHES','Top 3 J.1 evidence scores are always shown','PASSES J.1 GATE','POSSIBLE · BELOW GATE']:\n",
    "for token in ['LIVE POSSIBLE MATCHES','PASSES J.1 GATE','POSSIBLE · BELOW GATE','Possible-match gate']:\n"
)
replace_once(verifier,
    "for stale in ['guesses below their J.1 evidence threshold are not shown or logged','Low-evidence output stays hidden']:\n",
    "for stale in ['guesses below their J.1 evidence threshold are not shown or logged','Low-evidence output stays hidden','Top 3 J.1 evidence scores are always shown']:\n"
)

(ROOT / "verification/verify_possible_match_gate.py").write_text('''from pathlib import Path

root = Path(__file__).resolve().parents[1]
gate = (root / "app/src/main/java/com/pgotta/stridulate/audio/PossibleMatchGate.kt").read_text()
vm = (root / "app/src/main/java/com/pgotta/stridulate/ui/StridulateViewModel.kt").read_text()
listen = (root / "app/src/main/java/com/pgotta/stridulate/ui/screens/ListenScreen.kt").read_text()
qa = (root / "app/src/main/java/com/pgotta/stridulate/ui/components/TestFeedbackControls.kt").read_text()
repo = (root / "app/src/main/java/com/pgotta/stridulate/qa/TestFeedbackRepository.kt").read_text()

checks = {
    "gate has balanced non-insect filter": "signature.insectLikelihood" in gate and "quality.blockingReason" in gate,
    "gate requires recurrence": "requiredConsecutiveWindows" in gate and "consecutiveWindows" in gate,
    "frozen J1 explicitly untouched": "does NOT change frozen J.1 acceptance" in gate,
    "live candidates filtered through gate": "PossibleMatchGate.allows" in vm,
    "raw top3 retained for QA": "candidates = rawTopThree" in vm,
    "gate slider exposed": "Possible-match gate" in listen and "STRICT" in listen and "SENSITIVE" in listen,
    "QA collapsed by default": "var expanded by rememberSaveable { mutableStateOf(false) }" in qa,
    "QA export captures gate": "possible_match_gate_level" in repo and "insect_likelihood" in repo,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("POSSIBLE MATCH GATE VERIFY FAIL: " + "; ".join(failed))
print("POSSIBLE MATCH GATE VERIFY PASS: noise/silence filter + recurrence + slider + collapsible QA + diagnostics")
''')

workflow = ROOT / ".github/workflows/android-build.yml"
replace_once(workflow,
'''      - name: Verify beta QA feedback contracts
        run: python verification/verify_qa_feedback.py
''',
'''      - name: Verify beta QA feedback contracts
        run: python verification/verify_qa_feedback.py

      - name: Verify possible-match gate and compact QA contracts
        run: python verification/verify_possible_match_gate.py
''')

print("Applied v0.3 live noise-gate + collapsible QA patch")
