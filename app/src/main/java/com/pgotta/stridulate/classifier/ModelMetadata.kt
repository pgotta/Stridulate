package com.pgotta.stridulate.classifier

import android.content.Context
import kotlin.math.abs
import org.json.JSONObject

/** Runtime contract bundled beside the 67-class epoch-19 model. */
data class ModelMetadata(
    val schemaVersion: Int,
    val dataset: String,
    val classes: Int,
    val labelsSha256: String,
    val unknownLabel: String,
    val unknownIndex: Int,
    val inputShape: List<Int>,
    val outputShape: List<Int>,
    val sampleRate: Int,
    val clipSeconds: Double,
    val windowOverlap: Double,
    val nMels: Int,
    val nFft: Int,
    val winLength: Int,
    val hopLength: Int,
    val fmin: Double,
    val fmax: Double,
    val topDb: Double,
    val center: Boolean,
    val padMode: String,
    val melScale: String,
    val normalizationMean: Double,
    val normalizationStd: Double,
    val calibrationTemperature: Double,
    val minimumConfidence: Double,
    val minimumMargin: Double,
    val pooling: String
) {
    companion object {
        private const val V5_POOLING = "mean_logits_across_all_50_percent_overlapping_windows"

        fun load(context: Context, assetName: String = "model_meta.json"): ModelMetadata {
            val root = context.assets.open(assetName).bufferedReader().use { JSONObject(it.readText()) }
            val normalization = root.getJSONObject("normalization")
            return ModelMetadata(
                schemaVersion = root.getInt("schema_version"),
                dataset = root.getString("dataset"),
                classes = root.getInt("classes"),
                labelsSha256 = root.getString("labels_sha256"),
                unknownLabel = root.getString("unknown_label"),
                unknownIndex = root.getInt("unknown_index"),
                inputShape = root.getJSONArray("model_input_shape").toIntList(),
                outputShape = root.getJSONArray("model_output_shape").toIntList(),
                sampleRate = root.getInt("sample_rate"),
                clipSeconds = root.getDouble("clip_seconds"),
                windowOverlap = root.getDouble("window_overlap"),
                nMels = root.getInt("n_mels"),
                nFft = root.getInt("n_fft"),
                winLength = root.getInt("win_length"),
                hopLength = root.getInt("hop_length"),
                fmin = root.getDouble("fmin"),
                fmax = root.getDouble("fmax"),
                topDb = root.getDouble("top_db"),
                center = root.getBoolean("center"),
                padMode = root.getString("pad_mode"),
                melScale = root.getString("mel_scale"),
                normalizationMean = normalization.getDouble("mean"),
                normalizationStd = normalization.getDouble("std"),
                calibrationTemperature = root.getDouble("calibration_temperature"),
                minimumConfidence = root.getDouble("minimum_confidence"),
                minimumMargin = root.getDouble("minimum_top1_top2_margin"),
                pooling = root.getString("pooling")
            ).also { metadata ->
                require(metadata.schemaVersion == 4) { "Unsupported model metadata schema ${metadata.schemaVersion}." }
                require(metadata.dataset.isNotBlank()) { "Model metadata is missing its dataset provenance." }
                require(metadata.classes > 1) { "Model metadata has no usable classes." }
                require(metadata.unknownIndex in 0 until metadata.classes) { "Invalid Unknown class index." }
                require(metadata.inputShape.size == 4 && metadata.inputShape[0] == 1 && metadata.inputShape[3] == 1) {
                    "Expected a single-channel, four-dimensional model input."
                }
                require(metadata.inputShape[1] == metadata.nMels) { "Input mel count does not match metadata." }
                require(metadata.outputShape.size == 2 && metadata.outputShape[0] == 1) {
                    "Expected a batched two-dimensional model output."
                }
                require(metadata.outputShape.fold(1) { product, value -> product * value } == metadata.classes) {
                    "Metadata output shape does not match its class count."
                }
                require(metadata.sampleRate == 44100) { "The active model requires 44.1 kHz preprocessing." }
                require(close(metadata.clipSeconds, 5.0) && close(metadata.windowOverlap, 0.5)) {
                    "The active model requires 5-second windows with 50% overlap."
                }
                require(metadata.nMels == 128 && metadata.nFft == 2048 && metadata.hopLength == 512) {
                    "The active model mel settings do not match the supported Android preprocessor."
                }
                require(metadata.winLength == metadata.nFft) { "Only full-length periodic Hann windows are supported." }
                require(close(metadata.fmin, 400.0) && close(metadata.fmax, 22050.0) && close(metadata.topDb, 80.0)) {
                    "The active model frequency or decibel settings do not match the Android frontend."
                }
                require(metadata.center && metadata.padMode == "reflect") {
                    "The active model requires centered STFT with reflect padding."
                }
                require(metadata.melScale == "htk") { "The active model requires the HTK mel scale." }
                require(root.isNull("mel_norm")) { "The active model requires mel_norm=null." }
                require(normalization.getString("type") == "global_zscore") {
                    "The active model requires global z-score normalization."
                }
                require(metadata.normalizationStd > 0.0) { "Invalid global normalization standard deviation." }
                require(metadata.calibrationTemperature > 0.0) { "Invalid calibration temperature." }
                require(metadata.minimumConfidence in 0.0..1.0 && metadata.minimumMargin in 0.0..1.0) {
                    "Invalid calibrated rejection thresholds."
                }
                require(metadata.pooling == V5_POOLING) { "Unsupported model pooling contract." }
            }
        }

        private fun close(a: Double, b: Double): Boolean = abs(a - b) < 1e-9
    }
}

private fun org.json.JSONArray.toIntList(): List<Int> =
    List(length()) { index -> getInt(index) }
