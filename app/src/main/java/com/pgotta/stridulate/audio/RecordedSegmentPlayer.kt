package com.pgotta.stridulate.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File

/** Plays a marked portion of a locally saved detection-log recording. */
object RecordedSegmentPlayer {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var stopTask: Runnable? = null

    fun play(context: Context, filePath: String, startSeconds: Double, endSeconds: Double) {
        stop()
        val file = File(filePath)
        if (!file.exists()) return
        val startMs = (startSeconds.coerceAtLeast(0.0) * 1000.0).toInt()
        val durationMs = ((endSeconds - startSeconds).coerceAtLeast(1.0) * 1000.0).toLong()
        val mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(file.absolutePath)
            setOnPreparedListener { prepared ->
                prepared.seekTo(startMs)
                prepared.start()
                val task = Runnable { stop() }
                stopTask = task
                handler.postDelayed(task, durationMs)
            }
            setOnCompletionListener { stop() }
            setOnErrorListener { _, _, _ -> stop(); true }
        }
        player = mediaPlayer
        runCatching { mediaPlayer.prepareAsync() }.onFailure { stop() }
    }

    fun stop() {
        stopTask?.let(handler::removeCallbacks)
        stopTask = null
        val active = player
        player = null
        if (active != null) {
            runCatching { active.stop() }
            runCatching { active.reset(); active.release() }
        }
    }
}
