package com.pgotta.stridulate.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pgotta.stridulate.community.CommunityObservationRecord
import com.pgotta.stridulate.community.CommunityRecordStatus
import com.pgotta.stridulate.community.EvidenceSource
import com.pgotta.stridulate.ui.StridulateViewModel
import com.pgotta.stridulate.ui.components.GhostButton
import com.pgotta.stridulate.ui.components.PrimaryButton
import com.pgotta.stridulate.ui.reanalyzeSavedUnknown
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
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun CommunityArchiveScreen(
    records: List<CommunityObservationRecord>,
    busyMessage: String?,
    onBack: () -> Unit,
    onRefreshAll: () -> Unit,
    onOpenRecord: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        AppBarRow(
            "Unknowns",
            "${records.size} SAVED",
            onBack = onBack,
            status = when {
                records.any { it.hasSpeciesLevelCommunityIdentification } -> "species IDs found"
                records.any { it.hasCommunityIdentification } -> "broad IDs found"
                else -> "community review"
            },
            statusOn = records.any { it.hasCommunityIdentification }
        )
        if (busyMessage != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(11.dp)).background(Panel)
                    .border(BorderStroke(1.dp, Line), RoundedCornerShape(11.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = Biolume, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(busyMessage, fontFamily = Inter, color = ParchDim, fontSize = 12.5.sp)
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Panel)
                .border(BorderStroke(1.dp, Line), RoundedCornerShape(13.dp)).padding(14.dp)
        ) {
            Text("COMMUNITY IDENTIFICATION LOOP", fontFamily = JetBrainsMono, color = Amber, fontSize = 10.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(7.dp))
            Text(
                "Move any Log recording here for manual review. Listen to the complete WAV, re-analyze it with frozen J.1, add notes, and optionally share it to iNaturalist for community identification. Nothing is uploaded unless you choose Share.",
                fontFamily = Inter,
                color = ParchDim,
                fontSize = 12.5.sp,
                lineHeight = 18.sp
            )
            if (records.any { it.iNaturalistObservationId != null }) {
                Spacer(Modifier.height(10.dp))
                GhostButton("Check all linked observations", onRefreshAll, Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(12.dp))

        if (records.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("🎙️", fontSize = 38.sp)
                Spacer(Modifier.height(12.dp))
                Text("No unknown recordings saved", fontFamily = Fraunces, color = Parch, fontSize = 20.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Move a recording from Log, or save a No confident match from the result screen. Unknowns keeps the full WAV available for re-analysis, listening, notes and optional community review.",
                    fontFamily = Inter,
                    color = Mute,
                    fontSize = 12.5.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(records, key = { it.id }) { record ->
                    CommunityRecordRow(record) { onOpenRecord(record.id) }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun CommunityRecordRow(record: CommunityObservationRecord, onClick: () -> Unit) {
    val statusColor = statusColor(record.status)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(13.dp))
            .clickable(onClick = onClick).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF17231F)),
            contentAlignment = Alignment.Center
        ) { Text(if (record.hasCommunityIdentification) "✓" else "?", color = statusColor, fontSize = 19.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                record.displayTaxon ?: record.candidates.firstOrNull()?.commonName ?: "Unknown singing insect",
                fontFamily = Fraunces,
                color = Parch,
                fontSize = 16.sp
            )
            Text(record.id, fontFamily = JetBrainsMono, color = Mute, fontSize = 9.5.sp)
            Text(
                "${record.status.displayName} · ${formatShortDate(record.createdAtMillis)}",
                fontFamily = Inter,
                color = statusColor,
                fontSize = 11.sp
            )
        }
        Text("›", color = Mute, fontSize = 20.sp)
    }
}

@Composable
fun CommunityRecordScreen(
    record: CommunityObservationRecord,
    busyMessage: String?,
    onBack: () -> Unit,
    onShareToINaturalist: () -> Unit,
    onLinkINaturalist: (String) -> Unit,
    onRefreshINaturalist: () -> Unit,
    onOpenINaturalist: () -> Unit,
    onOpenGitHubTracking: () -> Unit,
    onPlayRecording: () -> Unit,
    onUpdateNote: (String) -> Unit,
    onApproveTraining: (label: String, credit: String, rightsConfirmed: Boolean) -> Unit,
    onExportBundle: () -> Unit,
    onDelete: () -> Unit
) {
    val vm: StridulateViewModel = viewModel()
    var observationLink by remember(record.iNaturalistObservationId) { mutableStateOf(record.iNaturalistUrl.orEmpty()) }
    var showApprove by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var reviewNote by remember(record.id, record.note) { mutableStateOf(record.note.orEmpty()) }

    if (showApprove) {
        var label by remember(record.communityTaxonScientificName) {
            mutableStateOf(record.communityTaxonScientificName ?: record.observerTaxonScientificName.orEmpty())
        }
        var credit by remember(record.contributorCredit) { mutableStateOf(record.contributorCredit.orEmpty()) }
        var rightsConfirmed by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showApprove = false },
            title = { Text("Approve for training", fontFamily = Fraunces, color = Parch) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "This is a human review step—not an automatic acceptance of iNaturalist consensus. Confirm the recording, label, and attribution. The original local WAV will be contributed under CC BY 4.0.",
                        fontFamily = Inter, color = ParchDim, fontSize = 12.5.sp, lineHeight = 18.sp
                    )
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Reviewed scientific label") }, singleLine = true)
                    OutlinedTextField(value = credit, onValueChange = { credit = it }, label = { Text("Contributor name or handle") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rightsConfirmed, onCheckedChange = { rightsConfirmed = it })
                        Text(
                            "I recorded this audio or have permission to license it under CC BY 4.0.",
                            fontFamily = Inter, color = ParchDim, fontSize = 11.5.sp, lineHeight = 16.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onApproveTraining(label, credit, rightsConfirmed)
                        showApprove = false
                    },
                    enabled = label.isNotBlank() && credit.isNotBlank() && rightsConfirmed
                ) { Text("Approve CC BY 4.0") }
            },
            dismissButton = { TextButton(onClick = { showApprove = false }) { Text("Cancel") } },
            containerColor = Panel2
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete saved recording?", fontFamily = Fraunces, color = Parch) },
            text = { Text("This removes the local WAV and metadata. It does not delete an iNaturalist observation.", color = ParchDim) },
            confirmButton = { TextButton(onClick = { showDelete = false; onDelete() }) { Text("Delete", color = Danger) } },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
            containerColor = Panel2
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        AppBarRow(
            "Saved unknown",
            record.status.displayName.uppercase(),
            onBack = onBack,
            status = record.status.displayName.lowercase(),
            statusOn = record.hasCommunityIdentification
        )

        busyMessage?.let {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(11.dp)).background(Panel)
                    .border(BorderStroke(1.dp, Line), RoundedCornerShape(11.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = Biolume, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(it, fontFamily = Inter, color = ParchDim, fontSize = 12.5.sp)
            }
        }

        SectionCard("RECORDING") {
            ValueLine("Record ID", record.id)
            ValueLine("Saved", formatLongDate(record.createdAtMillis))
            ValueLine(
                "Observed",
                record.observedAtMillis?.let(::formatLongDate)
                    ?: if (record.source == EvidenceSource.IMPORTED) "Original time unknown—correct it in iNaturalist" else "Unknown"
            )
            ValueLine("Length", String.format(Locale.US, "%.1f seconds", record.durationSeconds))
            ValueLine("Quality", listOfNotNull(record.qualityGrade, record.qualityScore?.let { "$it/100" }).joinToString(" · ").ifBlank { "Not measured" })
            ValueLine("Context", record.locationLabel ?: record.region ?: "Audio only")
            record.temperatureF?.let { ValueLine("Temperature", String.format(Locale.US, "%.0f°F", it)) }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard("LISTEN AND REVIEW") {
            PrimaryButton("▶ Play full recording", onPlayRecording, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                "Re-analyze with frozen J.1",
                { vm.reanalyzeSavedUnknown(record.id) },
                Modifier.fillMaxWidth(),
                container = Biolume,
                content = Color(0xFF0B1A0C)
            )
            Text(
                "Runs the saved WAV through the new 88-species detector audio-only (up to 30 seconds). The original Unknown, notes and linked iNaturalist observation stay unchanged.",
                fontFamily = Inter, color = Mute, fontSize = 10.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 7.dp)
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = reviewNote,
                onValueChange = { reviewNote = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Private review notes") },
                placeholder = { Text("What did you hear? Suspected species, background noise, time in the recording…") },
                minLines = 3
            )
            Spacer(Modifier.height(8.dp))
            GhostButton("Save review note", { onUpdateNote(reviewNote) }, Modifier.fillMaxWidth())
            Text(
                "Notes and audio remain on this device unless you explicitly share or export them.",
                fontFamily = Inter, color = Mute, fontSize = 10.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 7.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionCard("STRIDULATE RESULT") {
            ValueLine("Outcome", record.decision.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
            ValueLine("Model top", "${record.modelTopLabel.replace('_', ' ')} · J.1 score ${(record.modelTopConfidence * 100).roundToInt()}")
            if (record.candidates.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                record.candidates.forEachIndexed { index, candidate ->
                    Text(
                        "${index + 1}. ${candidate.commonName ?: candidate.scientificName ?: candidate.label} · ${(candidate.audioScore * 100).roundToInt()}% · ${candidate.reliabilityTier}",
                        fontFamily = Inter, color = ParchDim, fontSize = 11.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard("1 · SHARE FOR COMMUNITY ID") {
            Text(
                "This opens Android's share sheet with the original WAV and a prepared note. Choose iNaturalist, enter or verify the original date/location, and start with a broad ID such as Insects unless you personally support something narrower.",
                fontFamily = Inter, color = ParchDim, fontSize = 12.5.sp, lineHeight = 18.sp
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton("Share WAV to iNaturalist", onShareToINaturalist, Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(12.dp))
        SectionCard("2 · LINK THE OBSERVATION") {
            if (record.iNaturalistObservationId == null) {
                Text(
                    "After uploading, copy the iNaturalist observation URL and paste it here. Stridulate stores only the public observation ID and checks it on demand.",
                    fontFamily = Inter, color = ParchDim, fontSize = 12.5.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(9.dp))
                OutlinedTextField(
                    value = observationLink,
                    onValueChange = { observationLink = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("iNaturalist observation URL or ID") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Link and check now", { onLinkINaturalist(observationLink) }, Modifier.fillMaxWidth())
            } else {
                ValueLine("Observation", record.iNaturalistObservationId.toString())
                ValueLine("Observer ID", record.observerTaxonCommonName ?: record.observerTaxonScientificName ?: "Not set")
                ValueLine("Community ID", record.communityTaxonCommonName ?: record.communityTaxonScientificName ?: "Still needs identification")
                ValueLine(
                    "Resolution",
                    when {
                        record.hasSpeciesLevelCommunityIdentification -> "Species-level; human review still required"
                        record.hasCommunityIdentification -> "Broad ${record.communityTaxonRank ?: "taxon"}; needs refinement"
                        else -> "Awaiting community taxon"
                    }
                )
                record.communityTaxonScientificName?.let { scientific ->
                    Text(scientific, fontFamily = Fraunces, fontStyle = FontStyle.Italic, color = ParchDim, fontSize = 13.sp)
                }
                ValueLine("Rank", record.communityTaxonRank ?: "—")
                ValueLine("iNaturalist quality", record.iNaturalistQualityGrade ?: "—")
                ValueLine("Activity", "${record.identificationsCount} identifications · ${record.commentsCount} comments")
                record.soundLicenseCode?.let { ValueLine("iNaturalist sound license", it) }
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Check latest ID", onRefreshINaturalist, Modifier.weight(1f))
                    GhostButton("Open observation", onOpenINaturalist, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                GhostButton("Track this ID on GitHub", onOpenGitHubTracking, Modifier.fillMaxWidth())
                Text(
                    "This opens a prefilled public issue. A repository workflow can post the latest public iNaturalist community ID without receiving your iNaturalist login or copying its audio.",
                    fontFamily = Inter, color = Mute, fontSize = 10.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 7.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard("3 · CONTRIBUTE A KNOWN RECORDING") {
            Text(
                "An iNaturalist community taxon is evidence, not an automatic model label. Listen again and verify the identification. Approval creates a ZIP containing your original WAV and metadata; Stridulate never downloads iNaturalist-hosted media into the dataset.",
                fontFamily = Inter, color = ParchDim, fontSize = 12.5.sp, lineHeight = 18.sp
            )
            Spacer(Modifier.height(10.dp))
            if (record.status == CommunityRecordStatus.TRAINING_APPROVED) {
                ValueLine("Reviewed label", record.approvedTrainingLabel ?: "—")
                ValueLine("Contributor", record.contributorCredit ?: "—")
                ValueLine("License", record.contributionLicense ?: "—")
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Export GitHub contribution ZIP", onExportBundle, Modifier.fillMaxWidth())
            } else {
                PrimaryButton(
                    "Review and approve label",
                    { showApprove = true },
                    Modifier.fillMaxWidth(),
                    container = if (record.hasSpeciesLevelCommunityIdentification) Biolume else Amber,
                    content = Color(0xFF0B1A0C)
                )
                if (!record.hasSpeciesLevelCommunityIdentification) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (record.hasCommunityIdentification) {
                            "The current community result is broad and should not be treated as a known species. Approve only after independent review supports a specific label."
                        } else {
                            "No community taxon is available yet. Approve only if you independently reviewed the recording and are confident."
                        },
                        fontFamily = Inter, color = Amber, fontSize = 11.5.sp, lineHeight = 16.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        GhostButton("Delete local recording", { showDelete = true }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Panel)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(13.dp)).padding(14.dp)
    ) {
        Text(title, fontFamily = JetBrainsMono, color = Amber, fontSize = 10.sp, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun ValueLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, fontFamily = JetBrainsMono, color = Mute, fontSize = 10.sp, modifier = Modifier.width(112.dp))
        Text(value, fontFamily = Inter, color = ParchDim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
    }
}

private fun statusColor(status: CommunityRecordStatus): Color = when (status) {
    CommunityRecordStatus.SAVED -> Mute
    CommunityRecordStatus.SHARED -> Amber
    CommunityRecordStatus.LINKED -> Amber
    CommunityRecordStatus.COMMUNITY_ID -> Biolume
    CommunityRecordStatus.TRAINING_APPROVED -> Biolume
}

private fun formatShortDate(value: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(value))

private fun formatLongDate(value: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))
