package com.pgotta.stridulate.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgotta.stridulate.audio.PossibleMatchGate
import com.pgotta.stridulate.audio.SoundSensitivity
import com.pgotta.stridulate.audio.InsectSignalAssessment
import com.pgotta.stridulate.classifier.Candidate
import com.pgotta.stridulate.data.Species
import com.pgotta.stridulate.qa.FeedbackVerdict
import com.pgotta.stridulate.ui.Detection
import com.pgotta.stridulate.ui.components.CandidateFeedbackButtons
import com.pgotta.stridulate.ui.components.PrimaryButton
import com.pgotta.stridulate.ui.components.TestFeedbackPanel
import com.pgotta.stridulate.ui.components.RealSpectrogram
import com.pgotta.stridulate.ui.components.SpeciesThumbnail
import com.pgotta.stridulate.ui.theme.Amber
import com.pgotta.stridulate.ui.theme.Biolume
import com.pgotta.stridulate.ui.theme.Danger
import com.pgotta.stridulate.ui.theme.Fraunces
import com.pgotta.stridulate.ui.theme.Ink
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
 * Live field-test screen.
 *
 * IMPORTANT: score-ranked possible matches are deliberately independent of the J.1
 * acceptance gate. The gate controls what is logged as an accepted detection; it
 * must never erase a useful candidate from the live research UI.
 */
@Composable
fun ListenScreen(
    spectrogramColumns: List<FloatArray>,
    loudness: Float,
    candidates: List<Candidate>,
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
    val markerFractions = detections.flatMap { it.occurrences }
        .filter { it.endSeconds >= visibleStart }
        .map { ((it.endSeconds - visibleStart) / visibleSpan).toFloat().coerceIn(0f, 1f) }
    val acceptedWindows = detections.sumOf { it.occurrences.size }
    val heardNow = candidates.isNotEmpty() && signalAssessment?.passed == true

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        AppBarRow(
            title = "Listening",
            sub = "FROZEN J.1 · PERCH 2.0 · LIVE TOP 3",
            onBack = onCancel,
            status = "recording",
            statusOn = true
        )

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).scale(pulseScale).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Danger, Color(0xFF7A221A)))),
                contentAlignment = Alignment.Center
            ) { Box(Modifier.size(13.dp).clip(CircleShape).background(Color.White)) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Listening for singing insects", fontFamily = Fraunces, fontSize = 19.sp, color = Parch)
                Text(
                    "FIRST CHECK AFTER ~5 SEC · ${elapsedSeconds.toInt()} SEC",
                    fontFamily = JetBrainsMono,
                    fontSize = 9.5.sp,
                    color = Mute,
                    letterSpacing = 0.7.sp
                )
            }
        }

        Spacer(Modifier.height(9.dp))
        Row(
            Modifier.fillMaxWidth().height(184.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SpecBg)
                    .border(BorderStroke(1.dp, if (heardNow) Amber.copy(alpha = 0.72f) else Line), RoundedCornerShape(14.dp))
            ) {
                RealSpectrogram(
                    columns = spectrogramColumns,
                    modifier = Modifier.fillMaxSize(),
                    markerFractions = markerFractions,
                    activeMarkerFraction = if (heardNow) 0.985f else null
                )
                Text(
                    if (heardNow) "AMBER = HEARD NOW" else "WAITING FOR INSECT-LIKE SIGNAL",
                    modifier = Modifier.align(Alignment.BottomStart).padding(7.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Color(0xCC08100E))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    fontFamily = JetBrainsMono,
                    fontSize = 7.5.sp,
                    color = if (heardNow) Amber else Mute
                )
            }

            Spacer(Modifier.width(6.dp))
            VerticalControlRail(
                title = "GAIN",
                valueText = if (sensitivity <= 0.001f) "1.0×" else "${"%.1f".format(SoundSensitivity.gain)}×",
                value = sensitivity,
                topHint = "4×",
                bottomHint = "OFF",
                accent = Biolume,
                onValueChange = { value ->
                    sensitivity = value.coerceIn(0f, 1f)
                    SoundSensitivity.set(context, sensitivity)
                }
            )
            Spacer(Modifier.width(4.dp))
            VerticalControlRail(
                title = "GATE",
                valueText = PossibleMatchGate.profile(possibleMatchSensitivity).take(4),
                value = possibleMatchSensitivity,
                topHint = "SENS",
                bottomHint = "STRICT",
                accent = Amber,
                onValueChange = { value ->
                    possibleMatchSensitivity = value.coerceIn(0f, 1f)
                    onPossibleMatchSensitivityChanged(possibleMatchSensitivity)
                }
            )
        }
        Spacer(Modifier.height(7.dp))
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

        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "LIVE POSSIBLE MATCHES",
                fontFamily = JetBrainsMono,
                fontSize = 10.sp,
                color = Amber,
                letterSpacing = 1.4.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                signalAssessment?.let { "signal ${it.score}/100" } ?: "level ${(loudness * 100).toInt().coerceIn(0, 100)}%",
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                color = Mute
            )
        }
        Text(
            if (heardNow) "Current window passes the insect/noise gate. Move GATE toward STRICT to hide weak matches immediately."
            else "Move GATE toward SENSITIVE to reveal weaker current-window candidates; toward STRICT to suppress them.",
            fontFamily = Inter,
            fontSize = 9.5.sp,
            color = Mute,
            lineHeight = 12.sp
        )
        Spacer(Modifier.height(5.dp))

        if (candidates.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(13.dp))
                    .background(Panel).border(BorderStroke(1.dp, Line), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (elapsedSeconds < 5.0) {
                        "Listening…\nFirst analysis starts after ~5 seconds."
                    } else if (signalAssessment?.passed == false) {
                        "${signalAssessment.reason}\nRaw J.1 scores are still retained in the QA log."
                    } else {
                        "No stable possible match above the current display gate.\nMove the Insect / noise gate toward SENSITIVE only for weak-caller testing."
                    },
                    fontFamily = Inter,
                    fontSize = 13.sp,
                    color = ParchDim,
                    lineHeight = 19.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                itemsIndexed(candidates.take(3), key = { _, candidate -> candidate.label }) { index, candidate ->
                    LiveCandidateRow(
                        rank = index + 1,
                        candidate = candidate,
                        heardNow = heardNow && index == 0
                    ) { verdict -> onTestFeedback(candidate.label, verdict) }
                }
                item {
                    Text(
                        "$acceptedWindows gate-passing window${if (acceptedWindows == 1) "" else "s"} logged in this recording.",
                        fontFamily = JetBrainsMono,
                        fontSize = 8.5.sp,
                        color = if (acceptedWindows > 0) Biolume else Mute,
                        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(9.dp))
        PrimaryButton(
            "Stop & save to Log",
            onStop,
            Modifier.fillMaxWidth(),
            container = Danger,
            content = Color.White
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun VerticalControlRail(
    title: String,
    valueText: String,
    value: Float,
    topHint: String,
    bottomHint: String,
    accent: Color,
    onValueChange: (Float) -> Unit
) {
    Column(
        Modifier.width(43.dp).fillMaxHeight()
            .clip(RoundedCornerShape(11.dp))
            .background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(11.dp))
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            valueText.uppercase(),
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Bold,
            fontSize = 7.5.sp,
            color = accent,
            maxLines = 1
        )
        Text(topHint, fontFamily = JetBrainsMono, fontSize = 6.5.sp, color = Mute, maxLines = 1)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = 0f..1f,
                modifier = Modifier.width(116.dp).rotate(-90f)
            )
        }
        Text(bottomHint, fontFamily = JetBrainsMono, fontSize = 6.5.sp, color = Mute, maxLines = 1)
        Text(
            title,
            fontFamily = JetBrainsMono,
            fontSize = 7.sp,
            color = ParchDim,
            letterSpacing = 0.7.sp
        )
    }
}

@Composable
private fun LiveCandidateRow(
    rank: Int,
    candidate: Candidate,
    heardNow: Boolean,
    onFeedback: (FeedbackVerdict) -> Unit
) {
    val species = candidate.species ?: return
    val scorePct = (candidate.audioConfidence * 100.0).roundToInt().coerceIn(0, 100)
    val thresholdPct = candidate.acceptanceThreshold?.let { (it * 100.0).roundToInt().coerceIn(0, 100) }
    val passed = candidate.evidenceAccepted == true
    val statusColor = if (passed) Biolume else Amber
    val statusBase = when {
        candidate.callCompatibilityPassed == false -> "J.1 CANDIDATE · CALL PROFILE CONFLICT"
        passed -> "PASSES J.1 GATE"
        thresholdPct != null -> "POSSIBLE · BELOW J.1 GATE · NEEDS $thresholdPct"
        else -> "POSSIBLE RESULT"
    }
    val status = if (heardNow) "● HEARD NOW · $statusBase" else statusBase
    val cardBorder = when {
        heardNow -> Amber
        passed -> Biolume.copy(alpha = 0.45f)
        else -> Line
    }
    val cardBackground = if (heardNow) Amber.copy(alpha = 0.10f) else Panel

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(cardBackground)
            .border(BorderStroke(if (heardNow) 1.5.dp else 1.dp, cardBorder), RoundedCornerShape(13.dp))
            .padding(9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$rank",
                modifier = Modifier.width(20.dp),
                fontFamily = JetBrainsMono,
                fontSize = 10.sp,
                color = Amber
            )
            Box(Modifier.size(58.dp, 44.dp).clip(RoundedCornerShape(8.dp)).background(Ink)) {
                SpeciesThumbnail(species, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(species.common, fontFamily = Fraunces, fontSize = 14.5.sp, color = Parch)
                if (!species.common.equals(species.latin, ignoreCase = true)) {
                    Text(
                        species.latin,
                        fontFamily = Fraunces,
                        fontStyle = FontStyle.Italic,
                        fontSize = 10.5.sp,
                        color = Mute
                    )
                }
                Text(status, fontFamily = JetBrainsMono, fontSize = 8.sp, color = statusColor)
            }
            Text(
                "J.1 $scorePct",
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = when {
                    heardNow -> Amber
                    passed -> Biolume
                    else -> Parch
                }
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(100.dp)).background(Panel2)) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(candidate.audioConfidence.toFloat().coerceIn(0f, 1f))
                    .background(statusColor)
            )
        }
        Spacer(Modifier.height(7.dp))
        CandidateFeedbackButtons(onFeedback)
    }
}
