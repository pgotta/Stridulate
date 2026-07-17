package com.pgotta.stridulate.community

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object CommunityShare {
    const val DEFAULT_GITHUB_REPOSITORY_URL = "https://github.com/pgotta/Stridulate"
    fun shareAudioForIdentification(
        context: Context,
        repository: CommunityObservationRepository,
        record: CommunityObservationRecord
    ) {
        val audio = repository.audioFile(record)
        require(audio.exists()) { "Saved WAV file is missing." }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            audio
        )
        val text = buildString {
            appendLine("Unknown singing insect recorded with Stridulate.")
            appendLine("Stridulate record ID: ${record.id}")
            appendLine("Observed: ${record.observedAtMillis?.let(::formatDate) ?: "enter the original date/time"}")
            appendLine("Location: ${record.locationLabel ?: record.region ?: "enter the original location"}")
            record.temperatureF?.let { appendLine("Weather context: ${String.format(Locale.US, "%.0f°F", it)}") }
            appendLine("Model outcome: ${record.decision.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }}")
            if (record.candidates.isNotEmpty()) {
                appendLine("Closest supported possibilities (not confirmations):")
                record.candidates.forEach { candidate ->
                    appendLine("- ${candidate.commonName ?: candidate.scientificName ?: candidate.label}: ${(candidate.audioScore * 100).toInt()}%")
                }
            }
            appendLine()
            appendLine("Please identify only as narrowly as you can support from the recording. A broad ID such as Insects is better than an uncertain species guess.")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Unknown insect sound · ${record.id}")
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share WAV to iNaturalist or another identifier"))
    }


    /**
     * Opens a public GitHub tracking issue without embedding a GitHub token in the app.
     * The scheduled repository workflow can refresh the linked public iNaturalist observation.
     */
    fun githubTrackingIssueUrl(record: CommunityObservationRecord): String {
        val observationUrl = record.iNaturalistUrl
            ?: throw IllegalStateException("Link an iNaturalist observation before opening a GitHub tracking issue.")
        val title = "[Community ID] ${record.id}"
        val body = buildString {
            appendLine("## Stridulate community identification")
            appendLine()
            appendLine("- **Record ID:** ${record.id}")
            appendLine("- **iNaturalist observation:** $observationUrl")
            appendLine("- **Current community taxon:** ${record.displayTaxon ?: "Awaiting identification"}")
            appendLine("- **Stridulate outcome:** ${record.decision.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }}")
            appendLine("- **Approximate context:** ${record.locationLabel ?: record.region ?: "Audio only"}")
            appendLine()
            appendLine("The repository bot may update this issue from the public iNaturalist observation. It does not download or approve iNaturalist media for model training.")
            appendLine()
            appendLine("Once a human has reviewed the label and the contributor has approved the original local WAV under CC BY 4.0, attach the Stridulate contribution ZIP here.")
            appendLine()
            appendLine("<!-- stridulate-record:${record.id} -->")
        }
        return "$DEFAULT_GITHUB_REPOSITORY_URL/issues/new" +
            "?title=${encode(title)}" +
            "&labels=${encode("inaturalist-tracking,needs-id")}" +
            "&body=${encode(body)}"
    }

    fun shareTrainingBundle(context: Context, file: File, record: CommunityObservationRecord) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val body = buildString {
            appendLine("Stridulate identified-recording contribution")
            appendLine("Record ID: ${record.id}")
            appendLine("Reviewed label: ${record.approvedTrainingLabel}")
            appendLine("iNaturalist: ${record.iNaturalistUrl ?: "not linked"}")
            appendLine("License: ${record.contributionLicense}")
            appendLine()
            appendLine("Attach this ZIP to the GitHub identified-recording issue form. Maintainer review is still required.")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Stridulate recording contribution · ${record.id}")
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share training contribution bundle"))
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun formatDate(value: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))
}
