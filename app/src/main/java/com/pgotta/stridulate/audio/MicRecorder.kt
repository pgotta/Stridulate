package com.pgotta.stridulate.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live microphone capture. It keeps a six-second rolling *raw* float buffer for
 * inference while streaming the complete original PCM16 recording to a private
 * temp file. SoundSensitivity is applied only to the live visualization here;
 * ClipAnalyzer applies the same gain once to the raw buffer before inference.
 */
class MicRecorder(
    private val fftSize: Int = 4096
) {
    @Volatile private var recording = false
    private var thread: Thread? = null
    private var rawFile: File? = null
    private var rawOutput: BufferedOutputStream? = null
    private var totalSamples: Long = 0L

    var sampleRate: Int = 48000
        private set

    private val _spectrogramColumn = MutableStateFlow(FloatArray(0))
    val spectrogramColumn: StateFlow<FloatArray> = _spectrogramColumn

    private val _loudness = MutableStateFlow(0f)
    val loudness: StateFlow<Float> get() = _loudness

    private val pcmBuffer = ArrayList<Float>()
    private val pcmLock = Any()

    fun capturedPcm(): Pair<FloatArray, Int> = synchronized(pcmLock) {
        pcmBuffer.toFloatArray() to sampleRate
    }

    fun clearPcm() = synchronized(pcmLock) { pcmBuffer.clear() }

    fun capturedRawFile(): File? = rawFile
    fun capturedDurationSeconds(): Double = totalSamples.toDouble() / sampleRate.toDouble()

    @SuppressLint("MissingPermission")
    fun start(rawPcmFile: File? = null, onFrame: (spectrum: FloatArray, timeSec: Double) -> Unit) {
        if (recording) return
        clearPcm()
        totalSamples = 0L
        rawFile = rawPcmFile
        rawOutput = rawPcmFile?.let {
            it.parentFile?.mkdirs()
            BufferedOutputStream(FileOutputStream(it))
        }

        val minBuf = AudioRecord.getMinBufferSize(
            48000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, fftSize * 2)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            48000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        sampleRate = record.sampleRate

        val fft = Fft(fftSize)
        val pcmShort = ShortArray(fftSize)
        val rawFrame = FloatArray(fftSize)
        val analysisFrame = FloatArray(fftSize)
        val pcmBytes = ByteArray(fftSize * 2)
        val specHeight = 96

        recording = true
        record.startRecording()
        val startNs = System.nanoTime()

        thread = Thread {
            var filled = 0
            try {
                while (recording) {
                    val read = record.read(pcmShort, filled, fftSize - filled)
                    if (read <= 0) continue
                    filled += read
                    if (filled < fftSize) continue
                    filled = 0

                    var byteIndex = 0
                    for (i in 0 until fftSize) {
                        val sample = pcmShort[i]
                        val raw = sample / 32768f
                        rawFrame[i] = raw
                        analysisFrame[i] = SoundSensitivity.applySample(raw)
                        pcmBytes[byteIndex++] = (sample.toInt() and 0xFF).toByte()
                        pcmBytes[byteIndex++] = ((sample.toInt() ushr 8) and 0xFF).toByte()
                    }
                    rawOutput?.write(pcmBytes)
                    totalSamples += fftSize

                    synchronized(pcmLock) {
                        for (v in rawFrame) pcmBuffer.add(v)
                        val maxLen = sampleRate * PCM_SECONDS
                        if (pcmBuffer.size > maxLen) {
                            val excess = pcmBuffer.size - maxLen
                            pcmBuffer.subList(0, excess).clear()
                        }
                    }

                    val spectrum = fft.magnitudeSpectrum(analysisFrame)
                    val t = (System.nanoTime() - startNs) / 1e9
                    onFrame(spectrum, t)

                    val col = FloatArray(specHeight)
                    val hiBin = (16000.0 / (sampleRate / 2.0) * (fftSize / 2)).toInt()
                        .coerceIn(1, spectrum.size - 1)
                    var peak = 0f
                    for (r in 0 until specHeight) {
                        val frac = 1f - r.toFloat() / specHeight
                        val bin = (frac * hiBin).toInt().coerceIn(0, spectrum.size - 1)
                        val v = spectrum[bin] / 255f
                        col[r] = v
                        if (v > peak) peak = v
                    }
                    _spectrogramColumn.value = col
                    _loudness.value = peak
                }
            } finally {
                runCatching { rawOutput?.flush() }
                runCatching { rawOutput?.close() }
                rawOutput = null
                try { record.stop() } catch (_: Exception) {}
                record.release()
            }
        }.also { it.start() }
    }

    fun stop() {
        recording = false
        thread?.join(1000)
        thread = null
    }

    fun discardCapture() {
        stop()
        runCatching { rawFile?.delete() }
        rawFile = null
        totalSamples = 0L
    }

    companion object {
        private const val PCM_SECONDS = 6
    }
}
