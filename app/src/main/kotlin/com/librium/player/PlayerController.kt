package com.librium.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * UI-facing playback coordinator. Owns UI-level state that is NOT the
 * backend's concern (media title, fullscreen, cleared errors) and delegates
 * everything else to the injected [PlayerEngine].
 *
 * The Compose UI talks to this (via `PlayerViewModel`), never to
 * [PlayerEngine] implementations or native mpv types directly.
 */
interface PlayerController {
    val state: StateFlow<PlayerState>

    fun openVideo(uri: String, displayName: String? = null)
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
    fun setSubtitleDelay(delayMs: Long)
    fun setFullscreen(fullscreen: Boolean)
    fun toggleFullscreen()
    fun clearError()
}

class DefaultPlayerController(
    private val engine: PlayerEngine,
    scope: CoroutineScope,
) : PlayerController {

    private val ui = MutableStateFlow(UiOverlay())
    private val _state = MutableStateFlow(engine.state.value)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    init {
        engine.state
            .onEach { backend ->
                val overlay = ui.value
                _state.update {
                    backend.copy(
                        mediaTitle = overlay.mediaTitle ?: backend.mediaTitle,
                        isFullscreen = overlay.isFullscreen,
                        // A UI-level error (e.g. SAF failure) wins until cleared.
                        error = overlay.error ?: backend.error,
                    )
                }
            }
            .launchIn(scope)
    }

    override fun openVideo(uri: String, displayName: String?) {
        ui.update { it.copy(mediaTitle = displayName, error = null) }
        engine.openVideo(uri)
    }

    override fun play() = engine.play()
    override fun pause() = engine.pause()
    override fun togglePlayPause() = engine.togglePlayPause()
    override fun seekTo(positionMs: Long) = engine.seekTo(positionMs)
    override fun seekBy(deltaMs: Long) = engine.seekBy(deltaMs)
    override fun setVolume(volume01: Int) = engine.setVolume(volume01)
    override fun toggleMute() = engine.toggleMute()
    override fun selectAudioTrack(mpvId: Int) = engine.selectAudioTrack(mpvId)
    override fun selectSubtitleTrack(mpvId: Int?) = engine.selectSubtitleTrack(mpvId)
    override fun setSubtitlesEnabled(enabled: Boolean) = engine.setSubtitlesEnabled(enabled)
    override fun addExternalSubtitle(uri: String) = engine.addExternalSubtitle(uri)
    override fun setSubtitleDelay(delayMs: Long) = engine.setSubtitleDelay(delayMs)

    override fun setFullscreen(fullscreen: Boolean) {
        ui.update { it.copy(isFullscreen = fullscreen) }
        refreshOverlay()
    }

    override fun toggleFullscreen() = setFullscreen(!_state.value.isFullscreen)

    override fun clearError() {
        ui.update { it.copy(error = null) }
        refreshOverlay()
    }

    private fun refreshOverlay() {
        val overlay = ui.value
        _state.update {
            it.copy(
                mediaTitle = overlay.mediaTitle ?: it.mediaTitle,
                isFullscreen = overlay.isFullscreen,
                error = overlay.error,
            )
        }
    }

    private data class UiOverlay(
        val mediaTitle: String? = null,
        val isFullscreen: Boolean = false,
        val error: String? = null,
    )
}
