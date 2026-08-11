package com.pgotta.stridulate.qa

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object TestFeedbackShare {
    fun share(context: Context, file: File) {
        require(file.isFile) { "QA feedback export is missing." }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Stridulate beta QA feedback")
            putExtra(Intent.EXTRA_TEXT, "Stridulate v0.3 QA feedback: Correct / Incorrect / Noise labels with J.1 Top 3 scores and gates.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export Stridulate QA test log"))
    }
}
