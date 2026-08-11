package com.pgotta.stridulate.classifier

import android.content.Context
import com.pgotta.stridulate.audio.MeasuredSignature
import com.pgotta.stridulate.data.Species
import com.pgotta.stridulate.data.SpeciesReliabilityRepository
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.exp

/**
 * Exact shadow copy of the pre-v0.3 Epoch-19 Android classifier.
 *
 * This model is DIAGNOSTIC ONLY. Frozen J.1 / Perch remains the production
 * identification/logging path. The legacy model receives the same gained PCM as
 * J.1 so field/lab comparisons are apples-to-apples with respect to microphone gain.
 */
class LegacyStridulateClassifier(
    context: Context,
    species: List<Species>,
    modelAsset: String = MODEL_ASSET,
    labelsAsset: String = LABELS_ASSET,
    metadataAsset: String = METADATA_ASSET,
    normalizationAsset: String = NORMALIZATION_ASSET,
    reliabilityAsset: String = RELIABILITY_ASSET
) : InsectClassifier {

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Stridulate-Legacy-TFLite")
    }

    private val metadata = ModelMetadata.load(context, metadataAsset)
    private val labelBytes = context.assets.open(labelsAsset).use { it.readBytes() }
    private val labels: List<String> = labelBytes.toString(Charsets.UTF_8)
        .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    private fun normLatin(value: String): String =
        value.lowercase().replace('_', ' ').trim().replace(Regex("\\s+"), " ")

    private val speciesByLatin = species.associateBy { normLatin(it.latin) }
    private val reliabilityRepository = SpeciesReliabilityRepository(context, reliabilityAsset)
    private val normalizationFile = context.assets.open(normalizationAsset).bufferedReader().use { JSONObject(it.readText()) }
    private val mel = MelSpectrogram(metadata)
    private val interpreter: Interpreter
    private var outputElementCount: Int = 0

    override val policy = ClassificationPolicy(
        unknownLabel = metadata.unknownLabel,
        minimumConfidence = metadata.minimumConfidence,
        minimumMargin = metadata.minimumMargin,
        reliabilityByLabel = labels.associateWith(reliabilityRepository::forLabel),
        openSetSafetyPolicy = reliabilityRepository.openSetSafetyPolicy
    )
    override val classCount: Int get() = outputElementCount

    val backendName: String = "Old Stridulate · Epoch 19 · 67-class TFLite"

    init {
        require(assetSizeAndShaMatch(context, modelAsset)) {
            "Legacy Epoch-19 TFLite bytes/SHA-256 do not match the frozen comparison model."
        }
        require(labelsChecksumMatches(labelBytes, metadata.labelsSha256)) {
            "Legacy labels do not match the Epoch-19 metadata checksum."
        }
        require(kotlin.math.abs(normalizationFile.getDouble("mel_mean") - metadata.normalizationMean) < 1e-12 &&
            kotlin.math.abs(normalizationFile.getDouble("mel_std") - metadata.normalizationStd) < 1e-12) {
            "Legacy normalization does not match Epoch-19 metadata."
        }
        require(labels.size == metadata.classes) {
            "Legacy metadata declares ${metadata.classes} classes, but labels have ${labels.size}."
        }
        require(labels.getOrNull(metadata.unknownIndex) == metadata.unknownLabel) {
            "Legacy Unknown label/index contract does not match."
        }
        require(reliabilityRepository.modelLabelsSha256.equals(metadata.labelsSha256, ignoreCase = true)) {
            "Legacy reliability file does not match the Epoch-19 label checksum."
        }
        require(reliabilityRepository.labels == labels.toSet()) {
            "Legacy reliability file does not cover the exact Epoch-19 labels."
        }
        labels.filterNot { it == metadata.unknownLabel }.forEach { label ->
            require(speciesByLatin.containsKey(normLatin(label))) {
                "No field-guide entry exists for legacy model label $label."
            }
        }

        val model = loadModelFile(context, modelAsset)
        interpreter = try {
            executor.submit<Interpreter> {
                Interpreter(model, Interpreter.Options().apply { setNumThreads(4) }).also(::validateContract)
            }.get()
        } catch (e: ExecutionException) {
            executor.shutdownNow()
            val cause = e.cause ?: e
            throw IllegalStateException(cause.message ?: cause.javaClass.simpleName, cause)
        } catch (e: Throwable) {
            executor.shutdownNow()
            throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    override fun classify(signature: MeasuredSignature): List<Candidate> = emptyList()

    override fun classify(pcm: FloatArray, sampleRate: Int, signature: MeasuredSignature): List<Candidate> {
        val melWindows = mel.fromPcmWindows(pcm, sampleRate)
        require(melWindows.isNotEmpty()) { "Legacy preprocessor produced no audio windows." }
        return try {
            executor.submit<List<Candidate>> { runPooledInference(melWindows) }.get()
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            throw IllegalStateException(cause.message ?: cause.javaClass.simpleName, cause)
        }
    }

    private fun runPooledInference(melWindows: List<Array<FloatArray>>): List<Candidate> {
        val pooledLogits = DoubleArray(outputElementCount)
        melWindows.forEach { melSpec ->
            val logits = runSingleWindow(melSpec)
            for (index in pooledLogits.indices) pooledLogits[index] += logits[index]
        }
        val windowCount = melWindows.size.toDouble()
        for (index in pooledLogits.indices) pooledLogits[index] /= windowCount

        val scaled = DoubleArray(pooledLogits.size) { pooledLogits[it] / metadata.calibrationTemperature }
        val maxLogit = scaled.maxOrNull() ?: 0.0
        val exps = DoubleArray(scaled.size) { exp(scaled[it] - maxLogit) }
        val sum = exps.sum().takeIf { it > 0.0 && it.isFinite() }
            ?: throw IllegalStateException("Legacy model produced invalid calibrated scores.")
        val probabilities = DoubleArray(exps.size) { exps[it] / sum }
        val order = probabilities.indices.sortedByDescending { probabilities[it] }
        val topIndex = order.firstOrNull() ?: -1
        val runnerIndex = order.getOrNull(1) ?: -1
        val topConfidence = if (topIndex >= 0) probabilities[topIndex] else 0.0
        val runnerConfidence = if (runnerIndex >= 0) probabilities[runnerIndex] else 0.0
        val topLabel = labels.getOrNull(topIndex).orEmpty()
        val topAccepted = topIndex >= 0 &&
            topLabel != metadata.unknownLabel &&
            topConfidence >= metadata.minimumConfidence &&
            (topConfidence - runnerConfidence) >= metadata.minimumMargin

        return order.map { index ->
            val label = labels[index]
            Candidate(
                label = label,
                species = if (label == metadata.unknownLabel) null else speciesByLatin[normLatin(label)],
                confidence = probabilities[index],
                rawScore = pooledLogits[index],
                reliability = reliabilityRepository.forLabel(label),
                acceptanceThreshold = if (label == metadata.unknownLabel) null else metadata.minimumConfidence,
                evidenceAccepted = index == topIndex && topAccepted,
                evidenceSupport = if (index == topIndex) {
                    "Old Stridulate base gate: confidence >= ${metadata.minimumConfidence} and margin >= ${metadata.minimumMargin}"
                } else {
                    "Old Stridulate shadow ranking"
                }
            )
        }
    }

    private fun runSingleWindow(melSpec: Array<FloatArray>): DoubleArray {
        val frames = metadata.inputShape[2]
        val input = ByteBuffer.allocateDirect(Float.SIZE_BYTES * metadata.nMels * frames)
            .order(ByteOrder.nativeOrder())
        for (melIndex in 0 until metadata.nMels) {
            for (time in 0 until frames) input.putFloat(melSpec[melIndex][time])
        }
        input.rewind()

        val output = ByteBuffer.allocateDirect(Float.SIZE_BYTES * outputElementCount)
            .order(ByteOrder.nativeOrder())
        interpreter.run(input, output)
        output.rewind()
        return DoubleArray(outputElementCount) { output.float.toDouble() }.also { logits ->
            require(logits.all(Double::isFinite)) { "Legacy model produced a non-finite result." }
        }
    }

    private fun validateContract(runtime: Interpreter) {
        val input = runtime.getInputTensor(0)
        val output = runtime.getOutputTensor(0)
        val inputShape = input.shape().toList()
        val outputShape = output.shape().toList()
        require(input.dataType() == DataType.FLOAT32 && output.dataType() == DataType.FLOAT32)
        require(inputShape == metadata.inputShape) {
            "Legacy model input shape is $inputShape; expected ${metadata.inputShape}."
        }
        require(outputShape == metadata.outputShape) {
            "Legacy model output shape is $outputShape; expected ${metadata.outputShape}."
        }
        outputElementCount = outputShape.fold(1) { product, dimension -> product * dimension }
        require(outputElementCount == labels.size)
    }

    private fun loadModelFile(context: Context, asset: String): MappedByteBuffer {
        context.assets.openFd(asset).use { afd ->
            FileInputStream(afd.fileDescriptor).channel.use { channel ->
                return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }

    private fun labelsChecksumMatches(bytes: ByteArray, expected: String): Boolean {
        val text = bytes.toString(Charsets.UTF_8)
        val lf = text.replace("\r\n", "\n").replace('\r', '\n')
        val candidates = listOf(
            bytes,
            lf.toByteArray(Charsets.UTF_8),
            lf.replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
        )
        return candidates.any { sha256(it).equals(expected, ignoreCase = true) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    override fun close() {
        try {
            executor.submit { interpreter.close() }.get()
        } catch (_: Throwable) {
        } finally {
            executor.shutdownNow()
        }
    }

    private fun assetSizeAndShaMatch(context: Context, asset: String): Boolean = try {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        context.assets.open(asset).buffered(1024 * 1024).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
                total += count
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        total == MODEL_BYTES && sha.equals(MODEL_SHA256, ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    companion object {
        const val MODEL_ASSET = "legacy_insect_model.tflite"
        const val LABELS_ASSET = "legacy_labels.txt"
        const val METADATA_ASSET = "model_meta.json"
        const val NORMALIZATION_ASSET = "normalization.json"
        const val RELIABILITY_ASSET = "android_reliability.json"
        const val MODEL_BYTES = 81_037_632L
        const val MODEL_SHA256 = "395ba28333005261956edc3fd5366e8b14f57dbe3d3cb14d40ba6ea2da0afccf"

        fun isAvailable(context: Context): Boolean = try {
            context.assets.openFd(MODEL_ASSET).use { it.declaredLength == MODEL_BYTES }
        } catch (_: Exception) {
            false
        }
    }
}
