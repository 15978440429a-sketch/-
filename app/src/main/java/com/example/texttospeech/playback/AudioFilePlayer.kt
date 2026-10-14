package com.example.texttospeech.playback

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Called on the main thread. prepareAsync keeps disk decoding off the UI thread. */
class AudioFilePlayer {
    class PlaybackFailure(cause: Throwable) : IOException("Audio playback failed", cause)

    private var player: MediaPlayer? = null
    private var continuation: CancellableContinuation<Unit>? = null
    private var pauseRequested = false

    suspend fun play(file: File, startPaused: Boolean = false): Unit = suspendCancellableCoroutine { cont ->
        stop()
        pauseRequested = startPaused
        val media = MediaPlayer()
        player = media
        continuation = cont
        var released = false

        fun release() {
            if (released) return
            released = true
            if (player === media) {
                player = null
                continuation = null
            }
            runCatching { media.release() }
        }

        fun fail(error: Throwable) {
            release()
            if (cont.isActive) cont.resumeWithException(PlaybackFailure(error))
        }

        cont.invokeOnCancellation {
            if (player === media) release()
        }
        try {
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            media.setDataSource(file.absolutePath)
            media.setOnPreparedListener {
                try {
                    if (cont.isActive && !pauseRequested) it.start()
                } catch (error: RuntimeException) {
                    fail(error)
                }
            }
            media.setOnCompletionListener {
                release()
                if (cont.isActive) cont.resume(Unit)
            }
            media.setOnErrorListener { _, _, _ ->
                fail(IOException("MediaPlayer could not decode audio"))
                true
            }
            media.prepareAsync()
        } catch (error: Exception) {
            fail(error)
        }
    }

    fun pause(): Boolean {
        pauseRequested = true
        val active = player ?: return false
        return runCatching {
            if (active.isPlaying) active.pause()
            true
        }.getOrDefault(false)
    }

    fun resume(): Boolean {
        pauseRequested = false
        val active = player ?: return false
        return runCatching {
            if (!active.isPlaying) active.start()
            true
        }.getOrDefault(false)
    }

    fun hasActivePlayer(): Boolean = player != null

    fun stop() {
        pauseRequested = false
        continuation?.cancel()
        // Cancellation normally releases the player; this also covers a completed continuation.
        runCatching { player?.release() }
        player = null
        continuation = null
    }
}
