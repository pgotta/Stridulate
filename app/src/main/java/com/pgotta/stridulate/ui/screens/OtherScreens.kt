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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgotta.stridulate.data.ReliabilityInfo
import com.pgotta.stridulate.data.ReliabilityTier
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

private sealed interface PendingLogAction {
    val session: DetectionLogSession
    data class Delete(override val session: DetectionLogSession) : PendingLogAction
    data class MoveToUnknowns(override val session: DetectionLogSession) : PendingLogAction
}

// ---------------- PERSISTENT LOG ----------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    sessions: List<DetectionLogSession>,
    resolveSpecies: (String) -> Species?,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onMoveToUnknowns: (String) -> Unit,
    onOpenGuide: (String) -> Unit,
    onPlaySegment: (String, Double, Double) -> Unit
) {
    var pendingAction by remember { mutableStateOf<PendingLogAction?>(null) }
    var showClearAll by remember { mutableStateOf(false) }

    pendingAction?.let { action ->
        val moving = action is PendingLogAction.MoveToUnknowns
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = {
                Text(
                    if (moving) "Move recording to Unknowns?" else "Delete this recording?",
                    fontFamily = Fraunces,
                    color = Parch
                )
            },
            text = {
                Text(
                    if (moving) {
                        "The complete WAV will be copied into Unknowns for listening, notes and optional community review, then removed from Log."
                    } else {
                        "This permanently removes the local WAV and its detection markers. This cannot be undone."
                    },
                    fontFamily = Inter,
                    color = ParchDim,
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val sessionId = action.session.id
                        pendingAction = null
                        if (moving) onMoveToUnknowns(sessionId) else onDeleteSession(sessionId)
                    }
                ) {
                    Text(if (moving) "Move to Unknowns" else "Delete", color = if (moving) Biolume else Danger)
                }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("Cancel") } },
            containerColor = Panel2
        )
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("Clear every Log recording?", fontFamily = Fraunces, color = Parch) },
            text = {
                Text(
                    "This permanently deletes all ${sessions.size} locally saved Log recordings. Recordings already moved to Unknowns are not affected.",
                    fontFamily = Inter,
                    color = ParchDim,
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showClearAll = false; onClear() }) {
                    Text("Clear all", color = Danger)
                }
            },
            dismissButton = { TextButton(onClick = { showClearAll = false }) { Text("Cancel") } },
            containerColor = Panel2
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        AppBarRow("Log", "SAVED RECORDINGS", onBack = onBack, trailing = {
            if (sessions.isNotEmpty()) {
                Text(
                    "Clear all",
                    color = Danger,
                    fontFamily = JetBrainsMono,
                    fontSize = 10.sp,
                    modifier = Modifier.clickable { showClearAll = true }.padding(8.dp)
                )
            }
        })
        val speciesCount = sessions.flatMap { it.detections }.map { it.speciesId }.toSet().size
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Recording history", fontFamily = Fraunces, fontSize = 20.sp, color = Parch)
            Text("$speciesCount species · ${sessions.size} recordings", fontFamily = JetBrainsMono,
                fontSize = 10.sp, color = Biolume)
        }
        if (sessions.isNotEmpty()) {
            Text(
                "Swipe right to review in Unknowns · swipe left to delete. Both actions require confirmation.",
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                color = Mute,
                lineHeight = 14.sp,
                modifier = Modifier.padding(bottom = 11.dp)
            )
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
                    "No saved recordings yet.
Tap Listen to start rolling identification.",
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
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            when (value) {
                                SwipeToDismissBoxValue.StartToEnd -> pendingAction = PendingLogAction.MoveToUnknowns(session)
                                SwipeToDismissBoxValue.EndToStart -> pendingAction = PendingLogAction.Delete(session)
                                SwipeToDismissBoxValue.Settled -> Unit
                            }
                            false
                        }
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = true,
                        enableDismissFromEndToStart = true,
                        backgroundContent = {
                            val moving = dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd
                            Row(
                                Modifier.fillMaxSize()
                                    .background(if (moving) Color(0xFF17372C) else Color(0xFF4A211D), RoundedCornerShape(15.dp))
                                    .padding(horizontal = 18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = if (moving) Arrangement.Start else Arrangement.End
                            ) {
                                Text(
                                    if (moving) "Move to Unknowns" else "Delete",
                                    fontFamily = JetBrainsMono,
                                    fontSize = 11.sp,
                                    color = if (moving) Biolume else Color.White
                                )
                            }
                        },
                        content = {
                            LogSessionCard(
                                session = session,
                                resolveSpecies = resolveSpecies,
                                onOpenGuide = onOpenGuide,
                                onPlaySegment = onPlaySegment,
                                onMoveToUnknowns = { pendingAction = PendingLogAction.MoveToUnknowns(session) },
                                onDelete = { pendingAction = PendingLogAction.Delete(session) }
                            )
                        }
                    )
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
    onPlaySegment: (String, Double, Double) -> Unit,
    onMoveToUnknowns: () -> Unit,
    onDelete: () -> Unit
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
                "Nothing passed the confidence, margin, quality and enabled-tier gates. The complete recording is still available below and can be moved to Unknowns for manual review.",
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
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LogActionButton("▶ Full audio", Modifier.weight(1f)) {
                onPlaySegment(session.audioFilePath, 0.0, session.durationSeconds)
            }
            LogActionButton("Review", Modifier.weight(1f), accent = true, onClick = onMoveToUnknowns)
            LogActionButton("Delete", Modifier.weight(0.8f), danger = true, onClick = onDelete)
        }
    }
}

@Composable
private fun LogActionButton(
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val color = when {
        danger -> Danger
        accent -> Biolume
        else -> ParchDim
    }
    Box(
        modifier.clip(RoundedCornerShape(100.dp))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.55f)), RoundedCornerShape(100.dp))
            .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 8.5.sp, color = color)
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
