package com.librium.ui.player

import com.librium.player.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerUiStatusTest {

    @Test
    fun no_media_by_default() {
        assertEquals(PlayerUiStatus.NO_MEDIA, PlayerState().uiStatus)
    }

    @Test
    fun error_wins_over_everything() {
        val state = PlayerState(
            hasMedia = true,
            isPaused = false,
            isLoading = false,
            error = "boom",
        )
        assertEquals(PlayerUiStatus.ERROR, state.uiStatus)
    }

    @Test
    fun loading_without_media() {
        assertEquals(
            PlayerUiStatus.LOADING,
            PlayerState(isLoading = true, hasMedia = false).uiStatus,
        )
    }

    @Test
    fun loading_with_media_is_buffering() {
        assertEquals(
            PlayerUiStatus.BUFFERING,
            PlayerState(isLoading = true, hasMedia = true).uiStatus,
        )
    }

    @Test
    fun paused_playing_transition() {
        assertEquals(
            PlayerUiStatus.PAUSED,
            PlayerState(hasMedia = true, isPaused = true).uiStatus,
        )
        assertEquals(
            PlayerUiStatus.PLAYING,
            PlayerState(hasMedia = true, isPaused = false).uiStatus,
        )
    }
}
