package com.pgotta.stridulate.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pgotta.stridulate.audio.RecordingQualityGrade
import com.pgotta.stridulate.audio.ReferenceSoundPlayer
import com.pgotta.stridulate.classifier.Candidate
import com.pgotta.stridulate.data.ReliabilityTier
import com.pgotta.stridulate.data.Species
import com.pgotta.stridulate.ui.IdResult
import com.pgotta.stridulate.ui.IdentificationDecision
import com.pgotta.stridulate.ui.StridulateViewModel
import com.pgotta.stridulate.ui.components.ConfidenceRing
import com.pgotta.stridulate.ui.components.GhostButton
import com.pgotta.stridulate.ui.components.PrimaryButton
import com.pgotta.stridulate.ui.components.ProceduralSpectrogram
import com.pgotta.stridulate.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun ResultScreen(
    result: IdResult,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenGuide: (String) -> Unit,
    onPlay: (Species) -> Unit,
    canRefreshContext: Boolean = false,
    onRefreshContext: () -> Unit = {},
    onSaveForCommunity: () -> Unit = {},
    onShareForIdentification: (String) -> Unit = {},
    onOpenCommunity: () -> Unit = {}
) {
    val vm: StridulateViewModel = viewModel()
    val scrollState = rememberScrollState()
    var localGuideId by remember(result) { mutableStateOf<String?>(null) }
    val top = result.candidates.firstOrNull()
    val species = top?.species
    val percentage = ((top?.audioConfidence ?: result.modelTopConfidence) * 100).roundToInt()
    val signature = result.signature

    BackHandler(enabled = localGuideId != null) {
        ReferenceSoundPlayer.stop()
        localGuideId = null
    }

    val heading = when (result.decision) {
        IdentificationDecision.IDENTIFIED -> "High confidence"
        IdentificationDecision.POSSIBLE_MATCH -> "Likely match"
        IdentificationDecision.NO_CONFIDENT_MATCH -> "No confident match"
    }
    val badge = when (result.decision) {
        IdentificationDecision.IDENTIFIED -> "FROZEN J.1 HIGH-EVIDENCE BAND · CONFIRM THE CALL"
        IdentificationDecision.POSSIBLE_MATCH -> "J.1 SPECIES THRESHOLD PASSED · CONFIRM THE CALL"
        IdentificationDecision.NO_CONFIDENT_MATCH -> "NO J.1 SPECIES CROSSED ITS ACCEPTANCE GATE"
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 18.dp)) {
            AppBarRow(
                heading,
                "FROZEN J.1 · PERCH 2.0 · 88 SPECIES",
                onBack = onBack,
                status = when (result.decision) {
                    IdentificationDecision.IDENTIFIED -> "high evidence"
                    IdentificationDecision.POSSIBLE_MATCH -> "likely"
                    IdentificationDecision.NO_CONFIDENT_MATCH -> "unresolved"
                },
                statusOn = result.decision != IdentificationDecision.NO_CONFIDENT_MATCH
            )

            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (top != null) ConfidenceRing(percentage)
                Text(
                    if (result.decision == IdentificationDecision.NO_CONFIDENT_MATCH) {
                        "CLOSEST CALIBRATED SCORE — NOT AN IDENTIFICATION"
                    } else {
                        "CALIBRATED J.1 EVIDENCE SCORE — NOT CERTAINTY"
                    },
                    color = Mute,
                    fontFamily = JetBrainsMono,
                    fontSize = 9.sp,
                    letterSpacing = 1.4.sp
                )
                Spacer(Modifier.height(9.dp))
                Text(
                    badge,
                    color = when (result.decision) {
                        IdentificationDecision.IDENTIFIED -> Biolume
                        IdentificationDecision.POSSIBLE_MATCH -> tierColor(top?.reliability?.tier)
                        IdentificationDecision.NO_CONFIDENT_MATCH -> Danger
                    },
                    fontFamily = JetBrainsMono,
                    fontSize = 10.5.sp,
                    letterSpacing = 1.2.sp,
                    textAlign = TextAlign.Center
                )
            }

            if (species != null) {
                Spacer(Modifier.height(18.dp))
                if (result.decision == IdentificationDecision.NO_CONFIDENT_MATCH) {
                    Text(
                        "CLOSEST SUPPORTED SPECIES — NOT AN IDENTIFICATION",
                        color = Mute,
                        fontFamily = JetBrainsMono,
                        fontSize = 9.5.sp,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "${species.family} · ${top.reliability.tier.displayName} evaluation tier",
                    color = tierColor(top.reliability.tier),
                    fontFamily = JetBrainsMono,
                    fontSize = 10.5.sp,
                    letterSpacing = 1.3.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(5.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable { localGuideId = species.id }.padding(vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        species.common,
                        fontFamily = Fraunces,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 28.sp,
                        color = Parch,
                        textAlign = TextAlign.Center
                    )
                    if (!species.common.equals(species.latin, ignoreCase = true)) {
                        Text(
                            species.latin,
                            fontFamily = Fraunces,
                            fontStyle = FontStyle.Italic,
                            fontSize = 16.sp,
                            color = ParchDim,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            "No separate English common name bundled",
                            fontFamily = Inter,
                            fontSize = 11.sp,
                            color = Mute,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(Modifier.height(19.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    val pulse = if (species.signature.pulseRate != null) {
                        "${signature.pulseRate?.roundToInt() ?: "—"} / s"
                    } else "broadband"
                    Fact("Pulse", pulse)
                    Fact("Peak", "${"%.1f".format(signature.peakFreqKHz)} kHz")
                    Fact("Type", species.callType)
                }

                Spacer(Modifier.height(15.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Box(Modifier.width(2.dp).height(58.dp).background(Amber))
                    Spacer(Modifier.width(13.dp))
                    Text(species.songDesc, color = ParchDim, fontFamily = Inter, fontSize = 13.5.sp, lineHeight = 20.sp)
                }

                Spacer(Modifier.height(15.dp))
                PrimaryButton(
                    "▸ Play community recording",
                    { onPlay(species) },
                    Modifier.fillMaxWidth(),
                    container = Biolume,
                    content = Color(0xFF0B1A0C)
                )
                Spacer(Modifier.height(9.dp))
                GhostButton("Open full field guide", { localGuideId = species.id }, Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(22.dp))
            DecisionCard(result)
            Spacer(Modifier.height(18.dp))
            CommunityReviewCard(result, onSaveForCommunity, onShareForIdentification, onOpenCommunity)

            result.recordingQuality?.let {
                Spacer(Modifier.height(18.dp))
                RecordingQualityCard(result)
            }

            Spacer(Modifier.height(18.dp))
            ObservationContextResultCard(result, canRefreshContext, onRefreshContext)

            Spacer(Modifier.height(23.dp))
            Text("TOP 3 SPECIES MATCHES", color = Amber, fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(5.dp))
            Text(
                if (result.contextApplied) {
                    "Ranked with small region/season/time adjustments. Percentages remain frozen J.1 audio evidence scores, not scientific certainty."
                } else {
                    "Ranked by frozen J.1 audio evidence. Confirm candidates against the call and field-guide information."
                },
                fontFamily = Inter,
                fontSize = 11.5.sp,
                color = Mute,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(10.dp))
            result.candidates.take(3).forEachIndexed { index, candidate ->
                SpeciesMatchRow(index + 1, candidate) { candidate.species?.let { localGuideId = it.id } }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Recording measurements: peak ${"%.1f".format(signature.peakFreqKHz)} kHz · " +
                    "bandwidth ${"%.1f".format(signature.bandwidthKHz)} kHz" +
                    (signature.pulseRate?.let { " · pulse ${it.roundToInt()}/s" } ?: " · broadband call") +
                    ". Frozen J.1 is the final Stage-J dominant-caller detector; true simultaneous multi-insect separation is reserved for Stage K.",
                fontFamily = JetBrainsMono,
                fontSize = 10.5.sp,
                color = Mute,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(26.dp))
        }

        localGuideId?.let { id ->
            val sp = vm.repo.byId(id)
            val localCandidate = result.candidates.firstOrNull { it.species?.id == id }
            if (sp != null) {
                Box(Modifier.fillMaxSize().background(Ink)) {
                    GuideScreen(
                        sp = sp,
                        reliability = localCandidate?.reliability ?: vm.reliabilityFor(sp),
                        observationContext = result.observationContext,
                        contextAssessment = vm.contextAssessmentFor(sp),
                        coverageNote = vm.contextCoverageFor(sp),
                        onBack = {
                            ReferenceSoundPlayer.stop()
                            localGuideId = null
                        },
                        onPlay = onPlay,
                        onStopPlayback = ReferenceSoundPlayer::stop
                    )
                }
            }
        }
    }
}

@Composable
private fun CommunityReviewCard(result: IdResult, onSave: () -> Unit, onShare: (String) -> Unit, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(12.dp)).padding(14.dp)
    ) {
        Text("HELP TEACH STRIDULATE", color = Amber, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.7.sp)
        Spacer(Modifier.height(7.dp))
        Text(
            if (result.communityRecordId == null) {
                "Save the original WAV and result metadata locally. You can share it to iNaturalist for community identification, link the response, and later export a human-reviewed GitHub training bundle."
            } else {
                "Saved as ${result.communityRecordId}. The audio stays local until you explicitly share it."
            },
            color = ParchDim,
            fontFamily = Inter,
            fontSize = 12.5.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(10.dp))
        val recordId = result.communityRecordId
        if (recordId == null) {
            PrimaryButton("Save recording to Unknowns", onSave, Modifier.fillMaxWidth())
        } else {
            PrimaryButton("Share WAV for iNaturalist ID", { onShare(recordId) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            GhostButton("Open saved Unknown", onOpen, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DecisionCard(result: IdResult) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(12.dp)).padding(14.dp)
    ) {
        Text("WHY THIS RESULT", color = Amber, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.7.sp)
        Spacer(Modifier.height(7.dp))
        Text(result.decisionReason, color = ParchDim, fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricPill("Best audio", "${(result.modelTopConfidence * 100).roundToInt()}%")
            MetricPill("Required", "${(result.requiredConfidence * 100).roundToInt()}%")
            MetricPill("Margin", "${(result.modelMargin * 100).roundToInt()}%")
            MetricPill("Min margin", "${(result.requiredMargin * 100).roundToInt()}%")
        }
        Spacer(Modifier.height(9.dp))
        Text(
            "Acoustic check: ${if (result.acousticCheckPassed) "PASSED" else "REJECTED"} — ${result.acousticCheckSummary}",
            color = if (result.acousticCheckPassed) Mute else Danger,
            fontFamily = Inter,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        if (result.decision == IdentificationDecision.NO_CONFIDENT_MATCH) {
            Spacer(Modifier.height(9.dp))
            Text(
                "Try a closer, cleaner recording with one caller and less wind, speech, traffic or overlapping insects.",
                color = Mute,
                fontFamily = Inter,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun RecordingQualityCard(result: IdResult) {
    val quality = result.recordingQuality ?: return
    val gradeColor = when (quality.grade) {
        RecordingQualityGrade.GOOD -> Biolume
        RecordingQualityGrade.FAIR -> Amber
        RecordingQualityGrade.POOR -> Danger
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(12.dp)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("RECORDING QUALITY", color = Amber, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.7.sp)
            Spacer(Modifier.weight(1f))
            Text("${quality.grade.displayName.uppercase()} · ${quality.score}/100", color = gradeColor, fontFamily = JetBrainsMono, fontSize = 10.sp)
        }
        Spacer(Modifier.height(7.dp))
        Text(quality.summary, color = ParchDim, fontFamily = Inter, fontSize = 12.5.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricPill("Length", "${"%.1f".format(quality.durationSeconds)}s")
            MetricPill("Clarity", "${quality.signalClarityScore}/100")
            MetricPill("Active", "${quality.activeSignalPercent.roundToInt()}%")
            MetricPill("Clipped", "${"%.1f".format(quality.clippingPercent)}%")
        }
        if (quality.warnings.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            quality.warnings.forEach { warning ->
                Text("• $warning", color = Mute, fontFamily = Inter, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        if (quality.possibleOverlap) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Multiple-caller check: possible overlap or changing chorus detected. Stage J does not source-separate simultaneous callers; Stage K will address that separately.",
                color = Amber,
                fontFamily = Inter,
                fontSize = 11.5.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun ObservationContextResultCard(result: IdResult, canRefresh: Boolean, onRefresh: () -> Unit) {
    val context = result.observationContext
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(12.dp)).padding(14.dp)
    ) {
        Text("OBSERVATION CONTEXT", color = Amber, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.7.sp)
        Spacer(Modifier.height(7.dp))
        if (!context.enabled) {
            Text(
                context.message.ifBlank { "Context was off. This ranking used only the audio model." },
                color = ParchDim,
                fontFamily = Inter,
                fontSize = 12.5.sp,
                lineHeight = 18.sp
            )
            if (canRefresh) {
                Spacer(Modifier.height(10.dp))
                GhostButton("Apply current conditions and rerank", onRefresh, Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text("Use this only when the recording was made here and now.", color = Mute, fontFamily = Inter, fontSize = 10.5.sp)
            }
        } else {
            Text(context.locationLabel ?: context.region.displayName, color = Parch, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
            Spacer(Modifier.height(5.dp))
            Text(
                buildString {
                    append(context.temperatureLabel)
                    context.humidityLabel?.let { append(" · "); append(it) }
                    append(" · ${context.seasonLabel} · ${context.dayPeriodLabel.lowercase()}")
                },
                color = Amber,
                fontFamily = JetBrainsMono,
                fontSize = 10.5.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(context.weatherAgeLabel)
                    context.weatherSourceAgeLabel?.let { append(" · "); append(it) }
                },
                color = if (context.isFresh && context.isTemperatureCurrentForScoring) Biolume else Mute,
                fontFamily = JetBrainsMono,
                fontSize = 9.5.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(result.contextSummary, color = ParchDim, fontFamily = Inter, fontSize = 12.5.sp, lineHeight = 18.sp)
            if (context.temperatureF != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        context.isTemperatureCurrentForScoring ->
                            "Weather source: Open-Meteo current conditions. Temperature can affect ranking only for a sourced species profile."
                        context.hasUsableTemperatureFallback ->
                            "Weather source: Open-Meteo · offline fallback. Older weather is displayed but is not used for species scoring after 30 minutes."
                        else -> "Weather is too old for scoring. Refresh before using environmental context."
                    },
                    color = Mute,
                    fontFamily = Inter,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            GhostButton("Refresh current location + weather", onRefresh, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RowScope.Fact(label: String, value: String) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(12.dp)).padding(vertical = 11.dp, horizontal = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label.uppercase(), fontFamily = JetBrainsMono, fontSize = 8.5.sp, color = Mute, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(5.dp))
        Text(value, fontFamily = Fraunces, fontSize = 14.5.sp, color = Parch, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RowScope.MetricPill(label: String, value: String) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Panel2).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label.uppercase(), fontFamily = JetBrainsMono, fontSize = 7.5.sp, color = Mute, textAlign = TextAlign.Center)
        Spacer(Modifier.height(3.dp))
        Text(value, fontFamily = JetBrainsMono, fontSize = 11.sp, color = Parch)
    }
}

@Composable
private fun SpeciesMatchRow(rank: Int, candidate: Candidate, onClick: () -> Unit) {
    val species = candidate.species ?: return
    val percentage = (candidate.audioConfidence * 100).roundToInt()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$rank", modifier = Modifier.width(22.dp), fontFamily = JetBrainsMono, fontSize = 12.sp, color = Amber)
            Box(Modifier.size(64.dp, 34.dp).clip(RoundedCornerShape(6.dp))) {
                ProceduralSpectrogram(species.group, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(species.common, fontFamily = Fraunces, fontSize = 15.sp, color = Parch)
                if (!species.common.equals(species.latin, ignoreCase = true)) {
                    Text(species.latin, fontFamily = Fraunces, fontStyle = FontStyle.Italic, fontSize = 10.5.sp, color = Mute)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("$percentage%", fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Parch)
                Text(candidate.reliability.tier.displayName.uppercase(), fontFamily = JetBrainsMono, fontSize = 8.sp, color = tierColor(candidate.reliability.tier))
            }
        }
        Spacer(Modifier.height(9.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(100.dp)).background(Panel2)) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(candidate.audioConfidence.toFloat().coerceIn(0f, 1f))
                    .background(tierColor(candidate.reliability.tier))
            )
        }
        candidate.evidenceSupport?.let { support ->
            Spacer(Modifier.height(7.dp))
            Text(support, fontFamily = Inter, fontSize = 10.5.sp, color = Mute, lineHeight = 15.sp)
        }
        candidate.contextSummary?.let { summary ->
            Spacer(Modifier.height(5.dp))
            Text(summary, fontFamily = Inter, fontSize = 10.5.sp, color = Mute, lineHeight = 15.sp)
        }
    }
}

private fun tierColor(tier: ReliabilityTier?): Color = when (tier) {
    ReliabilityTier.VERIFIED -> Biolume
    ReliabilityTier.GOOD -> AmberSoft
    ReliabilityTier.EXPERIMENTAL -> Amber
    ReliabilityTier.NOT_READY -> Danger
    ReliabilityTier.UNKNOWN_GATE, null -> Mute
}
