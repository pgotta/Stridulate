package com.pgotta.stridulate.community

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.roundToInt

/** Where the audio shown in a result came from. */
enum class EvidenceSource { LIVE, IMPORTED }

data class EvidenceAudio(
    val filePath: String,
    val sampleRate: Int,
    val durationSeconds: Double,
    val source: EvidenceSource,
    /** Null for imported audio whose original observation time is unknown. */
    val observedAtMillis: Long?
) {
    val file: File get() = File(filePath)
}

/** Writes temporary, lossless PCM WAV evidence for optional community review. */
object EvidenceAudioStore {
    fun writeTemp(
        context: Context,
        samples: FloatArray,
        sampleRate: Int,
        source: EvidenceSource,
        observedAtMillis: Long?
    ): EvidenceAudio {
        require(sampleRate > 0) { "Invalid sample rate." }
        require(samples.isNotEmpty()) { "No audio samples were captured." }
        val directory = File(context.cacheDir, "analysis_evidence").apply { mkdirs() }
        val file = File(directory, "evidence-${UUID.randomUUID()}.wav")
        writePcm16Wav(file, samples, sampleRate)
        return EvidenceAudio(
            filePath = file.absolutePath,
            sampleRate = sampleRate,
            durationSeconds = samples.size.toDouble() / sampleRate.toDouble(),
            source = source,
            observedAtMillis = observedAtMillis
        )
    }

    fun deleteQuietly(evidence: EvidenceAudio?) {
        runCatching { evidence?.file?.takeIf { it.exists() }?.delete() }
    }


    fun wrapRawPcm16AsWav(rawFile: File, wavFile: File, sampleRate: Int) {
        require(sampleRate > 0) { "Invalid sample rate." }
        require(rawFile.exists() && rawFile.length() > 0L) { "No raw PCM audio was captured." }
        wavFile.parentFile?.mkdirs()
        val dataSize = rawFile.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val byteRate = sampleRate * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(byteRate)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()
        BufferedOutputStream(FileOutputStream(wavFile)).use { out ->
            out.write(header)
            rawFile.inputStream().buffered().use { input -> input.copyTo(out) }
        }
    }

    fun writePcm16Wav(file: File, samples: FloatArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        val dataSize = samples.size * 2
        val byteRate = sampleRate * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(sampleRate)
            putInt(byteRate)
            putShort(2) // block align
            putShort(16) // bits/sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

        BufferedOutputStream(FileOutputStream(file)).use { out ->
            out.write(header)
            val buffer = ByteArray(8192)
            var offset = 0
            while (offset < samples.size) {
                val count = minOf(buffer.size / 2, samples.size - offset)
                var j = 0
                for (i in 0 until count) {
                    val value = (samples[offset + i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                    buffer[j++] = (value and 0xFF).toByte()
                    buffer[j++] = ((value ushr 8) and 0xFF).toByte()
                }
                out.write(buffer, 0, count * 2)
                offset += count
            }
        }
    }
}
