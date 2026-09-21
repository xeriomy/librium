package com.librium.ui.player

import com.librium.player.PlayerState

/**
 * Coarse UI status derived from [PlayerState] — never stored, only
 * computed, so it cannot disagree with engine state. The screen renders
 * loading spinners and the empty view from this instead of re-checking
 * individual flags at every call site.
 */
enum class PlayerUiStatus {
    NO_MEDIA,
    LOADING,
    BUFFERING,
    PAUSED,
    PLAYING,
    ERROR,
}

val PlayerState.uiStatus: PlayerUiStatus
    get() = when {
        error != null -> PlayerUiStatus.ERROR
        isLoading && !hasMedia -> PlayerUiStatus.LOADING
        isLoading -> PlayerUiStatus.BUFFERING
        !hasMedia -> PlayerUiStatus.NO_MEDIA
        isPaused -> PlayerUiStatus.PAUSED
        else -> PlayerUiStatus.PLAYING
    }
