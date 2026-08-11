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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgotta.stridulate.data.Species
import com.pgotta.stridulate.qa.FeedbackVerdict
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

@Composable
fun TestFeedbackPanel(
    species: List<Species>,
    targetKey: String?,
    feedbackCount: Int,
    onSetSpeciesTarget: (Species) -> Unit,
    onSetNoiseTarget: () -> Unit,
    onClearTarget: () -> Unit,
    onExport: () -> Unit,
    onClearLog: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showTargets by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
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

    if (showTargets) {
        AlertDialog(
            onDismissRequest = { showTargets = false },
            title = { Text("What are you testing?", color = Parch) },
            text = {
                LazyColumn(Modifier.height(430.dp)) {
                    item {
                        TargetRow("Unspecified / exploratory") { onClearTarget(); showTargets = false }
                        TargetRow("Noise / non-insect (wind, child, HVAC, etc.)") { onSetNoiseTarget(); showTargets = false }
                    }
                    items(species.sortedBy { it.common.lowercase() }, key = { it.id }) { sp ->
                        TargetRow("${sp.common}  ·  ${sp.latin}") { onSetSpeciesTarget(sp); showTargets = false }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTargets = false }) { Text("Close") } },
            containerColor = Panel2
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear QA test log?") },
            text = { Text("Export it first if you want to keep the current Correct / Incorrect / Noise feedback.") },
            confirmButton = { TextButton(onClick = { onClearLog(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            containerColor = Panel2
        )
    }
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
        fontSize = 12.sp,
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
        fontSize = 8.5.sp,
        color = accent
    )
}

private fun modelLabel(species: Species): String = species.latin.replace(' ', '_')
