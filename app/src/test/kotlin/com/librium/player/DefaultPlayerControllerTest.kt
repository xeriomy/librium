package com.librium.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Controller behavior against a fake engine: repeated selections forward
 * in order, UI overlay state (title, fullscreen, errors) never corrupts
 * backend state, and failures stay visible without killing playback state.
 */
class DefaultPlayerControllerTest {

    private class FakeEngine : PlayerEngine {
        val calls = mutableListOf<String>()
        private val _state = MutableStateFlow(PlayerState())
        override val state: StateFlow<PlayerState> = _state.asStateFlow()

        fun emit(value: PlayerState) = _state.update { value }

        override fun initialize() { calls.add("initialize") }
        override fun release() { calls.add("release") }
        override fun openVideo(uri: String) { calls.add("open:$uri") }
        override fun play() { calls.add("play") }
        override fun pause() { calls.add("pause") }
        override fun togglePlayPause() { calls.add("toggle") }
        override fun seekTo(positionMs: Long) { calls.add("seekTo:$positionMs") }
        override fun seekBy(deltaMs: Long) { calls.add("seekBy:$deltaMs") }
        override fun setVolume(volume01: Int) { calls.add("volume:$volume01") }
        override fun toggleMute() { calls.add("mute") }
        override fun selectAudioTrack(mpvId: Int) { calls.add("audio:$mpvId") }
        override fun selectSubtitleTrack(mpvId: Int?) { calls.add("sub:$mpvId") }
        override fun setSubtitlesEnabled(enabled: Boolean) { calls.add("subEnabled:$enabled") }
        override fun addExternalSubtitle(uri: String) { calls.add("subAdd:$uri") }
        override fun setSubtitleDelay(delayMs: Long) { calls.add("delay:$delayMs") }
        override fun setSubtitleAppearance(appearance: SubtitleAppearance) {
            calls.add("appearance:${appearance.fontSize}")
        }
        override fun attachSurface(surface: android.view.Surface) { calls.add("attach") }
        override fun detachSurface() { calls.add("detach") }
    }

    private fun controller(engine: FakeEngine): DefaultPlayerController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return DefaultPlayerController(engine, scope)
    }

    @Test
    fun repeated_video_selection_forwards_every_request_in_order() {
        val engine = FakeEngine()
        val controller = controller(engine)
        controller.openVideo("content://a/1", "A")
        controller.openVideo("content://b/2", "B")
        controller.openVideo("content://a/1", "A")
        controller.openVideo("content://b/2", "B")
        assertEquals(
            listOf("open:content://a/1", "open:content://b/2", "open:content://a/1", "open:content://b/2"),
            engine.calls,
        )
        // Latest title wins at the UI layer once the backend emits.
        // (Must differ from the initial value: equal StateFlow values
        // do not notify collectors.)
        engine.emit(PlayerState(hasMedia = true))
        assertEquals("B", controller.state.value.mediaTitle)
    }

    @Test
    fun backend_error_surfaces_and_recovery_keeps_media_state() {
        val engine = FakeEngine()
        val controller = controller(engine)
        engine.emit(PlayerState(hasMedia = true, isPaused = false, error = "boom"))
        assertEquals("boom", controller.state.value.error)
        assertTrue(controller.state.value.hasMedia)
        // Dismissing the banner never touches media state...
        controller.clearError()
        assertTrue(controller.state.value.error == null)
        assertTrue(controller.state.value.hasMedia)
        // ...and an unresolved backend fault re-asserts on next emission.
        engine.emit(PlayerState(hasMedia = true, isPaused = false, positionMs = 1234L, error = "boom"))
        assertEquals("boom", controller.state.value.error)
        engine.emit(PlayerState(hasMedia = true, isPaused = false, positionMs = 1234L, error = null))
        assertTrue(controller.state.value.error == null)
        assertTrue(controller.state.value.hasMedia)
    }

    @Test
    fun rapid_transport_commands_all_forward() {
        val engine = FakeEngine()
        val controller = controller(engine)
        controller.seekBy(10_000)
        controller.seekBy(10_000)
        controller.seekBy(-10_000)
        controller.togglePlayPause()
        assertEquals(
            listOf("seekBy:10000", "seekBy:10000", "seekBy:-10000", "toggle"),
            engine.calls,
        )
    }

    @Test
    fun subtitle_actions_forward_without_touching_media() {
        val engine = FakeEngine()
        val controller = controller(engine)
        controller.addExternalSubtitle("content://s/1.srt")
        controller.selectSubtitleTrack(2)
        controller.setSubtitleDelay(500)
        controller.setSubtitleAppearance(DEFAULT_SUBTITLE_APPEARANCE.copy(fontSize = 70f))
        assertEquals(
            listOf("subAdd:content://s/1.srt", "sub:2", "delay:500", "appearance:70.0"),
            engine.calls,
        )
    }

    @Test
    fun fullscreen_is_ui_state_only() {
        val engine = FakeEngine()
        val controller = controller(engine)
        controller.setFullscreen(true)
        assertTrue(controller.state.value.isFullscreen)
        assertTrue(engine.calls.isEmpty())
        controller.toggleFullscreen()
        assertTrue(!controller.state.value.isFullscreen)
    }
}
