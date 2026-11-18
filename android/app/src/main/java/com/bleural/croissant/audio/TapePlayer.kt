package com.bleural.croissant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** What the player holds: [file] loaded (null when stopped), and whether it is [playing]. */
data class PlayerState(val file: File? = null, val playing: Boolean = false, val durationMs: Long = 0L)

/**
 * A tape played back. It lives in the voice service, as the recorder does, so it plays on
 * with the page left, the screen off or the device "asleep": the service is in the
 * foreground, counts a playing tape as busy (so its wake lock stays), and the player holds
 * a partial wake lock of its own. One tape at a time; the end of a tape stops it, and the
 * next play starts it over.
 */
class TapePlayer(private val ctx: Context) {
    companion object {
        private const val TAG = "TapePlayer"
    }

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private var mp: MediaPlayer? = null

    /** Where the tape is, in ms; 0 when nothing is loaded. */
    @Synchronized
    fun positionMs(): Long = mp?.let { runCatching { it.currentPosition.toLong() }.getOrNull() } ?: 0L

    /** [file] from where it was paused, or from the start; pauses it when it is the one playing. */
    @Synchronized
    fun toggle(file: File) {
        val s = _state.value
        when {
            s.file == file && s.playing -> pause()
            s.file == file && mp != null -> { mp?.start(); _state.value = s.copy(playing = true) }
            else -> start(file)
        }
    }

    @Synchronized
    fun pause() {
        val p = mp ?: return
        if (!_state.value.playing) return
        runCatching { p.pause() }
        _state.value = _state.value.copy(playing = false)
    }

    /** To [fraction] of the way through the loaded tape. */
    @Synchronized
    fun seekTo(fraction: Float) {
        val p = mp ?: return
        val d = _state.value.durationMs
        if (d <= 0) return
        runCatching { p.seekTo((d * fraction.coerceIn(0f, 1f)).toInt()) }
    }

    @Synchronized
    fun stop() {
        mp?.let { runCatching { it.release() } }
        mp = null
        _state.value = PlayerState()
    }

    private fun start(file: File) {
        stop()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            p.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK)
            p.setDataSource(file.path)
            p.prepare()
            p.setOnCompletionListener { synchronized(this) { if (mp === p) stop() } }
            p.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "error $what/$extra on ${file.name}")
                synchronized(this) { if (mp === p) stop() }
                true
            }
            p.start()
        } catch (e: Exception) {
            Log.w(TAG, "cannot play ${file.name}", e)
            runCatching { p.release() }
            return
        }
        mp = p
        _state.value = PlayerState(file, playing = true, durationMs = p.duration.toLong())
        Log.i(TAG, "playing ${file.name}")
    }
}
