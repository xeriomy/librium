package com.librium.ui.player

import android.content.pm.ActivityInfo

/**
 * Fullscreen/orientation contract, kept in one place so UI state and the
 * Activity orientation can never disagree:
 *
 * - enter fullscreen -> landscape,
 * - exit fullscreen -> portrait (explicit, never UNSPECIFIED).
 *
 * The Activity is fully orientation-driven by [PlayerState.isFullscreen];
 * physical sensor rotation is intentionally not followed, so the surface
 * only ever changes size on explicit fullscreen toggles.
 */
object FullscreenOrientation {

    fun requestedOrientation(isFullscreen: Boolean): Int =
        if (isFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
}
