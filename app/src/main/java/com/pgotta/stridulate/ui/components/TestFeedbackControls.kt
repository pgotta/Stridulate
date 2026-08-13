package com.pgotta.stridulate.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgotta.stridulate.data.Species
import com.pgotta.stridulate.qa.FeedbackVerdict
import com.pgotta.stridulate.qa.QaSpeciesProgress
import com.pgotta.stridulate.qa.TestFeedbackRepository
import com.pgotta.stridulate.ui.theme.Amber
import com.pgotta.stridulate.ui.theme.Biolume
import com.pgotta.stridulate.ui.theme.Danger
import com.pgotta.stridulate.ui.theme.Inter
import com.pgotta.stridulate.ui.theme.JetBrainsMono
import com.pgotta.stridulate.ui.theme.Line
import com.pgotta.stridulate.ui.theme.Mute
import com.pgotta.stridulate.ui.theme.Panel
import com.pgotta.stridulate.ui.theme.Panel2
import com.pgotta.stridulate.ui.theme.Parch
import com.pgotta.stridulate.ui.theme.ParchDim

private const val QA_GOAL_PER_SPECIES = 3

@Composable
fun TestFeedbackPanel(
    species: List<Species>,
    targetKey: String?,
    feedbackCount: Int,
    onSetSpeciesTarget: (Species) -> Unit,
    onSetNoiseTarget: () -> Unit,
    onClearTarget: () -> Unit,
    onExport: () -> Unit,
    onClearLog: () -> Unit,
    onMarkCurrentNoise: () -> Unit = {}
) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showTargets by remember { mutableStateOf(false) }
    var showProgress by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val progress = remember(feedbackCount) { TestFeedbackRepository(context).progressSnapshot() }
    val speciesProgress = remember(species, progress) {
        species.associateWith { sp ->
            progress.byTarget[modelLabel(sp)] ?: QaSpeciesProgress(modelLabel(sp), 0, 0, 0, 0)
        }
    }
    val startedCount = speciesProgress.values.count { it.total > 0 }
    val goalCount = speciesProgress.values.count { it.total >= QA_GOAL_PER_SPECIES }
    val untestedCount = species.size - startedCount
    val targetName = when (targetKey) {
        null -> "Not set"
        TestFeedbackRepository.TARGET_NOISE -> "Noise / non-insect"
        else -> species.firstOrNull { modelLabel(it) == targetKey }?.common ?: targetKey.replace('_', ' ')
    }

    Column(
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
                    "Set the insect you are intentionally testing. Aim for $QA_GOAL_PER_SPECIES independent recordings per species; Stridulate keeps track for you.",
                    fontFamily = Inter, fontSize = 10.5.sp, color = ParchDim, lineHeight = 14.sp
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "$startedCount/${species.size} started · $goalCount at $QA_GOAL_PER_SPECIES+ · $untestedCount untested · ${progress.noiseTargetTests} noise tests",
                    fontFamily = JetBrainsMono, fontSize = 8.5.sp, color = Mute
                )
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallAction("Target: $targetName", Amber, Modifier.weight(1f)) { showTargets = true }
                    SmallAction("Testing progress", Biolume, Modifier.weight(1f)) { showProgress = true }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallAction("Export ALL QA ($feedbackCount)", Biolume, Modifier.weight(1f)) { onExport() }
                    if (feedbackCount > 0) SmallAction("Clear", Danger) { confirmClear = true }
                }
                Spacer(Modifier.height(6.dp))
                SmallAction("Mark current window as Noise", Amber, Modifier.fillMaxWidth()) { onMarkCurrentNoise() }
                if (targetKey == null) {
                    Spacer(Modifier.height(5.dp))
                    Text("Tip: set a target before marking Incorrect, so the log records what it should have been.", fontFamily = Inter, fontSize = 9.5.sp, color = Mute)
                }
            }
        }
    }

    if (showTargets) {
        AlertDialog(
            onDismissRequest = { showTargets = false },
            title = { Text("What are you testing?", color = Parch) },
            text = {
                LazyColumn(Modifier.height(430.dp)) {
                    item {
                        TargetRow("Unspecified / exploratory") { onClearTarget(); showTargets = false }
                        TargetRow("Noise / non-insect (wind, fan, HVAC, voices, etc.)") { onSetNoiseTarget(); showTargets = false }
                    }
                    items(species.sortedBy { it.common.lowercase() }, key = { it.id }) { sp ->
                        val p = speciesProgress.getValue(sp)
                        val suffix = if (p.total == 0) "UNTESTED" else "${p.total} tested · ✓${p.correct} · ✕${p.incorrect} · noise ${p.noise}"
                        TargetRow("${sp.common}  ·  $suffix") { onSetSpeciesTarget(sp); showTargets = false }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTargets = false }) { Text("Close") } },
            containerColor = Panel2
        )
    }

    if (showProgress) {
        QaProgressDialog(
            species = species,
            progressBySpecies = speciesProgress,
            correct = progress.correct,
            incorrect = progress.incorrect,
            noise = progress.noise,
            noiseTargetTests = progress.noiseTargetTests,
            exploratoryTests = progress.exploratoryTests,
            onPickSpecies = { sp -> onSetSpeciesTarget(sp); showProgress = false },
            onPickNoise = { onSetNoiseTarget(); showProgress = false },
            onDismiss = { showProgress = false }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear QA test log?") },
            text = { Text("Export it first if you want to keep the current Correct / Incorrect / Noise feedback and testing progress.") },
            confirmButton = { TextButton(onClick = { onClearLog(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            containerColor = Panel2
        )
    }
}

@Composable
private fun QaProgressDialog(
    species: List<Species>,
    progressBySpecies: Map<Species, QaSpeciesProgress>,
    correct: Int,
    incorrect: Int,
    noise: Int,
    noiseTargetTests: Int,
    exploratoryTests: Int,
    onPickSpecies: (Species) -> Unit,
    onPickNoise: () -> Unit,
    onDismiss: () -> Unit
) {
    var filter by rememberSaveable { mutableStateOf("needs") }
    val started = progressBySpecies.values.count { it.total > 0 }
    val complete = progressBySpecies.values.count { it.total >= QA_GOAL_PER_SPECIES }
    val filtered = species
        .filter { sp ->
            val total = progressBySpecies.getValue(sp).total
            when (filter) {
                "untested" -> total == 0
                "done" -> total >= QA_GOAL_PER_SPECIES
                "all" -> true
                else -> total < QA_GOAL_PER_SPECIES
            }
        }
        .sortedWith(compareBy<Species> { progressBySpecies.getValue(it).total }.thenBy { it.common.lowercase() })
    val nextNeeded = species
        .filter { progressBySpecies.getValue(it).total < QA_GOAL_PER_SPECIES }
        .minWithOrNull(compareBy<Species> { progressBySpecies.getValue(it).total }.thenBy { it.common.lowercase() })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("QA testing progress", color = Parch) },
        text = {
            Column {
                Text(
                    "Goal: $QA_GOAL_PER_SPECIES independent recordings per species. Tap any insect below to make it your next QA target.",
                    fontFamily = Inter, fontSize = 10.5.sp, color = ParchDim, lineHeight = 14.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "$started/${species.size} started · $complete/${species.size} at goal",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = Biolume
                )
                Text(
                    "ALL VERDICTS  ✓ $correct   ✕ $incorrect   NOISE $noise",
                    fontFamily = JetBrainsMono, fontSize = 8.5.sp, color = Mute
                )
                Text(
                    "Noise-target tests: $noiseTargetTests · exploratory: $exploratoryTests",
                    fontFamily = JetBrainsMono, fontSize = 8.5.sp, color = Mute
                )
                Spacer(Modifier.height(7.dp))
                nextNeeded?.let { next ->
                    SmallAction("Next needing test: ${next.common}", Biolume, Modifier.fillMaxWidth()) { onPickSpecies(next) }
                    Spacer(Modifier.height(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterAction("Needs", filter == "needs", Modifier.weight(1f)) { filter = "needs" }
                    FilterAction("Untested", filter == "untested", Modifier.weight(1f)) { filter = "untested" }
                    FilterAction("3+ Done", filter == "done", Modifier.weight(1f)) { filter = "done" }
                    FilterAction("All", filter == "all", Modifier.weight(1f)) { filter = "all" }
                }
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.height(330.dp)) {
                    item {
                        QaProgressSpecialRow("Noise / non-insect", "$noiseTargetTests targeted noise test${if (noiseTargetTests == 1) "" else "s"}") { onPickNoise() }
                    }
                    items(filtered, key = { it.id }) { sp ->
                        QaProgressRow(sp, progressBySpecies.getValue(sp)) { onPickSpecies(sp) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        containerColor = Panel2
    )
}

@Composable
private fun QaProgressRow(species: Species, progress: QaSpeciesProgress, onClick: () -> Unit) {
    val status = when {
        progress.total == 0 -> "UNTESTED"
        progress.total < QA_GOAL_PER_SPECIES -> "${progress.total}/$QA_GOAL_PER_SPECIES"
        else -> "${progress.total} TESTED"
    }
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(species.common, modifier = Modifier.weight(1f), fontFamily = Inter, fontSize = 11.5.sp, color = Parch)
            Text(status, fontFamily = JetBrainsMono, fontSize = 8.sp, color = if (progress.total >= QA_GOAL_PER_SPECIES) Biolume else Amber)
        }
        Text(species.latin, fontFamily = Inter, fontSize = 9.sp, color = Mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (progress.total > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("✓ ${progress.correct}", fontFamily = JetBrainsMono, fontSize = 8.5.sp, color = Biolume)
                Text("✕ ${progress.incorrect}", fontFamily = JetBrainsMono, fontSize = 8.5.sp, color = Danger)
                Text("noise ${progress.noise}", fontFamily = JetBrainsMono, fontSize = 8.5.sp, color = Amber)
            }
        }
    }
}

@Composable
private fun QaProgressSpecialRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(Panel, RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 7.dp)
    ) {
        Text(title, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Amber)
        Text(subtitle, fontFamily = JetBrainsMono, fontSize = 8.sp, color = Mute)
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun FilterAction(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Text(
        label,
        modifier = modifier.clickable(onClick = onClick)
            .background(if (selected) Amber.copy(alpha = 0.16f) else Panel, RoundedCornerShape(6.dp))
            .border(BorderStroke(1.dp, if (selected) Amber else Line), RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        fontFamily = JetBrainsMono,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        fontSize = 7.5.sp,
        color = if (selected) Amber else ParchDim
    )
}

@Composable
fun CandidateFeedbackButtons(onFeedback: (FeedbackVerdict) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        SmallAction("✓ Correct", Biolume, Modifier.weight(1f)) { onFeedback(FeedbackVerdict.CORRECT) }
        SmallAction("✕ Incorrect", Danger, Modifier.weight(1f)) { onFeedback(FeedbackVerdict.INCORRECT) }
        SmallAction("Noise", Amber, Modifier.weight(1f)) { onFeedback(FeedbackVerdict.NOISE) }
    }
}

@Composable
private fun TargetRow(text: String, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        fontFamily = Inter,
        fontSize = 11.5.sp,
        color = Parch
    )
}

@Composable
private fun SmallAction(label: String, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        modifier = modifier.clickable(onClick = onClick)
            .background(Panel2, RoundedCornerShape(7.dp))
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.65f)), RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 7.dp),
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 8.2.sp,
        color = accent
    )
}

private fun modelLabel(species: Species): String = species.latin.replace(' ', '_')
