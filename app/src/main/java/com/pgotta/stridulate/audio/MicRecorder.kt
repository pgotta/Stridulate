package com.pgotta.stridulate.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.min

/**
 * Live microphone capture. Emits blocks of mono float PCM plus a rolling
 * spectrogram column stream for the live view. Runs the same FFT →
 * FeatureExtractor pipeline the file path uses.
 */
class MicRecorder(
    private val fftSize: Int = 4096
) {
    @Volatile private var recording = false
    private var thread: Thread? = null

    var sampleRate: Int = 48000
        private set

    private val _spectrogramColumn = MutableStateFlow(FloatArray(0))
    val spectrogramColumn: StateFlow<FloatArray> = _spectrogramColumn

    private val _loudness = MutableStateFlow(0f)
    val loudness: StateFlow<Float> = _loudness

    // Rolling raw-PCM buffer (mono float) so the trained model can run on the
    // actual captured audio. Holds the most recent PCM_SECONDS of sound.
    private val pcmBuffer = ArrayList<Float>()
    private val pcmLock = Any()

    /** Snapshot of the most recent captured PCM (mono float) and its sample rate. */
    fun capturedPcm(): Pair<FloatArray, Int> = synchronized(pcmLock) {
        pcmBuffer.toFloatArray() to sampleRate
    }
    fun clearPcm() = synchronized(pcmLock) { pcmBuffer.clear() }

    /**
     * Start capturing. [onFrame] is called on the audio thread with each new
     * magnitude spectrum and its timestamp; the caller feeds it to a
     * FeatureExtractor. Requires RECORD_AUDIO permission (checked by caller).
     */
    @SuppressLint("MissingPermission")
    fun start(onFrame: (spectrum: FloatArray, timeSec: Double) -> Unit) {
        if (recording) return
        clearPcm()
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
        val frame = FloatArray(fftSize)
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

                    for (i in 0 until fftSize) frame[i] = pcmShort[i] / 32768f
                    synchronized(pcmLock) {
                        for (v in frame) pcmBuffer.add(v)
                        val maxLen = sampleRate * PCM_SECONDS
                        if (pcmBuffer.size > maxLen) {
                            val excess = pcmBuffer.size - maxLen
                            pcmBuffer.subList(0, excess).clear()
                        }
                    }
                    val spectrum = fft.magnitudeSpectrum(frame)
                    val t = (System.nanoTime() - startNs) / 1e9
                    onFrame(spectrum, t)

                    // build a downsampled spectrogram column (high freq at top)
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
                try { record.stop() } catch (_: Exception) {}
                record.release()
            }
        }.also { it.start() }
    }

    fun stop() {
        recording = false
        thread?.join(500)
        thread = null
    }

    companion object {
        private const val PCM_SECONDS = 6
    }
}
