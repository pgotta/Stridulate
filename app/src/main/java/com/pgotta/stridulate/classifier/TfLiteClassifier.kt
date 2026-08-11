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
 * migration. Despite the historical class name, v0.3 runs the frozen Stage J.1
 * Perch 2.0 + Stage-D affine/calibration path through ONNX Runtime.
 *
 * Normal repository builds may stage the three frozen runtime files externally.
 * Standalone Android Studio packages may bundle the exact same verified files under
 * assets/runtime; when present they are copied once into files/models on first launch.
 */
class TfLiteClassifier(
    context: Context,
    species: List<Species>,
    reliabilityAsset: String = "android_reliability.json"
) : InsectClassifier {

    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "Stridulate-J1-Perch") }
    private val labels = app.assets.open(LABELS_ASSET).bufferedReader().useLines { lines ->
        lines.map(String::trim).filter(String::isNotBlank).toList()
    }
    private val calibrationFile = ensureRuntimeFile(
        CALIBRATION_RELATIVE_PATH, CALIBRATION_BUNDLED_ASSET, CALIBRATION_BYTES, CALIBRATION_SHA256
    )
    private val affineFile = ensureRuntimeFile(
        AFFINE_RELATIVE_PATH, AFFINE_BUNDLED_ASSET, AFFINE_BYTES, AFFINE_SHA256
    )
    private val modelSourceAvailable = runtimeSourceAvailable(
        MODEL_RELATIVE_PATH, MODEL_BUNDLED_ASSET, PERCH_BYTES
    )
    private val calibration = J1Calibration.load(calibrationFile, labels)
    private val affine = J1Affine.load(affineFile, labels.size)
    private val reliabilityRepository = SpeciesReliabilityRepository(app, reliabilityAsset)
    private val speciesByLatin = species.associateBy { normalizeLatin(it.latin) }
    private val reliabilityByLabel = labels.associateWith(::j1Reliability)

    private val env = OrtEnvironment.getEnvironment("Stridulate-J1")
    private data class SessionBundle(
        val session: OrtSession,
        val inputName: String,
        val globalOutputName: String
    )
    @Volatile private var sessionBundle: SessionBundle? = null
    private val sessionLock = Any()

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
        require(modelSourceAvailable) {
            "Frozen J.1 Perch model is unavailable. Build the self-contained Android Studio package once so the verified model is downloaded into the APK."
        }
        // The 413 MB Perch model is intentionally not copied or opened here.
        // Session creation is deferred until the first analysis, which already runs off the UI thread.
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
        val active = getOrCreateSession()
        val bytes = ByteBuffer.allocateDirect(WINDOW_SAMPLES * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        val floats = bytes.asFloatBuffer()
        floats.put(window)
        floats.rewind()
        OnnxTensor.createTensor(env, floats, longArrayOf(1L, WINDOW_SAMPLES.toLong())).use { input ->
            active.session.run(mapOf(active.inputName to input), setOf(active.globalOutputName)).use { result ->
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

    private fun getOrCreateSession(): SessionBundle {
        sessionBundle?.let { return it }
        synchronized(sessionLock) {
            sessionBundle?.let { return it }
            val model = ensureRuntimeFile(
                MODEL_RELATIVE_PATH, MODEL_BUNDLED_ASSET, PERCH_BYTES, PERCH_SHA256
            )
            val options = OrtSession.SessionOptions()
            try {
                val opened = env.createSession(model.absolutePath, options)
                val input = opened.inputNames.singleOrNull()
                    ?: throw IllegalStateException("Perch must expose exactly one input.")
                val inputInfo = opened.inputInfo[input]?.info as? TensorInfo
                    ?: throw IllegalStateException("Perch input is not a float tensor.")
                val inputShape = inputInfo.shape
                require(inputShape.size == 2 && inputShape.last() == WINDOW_SAMPLES.toLong()) {
                    "Unexpected Perch input shape: ${inputShape.toList()}"
                }
                val global = opened.outputInfo.entries.filter { (_, node) ->
                    val info = node.info as? TensorInfo ?: return@filter false
                    info.shape.filter { it > 0 }.fold(1L) { a, b -> a * b } == EMBEDDING_DIM.toLong()
                }.map { it.key }
                require(global.size == 1) {
                    "Could not identify unique 1536-value Perch global embedding output."
                }
                return SessionBundle(opened, input, global.single()).also { sessionBundle = it }
            } catch (t: Throwable) {
                throw IllegalStateException(t.message ?: t.javaClass.simpleName, t)
            } finally {
                options.close()
            }
        }
    }

    private fun runtimeSourceAvailable(
        relativePath: String,
        bundledAsset: String,
        expectedBytes: Long
    ): Boolean {
        val target = File(app.filesDir, relativePath)
        if (target.isFile && target.length() == expectedBytes) return true
        return try {
            app.assets.open(bundledAsset).use { true }
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureRuntimeFile(
        relativePath: String,
        bundledAsset: String,
        expectedBytes: Long,
        expectedSha256: String
    ): File {
        val target = File(app.filesDir, relativePath)
        if (target.isFile && target.length() == expectedBytes && fileSha256(target) == expectedSha256) {
            return target
        }

        val bundled = try { app.assets.open(bundledAsset) } catch (_: Exception) { null }
        if (bundled == null) {
            val detail = if (target.exists()) "installed file is invalid" else "runtime file is not installed"
            throw IllegalStateException(
                "Frozen J.1 $detail: ${target.name}. Use the self-contained Android Studio package or stage the verified runtime files."
            )
        }

        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".installing")
        runCatching { temp.delete() }
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            bundled.use { input ->
                temp.outputStream().buffered(1024 * 1024).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copied += count
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            require(copied == expectedBytes) {
                "Bundled frozen J.1 ${target.name} has $copied bytes; expected $expectedBytes."
            }
            require(sha.equals(expectedSha256, ignoreCase = true)) {
                "Bundled frozen J.1 ${target.name} checksum mismatch; refusing inference."
            }
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("Could not replace invalid frozen J.1 runtime ${target.name}.")
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            return target
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1024 * 1024).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun close() {
        try { executor.submit { sessionBundle?.session?.close() }.get() }
        catch (_: Throwable) { }
        finally {
            sessionBundle = null
            executor.shutdownNow()
        }
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
            val independent = DoubleArray(CLASS_COUNT) { sigmoid(raw[it] / 4.0) }
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
                require(file.isFile) { "Frozen J.1 calibration is missing from verified runtime storage." }
                val bytes = file.readBytes()
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                require(digest.equals(CALIBRATION_SHA256, ignoreCase = true)) {
                    "Frozen J.1 calibration checksum mismatch."
                }
                val root = org.json.JSONObject(bytes.toString(Charsets.UTF_8))
                require(root.getInt("schema_version") == 1)
                require(root.getInt("species_count") == CLASS_COUNT)
                require(root.getInt("embedding_dim") == EMBEDDING_DIM)
                require(root.getInt("sample_rate") == SAMPLE_RATE)
                require(root.getInt("window_samples") == WINDOW_SAMPLES)
                val thresholdsJson = root.getJSONArray("thresholds")
                val thresholds = DoubleArray(CLASS_COUNT) { thresholdsJson.getDouble(it) }
                val calibratorsJson = root.getJSONArray("calibrators")
                val calibrators = List(CLASS_COUNT) { index ->
                    val item = calibratorsJson.getJSONObject(index)
                    require(item.getString("species") == labels[index]) { "J.1 calibrator label order mismatch at $index." }
                    val weights = item.getJSONArray("coef")
                    require(weights.length() == FEATURE_COUNT)
                    Calibrator(DoubleArray(FEATURE_COUNT) { weights.getDouble(it) }, item.getDouble("intercept"))
                }
                val longSession = root.getJSONObject("long_session_policy")
                return J1Calibration(
                    thresholds,
                    calibrators,
                    root.getDouble("temperature"),
                    longSession.getDouble("threshold_offset"),
                    longSession.getDouble("persistence_ratio"),
                    longSession.getInt("persistence_windows")
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
                    "Frozen J.1 affine head is missing or has the wrong size in verified runtime storage."
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
        private const val LABELS_ASSET = "j1_labels.txt"
        private const val CALIBRATION_RELATIVE_PATH = "models/j1_calibration.json"
        private const val CALIBRATION_BUNDLED_ASSET = "runtime/j1_calibration.json"
        private const val CALIBRATION_BYTES = 34189L
        private const val CALIBRATION_SHA256 = "fddecaabe0e39ebdb98eac5e804b2f77a5c9f9f25afd4510b5c740cd83e2d7f9"
        private const val AFFINE_RELATIVE_PATH = "models/j1_stage_d_affine.bin"
        private const val AFFINE_BUNDLED_ASSET = "runtime/j1_stage_d_affine.bin"
        private const val AFFINE_BYTES = 541040L
        private const val AFFINE_SHA256 = "066c6cf64b165abb83af93e4b1a38a4a3ffce2fa9ec476a5b3b9695466a6d76a"
        private const val MODEL_RELATIVE_PATH = "models/perch_v2_no_dft.onnx"
        private const val MODEL_BUNDLED_ASSET = "runtime/perch_v2_no_dft.onnx"
        private const val PERCH_BYTES = 413350933L
        private const val PERCH_SHA256 = "4dcf71c18a147198545944bb5149697e89e3ad2e16637fa8f0edf6d13035a017"

        private fun sigmoid(value: Double): Double {
            val z = value.coerceIn(-40.0, 40.0)
            return 1.0 / (1.0 + exp(-z))
        }
    }
}
