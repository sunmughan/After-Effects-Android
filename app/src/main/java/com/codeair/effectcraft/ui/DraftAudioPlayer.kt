package com.codeair.effectcraft.ui

import android.media.MediaPlayer
import com.codeair.effectcraft.data.AudioClipDocument

/**
 * Batch 2 (P21): audible audio preview for the unified audio model.
 * A thin, single-clip [MediaPlayer] wrapper — no new dependency
 * (ExoPlayer is not in the app). One clip plays at a time: the audible
 * (unmuted) clip under the playhead. Play starts it from the playhead
 * position, Pause pauses it, scrubbing seeks it, and the end of a clip
 * hands back to the caller ([onClipEnded]) so the next clip under the
 * playhead can start.
 *
 * Honest limit: the audio clock and the frame-advance loop are two
 * clocks. The player seeks to the playhead on Play and on scrub, so
 * audio starts in sync, but sample-accurate lock over a long play is
 * not claimed — the transport label says what is playing, never that
 * audio and video are sample-locked.
 */
class DraftAudioPlayer {

    private var player: MediaPlayer? = null
    private var currentClipId: String? = null

    /** Reported when a clip cannot be opened or fails during playback. */
    var onError: ((String) -> Unit)? = null

    /** Fired when the current clip plays to its end. */
    var onClipEnded: (() -> Unit)? = null

    val isPlaying: Boolean get() = player?.isPlaying == true

    /**
     * Plays [clip] from [startFrame]. The same clip re-plays from the
     * new position without re-preparing; a different clip replaces the
     * current player.
     */
    fun play(clip: AudioClipDocument, startFrame: Int, frameRate: Int) {
        val existing = player
        if (existing != null && currentClipId == clip.id) {
            runCatching {
                existing.seekTo(audioPreviewSourceMs(clip, startFrame, frameRate))
                if (!existing.isPlaying) existing.start()
            }.onFailure { startNew(clip, startFrame, frameRate) }
            return
        }
        startNew(clip, startFrame, frameRate)
    }

    /** Pauses the current clip, if it is playing. */
    fun pause() {
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
    }

    /**
     * Seeks the current clip to [frame]; true when the seek landed.
     * False when nothing is playing or [clip] is not the current clip —
     * the caller then starts the right clip instead.
     */
    fun seekToFrame(clip: AudioClipDocument, frame: Int, frameRate: Int): Boolean {
        val existing = player ?: return false
        if (currentClipId != clip.id) return false
        return runCatching {
            existing.seekTo(audioPreviewSourceMs(clip, frame, frameRate))
            true
        }.getOrDefault(false)
    }

    /** Stops playback and releases the player. */
    fun stop() {
        player?.let { current ->
            runCatching { current.stop() }
            runCatching { current.release() }
        }
        player = null
        currentClipId = null
    }

    fun release() = stop()

    private fun startNew(clip: AudioClipDocument, startFrame: Int, frameRate: Int) {
        stop()
        val fresh = MediaPlayer()
        try {
            fresh.setDataSource(clip.sourcePath)
            val volume = if (clip.muted) 0f else clip.gain.coerceIn(0f, 1f)
            fresh.setVolume(volume, volume)
            fresh.setOnCompletionListener { onClipEnded?.invoke() }
            fresh.setOnErrorListener { _, _, _ ->
                onError?.invoke(
                    "Audio preview unavailable · ${clip.sourcePath.substringAfterLast('/')} could not be played"
                )
                true
            }
            fresh.prepare()
            fresh.seekTo(audioPreviewSourceMs(clip, startFrame, frameRate))
            fresh.start()
            player = fresh
            currentClipId = clip.id
        } catch (failure: Exception) {
            runCatching { fresh.release() }
            onError?.invoke("Audio preview unavailable · ${failure.message ?: "could not open audio"}")
        }
    }
}

/**
 * Source position in milliseconds for timeline [frame] of [clip]:
 * the clip's source in-point plus the offset of the frame inside the
 * clip. Pure, so the seek math is unit-testable without a MediaPlayer.
 */
fun audioPreviewSourceMs(clip: AudioClipDocument, frame: Int, frameRate: Int): Int {
    val offsetFrames = (frame - clip.clip.startFrame).coerceAtLeast(0)
    return ((clip.clip.sourceStartFrame + offsetFrames) * 1000L / frameRate.coerceAtLeast(1)).toInt()
}
