package com.librium.ui.player

import android.app.Application
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.librium.core.LibLog
import com.librium.player.DefaultPlayerController
import com.librium.player.MpvPlayerEngine
import com.librium.player.PlayerController
import com.librium.player.PlayerState
import kotlinx.coroutines.flow.StateFlow

/**
 * Activity-scoped holder for the player. Creates the libmpv engine once,
 * so rotation / recomposition never recreates native state; the video
 * surface re-attaches via [attachSurface]/[detachSurface].
 */
class PlayerViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val engine = MpvPlayerEngine(application.applicationContext)
    private val controller: PlayerController =
        DefaultPlayerController(engine, viewModelScope)

    val state: StateFlow<PlayerState> = controller.state

    init {
        LibLog.i(LibLog.PLAYER) { "PlayerViewModel created" }
        engine.initialize()
    }

    fun openVideo(uri: String, displayName: String? = null) =
        controller.openVideo(uri, displayName)

    fun togglePlayPause() = controller.togglePlayPause()
    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    fun seekBy(deltaMs: Long) = controller.seekBy(deltaMs)
    fun setVolume(volume: Int) = controller.setVolume(volume)
    fun toggleMute() = controller.toggleMute()
    fun selectAudioTrack(mpvId: Int) = controller.selectAudioTrack(mpvId)
    fun selectSubtitleTrack(mpvId: Int?) = controller.selectSubtitleTrack(mpvId)
    fun setSubtitlesEnabled(enabled: Boolean) = controller.setSubtitlesEnabled(enabled)
    fun addExternalSubtitle(uri: String) = controller.addExternalSubtitle(uri)
    fun setSubtitleDelay(delayMs: Long) = controller.setSubtitleDelay(delayMs)
    fun toggleFullscreen() = controller.toggleFullscreen()
    fun setFullscreen(fullscreen: Boolean) = controller.setFullscreen(fullscreen)
    fun clearError() = controller.clearError()

    fun attachSurface(surface: Surface) = engine.attachSurface(surface)
    fun detachSurface() = engine.detachSurface()

    override fun onCleared() {
        LibLog.i(LibLog.PLAYER) { "PlayerViewModel cleared" }
        engine.release()
        super.onCleared()
    }
}
