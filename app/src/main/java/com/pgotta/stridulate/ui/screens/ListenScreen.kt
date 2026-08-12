package com.pgotta.stridulate.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgotta.stridulate.audio.InsectSignalAssessment
import com.pgotta.stridulate.audio.PossibleMatchGate
import com.pgotta.stridulate.audio.SoundSensitivity
import com.pgotta.stridulate.classifier.Candidate
import com.pgotta.stridulate.data.Species
import com.pgotta.stridulate.qa.FeedbackVerdict
import com.pgotta.stridulate.ui.Detection
import com.pgotta.stridulate.ui.components.CandidateFeedbackButtons
import com.pgotta.stridulate.ui.components.PrimaryButton
import com.pgotta.stridulate.ui.components.RealSpectrogram
import com.pgotta.stridulate.ui.components.TestFeedbackPanel
import com.pgotta.stridulate.ui.theme.Amber
import com.pgotta.stridulate.ui.theme.Biolume
import com.pgotta.stridulate.ui.theme.Danger
import com.pgotta.stridulate.ui.theme.Fraunces
import com.pgotta.stridulate.ui.theme.Inter
import com.pgotta.stridulate.ui.theme.JetBrainsMono
import com.pgotta.stridulate.ui.theme.Line
import com.pgotta.stridulate.ui.theme.Mute
import com.pgotta.stridulate.ui.theme.Panel
import com.pgotta.stridulate.ui.theme.Panel2
import com.pgotta.stridulate.ui.theme.Parch
import com.pgotta.stridulate.ui.theme.ParchDim
import com.pgotta.stridulate.ui.theme.SpecBg
import kotlin.math.roundToInt

/**
 * Live beta comparison screen.
 *
 * Frozen J.1 / Perch remains the production detector. The old Epoch-19 model is
 * shown only as a shadow comparator and never drives saved/accepted detections.
 */
@Composable
fun ListenScreen(
    spectrogramColumns: List<FloatArray>,
    loudness: Float,
    candidates: List<Candidate>,
    legacyCandidates: List<Candidate>,
    legacyComparisonAvailable: Boolean,
    signalAssessment: InsectSignalAssessment?,
    detections: List<Detection>,
    elapsedSeconds: Double,
    testSpecies: List<Species>,
    testTargetKey: String?,
    testFeedbackCount: Int,
    onSetTestTargetSpecies: (Species) -> Unit,
    onSetTestTargetNoise: () -> Unit,
    onClearTestTarget: () -> Unit,
    onExportTestFeedback: () -> Unit,
    onClearTestFeedback: () -> Unit,
    onMarkCurrentNoise: () -> Unit,
    onPossibleMatchSensitivityChanged: (Float) -> Unit,
    onTestFeedback: (String, FeedbackVerdict) -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var sensitivity by remember { mutableStateOf(SoundSensitivity.initialize(context)) }
    var possibleMatchSensitivity by remember { mutableStateOf(PossibleMatchGate.initialize(context)) }
    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "s"
    )

    val secondsPerColumn = 4096.0 / 48_000.0
    val visibleSpan = (spectrogramColumns.size * secondsPerColumn).coerceAtLeast(0.1)
    val visibleStart = (elapsedSeconds - visibleSpan).coerceAtLeast(0.0)
    val acceptedWindows = detections.sumOf { it.occurrences.size }
    val heardNow = signalAssessment?.passed == true && (candidates.isNotEmpty() || legacyCandidates.isNotEmpty())

    // HEARD NOW markers are visual timeline markers, separate from accepted/logged
    // detections. Each fresh qualifying analysis drops a marker at the live edge.
    // Once placed, that timestamp remains fixed while the spectrogram window scrolls,
    // so the marker travels left with the sound instead of being pinned to the edge.
    val heardMarkerTimes = remember { mutableStateListOf<Double>() }
    LaunchedEffect(signalAssessment, candidates, legacyCandidates) {
        if (heardNow) {
            val markerTime = elapsedSeconds
            val previous = heardMarkerTimes.lastOrNull()
            // Slider re-evaluation can emit another state for the same cached window.
            // Avoid creating duplicate lines for those near-simultaneous updates.
            if (previous == null || markerTime - previous >= 1.0) {
                heardMarkerTimes.add(markerTime)
                while (heardMarkerTimes.size > 100) heardMarkerTimes.removeAt(0)
            }
        }
    }

    val activeMarkerTime = heardMarkerTimes.lastOrNull()?.takeIf { heardNow }
    val historicalHeardTimes = if (activeMarkerTime != null && heardMarkerTimes.isNotEmpty()) {
        heardMarkerTimes.dropLast(1)
    } else {
        heardMarkerTimes.toList()
    }
    val markerFractions = (
        detections.flatMap { detection -> detection.occurrences.map { it.endSeconds } } +
            historicalHeardTimes
        )
        .distinct()
        .filter { it >= visibleStart }
        .map { ((it - visibleStart) / visibleSpan).toFloat().coerceIn(0f, 1f) }
    val activeMarkerFraction = activeMarkerTime
        ?.takeIf { it >= visibleStart }
        ?.let { ((it - visibleStart) / visibleSpan).toFloat().coerceIn(0f, 1f) }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        AppBarRow(
            title = "Listening",
            sub = if (legacyComparisonAvailable) "NEW J.1/PERCH 88 · OLD STRIDULATE 67" else "FROZEN J.1 · PERCH 2.0 · 88 SPECIES",
            onBack = onCancel,
            status = "recording",
            statusOn = true
        )

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).scale(pulseScale).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Danger, Color(0xFF7A221A)))),
                contentAlignment = Alignment.Center
            ) { Box(Modifier.size(11.dp).clip(CircleShape).background(Color.White)) }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Listening for singing insects", fontFamily = Fraunces, fontSize = 18.sp, color = Parch)
                Text(
                    "LIVE TUNING · ${elapsedSeconds.toInt()} SEC",
                    fontFamily = JetBrainsMono,
                    fontSize = 9.sp,
                    color = Mute,
                    letterSpacing = 0.7.sp
                )
            }
        }

        Spacer(Modifier.height(7.dp))
        Row(
            Modifier.fillMaxWidth().height(224.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clip(RoundedCornerShape(13.dp))
                    .background(SpecBg)
                    .border(BorderStroke(1.dp, if (heardNow) Amber.copy(alpha = 0.80f) else Line), RoundedCornerShape(13.dp))
            ) {
                RealSpectrogram(
                    columns = spectrogramColumns,
                    modifier = Modifier.fillMaxSize(),
                    markerFractions = markerFractions,
                    activeMarkerFraction = if (heardNow) activeMarkerFraction else null
                )
                Text(
                    when {
                        heardNow -> "● HEARD NOW"
                        elapsedSeconds < 5.0 -> "FIRST ANALYSIS AFTER ~5 SEC"
                        else -> "NO INSECT ABOVE CURRENT GATE"
                    },
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .clip(RoundedCornerShape(5.dp)).background(Color(0xD008100E))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    fontFamily = JetBrainsMono,
                    fontSize = 7.5.sp,
                    color = if (heardNow) Amber else Mute
                )
            }

            Spacer(Modifier.width(5.dp))
            FineVerticalControl(
                title = "GAIN",
                value = sensitivity,
                valueText = if (sensitivity <= 0.001f) "1.0×" else "${"%.1f".format(SoundSensitivity.gain)}×",
                topHint = "4×",
                bottomHint = "1×",
                accent = Biolume,
                onValueChange = { value ->
                    sensitivity = value.coerceIn(0f, 1f)
                    SoundSensitivity.set(context, sensitivity)
                }
            )
            Spacer(Modifier.width(4.dp))
            FineVerticalControl(
                title = "GATE",
                value = possibleMatchSensitivity,
                valueText = "${(possibleMatchSensitivity * 100f).roundToInt()}",
                topHint = "SENS",
                bottomHint = "STRICT",
                accent = Amber,
                onValueChange = { value ->
                    possibleMatchSensitivity = value.coerceIn(0f, 1f)
                    onPossibleMatchSensitivityChanged(possibleMatchSensitivity)
                }
            )
        }

        Spacer(Modifier.height(5.dp))
        TestFeedbackPanel(
            species = testSpecies,
            targetKey = testTargetKey,
            feedbackCount = testFeedbackCount,
            onSetSpeciesTarget = onSetTestTargetSpecies,
            onSetNoiseTarget = onSetTestTargetNoise,
            onClearTarget = onClearTestTarget,
            onExport = onExportTestFeedback,
            onClearLog = onClearTestFeedback,
            onMarkCurrentNoise = onMarkCurrentNoise
        )

        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "LIVE MODEL COMPARISON",
                fontFamily = JetBrainsMono,
                fontSize = 9.5.sp,
                color = Amber,
                letterSpacing = 1.2.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                "GATE ${(possibleMatchSensitivity * 100f).roundToInt()} · ${PossibleMatchGate.profile(possibleMatchSensitivity)}",
                fontFamily = JetBrainsMono,
                fontSize = 8.sp,
                color = Mute
            )
        }
        Spacer(Modifier.height(4.dp))

        if (!heardNow) {
            Box(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(Panel).border(BorderStroke(1.dp, Line), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when {
                        elapsedSeconds < 5.0 -> "Listening… first model comparison starts after ~5 seconds."
                        signalAssessment?.passed == false -> signalAssessment.reason
                        else -> "No stable possible match above the current gate."
                    },
                    fontFamily = Inter,
                    fontSize = 12.sp,
                    color = ParchDim,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(14.dp)
                )
            }
        } else {
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CompactModelColumn(
                        title = "NEW · J.1/PERCH 88",
                        candidates = candidates,
                        heardNow = heardNow,
                        legacy = false,
                        modifier = Modifier.weight(1f)
                    )
                    CompactModelColumn(
                        title = "OLD · STRIDULATE 67",
                        candidates = legacyCandidates,
                        heardNow = heardNow,
                        legacy = true,
                        unavailable = !legacyComparisonAvailable,
                        modifier = Modifier.weight(1f)
                    )
                }
                val topNew = candidates.firstOrNull()
                if (topNew != null) {
                    Spacer(Modifier.height(5.dp))
                    CandidateFeedbackButtons { verdict -> onTestFeedback(topNew.label, verdict) }
                    Text(
                        "QA buttons label the NEW top match; the OLD Top 3 from the same window is saved beside it for head-to-head analysis.",
                        fontFamily = Inter,
                        fontSize = 8.5.sp,
                        color = Mute,
                        lineHeight = 11.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                Text(
                    "$acceptedWindows J.1 gate-passing window${if (acceptedWindows == 1) "" else "s"} logged · signal ${signalAssessment?.score ?: 0}/100",
                    fontFamily = JetBrainsMono,
                    fontSize = 8.sp,
                    color = if (acceptedWindows > 0) Biolume else Mute,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }

        Spacer(Modifier.height(7.dp))
        PrimaryButton(
            "Stop & save to Log",
            onStop,
            Modifier.fillMaxWidth(),
            container = Danger,
            content = Color.White
        )
        Spacer(Modifier.height(9.dp))
    }
}

/**
 * True continuous vertical control with the full rail used as touch travel.
 * This avoids the tiny effective touch range of a rotated horizontal Slider.
 */
@Composable
private fun FineVerticalControl(
    title: String,
    value: Float,
    valueText: String,
    topHint: String,
    bottomHint: String,
    accent: Color,
    onValueChange: (Float) -> Unit
) {
    Column(
        Modifier.width(48.dp).fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(10.dp))
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(valueText, fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 8.sp, color = accent, maxLines = 1)
        Text(topHint, fontFamily = JetBrainsMono, fontSize = 6.5.sp, color = Mute, maxLines = 1)
        Box(
            Modifier.weight(1f).width(40.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        onValueChange((1f - offset.y / h).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            onValueChange((1f - offset.y / h).coerceIn(0f, 1f))
                        },
                        onDrag = { change, _ ->
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            onValueChange((1f - change.position.y / h).coerceIn(0f, 1f))
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2f
                val pad = 8f
                val top = pad
                val bottom = size.height - pad
                val y = bottom - value.coerceIn(0f, 1f) * (bottom - top)
                drawLine(
                    color = Panel2,
                    start = Offset(x, top),
                    end = Offset(x, bottom),
                    strokeWidth = 8f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = accent.copy(alpha = 0.72f),
                    start = Offset(x, y),
                    end = Offset(x, bottom),
                    strokeWidth = 6f,
                    cap = StrokeCap.Round
                )
                drawCircle(color = accent, radius = 8f, center = Offset(x, y))
                drawCircle(color = Parch, radius = 3f, center = Offset(x, y))
            }
        }
        Text(bottomHint, fontFamily = JetBrainsMono, fontSize = 6.5.sp, color = Mute, maxLines = 1)
        Text(title, fontFamily = JetBrainsMono, fontSize = 7.sp, color = ParchDim, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun CompactModelColumn(
    title: String,
    candidates: List<Candidate>,
    heardNow: Boolean,
    legacy: Boolean,
    modifier: Modifier = Modifier,
    unavailable: Boolean = false
) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(Panel)
            .border(BorderStroke(1.dp, if (heardNow && candidates.isNotEmpty()) Amber.copy(alpha = 0.55f) else Line), RoundedCornerShape(10.dp))
            .padding(horizontal = 7.dp, vertical = 6.dp)
    ) {
        Text(
            title,
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Bold,
            fontSize = 7.7.sp,
            color = if (legacy) ParchDim else Biolume,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        when {
            unavailable -> Text(
                "Comparison model not bundled",
                fontFamily = Inter,
                fontSize = 9.sp,
                color = Mute,
                lineHeight = 12.sp
            )
            candidates.isEmpty() -> Text(
                "No match at current gate",
                fontFamily = Inter,
                fontSize = 9.sp,
                color = Mute
            )
            else -> candidates.take(3).forEachIndexed { index, candidate ->
                CompactCandidateRow(index + 1, candidate, heardNow && index == 0, legacy)
            }
        }
    }
}

@Composable
private fun CompactCandidateRow(rank: Int, candidate: Candidate, heardNow: Boolean, legacy: Boolean) {
    val name = candidate.species?.common ?: "Unknown / unsupported"
    val score = (candidate.audioConfidence * 100.0).roundToInt().coerceIn(0, 100)
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (heardNow) Amber.copy(alpha = 0.13f) else Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$rank",
            modifier = Modifier.width(12.dp),
            fontFamily = JetBrainsMono,
            fontSize = 7.5.sp,
            color = if (heardNow) Amber else Mute
        )
        Text(
            name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = Inter,
            fontWeight = if (heardNow) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 9.2.sp,
            color = if (heardNow) Parch else ParchDim
        )
        Text(
            if (legacy) "$score%" else "$score",
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            color = if (heardNow) Amber else Parch
        )
    }
}
