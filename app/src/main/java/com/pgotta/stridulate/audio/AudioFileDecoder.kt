package com.pgotta.stridulate.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the audio track out of ANY audio or video file the user picks.
 *
 * For a video file (mp4, mkv, ...) this pulls just the audio track and ignores
 * the video entirely — exactly what we want: "listen to the audio of a video".
 * For an audio file (m4a, mp3, wav, ogg, ...) it decodes it directly.
 *
 * Output is mono float PCM in [-1, 1] at the file's native sample rate, which is
 * then fed frame-by-frame into the same [FeatureExtractor] the microphone uses.
 * So the identification pipeline is identical whether the sound came from the
 * mic or from a saved clip.
 */
object AudioFileDecoder {

    data class DecodedAudio(val samples: FloatArray, val sampleRate: Int)

    /** Progress callback receives 0f..1f as decoding proceeds. */
    fun interface Progress { fun onProgress(fraction: Float) }

    /**
     * Decode up to [maxSeconds] of audio from [uri]. We cap the length because a
     * few seconds of clear song is plenty to identify, and it keeps memory sane
     * for long videos.
     */
    fun decode(
        context: Context,
        uri: Uri,
        maxSeconds: Double = 30.0,
        progress: Progress? = null
    ): DecodedAudio {
        val extractor = MediaExtractor()
        context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
            requireNotNull(pfd) { "Could not open the selected file." }
            extractor.setDataSource(pfd.fileDescriptor)
        }

        // Find the first audio track (skips video tracks automatically).
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) { trackIndex = i; format = f; break }
        }
        require(trackIndex >= 0 && format != null) {
            "No audio track found in this file. Pick a file that contains sound."
        }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
            format.getLong(MediaFormat.KEY_DURATION) else (maxSeconds * 1_000_000).toLong()

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val out = ArrayList<Float>(sampleRate * maxSeconds.toInt().coerceAtLeast(1))
        val info = MediaCodec.BufferInfo()
        var sawInputEnd = false
        var sawOutputEnd = false
        val maxUs = (maxSeconds * 1_000_000).toLong()

        try {
            while (!sawOutputEnd) {
                if (!sawInputEnd) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0 || extractor.sampleTime > maxUs) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(
                                inIndex, 0, sampleSize, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                    if (info.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        appendPcm16AsMonoFloat(outBuf, channels, out)
                        progress?.onProgress(
                            (info.presentationTimeUs.toFloat() / maxUs.coerceAtLeast(1))
                                .coerceIn(0f, 1f)
                        )
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        progress?.onProgress(1f)

        return DecodedAudio(out.toFloatArray(), sampleRate)
    }

    /**
     * MediaCodec PCM output is 16-bit little-endian interleaved. Downmix to mono
     * and convert to float in [-1, 1].
     */
    private fun appendPcm16AsMonoFloat(buf: ByteBuffer, channels: Int, out: ArrayList<Float>) {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val shorts = buf.asShortBuffer()
        val frames = shorts.remaining() / channels.coerceAtLeast(1)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += shorts.get()
            val mono = sum.toFloat() / channels
            out.add((mono / 32768f).coerceIn(-1f, 1f))
        }
    }
}
