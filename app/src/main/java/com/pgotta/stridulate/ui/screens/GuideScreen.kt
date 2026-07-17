package com.pgotta.stridulate.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.pgotta.stridulate.data.ReliabilityInfo
import com.pgotta.stridulate.data.ReliabilityTier
import com.pgotta.stridulate.data.Species
import com.pgotta.stridulate.data.SpeciesPhoto
import com.pgotta.stridulate.environment.ContextAssessment
import com.pgotta.stridulate.environment.ObservationContext
import com.pgotta.stridulate.ui.components.ProceduralSpectrogram
import com.pgotta.stridulate.ui.components.RangeMap
import com.pgotta.stridulate.ui.theme.*

@Composable
fun GuideScreen(
    sp: Species,
    reliability: ReliabilityInfo,
    observationContext: ObservationContext,
    contextAssessment: ContextAssessment,
    coverageNote: String?,
    onBack: () -> Unit,
    onPlay: (Species) -> Unit,
    onStopPlayback: () -> Unit
) {
    // A reference recording belongs to this detail screen. This also cancels a
    // pending network lookup, preventing it from starting after navigation.
    DisposableEffect(sp.id) {
        onDispose(onStopPlayback)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
    ) {
        AppBarRow(sp.common, "${sp.group.uppercase()} · FIELD GUIDE", onBack = onBack)

        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        var photoInfo by remember(sp.id) { mutableStateOf<SpeciesPhoto.PhotoInfo?>(null) }
        var photoLookupDone by remember(sp.id) { mutableStateOf(false) }
        var photoRendered by remember(sp.id) { mutableStateOf(false) }
        var photoLoadFailed by remember(sp.id) { mutableStateOf(false) }

        LaunchedEffect(sp.id) {
            photoLookupDone = false
            photoRendered = false
            photoLoadFailed = false
            photoInfo = SpeciesPhoto.photoFor(sp)
            photoLookupDone = true
        }

        Box(
            Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp))
                .background(Brush.radialGradient(listOf(Color(0xFF1D2B24), Color(0xFF0D1512))))
                .border(BorderStroke(1.dp, Line), RoundedCornerShape(16.dp))
        ) {
            ProceduralSpectrogram(sp.group, Modifier.fillMaxSize())

            photoInfo?.let { info ->
                AsyncImage(
                    model = ImageRequest.Builder(context).data(info.imageUrl).crossfade(true).build(),
                    contentDescription = "${sp.common}, ${sp.latin}",
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                    onSuccess = {
                        photoRendered = true
                        photoLoadFailed = false
                    },
                    onError = {
                        photoRendered = false
                        photoLoadFailed = true
                    }
                )
            }

            if (!photoLookupDone) {
                CircularProgressIndicator(
                    color = Biolume,
                    strokeWidth = 2.dp,
                    modifier = Modifier.align(Alignment.Center).size(28.dp)
                )
            } else if (photoInfo == null || photoLoadFailed) {
                Text(
                    "PHOTO UNAVAILABLE · OFFLINE ART SHOWN",
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Color(0xCC08100E))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    fontFamily = JetBrainsMono,
                    fontSize = 8.5.sp,
                    color = Mute
                )
            }

            if (photoRendered) {
                photoInfo?.let { info ->
                    val credit = buildString {
                        append("PHOTO · ").append(info.attribution)
                        info.licenseCode?.let { append(" · ").append(it.uppercase()) }
                    }
                    Text(
                        text = credit,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                            .clip(RoundedCornerShape(6.dp)).background(Color(0xD908100E))
                            .clickable(enabled = info.sourceUrl != null) {
                                info.sourceUrl?.let(uriHandler::openUri)
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .fillMaxWidth(0.78f),
                        fontFamily = JetBrainsMono,
                        fontSize = 8.5.sp,
                        color = ParchDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Text(
                "${reliability.tier.displayName.uppercase()} TIER",
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                    .clip(RoundedCornerShape(100.dp)).background(Color(0xD908100E))
                    .border(BorderStroke(1.dp, tierColor(reliability.tier)), RoundedCornerShape(100.dp))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                color = tierColor(reliability.tier),
                letterSpacing = 0.8.sp
            )
            Text(
                "◂ ${sp.sizeMm[0]}–${sp.sizeMm[1]} mm ▸",
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                    .clip(RoundedCornerShape(100.dp)).background(Color(0xB308100E))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                fontFamily = JetBrainsMono,
                fontSize = 10.sp,
                color = ParchDim
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(sp.common, fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, color = Parch)
        Text(
            "${sp.latin} · ${sp.authority}",
            fontFamily = Fraunces,
            fontStyle = FontStyle.Italic,
            fontSize = 14.sp,
            color = ParchDim
        )
        Text(
            "${sp.family} · ${sp.familyLatin}",
            fontFamily = JetBrainsMono,
            fontSize = 10.sp,
            color = Mute,
            letterSpacing = 0.4.sp,
            modifier = Modifier.padding(top = 7.dp)
        )

        Spacer(Modifier.height(13.dp))
        val guideBlurb = if (sp.blurb.contains("included in Stridulate's trained model")) {
            "${sp.common} is a ${sp.group} in the ${sp.familyLatin} family. It is associated with " +
                "${sp.habitat.lowercase()}. Confirm a possible identification with the call pattern, " +
                "season, broad range and a clear reference recording."
        } else sp.blurb
        Text(guideBlurb, fontFamily = Inter, fontSize = 14.5.sp, color = ParchDim, lineHeight = 21.sp)

        Spacer(Modifier.height(18.dp))
        ReliabilityPanel(reliability)

        Spacer(Modifier.height(22.dp))
        Eyebrow2("Listen & compare")
        Spacer(Modifier.height(8.dp))
        PlayerRow(sp, onPlay)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(12.dp))
                .background(SpecBg).border(BorderStroke(1.dp, Line), RoundedCornerShape(12.dp))
                .padding(6.dp)
        ) { ProceduralSpectrogram(sp.group, Modifier.fillMaxSize()) }
        Spacer(Modifier.height(9.dp))
        Text(sp.songDesc, fontFamily = JetBrainsMono, fontSize = 12.sp, color = ParchDim, lineHeight = 18.sp)

        Spacer(Modifier.height(22.dp))
        Eyebrow2("Call signature")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            GuideFact("Dominant", "${sp.freqKHz} kHz")
            GuideFact(
                "Range",
                if (sp.freqRange.size >= 2) "${sp.freqRange[0]}–${sp.freqRange[1]} kHz" else "—"
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            GuideFact("Pulse", if (sp.pulseRate != null) "${sp.pulseRate.toInt()} /s" else "buzz / phrase")
            GuideFact("Call type", sp.callType)
        }

        Spacer(Modifier.height(22.dp))
        Eyebrow2("When to listen")
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
                .border(BorderStroke(1.dp, Line), RoundedCornerShape(12.dp)).padding(14.dp)
        ) {
            MonthsRow(sp.months)
            Spacer(Modifier.height(11.dp))
            Text(
                if (sp.nocturnal) "Most useful listening window: evening through night." else "Most useful listening window: daylight and early evening.",
                fontFamily = Inter,
                fontSize = 13.sp,
                color = ParchDim,
                lineHeight = 18.sp
            )
            if (sp.tempSensitive) {
                Spacer(Modifier.height(7.dp))
                Text(
                    "Call timing can change with temperature. Stridulate records live outdoor temperature as context, but does not adjust this species without a sourced species-specific range.",
                    fontFamily = Inter,
                    fontSize = 12.sp,
                    color = Mute,
                    lineHeight = 17.sp
                )
            }
        }

        if (observationContext.enabled) {
            Spacer(Modifier.height(18.dp))
            Eyebrow2("Current observation context")
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
                    .border(BorderStroke(1.dp, Line), RoundedCornerShape(12.dp)).padding(14.dp)
            ) {
                Text(
                    observationContext.locationLabel ?: observationContext.region.displayName,
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp,
                    color = Parch
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    buildString {
                        append(observationContext.temperatureLabel)
                        observationContext.humidityLabel?.let { append(" · "); append(it) }
                        append(" · ${observationContext.seasonLabel} · ${observationContext.dayPeriodLabel.lowercase()}")
                    },
                    fontFamily = JetBrainsMono,
                    fontSize = 10.5.sp,
                    color = Amber
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    contextAssessment.summary,
                    fontFamily = Inter,
                    fontSize = 12.5.sp,
                    color = ParchDim,
                    lineHeight = 18.sp
                )
                Text(
                    "This is gentle ranking support only; local microclimates and unusual emergence dates can differ.",
                    fontFamily = Inter,
                    fontSize = 11.5.sp,
                    color = Mute,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        Eyebrow2("Range & habitat")
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
                .border(BorderStroke(1.dp, Line), RoundedCornerShape(12.dp)).padding(14.dp)
        ) {
            RangeMap(sp)
            Spacer(Modifier.height(10.dp))
            Text(
                coverageNote ?: "General range: ${sp.range}.",
                fontFamily = Inter,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Parch
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "Habitat: ${sp.habitat}. Map dots are a sample of research-grade U.S. observations, not a complete boundary. No dot does not prove absence.",
                fontFamily = Inter,
                fontSize = 12.5.sp,
                color = ParchDim,
                lineHeight = 18.sp
            )
        }

        Spacer(Modifier.height(18.dp))
        Eyebrow2("How to confirm")
        Spacer(Modifier.height(8.dp))
        Text(
            "Compare the rhythm and dominant pitch against the reference recording, check that the month and broad range make sense, and listen for more than one clean phrase. Overlapping callers, wind and phone microphone distance can all change the model score.",
            fontFamily = Inter,
            fontSize = 13.sp,
            color = ParchDim,
            lineHeight = 19.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
                .border(BorderStroke(1.dp, Line), RoundedCornerShape(12.dp)).padding(14.dp)
        )

        Spacer(Modifier.height(26.dp))
    }
}

@Composable
private fun ReliabilityPanel(info: ReliabilityInfo) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
            .border(BorderStroke(1.dp, tierColor(info.tier).copy(alpha = 0.55f)), RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "MODEL RELIABILITY",
                color = Mute,
                fontFamily = JetBrainsMono,
                fontSize = 9.5.sp,
                letterSpacing = 1.4.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                info.tier.displayName.uppercase(),
                color = tierColor(info.tier),
                fontFamily = JetBrainsMono,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(info.conciseExplanation, fontFamily = Inter, fontSize = 12.5.sp, color = ParchDim, lineHeight = 18.sp)
        if (info.f1 != null && info.lockedRecordings != null) {
            Spacer(Modifier.height(7.dp))
            Text(
                "V50 locked holdout: F1 ${"%.2f".format(info.f1)} across ${info.lockedRecordings} recordings",
                fontFamily = JetBrainsMono,
                fontSize = 9.5.sp,
                color = Mute
            )
        }
    }
}

private fun tierColor(tier: ReliabilityTier): Color = when (tier) {
    ReliabilityTier.VERIFIED -> Biolume
    ReliabilityTier.GOOD -> AmberSoft
    ReliabilityTier.EXPERIMENTAL -> Amber
    ReliabilityTier.UNKNOWN_GATE -> Mute
}

@Composable
private fun Eyebrow2(t: String) = Text(
    t.uppercase(),
    color = Amber,
    fontFamily = JetBrainsMono,
    fontSize = 11.sp,
    letterSpacing = 2.sp
)

@Composable
private fun PlayerRow(sp: Species, onPlay: (Species) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(12.dp))
            .clickable { onPlay(sp) }.padding(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Biolume), contentAlignment = Alignment.Center) {
            Text("▸", color = Color(0xFF0B1A0C), fontSize = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Play reference recording", fontFamily = Inter, fontSize = 13.5.sp, color = Parch)
            Text("COMPARE RHYTHM + PITCH", fontFamily = JetBrainsMono, fontSize = 8.5.sp, color = Mute)
        }
        Text("ONLINE", fontFamily = JetBrainsMono, fontSize = 9.sp, color = Mute)
    }
}

@Composable
private fun RowScope.GuideFact(label: String, value: String) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(12.dp))
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label.uppercase(), fontFamily = JetBrainsMono, fontSize = 8.5.sp, color = Mute)
        Spacer(Modifier.height(5.dp))
        Text(value, fontFamily = Fraunces, fontSize = 14.5.sp, color = Parch)
    }
}

@Composable
private fun MonthsRow(months: List<Int>) {
    val labels = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        labels.forEachIndexed { index, month ->
            val active = months.getOrElse(index) { 0 } == 1
            Box(
                Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(5.dp))
                    .background(if (active) Color(0xFF2E4220) else Color(0xFF1A2723)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    month,
                    fontFamily = JetBrainsMono,
                    fontSize = 8.5.sp,
                    color = if (active) Biolume else Color(0xFF5C6D67)
                )
            }
        }
    }
}
