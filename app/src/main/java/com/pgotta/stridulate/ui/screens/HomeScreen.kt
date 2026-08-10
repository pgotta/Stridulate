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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgotta.stridulate.environment.ContextStatus
import com.pgotta.stridulate.environment.ObservationContext
import com.pgotta.stridulate.ui.components.Chip
import com.pgotta.stridulate.ui.components.SectionHeader
import com.pgotta.stridulate.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    speciesCount: Int,
    sessionCount: Int,
    unknownCount: Int,
    modelStatus: String,
    usingTrainedModel: Boolean,
    observationContext: ObservationContext,
    onUseDeviceLocation: () -> Unit,
    onUseManualLocation: (String) -> Unit,
    onRefreshContext: () -> Unit,
    onDisableContext: () -> Unit,
    onListen: () -> Unit,
    onImport: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenSession: () -> Unit,
    onOpenCommunity: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var showManualLocation by remember { mutableStateOf(false) }
    var manualLocation by remember(observationContext.manualQuery) {
        mutableStateOf(observationContext.manualQuery.orEmpty())
    }

    if (showManualLocation) {
        AlertDialog(
            onDismissRequest = { showManualLocation = false },
            title = { Text("City or ZIP", fontFamily = Fraunces, color = Parch) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Stridulate uses this only to obtain a broad U.S. region, local season/time and current temperature. Audio stays on-device.",
                        fontFamily = Inter,
                        color = ParchDim,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    OutlinedTextField(
                        value = manualLocation,
                        onValueChange = { manualLocation = it },
                        label = { Text("Example: Charlton, MA or 01507") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUseManualLocation(manualLocation)
                        showManualLocation = false
                    },
                    enabled = manualLocation.trim().length >= 2
                ) { Text("Use location") }
            },
            dismissButton = { TextButton(onClick = { showManualLocation = false }) { Text("Cancel") } },
            containerColor = Panel2
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 28.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "LOCAL INSECT SOUND ID",
                color = Amber,
                fontFamily = JetBrainsMono,
                fontSize = 10.5.sp,
                letterSpacing = 2.6.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = ParchDim)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Stridulate", fontFamily = Fraunces, fontWeight = FontWeight.Black, fontSize = 48.sp, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            "Identify a dominant cricket, katydid, cicada or other supported singing insect with frozen J.1 / Perch 2.0, then compare the call and field-guide details.",
            color = ParchDim,
            fontFamily = Inter,
            fontSize = 14.5.sp,
            lineHeight = 21.sp
        )
        Spacer(Modifier.height(15.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Chip("$speciesCount species", accent = true)
            Chip(if (usingTrainedModel) "Frozen J.1" else "Model unavailable")
            Chip("Audio on-device")
        }

        if (!usingTrainedModel) {
            Spacer(Modifier.height(12.dp))
            Text(
                modelStatus,
                color = Amber,
                fontFamily = JetBrainsMono,
                fontSize = 10.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(Color(0x1AF0B44D))
                    .border(BorderStroke(1.dp, Color(0x553F321A)), RoundedCornerShape(10.dp))
                    .padding(11.dp)
            )
        }

        Spacer(Modifier.height(22.dp))
        ObservationContextCard(
            context = observationContext,
            onUseDeviceLocation = onUseDeviceLocation,
            onManualLocation = { showManualLocation = true },
            onRefresh = onRefreshContext,
            onDisable = onDisableContext
        )

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(Panel2, Panel)))
                .border(BorderStroke(1.dp, Line), RoundedCornerShape(20.dp))
                .clickable(enabled = usingTrainedModel, onClick = onListen).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(60.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Danger, Color(0xFF7A221A)))),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Mic, null, tint = Color.White, modifier = Modifier.size(25.dp)) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Start listening", fontFamily = Fraunces, fontSize = 21.sp, color = Parch)
                Text(
                    if (observationContext.enabled) "AUDIO + SOFT OBSERVATION CONTEXT" else "AUDIO MODEL ONLY",
                    fontFamily = JetBrainsMono,
                    fontSize = 9.5.sp,
                    color = Mute,
                    letterSpacing = 0.8.sp
                )
            }
            Text("›", color = Amber, fontSize = 30.sp)
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Panel)
                .border(BorderStroke(1.dp, Line), RoundedCornerShape(15.dp))
                .clickable(enabled = usingTrainedModel, onClick = onImport).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🎞️", fontSize = 24.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Analyze a recording", fontFamily = Fraunces, fontSize = 17.sp, color = Parch)
                Text("AUDIO OR VIDEO · UP TO 30 SECONDS", fontFamily = JetBrainsMono, fontSize = 9.sp, color = Mute, letterSpacing = 0.8.sp)
            }
            Text("›", color = Amber, fontSize = 25.sp)
        }

        Spacer(Modifier.height(25.dp))
        SectionHeader("Explore")
        Spacer(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeTile("📖", "Field guide", "$speciesCount SUPPORTED SPECIES", onOpenGuide)
            HomeTile("🌙", "Log", if (sessionCount > 0) "$sessionCount HEARD" else "SAVED RECORDINGS", onOpenSession)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeTile("?", "Unknowns", if (unknownCount > 0) "$unknownCount SAVED" else "COMMUNITY ID", onOpenCommunity)
            HomeTile("🗺", "Range map", "LIKELY IN YOUR REGION", onOpenMap)
        }
    }
}

@Composable
private fun ObservationContextCard(
    context: ObservationContext,
    onUseDeviceLocation: () -> Unit,
    onManualLocation: () -> Unit,
    onRefresh: () -> Unit,
    onDisable: () -> Unit
) {
    var ageTick by remember(context.refreshedAtMillis, context.enabled) { mutableStateOf(0L) }
    LaunchedEffect(context.refreshedAtMillis, context.enabled) {
        while (context.enabled) {
            delay(30_000L)
            ageTick++
        }
    }
    val currentAgeLabel = remember(context.refreshedAtMillis, ageTick) { context.weatherAgeLabel }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(15.dp)).padding(15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocationOn, null, tint = Amber, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text("Observation context", fontFamily = Fraunces, fontSize = 17.sp, color = Parch)
            Spacer(Modifier.weight(1f))
            Text(
                if (context.enabled) "ON" else "OPTIONAL",
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                color = if (context.enabled) Biolume else Mute,
                letterSpacing = 1.sp
            )
        }
        Spacer(Modifier.height(8.dp))

        if (!context.enabled) {
            Text(
                "Adds broad region, current season, true day/night status and current outdoor weather as gentle ranking support. Live recordings refresh automatically after 10 minutes.",
                fontFamily = Inter, fontSize = 12.5.sp, color = ParchDim, lineHeight = 18.sp
            )
            Spacer(Modifier.height(11.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContextAction("Use approximate location", onUseDeviceLocation, Modifier.weight(1f))
                ContextAction("City / ZIP", onManualLocation, Modifier.weight(0.75f))
            }
        } else {
            if (context.status == ContextStatus.REFRESHING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Biolume, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(9.dp))
                    Text(context.message, fontFamily = Inter, fontSize = 12.5.sp, color = ParchDim)
                }
            } else {
                Text(
                    context.locationLabel ?: context.region.displayName,
                    fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Parch
                )
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Chip(context.temperatureLabel, accent = context.temperatureF != null && context.isFresh)
                    context.humidityLabel?.let { Chip(it) }
                    Chip(context.seasonLabel)
                    Chip(context.dayPeriodLabel)
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    buildString {
                        append(currentAgeLabel)
                        context.weatherSourceAgeLabel?.let { append(" · "); append(it) }
                    },
                    fontFamily = JetBrainsMono,
                    fontSize = 9.5.sp,
                    color = if (context.isFresh && context.isTemperatureCurrentForScoring) Biolume else Amber
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    context.message,
                    fontFamily = Inter,
                    fontSize = 11.5.sp,
                    color = if (context.status == ContextStatus.ERROR) Danger else Mute,
                    lineHeight = 16.sp
                )
                if (context.temperatureF != null) {
                    Text(
                        when {
                            context.isTemperatureCurrentForScoring -> "Open-Meteo current weather · fresh enough for sourced temperature rules"
                            context.hasUsableTemperatureFallback -> "Open-Meteo offline fallback · displayed only after 30 minutes"
                            else -> "Weather expired · refresh before using temperature context"
                        },
                        fontFamily = JetBrainsMono, fontSize = 8.5.sp, color = Mute, modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(11.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContextAction("Refresh now", onRefresh, Modifier.weight(0.82f), enabled = context.status != ContextStatus.REFRESHING)
                ContextAction("Change city / ZIP", onManualLocation, Modifier.weight(1f))
                ContextAction("Turn off", onDisable, Modifier.weight(0.65f))
            }
        }
    }
}

@Composable
private fun ContextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.clip(RoundedCornerShape(9.dp)).border(BorderStroke(1.dp, Line), RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 9.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontFamily = Inter, fontSize = 11.sp, color = if (enabled) ParchDim else Mute)
    }
}

@Composable
private fun RowScope.HomeTile(icon: String, title: String, sub: String, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(15.dp)).background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(15.dp)).clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(icon, fontSize = 22.sp)
        Text(title, fontFamily = Fraunces, fontSize = 16.sp, color = Parch)
        Text(sub, fontFamily = JetBrainsMono, fontSize = 9.5.sp, color = Mute, letterSpacing = 0.4.sp)
    }
}
