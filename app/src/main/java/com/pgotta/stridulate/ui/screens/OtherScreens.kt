package com.pgotta.stridulate.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgotta.stridulate.data.ReliabilityInfo
import com.pgotta.stridulate.data.ReliabilityTier
import com.pgotta.stridulate.data.SoundPack
import com.pgotta.stridulate.data.Species
import com.pgotta.stridulate.log.DetectionLogSession
import com.pgotta.stridulate.log.LoggedSpeciesDetection
import com.pgotta.stridulate.ui.components.SpeciesThumbnail
import com.pgotta.stridulate.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
private val dateFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

// ---------------- PERSISTENT LOG ----------------
@Composable
fun SessionScreen(
    sessions: List<DetectionLogSession>,
    resolveSpecies: (String) -> Species?,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onOpenGuide: (String) -> Unit,
    onPlaySegment: (String, Double, Double) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        AppBarRow("Log", "SAVED RECORDINGS", onBack = onBack, trailing = {
            Text("⌫", color = Parch, fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onClear).padding(8.dp))
        })
        val speciesCount = sessions.flatMap { it.detections }.map { it.speciesId }.toSet().size
        Row(
            Modifier.fillMaxWidth().padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Recording history", fontFamily = Fraunces, fontSize = 20.sp, color = Parch)
            Text("$speciesCount species · ${sessions.size} recordings", fontFamily = JetBrainsMono,
                fontSize = 10.sp, color = Biolume)
        }
        if (sessions.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("🦗", fontSize = 34.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "No saved recordings yet.\nTap Listen to start rolling identification.",
                    fontFamily = JetBrainsMono,
                    fontSize = 12.sp,
                    color = Mute,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(sessions, key = { it.id }) { session ->
                    LogSessionCard(session, resolveSpecies, onOpenGuide, onPlaySegment)
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun LogSessionCard(
    session: DetectionLogSession,
    resolveSpecies: (String) -> Species?,
    onOpenGuide: (String) -> Unit,
    onPlaySegment: (String, Double, Double) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Panel2)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(15.dp)).padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(dateFmt.format(Date(session.startedAtMillis)), fontFamily = Fraunces,
                    fontSize = 17.sp, color = Parch)
                Text(
                    "${timeFmt.format(Date(session.startedAtMillis))} · ${session.durationSeconds.toInt()} sec",
                    fontFamily = JetBrainsMono, fontSize = 9.5.sp, color = Mute
                )
            }
            Text(
                if (session.detections.isEmpty()) "NO ACCEPTED CALLS" else "${session.detections.size} SPECIES",
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                color = if (session.detections.isEmpty()) Amber else Biolume
            )
        }
        Spacer(Modifier.height(10.dp))
        if (session.detections.isEmpty()) {
            Text(
                "The audio was saved for review, but every model output was below the active confidence, margin, quality, or tier rules.",
                fontFamily = Inter, fontSize = 12.sp, color = ParchDim, lineHeight = 17.sp
            )
        } else {
            session.detections.forEachIndexed { index, detection ->
                val species = resolveSpecies(detection.speciesId)
                if (species != null) {
                    LoggedDetectionRow(
                        species = species,
                        detection = detection,
                        session = session,
                        onOpenGuide = onOpenGuide,
                        onPlaySegment = onPlaySegment
                    )
                    if (index != session.detections.lastIndex) Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun LoggedDetectionRow(
    species: Species,
    detection: LoggedSpeciesDetection,
    session: DetectionLogSession,
    onOpenGuide: (String) -> Unit,
    onPlaySegment: (String, Double, Double) -> Unit
) {
    val latest = detection.occurrences.lastOrNull()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(12.dp)).padding(9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(72.dp, 52.dp).clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenGuide(species.id) }
            ) { SpeciesThumbnail(species, Modifier.fillMaxSize()) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).clickable { onOpenGuide(species.id) }) {
                Text(species.common, fontFamily = Fraunces, fontSize = 15.sp, color = Parch)
                Text(species.latin, fontFamily = Fraunces, fontStyle = FontStyle.Italic,
                    fontSize = 11.sp, color = Mute)
                Text(
                    "${detection.occurrences.size} accepted window${if (detection.occurrences.size == 1) "" else "s"}",
                    fontFamily = JetBrainsMono, fontSize = 8.5.sp, color = ParchDim
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${detection.latestConfidencePct}%", fontFamily = JetBrainsMono,
                    fontSize = 15.sp, color = Biolume)
                Text("peak ${detection.peakConfidencePct}%", fontFamily = JetBrainsMono,
                    fontSize = 8.sp, color = Mute)
            }
        }
        Spacer(Modifier.height(7.dp))
        DetectionTimeline(session.durationSeconds, detection)
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                "▶ Play latest marked call",
                modifier = Modifier.clip(RoundedCornerShape(100.dp)).background(Color(0x1A66D59A))
                    .border(BorderStroke(1.dp, Color(0x553F7D5F)), RoundedCornerShape(100.dp))
                    .clickable(enabled = latest != null) {
                        latest?.let { onPlaySegment(session.audioFilePath, it.startSeconds, it.endSeconds) }
                    }.padding(horizontal = 10.dp, vertical = 6.dp),
                fontFamily = JetBrainsMono, fontSize = 9.sp,
                color = if (latest != null) Biolume else Mute
            )
        }
    }
}

@Composable
private fun DetectionTimeline(durationSeconds: Double, detection: LoggedSpeciesDetection) {
    Canvas(
        Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(7.dp)).background(SpecBg)
    ) {
        val center = size.height / 2f
        val step = size.width / 36f
        for (i in 0..36) {
            val amp = (3f + ((i * 17) % 11))
            drawLine(
                color = Color(0xFF315149),
                start = Offset(i * step, center - amp),
                end = Offset(i * step, center + amp),
                strokeWidth = 1.5f
            )
        }
        detection.occurrences.forEach { occurrence ->
            val fraction = if (durationSeconds > 0) occurrence.endSeconds / durationSeconds else 0.0
            val x = (fraction.coerceIn(0.0, 1.0) * size.width).toFloat()
            drawLine(Amber, Offset(x, 2f), Offset(x, size.height - 2f), strokeWidth = 3f)
        }
    }
}

// ---------------- BROWSE / FIELD GUIDE INDEX ----------------
@Composable
fun BrowseScreen(
    species: List<Species>,
    reliabilityFor: (Species) -> ReliabilityInfo,
    onBack: () -> Unit,
    onOpenGuide: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var groupFilter by remember { mutableStateOf("all") }
    var tierFilter by remember { mutableStateOf<ReliabilityTier?>(null) }

    val filtered = species.filter { sp ->
        val reliability = reliabilityFor(sp)
        (groupFilter == "all" || sp.group == groupFilter) &&
            (tierFilter == null || reliability.tier == tierFilter) &&
            (query.isBlank() || listOf(sp.common, sp.latin, sp.family, sp.callType, sp.habitat)
                .any { it.contains(query, ignoreCase = true) })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        AppBarRow("Field guide", "${filtered.size} SPECIES", onBack = onBack)
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search species, family, sound…", color = Mute, fontSize = 14.sp) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions.Default,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Panel, unfocusedContainerColor = Panel,
                focusedBorderColor = Biolume2, unfocusedBorderColor = Line,
                focusedTextColor = Parch, unfocusedTextColor = Parch, cursorColor = Biolume
            )
        )
        Spacer(Modifier.height(9.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(listOf("all" to "All", "cricket" to "Crickets", "katydid" to "Katydids", "cicada" to "Cicadas")) { (g, label) ->
                FilterPill(label, groupFilter == g) { groupFilter = g }
            }
        }
        Spacer(Modifier.height(7.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            item { FilterPill("All tiers", tierFilter == null) { tierFilter = null } }
            items(listOf(ReliabilityTier.VERIFIED, ReliabilityTier.GOOD, ReliabilityTier.EXPERIMENTAL, ReliabilityTier.NOT_READY)) { tier ->
                FilterPill(tier.displayName, tierFilter == tier) { tierFilter = tier }
            }
        }
        Spacer(Modifier.height(11.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(filtered, key = { it.id }) { sp ->
                BrowseRow(sp, reliabilityFor(sp)) { onOpenGuide(sp.id) }
            }
        }
    }
}

@Composable
private fun FilterPill(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(100.dp))
            .background(if (on) Biolume else Panel)
            .border(BorderStroke(1.dp, if (on) Biolume else Line), RoundedCornerShape(100.dp))
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 7.dp)
    ) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 10.5.sp,
            color = if (on) Color(0xFF0B1A0C) else ParchDim)
    }
}

@Composable
private fun BrowseRow(sp: Species, reliability: ReliabilityInfo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(13.dp))
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(76.dp, 54.dp).clip(RoundedCornerShape(8.dp))) {
            SpeciesThumbnail(sp, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(sp.common, fontFamily = Fraunces, fontSize = 15.5.sp, color = Parch)
            Text(sp.latin, fontFamily = Fraunces, fontStyle = FontStyle.Italic,
                fontSize = 12.sp, color = Mute)
            Text(
                reliability.tier.displayName.uppercase(),
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                color = when (reliability.tier) {
                    ReliabilityTier.VERIFIED -> Biolume
                    ReliabilityTier.GOOD -> AmberSoft
                    ReliabilityTier.EXPERIMENTAL -> Amber
                    ReliabilityTier.NOT_READY -> Danger
                    ReliabilityTier.UNKNOWN_GATE -> Mute
                },
                letterSpacing = 0.5.sp
            )
        }
        Text("›", color = Mute, fontSize = 18.sp)
    }
}

// ---------------- PACKS ----------------
@Composable
fun PacksScreen(packs: List<SoundPack>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        AppBarRow("Sound packs", "OFFLINE MODELS", onBack = onBack,
            status = "no account", statusOn = false)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(Color(0x1466D59A))
                .border(BorderStroke(1.dp, Color(0xFF2E4A2A)), RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0x2666D59A)), contentAlignment = Alignment.Center) {
                Text("⤓", color = Biolume, fontSize = 20.sp)
            }
            Spacer(Modifier.width(13.dp))
            Column {
                Text("Everything runs on your device", fontFamily = Fraunces, fontSize = 16.sp, color = Parch)
                Text("On-device identification is being built. Regional model packs will download here in a future update, so the app can name hundreds of species fully offline. Nothing to download yet.",
                    fontFamily = Inter, fontSize = 12.5.sp, color = ParchDim, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        packs.forEach { p ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 9.dp)
                    .clip(RoundedCornerShape(13.dp)).background(Panel)
                    .border(BorderStroke(1.dp, Line), RoundedCornerShape(13.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A2723)), contentAlignment = Alignment.Center) {
                    Text(p.flag, fontSize = 18.sp)
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = Parch)
                    Text(p.note + (p.size?.let { " · $it" } ?: ""),
                        fontFamily = JetBrainsMono, fontSize = 10.5.sp, color = Mute)
                }
                // Disabled "coming soon" chip — no fake instant download.
                Box(
                    Modifier.clip(RoundedCornerShape(100.dp))
                        .border(BorderStroke(1.dp, Line), RoundedCornerShape(100.dp))
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                ) {
                    Text("Coming soon",
                        fontFamily = JetBrainsMono, fontSize = 10.5.sp, color = Mute)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------- Analyzing / Error overlays ----------------
@Composable
fun AnalyzingScreen(label: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Biolume, trackColor = Color(0xFF20302C), strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text(label, fontFamily = Fraunces, fontSize = 18.sp, color = Parch)
    }
}

@Composable
fun ErrorScreen(message: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        AppBarRow("Not identified", "TRY AGAIN", onBack = onBack)
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🌙", fontSize = 40.sp)
            Spacer(Modifier.height(14.dp))
            Text(message, fontFamily = Inter, fontSize = 15.sp, color = ParchDim,
                textAlign = TextAlign.Center, lineHeight = 22.sp)
        }
        Spacer(Modifier.weight(1f))
        com.pgotta.stridulate.ui.components.PrimaryButton("Back", onBack, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
    }
}
