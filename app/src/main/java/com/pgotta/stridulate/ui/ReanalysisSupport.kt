package com.pgotta.stridulate.ui

import android.net.Uri

/**
 * Re-analyzes a saved Unknown using the frozen J.1 audio path only.
 *
 * The original archive record, WAV, notes, iNaturalist linkage and training-review
 * state are left untouched. The standard result screen is temporary unless the
 * user explicitly chooses to save another Unknown.
 */
fun StridulateViewModel.reanalyzeSavedUnknown(recordId: String) {
    val record = communityRecords.value.firstOrNull { it.id == recordId }
    if (record == null) {
        showCommunityNotice("Saved recording was not found.")
        return
    }
    val audio = communityRepository.audioFile(record)
    if (!audio.isFile || audio.length() <= 44L) {
        showCommunityNotice("The saved WAV is missing or empty.")
        return
    }
    analyzeFile(Uri.fromFile(audio), useCurrentContext = false)
}
