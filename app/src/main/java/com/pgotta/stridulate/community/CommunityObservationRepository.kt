package com.pgotta.stridulate.community

import android.content.Context
import com.pgotta.stridulate.ui.IdResult
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Local-only archive for unresolved recordings and their optional community-identification links. */
class CommunityObservationRepository(private val context: Context) {
    private val directory = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }
    private val indexFile = File(directory, "index.json")
    private val _records = MutableStateFlow(loadRecords())
    val records: StateFlow<List<CommunityObservationRecord>> = _records

    fun audioFile(record: CommunityObservationRecord): File = File(directory, record.audioFileName)

    @Synchronized
    fun saveResult(result: IdResult): CommunityObservationRecord {
        result.communityRecordId?.let { existingId ->
            _records.value.firstOrNull { it.id == existingId }?.let { return it }
        }
        val evidence = result.evidenceAudio
            ?: throw IllegalStateException("This result no longer has an audio file to save.")
        if (!evidence.file.exists()) throw IllegalStateException("The temporary recording has expired.")

        val id = "STR-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6).uppercase(Locale.US)}"
        val audioName = "$id.wav"
        evidence.file.copyTo(File(directory, audioName), overwrite = true)
        val observation = result.observationContext
        val record = CommunityObservationRecord(
            id = id,
            createdAtMillis = System.currentTimeMillis(),
            observedAtMillis = evidence.observedAtMillis,
            source = evidence.source,
            audioFileName = audioName,
            sampleRate = evidence.sampleRate,
            durationSeconds = evidence.durationSeconds,
            decision = result.decision.name,
            decisionReason = result.decisionReason,
            modelTopLabel = result.modelTopLabel,
            modelTopConfidence = result.modelTopConfidence,
            candidates = result.candidates.take(3).map { candidate ->
                SavedCandidate(
                    label = candidate.label,
                    scientificName = candidate.species?.latin,
                    commonName = candidate.species?.common,
                    audioScore = candidate.audioConfidence,
                    reliabilityTier = candidate.reliability.tier.displayName
                )
            },
            qualityGrade = result.recordingQuality?.grade?.displayName,
            qualityScore = result.recordingQuality?.score,
            locationLabel = observation.locationLabel,
            latitude = observation.latitude,
            longitude = observation.longitude,
            region = observation.region.displayName,
            temperatureF = observation.temperatureF,
            weatherObservedAtMillis = observation.temperatureObservedAtMillis
        )
        replace(record)
        return record
    }

    @Synchronized
    fun markShared(recordId: String): CommunityObservationRecord = update(recordId) { current ->
        if (current.status.ordinal >= CommunityRecordStatus.SHARED.ordinal) current
        else current.copy(status = CommunityRecordStatus.SHARED)
    }

    @Synchronized
    fun linkINaturalist(recordId: String, value: String): CommunityObservationRecord {
        val observationId = parseObservationId(value)
            ?: throw IllegalArgumentException("Paste an iNaturalist observation URL or numeric observation ID.")
        return update(recordId) { current ->
            current.copy(
                status = CommunityRecordStatus.LINKED,
                iNaturalistObservationId = observationId,
                iNaturalistUrl = "https://www.inaturalist.org/observations/$observationId"
            )
        }
    }

    @Synchronized
    fun applyINaturalistSnapshot(recordId: String, snapshot: INaturalistSnapshot): CommunityObservationRecord =
        update(recordId) { current ->
            current.copy(
                status = if (current.status == CommunityRecordStatus.TRAINING_APPROVED) {
                    CommunityRecordStatus.TRAINING_APPROVED
                } else if (snapshot.communityTaxonScientificName != null) {
                    CommunityRecordStatus.COMMUNITY_ID
                } else {
                    CommunityRecordStatus.LINKED
                },
                iNaturalistObservationId = snapshot.observationId,
                iNaturalistUrl = snapshot.url,
                observerTaxonScientificName = snapshot.observerTaxonScientificName,
                observerTaxonCommonName = snapshot.observerTaxonCommonName,
                communityTaxonId = snapshot.communityTaxonId,
                communityTaxonScientificName = snapshot.communityTaxonScientificName,
                communityTaxonCommonName = snapshot.communityTaxonCommonName,
                communityTaxonRank = snapshot.communityTaxonRank,
                iNaturalistQualityGrade = snapshot.qualityGrade,
                identificationsCount = snapshot.identificationsCount,
                commentsCount = snapshot.commentsCount,
                iNaturalistUpdatedAt = snapshot.updatedAt,
                lastCheckedAtMillis = System.currentTimeMillis(),
                soundLicenseCode = snapshot.soundLicenseCode,
                soundAttribution = snapshot.soundAttribution
            )
        }

    @Synchronized
    fun approveForTraining(
        recordId: String,
        label: String,
        contributorCredit: String,
        rightsConfirmed: Boolean
    ): CommunityObservationRecord {
        val cleanLabel = label.trim()
        val credit = contributorCredit.trim()
        require(cleanLabel.isNotBlank()) { "Enter the reviewed species label." }
        require(credit.isNotBlank()) { "Enter the contributor credit/name." }
        require(rightsConfirmed) { "Confirm that you own the recording or have permission to license it." }
        return update(recordId) { current ->
            current.copy(
                status = CommunityRecordStatus.TRAINING_APPROVED,
                approvedTrainingLabel = cleanLabel,
                contributorCredit = credit,
                contributionLicense = CONTRIBUTION_LICENSE,
                rightsAttestedAtMillis = System.currentTimeMillis()
            )
        }
    }

    @Synchronized
    fun updateNote(recordId: String, note: String): CommunityObservationRecord =
        update(recordId) { it.copy(note = note.trim().takeIf(String::isNotBlank)) }

    @Synchronized
    fun delete(recordId: String) {
        val target = _records.value.firstOrNull { it.id == recordId } ?: return
        runCatching { audioFile(target).delete() }
        _records.value = _records.value.filterNot { it.id == recordId }
        persist()
    }

    fun exportTrainingBundle(recordId: String): File {
        val record = _records.value.firstOrNull { it.id == recordId }
            ?: throw IllegalArgumentException("Saved recording not found.")
        require(record.status == CommunityRecordStatus.TRAINING_APPROVED) {
            "Review and approve the community label before exporting a training bundle."
        }
        val audio = audioFile(record)
        require(audio.exists()) { "Saved WAV file is missing." }
        val exportDirectory = File(context.cacheDir, "community_exports").apply { mkdirs() }
        val zip = File(exportDirectory, "${record.id}-identified.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("${record.id}.wav"))
            audio.inputStream().use { it.copyTo(out) }
            out.closeEntry()

            val metadata = record.toJson().apply {
                put("schema", "stridulate-community-training-v1")
                put("review_required", true)
                put("training_use_note", "Human review remains required before this label enters a model dataset.")
            }.toString(2).toByteArray(Charsets.UTF_8)
            out.putNextEntry(ZipEntry("${record.id}.json"))
            out.write(metadata)
            out.closeEntry()

            val readme = buildString {
                appendLine("Stridulate community identification bundle")
                appendLine("Record: ${record.id}")
                appendLine("Reviewed label: ${record.approvedTrainingLabel}")
                appendLine("Contributor: ${record.contributorCredit}")
                appendLine("Contribution license: ${record.contributionLicense}")
                appendLine("Rights attested: ${record.rightsAttestedAtMillis ?: "missing"}")
                appendLine("iNaturalist: ${record.iNaturalistUrl ?: "not linked"}")
                appendLine()
                appendLine("The included WAV is the contributor's original local recording, not a downloaded iNaturalist asset.")
                appendLine("A maintainer must review the audio and iNaturalist evidence before adding it to training data.")
            }.toByteArray(Charsets.UTF_8)
            out.putNextEntry(ZipEntry("README.txt"))
            out.write(readme)
            out.closeEntry()
        }
        return zip
    }

    private fun parseObservationId(value: String): Long? {
        val trimmed = value.trim()
        trimmed.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        val match = Regex("(?:inaturalist\\.org/observations/|observations/)(\\d+)", RegexOption.IGNORE_CASE)
            .find(trimmed)
        return match?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
    }

    @Synchronized
    private fun update(
        recordId: String,
        transform: (CommunityObservationRecord) -> CommunityObservationRecord
    ): CommunityObservationRecord {
        val current = _records.value.firstOrNull { it.id == recordId }
            ?: throw IllegalArgumentException("Saved recording not found.")
        val updated = transform(current)
        replace(updated)
        return updated
    }

    @Synchronized
    private fun replace(record: CommunityObservationRecord) {
        _records.value = (listOf(record) + _records.value.filterNot { it.id == record.id })
            .sortedByDescending { it.createdAtMillis }
        persist()
    }

    private fun persist() {
        val root = JSONObject().apply {
            put("schema", "stridulate-community-archive-v1")
            put("records", JSONArray().apply { _records.value.forEach { put(it.toJson()) } })
        }
        val temp = File(directory, "index.json.tmp")
        temp.writeText(root.toString(2))
        if (!temp.renameTo(indexFile)) {
            temp.copyTo(indexFile, overwrite = true)
            temp.delete()
        }
    }

    private fun loadRecords(): List<CommunityObservationRecord> = runCatching {
        if (!indexFile.exists()) return@runCatching emptyList()
        val records = JSONObject(indexFile.readText()).optJSONArray("records") ?: JSONArray()
        buildList {
            for (i in 0 until records.length()) {
                records.optJSONObject(i)?.let { add(CommunityObservationRecord.fromJson(it)) }
            }
        }.filter { audioFile(it).exists() }.sortedByDescending { it.createdAtMillis }
    }.getOrDefault(emptyList())

    companion object {
        const val DIRECTORY_NAME = "community_observations"
        const val CONTRIBUTION_LICENSE = "CC BY 4.0"
    }
}
