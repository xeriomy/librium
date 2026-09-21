package com.librium.player

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/**
 * Playback backend contract. Phase 1 is backed by [MpvPlayerEngine]
 * (libmpv AAR); a future backend only needs to implement this interface —
 * UI and [PlayerController] stay untouched.
 *
 * Track ids follow mpv semantics: a non-negative backend id, or -1 for
 * "off / none". All calls must be safe from the main thread; implementations
 * hop to a background dispatcher internally.
 */
interface PlayerEngine {
    val state: StateFlow<PlayerState>

    fun initialize()
    fun release()

    fun openVideo(uri: String)
    fun play()
    fun pause()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)

    fun setVolume(volume01: Int)
    fun toggleMute()

    fun selectAudioTrack(mpvId: Int)
    fun selectSubtitleTrack(mpvId: Int?)
    fun setSubtitlesEnabled(enabled: Boolean)
    fun addExternalSubtitle(uri: String)

    /** Live subtitle delay applied by the backend (mpv `sub-delay`). */
    fun setSubtitleDelay(delayMs: Long)

    /** Tunes libass rendering; the backend publishes the result in state. */
    fun setSubtitleAppearance(appearance: SubtitleAppearance)

    fun attachSurface(surface: Surface)
    fun detachSurface()
}
