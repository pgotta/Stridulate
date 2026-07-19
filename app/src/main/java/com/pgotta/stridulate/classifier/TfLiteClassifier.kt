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

/** Runs the 67-class epoch-19 FLOAT32 TFLite model fully offline. */
class TfLiteClassifier(
    context: Context,
    species: List<Species>,
    modelAsset: String = "insect_model.tflite",
    labelsAsset: String = "labels.txt",
    metadataAsset: String = "model_meta.json",
    normalizationAsset: String = "normalization.json",
    reliabilityAsset: String = "android_reliability.json"
) : InsectClassifier {

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Stridulate-TFLite")
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

    val backendName: String = "Epoch 19 · 67-class · CPU · FLOAT32"
    val datasetName: String get() = metadata.dataset

    init {
        require(sha256(labelBytes).equals(metadata.labelsSha256, ignoreCase = true)) {
            "labels.txt does not match the active model metadata checksum."
        }
        require(kotlin.math.abs(normalizationFile.getDouble("mel_mean") - metadata.normalizationMean) < 1e-12 &&
            kotlin.math.abs(normalizationFile.getDouble("mel_std") - metadata.normalizationStd) < 1e-12) {
            "normalization.json does not match the active model metadata."
        }
        require(labels.size == metadata.classes) {
            "Metadata declares ${metadata.classes} classes, but labels.txt has ${labels.size}."
        }
        require(labels.getOrNull(metadata.unknownIndex) == metadata.unknownLabel) {
            "The unknown label/index contract does not match labels.txt."
        }
        require(reliabilityRepository.modelLabelsSha256.equals(metadata.labelsSha256, ignoreCase = true)) {
            "android_reliability.json does not match the model labels checksum."
        }
        require(reliabilityRepository.labels == labels.toSet()) {
            "android_reliability.json does not cover the exact ordered model label set."
        }
        require(reliabilityRepository.verifiedLabels.isNotEmpty() &&
            reliabilityRepository.verifiedLabels.all { it in labels && it != metadata.unknownLabel }) {
            "android_reliability.json has no valid Verified classes."
        }
        labels.filterNot { it == metadata.unknownLabel }.forEach { label ->
            require(speciesByLatin.containsKey(normLatin(label))) {
                "No field-guide entry exists for model label $label."
            }
        }

        val model = loadModelFile(context, modelAsset)
        interpreter = try {
            executor.submit<Interpreter> {
                Interpreter(
                    model,
                    Interpreter.Options().apply { setNumThreads(4) }
                ).also(::validateContract)
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

    private fun validateContract(runtime: Interpreter) {
        val input = runtime.getInputTensor(0)
        val output = runtime.getOutputTensor(0)
        val inputShape = input.shape().toList()
        val outputShape = output.shape().toList()

        require(input.dataType() == DataType.FLOAT32) {
            "Model input is ${input.dataType()}, expected FLOAT32."
        }
        require(output.dataType() == DataType.FLOAT32) {
            "Model output is ${output.dataType()}, expected FLOAT32."
        }
        require(inputShape == metadata.inputShape) {
            "Model input shape is $inputShape; v5 metadata requires ${metadata.inputShape}."
        }
        require(outputShape == metadata.outputShape) {
            "Model output shape is $outputShape; v5 metadata requires ${metadata.outputShape}."
        }

        // Derived from the actual output tensor: never assume 66 or any other class count.
        outputElementCount = outputShape.fold(1) { product, dimension -> product * dimension }
        require(outputShape.firstOrNull() == 1 && outputElementCount == labels.size) {
            "Model emits $outputElementCount values, but v5 labels contain ${labels.size}."
        }
    }

    private fun loadModelFile(context: Context, asset: String): MappedByteBuffer {
        context.assets.openFd(asset).use { afd ->
            FileInputStream(afd.fileDescriptor).channel.use { channel ->
                return channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength
                )
            }
        }
    }

    /** A hand-measured signature alone is insufficient for this neural model. */
    override fun classify(signature: MeasuredSignature): List<Candidate> = emptyList()

    override fun classify(
        pcm: FloatArray,
        sampleRate: Int,
        signature: MeasuredSignature
    ): List<Candidate> {
        val melWindows = mel.fromPcmWindows(pcm, sampleRate)
        require(melWindows.isNotEmpty()) { "The model preprocessor produced no audio windows." }
        melWindows.forEach { melSpec ->
            require(melSpec.size == metadata.nMels && melSpec.firstOrNull()?.size == metadata.inputShape[2]) {
                "Preprocessor produced ${melSpec.size}x${melSpec.firstOrNull()?.size ?: 0}; " +
                    "model requires ${metadata.nMels}x${metadata.inputShape[2]}."
            }
        }

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

        // Calibration is applied after mean-logit pooling, exactly as metadata specifies.
        val scaled = DoubleArray(pooledLogits.size) {
            pooledLogits[it] / metadata.calibrationTemperature
        }
        val maxLogit = scaled.maxOrNull() ?: 0.0
        val exps = DoubleArray(scaled.size) { exp(scaled[it] - maxLogit) }
        val sum = exps.sum().takeIf { it > 0.0 && it.isFinite() }
            ?: throw IllegalStateException("The model produced invalid calibrated scores.")
        val probabilities = DoubleArray(exps.size) { exps[it] / sum }

        return probabilities.indices
            .sortedByDescending { probabilities[it] }
            .map { index ->
                val label = labels[index]
                Candidate(
                    label = label,
                    species = if (label == metadata.unknownLabel) null else speciesByLatin[normLatin(label)],
                    confidence = probabilities[index],
                    rawScore = pooledLogits[index],
                    reliability = reliabilityRepository.forLabel(label)
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
            require(logits.all(Double::isFinite)) { "The model produced a non-finite result." }
        }
    }

    override fun close() {
        try {
            executor.submit { interpreter.close() }.get()
        } catch (_: Throwable) {
            // The process is shutting down.
        } finally {
            executor.shutdownNow()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
