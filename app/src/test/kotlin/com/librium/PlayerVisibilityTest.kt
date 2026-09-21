package com.librium

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerVisibilityTest {

    @Test
    fun no_state_never_shows_player() {
        assertFalse(shouldShowPlayer(hasMedia = false, isLoading = false, error = null))
    }

    @Test
    fun loaded_media_shows_player() {
        assertTrue(shouldShowPlayer(hasMedia = true, isLoading = false, error = null))
    }

    @Test
    fun loading_shows_player_for_spinner_feedback() {
        assertTrue(shouldShowPlayer(hasMedia = false, isLoading = true, error = null))
    }

    @Test
    fun error_keeps_player_visible_for_the_banner() {
        assertTrue(shouldShowPlayer(hasMedia = false, isLoading = false, error = "boom"))
    }
}
