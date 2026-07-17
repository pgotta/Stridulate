package com.pgotta.stridulate.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgotta.stridulate.data.SoundPack
import com.pgotta.stridulate.data.Species
import com.pgotta.stridulate.ui.Detection
import com.pgotta.stridulate.ui.components.ProceduralSpectrogram
import com.pgotta.stridulate.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

// ---------------- SESSION ----------------
@Composable
fun SessionScreen(
    detections: List<Detection>,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onOpenGuide: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        AppBarRow("Tonight", "SESSION DETECTIONS", onBack = onBack, trailing = {
            Text("⌫", color = Parch, fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onClear).padding(8.dp))
        })
        val species = detections.map { it.species.id }.toSet().size
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("This session", fontFamily = Fraunces, fontSize = 20.sp, color = Parch)
            Text("$species species", fontFamily = JetBrainsMono, fontSize = 11.sp, color = Biolume)
        }
        if (detections.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("🦗", fontSize = 34.sp)
                Spacer(Modifier.height(12.dp))
                Text("No callers yet tonight.\nTap Listen to start logging.",
                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = Mute,
                    textAlign = TextAlign.Center, lineHeight = 20.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(detections) { d -> DetectionRow(d) { onOpenGuide(d.species.id) } }
            }
        }
    }
}

@Composable
private fun DetectionRow(d: Detection, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(13.dp))
            .clickable(onClick = onClick).padding(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(84.dp, 40.dp).clip(RoundedCornerShape(7.dp))) {
            ProceduralSpectrogram(d.species.group, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.species.common, fontFamily = Fraunces, fontSize = 15.sp, color = Parch)
            Text(d.species.latin, fontFamily = Fraunces, fontStyle = FontStyle.Italic,
                fontSize = 11.5.sp, color = Mute)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${d.confidencePct}%", fontFamily = JetBrainsMono, fontSize = 14.sp, color = Biolume)
            Text(timeFmt.format(d.time), fontFamily = JetBrainsMono, fontSize = 10.sp, color = ParchDim)
        }
    }
}

// ---------------- BROWSE / FIELD GUIDE INDEX ----------------
@Composable
fun BrowseScreen(
    species: List<Species>,
    onBack: () -> Unit,
    onOpenGuide: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }

    val filtered = species.filter { sp ->
        (filter == "all" || sp.group == filter) &&
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
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "All", "cricket" to "Crickets",
                "katydid" to "Katydids", "cicada" to "Cicadas").forEach { (g, label) ->
                FilterPill(label, filter == g) { filter = g }
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(filtered) { sp -> BrowseRow(sp) { onOpenGuide(sp.id) } }
        }
    }
}

@Composable
private fun FilterPill(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(100.dp))
            .background(if (on) Biolume else Panel)
            .border(BorderStroke(1.dp, if (on) Biolume else Line), RoundedCornerShape(100.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 11.sp,
            color = if (on) Color(0xFF0B1A0C) else ParchDim)
    }
}

@Composable
private fun BrowseRow(sp: Species, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(13.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(76.dp, 38.dp).clip(RoundedCornerShape(7.dp))) {
            ProceduralSpectrogram(sp.group, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(sp.common, fontFamily = Fraunces, fontSize = 15.5.sp, color = Parch)
            Text(sp.latin, fontFamily = Fraunces, fontStyle = FontStyle.Italic,
                fontSize = 12.sp, color = Mute)
            Text(sp.familyLatin.substringAfterLast("·").trim().uppercase(),
                fontFamily = JetBrainsMono, fontSize = 9.5.sp, color = ParchDim, letterSpacing = 0.5.sp)
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
