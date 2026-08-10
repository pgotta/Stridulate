package com.pgotta.stridulate.classifier

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import com.pgotta.stridulate.audio.MeasuredSignature
import com.pgotta.stridulate.data.AcceptanceRule
import com.pgotta.stridulate.data.OpenSetSafetyPolicy
import com.pgotta.stridulate.data.ReliabilityInfo
import com.pgotta.stridulate.data.ReliabilityTier
import com.pgotta.stridulate.data.Species
import com.pgotta.stridulate.data.SpeciesReliabilityRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Compatibility entry point retained so the existing ViewModel does not need a
 * migration. Despite the historical class name, v3 runs the frozen Stage J.1
 * Perch 2.0 + Stage-D affine/calibration path through ONNX Runtime.
 *
 * Runtime files are intentionally not committed or packed into the APK. The v3
 * build/install helper verifies and stages Perch, the affine head and the original
 * archived J.1 calibration into files/models without clearing app data.
 */
class TfLiteClassifier(
    context: Context,
    species: List<Species>,
    @Suppress("UNUSED_PARAMETER") modelAsset: String = "insect_model.tflite",
    @Suppress("UNUSED_PARAMETER") labelsAsset: String = "labels.txt",
    @Suppress("UNUSED_PARAMETER") metadataAsset: String = "model_meta.json",
    @Suppress("UNUSED_PARAMETER") normalizationAsset: String = "normalization.json",
    reliabilityAsset: String = "android_reliability.json"
) : InsectClassifier {

    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "Stridulate-J1-Perch") }
    private val labels = app.assets.open(LABELS_ASSET).bufferedReader().useLines { lines ->
        lines.map(String::trim).filter(String::isNotBlank).toList()
    }
    private val calibration = J1Calibration.load(File(app.filesDir, CALIBRATION_RELATIVE_PATH), labels)
    private val affine = J1Affine.load(File(app.filesDir, AFFINE_RELATIVE_PATH), labels.size)
    private val reliabilityRepository = SpeciesReliabilityRepository(app, reliabilityAsset)
    private val speciesByLatin = species.associateBy { normalizeLatin(it.latin) }
    private val reliabilityByLabel = labels.associateWith(::j1Reliability)

    private val env = OrtEnvironment.getEnvironment("Stridulate-J1")
    private val session: OrtSession
    private val inputName: String
    private val globalOutputName: String

    override val policy = ClassificationPolicy(
        unknownLabel = "Unknown_or_unsupported",
        minimumConfidence = 0.0,
        minimumMargin = 0.0,
        reliabilityByLabel = reliabilityByLabel,
        openSetSafetyPolicy = OpenSetSafetyPolicy(
            enabled = true,
            fieldTestMode = true,
            strongMinimumConfidence = 0.95,
            strongMinimumMargin = 0.10,
            strongVerifiedOnly = false,
            strongRequiresGoodQuality = true,
            strongRequiresAcousticProfile = false,
            rulesByTier = ReliabilityTier.entries.associateWith { AcceptanceRule(0.0, 0.0, false) },
            speciesOverrides = emptyMap()
        )
    )

    override val classCount: Int get() = labels.size
    val backendName: String = "Frozen J.1 · Perch 2.0 · 88 species · ONNX CPU"
    val datasetName: String = "Stage D + frozen Stage J.1 calibration"

    init {
        require(labels.size == CLASS_COUNT && labels.distinct().size == CLASS_COUNT) {
            "Frozen J.1 requires exactly $CLASS_COUNT unique labels; got ${labels.size}."
        }
        labels.forEach { label ->
            require(speciesByLatin.containsKey(normalizeLatin(label))) {
                "No field-guide entry exists for frozen J.1 label $label."
            }
        }
        val model = File(app.filesDir, MODEL_RELATIVE_PATH)
        verifyPerchModel(model)
        try {
            val opened = executor.submit<Triple<OrtSession, String, String>> {
                val options = OrtSession.SessionOptions()
                try {
                    val s = env.createSession(model.absolutePath, options)
                    val input = s.inputNames.singleOrNull()
                        ?: throw IllegalStateException("Perch must expose exactly one input.")
                    val inputInfo = s.inputInfo[input]?.info as? TensorInfo
                        ?: throw IllegalStateException("Perch input is not a float tensor.")
                    val inputShape = inputInfo.shape
                    require(inputShape.size == 2 && inputShape.last() == WINDOW_SAMPLES.toLong()) {
                        "Unexpected Perch input shape: ${inputShape.toList()}"
                    }
                    val global = s.outputInfo.entries.filter { (_, node) ->
                        val info = node.info as? TensorInfo ?: return@filter false
                        info.shape.filter { it > 0 }.fold(1L) { a, b -> a * b } == EMBEDDING_DIM.toLong()
                    }.map { it.key }
                    require(global.size == 1) {
                        "Could not identify unique 1536-value Perch global embedding output."
                    }
                    Triple(s, input, global.single())
                } finally {
                    options.close()
                }
            }.get()
            session = opened.first
            inputName = opened.second
            globalOutputName = opened.third
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
        require(sampleRate > 0 && pcm.isNotEmpty()) { "Audio input is empty or has an invalid sample rate." }
        return try {
            executor.submit<List<Candidate>> { classifyInternal(pcm, sampleRate) }.get()
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            throw IllegalStateException(cause.message ?: cause.javaClass.simpleName, cause)
        }
    }

    private fun classifyInternal(pcm: FloatArray, sourceRate: Int): List<Candidate> {
        val resampled = resampleBandLimited(pcm, sourceRate, SAMPLE_RATE)
        val windows = windows(resampled)
        require(windows.isNotEmpty()) { "Perch preprocessor produced no five-second windows." }
        val perWindowScores = ArrayList<DoubleArray>(windows.size)
        val perWindowRaw = ArrayList<DoubleArray>(windows.size)
        for (window in windows) {
            val embedding = runPerch(window)
            val raw = affine.raw(embedding)
            perWindowRaw += raw
            perWindowScores += calibration.calibratedScores(raw)
        }

        val maxScores = DoubleArray(CLASS_COUNT)
        val maxRaw = DoubleArray(CLASS_COUNT)
        for (s in 0 until CLASS_COUNT) {
            maxScores[s] = perWindowScores.maxOf { it[s] }
            maxRaw[s] = perWindowRaw.maxOf { it[s] }
        }

        val accepted = BooleanArray(CLASS_COUNT)
        val decisionThresholds = DoubleArray(CLASS_COUNT)
        if (windows.size == 1) {
            for (s in 0 until CLASS_COUNT) {
                decisionThresholds[s] = calibration.thresholds[s]
                accepted[s] = maxScores[s] >= decisionThresholds[s]
            }
        } else {
            val hard = DoubleArray(CLASS_COUNT) {
                (calibration.thresholds[it] + calibration.sessionThresholdOffset).coerceIn(0.05, 0.9995)
            }
            val near = DoubleArray(CLASS_COUNT) {
                (hard[it] * calibration.sessionPersistenceRatio).coerceIn(0.05, 0.9995)
            }
            for (s in 0 until CLASS_COUNT) {
                decisionThresholds[s] = hard[s]
                val hardHit = perWindowScores.any { it[s] >= hard[s] }
                var run = 0
                var bestRun = 0
                for (scores in perWindowScores) {
                    run = if (scores[s] >= near[s]) run + 1 else 0
                    bestRun = max(bestRun, run)
                }
                accepted[s] = hardHit || bestRun >= calibration.sessionPersistenceWindows
            }
        }

        val order = (0 until CLASS_COUNT).sortedWith(
            compareByDescending<Int> { accepted[it] }.thenByDescending { maxScores[it] }.thenBy { it }
        )
        return order.map { index ->
            val label = labels[index]
            val threshold = decisionThresholds[index]
            val highThreshold = max(0.95, threshold + 0.05).coerceAtMost(0.9995)
            Candidate(
                label = label,
                species = speciesByLatin[normalizeLatin(label)],
                confidence = maxScores[index].coerceIn(0.0, 1.0),
                rawScore = maxRaw[index],
                reliability = reliabilityByLabel.getValue(label),
                acceptanceThreshold = threshold,
                highConfidenceThreshold = highThreshold,
                evidenceAccepted = accepted[index],
                evidenceSupport = if (windows.size == 1) {
                    "Frozen J.1 5-second calibrated threshold"
                } else {
                    "Frozen J.1 long-session policy across ${windows.size} windows"
                }
            )
        }
    }

    private fun runPerch(window: FloatArray): FloatArray {
        require(window.size == WINDOW_SAMPLES)
        val bytes = ByteBuffer.allocateDirect(WINDOW_SAMPLES * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        val floats = bytes.asFloatBuffer()
        floats.put(window)
        floats.rewind()
        OnnxTensor.createTensor(env, floats, longArrayOf(1L, WINDOW_SAMPLES.toLong())).use { input ->
            session.run(mapOf(inputName to input), setOf(globalOutputName)).use { result ->
                val value = result.iterator().next().value as? OnnxTensor
                    ?: throw IllegalStateException("Perch global output is not a tensor.")
                val buffer = value.floatBuffer
                    ?: throw IllegalStateException("Perch global output is not FLOAT32-compatible.")
                require(buffer.remaining() == EMBEDDING_DIM) {
                    "Perch global embedding has ${buffer.remaining()} values, expected $EMBEDDING_DIM."
                }
                return FloatArray(EMBEDDING_DIM).also(buffer::get)
            }
        }
    }

    private fun windows(samples: FloatArray): List<FloatArray> {
        if (samples.size <= WINDOW_SAMPLES) {
            return listOf(FloatArray(WINDOW_SAMPLES).also { out ->
                System.arraycopy(samples, 0, out, 0, samples.size.coerceAtMost(WINDOW_SAMPLES))
            })
        }
        val out = ArrayList<FloatArray>()
        var start = 0
        while (start + WINDOW_SAMPLES <= samples.size) {
            out += samples.copyOfRange(start, start + WINDOW_SAMPLES)
            start += WINDOW_HOP_SAMPLES
        }
        val lastStart = samples.size - WINDOW_SAMPLES
        if (out.isEmpty() || start - WINDOW_HOP_SAMPLES != lastStart) {
            out += samples.copyOfRange(lastStart, samples.size)
        }
        return out
    }

    private fun resampleBandLimited(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input.copyOf()
        val outSize = ((input.size.toLong() * toRate + fromRate / 2L) / fromRate)
            .coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val result = FloatArray(outSize)
        val radius = 12
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val cutoff = minOf(1.0, toRate.toDouble() / fromRate.toDouble()) * 0.94
        for (i in result.indices) {
            val src = i * ratio
            val center = floor(src).toInt()
            var sum = 0.0
            var weightSum = 0.0
            for (k in (center - radius + 1)..(center + radius)) {
                if (k !in input.indices) continue
                val d = src - k
                val ad = kotlin.math.abs(d)
                if (ad >= radius) continue
                val sinc = if (ad < 1e-10) cutoff else sin(PI * d * cutoff) / (PI * d)
                val window = 0.5 + 0.5 * cos(PI * d / radius)
                val w = sinc * window
                sum += input[k] * w
                weightSum += w
            }
            result[i] = if (kotlin.math.abs(weightSum) > 1e-12) {
                (sum / weightSum).toFloat().coerceIn(-1f, 1f)
            } else 0f
        }
        return result
    }

    private fun j1Reliability(label: String): ReliabilityInfo {
        val prior = reliabilityRepository.forLabel(label)
        return if (prior.tier == ReliabilityTier.NOT_READY || prior.tier == ReliabilityTier.UNKNOWN_GATE) {
            ReliabilityInfo(
                tier = ReliabilityTier.EXPERIMENTAL,
                primaryResultAllowed = true,
                uiWording = "Frozen J.1 supports this acoustic class; species-specific independent field evaluation is still limited.",
                evidenceSource = "Frozen Stage J.1 calibration"
            )
        } else prior.copy(primaryResultAllowed = true)
    }

    private fun verifyPerchModel(model: File) {
        require(model.isFile) {
            "Frozen J.1 Perch model is not installed. Run RUN_BUILD_AND_INSTALL_FINAL_ANDROID.bat to stage it without clearing app data."
        }
        require(model.length() == PERCH_BYTES) {
            "Perch model size mismatch: ${model.length()} bytes; expected $PERCH_BYTES."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().buffered(1024 * 1024).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        require(sha.equals(PERCH_SHA256, ignoreCase = true)) { "Perch model SHA-256 mismatch; refusing inference." }
    }

    override fun close() {
        try { executor.submit { session.close() }.get() }
        catch (_: Throwable) { }
        finally { executor.shutdownNow() }
    }

    private fun normalizeLatin(value: String): String =
        value.lowercase().replace('_', ' ').trim().replace(Regex("\\s+"), " ")

    private data class J1Calibration(
        val thresholds: DoubleArray,
        val calibrators: List<Calibrator>,
        val temperature: Double,
        val sessionThresholdOffset: Double,
        val sessionPersistenceRatio: Double,
        val sessionPersistenceWindows: Int
    ) {
        fun calibratedScores(raw: DoubleArray): DoubleArray {
            val independent = DoubleArray(CLASS_COUNT) { sigmoid(raw[it] / STAGE_D_TEMPERATURE) }
            val scaled = DoubleArray(CLASS_COUNT) { raw[it] / temperature }
            val maxValue = scaled.maxOrNull() ?: 0.0
            val exps = DoubleArray(CLASS_COUNT) { exp((scaled[it] - maxValue).coerceIn(-80.0, 80.0)) }
            val denom = exps.sum().coerceAtLeast(1e-300)
            val stageProb = DoubleArray(CLASS_COUNT) { exps[it] / denom }
            val top5 = scaled.indices.sortedByDescending { stageProb[it] }.take(5).toSet()
            return DoubleArray(CLASS_COUNT) { s ->
                val ind = independent[s]
                val ge25 = if (ind >= 0.25) 1.0 else 0.0
                val ge50 = if (ind >= 0.50) 1.0 else 0.0
                val features = doubleArrayOf(
                    ind, ind, ind, ind, ge25, ge50, ge25,
                    ind, ind, ind, ge25, ge50, ge25,
                    stageProb[s], if (s in top5) 1.0 else 0.0
                )
                val cal = calibrators[s]
                var z = cal.intercept
                for (j in features.indices) z += features[j] * cal.coef[j]
                sigmoid(z)
            }
        }

        companion object {
            fun load(file: File, labels: List<String>): J1Calibration {
                require(file.isFile && file.length() == CALIBRATION_BYTES) {
                    "Original frozen J.1 calibration is missing or has the wrong size. Run the v3 build/install helper."
                }
                val bytes = file.readBytes()
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                require(digest.equals(CALIBRATION_SHA256, ignoreCase = true)) {
                    "Original frozen J.1 calibration checksum mismatch."
                }
                val root = org.json.JSONObject(bytes.toString(Charsets.UTF_8))
                val thresholdsJson = root.getJSONArray("thresholds")
                require(thresholdsJson.length() == CLASS_COUNT) { "J.1 threshold count is not $CLASS_COUNT." }
                val thresholds = DoubleArray(CLASS_COUNT) { thresholdsJson.getDouble(it) }
                val calibratorsJson = root.getJSONArray("calibrators")
                require(calibratorsJson.length() == CLASS_COUNT) { "J.1 calibrator count is not $CLASS_COUNT." }
                val calibrators = List(CLASS_COUNT) { index ->
                    val item = calibratorsJson.getJSONObject(index)
                    require(item.getString("species") == labels[index]) { "J.1 calibrator label order mismatch at $index." }
                    val weights = item.getJSONArray("coef")
                    require(weights.length() == FEATURE_COUNT)
                    Calibrator(DoubleArray(FEATURE_COUNT) { weights.getDouble(it) }, item.getDouble("intercept"))
                }
                val sessionPolicy = root.getJSONObject("session_policy")
                return J1Calibration(
                    thresholds,
                    calibrators,
                    STAGE_D_TEMPERATURE,
                    sessionPolicy.getDouble("threshold_offset"),
                    sessionPolicy.getDouble("persistence_ratio"),
                    sessionPolicy.getInt("persistence_windows")
                )
            }
        }
    }

    private data class Calibrator(val coef: DoubleArray, val intercept: Double)

    private class J1Affine private constructor(private val weights: FloatArray, private val bias: FloatArray) {
        fun raw(embedding: FloatArray): DoubleArray {
            require(embedding.size == EMBEDDING_DIM)
            val out = DoubleArray(CLASS_COUNT) { bias[it].toDouble() }
            var offset = 0
            for (d in 0 until EMBEDDING_DIM) {
                val x = embedding[d].toDouble()
                for (s in 0 until CLASS_COUNT) out[s] += x * weights[offset + s]
                offset += CLASS_COUNT
            }
            return out
        }

        companion object {
            fun load(file: File, classes: Int): J1Affine {
                require(file.isFile && file.length() == AFFINE_BYTES) {
                    "Frozen J.1 affine head is missing or has the wrong size. Run the v3 build/install helper."
                }
                val bytes = file.readBytes()
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                require(digest.equals(AFFINE_SHA256, ignoreCase = true)) { "Frozen J.1 affine head checksum mismatch." }
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val magicBytes = ByteArray(8).also(buffer::get)
                require(magicBytes.toString(Charsets.US_ASCII) == "STRJ1AF1") { "Invalid J.1 affine magic." }
                val dim = buffer.int
                val count = buffer.int
                require(dim == EMBEDDING_DIM && count == classes && count == CLASS_COUNT) {
                    "Unexpected J.1 affine shape $dim x $count."
                }
                val weights = FloatArray(dim * count) { buffer.float }
                val bias = FloatArray(count) { buffer.float }
                require(!buffer.hasRemaining()) { "Unexpected trailing bytes in J.1 affine asset." }
                return J1Affine(weights, bias)
            }
        }
    }

    companion object {
        private const val CLASS_COUNT = 88
        private const val EMBEDDING_DIM = 1536
        private const val FEATURE_COUNT = 15
        private const val SAMPLE_RATE = 32000
        private const val WINDOW_SAMPLES = 160000
        private const val WINDOW_HOP_SAMPLES = 80000
        private const val STAGE_D_TEMPERATURE = 4.0
        private const val LABELS_ASSET = "j1_labels.txt"
        private const val CALIBRATION_RELATIVE_PATH = "models/j1_calibration.json"
        private const val CALIBRATION_BYTES = 69561L
        private const val CALIBRATION_SHA256 = "9a8323d4f6aea3bd85d36d55eadbc38d6eb85088451bee9682a46216ee79c70f"
        private const val AFFINE_RELATIVE_PATH = "models/j1_stage_d_affine.bin"
        private const val AFFINE_BYTES = 541040L
        private const val AFFINE_SHA256 = "066c6cf64b165abb83af93e4b1a38a4a3ffce2fa9ec476a5b3b9695466a6d76a"
        private const val MODEL_RELATIVE_PATH = "models/perch_v2_no_dft.onnx"
        private const val PERCH_BYTES = 413350933L
        private const val PERCH_SHA256 = "4dcf71c18a147198545944bb5149697e89e3ad2e16637fa8f0edf6d13035a017"

        private fun sigmoid(value: Double): Double {
            val z = value.coerceIn(-40.0, 40.0)
            return 1.0 / (1.0 + exp(-z))
        }
    }
}
