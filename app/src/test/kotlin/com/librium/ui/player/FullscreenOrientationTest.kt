package com.librium.ui.player

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class FullscreenOrientationTest {

    @Test
    fun enter_fullscreen_is_landscape() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            FullscreenOrientation.requestedOrientation(true),
        )
    }

    @Test
    fun exit_fullscreen_is_portrait_never_unspecified() {
        val orientation = FullscreenOrientation.requestedOrientation(false)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, orientation)
    }
}
