package com.librium.player

import kotlin.math.abs

/**
 * Caps position-state emission during playback. mpv fires `time-pos`
 * change events up to every frame; forwarding each one would recompose
 * the whole player screen at display refresh rate. Updates smaller than
 * [minDeltaMs] are dropped (the seek tracker still sees every raw value),
 * and [reset] forces the next update through so seeks and new files always
 * paint immediately.
 */
class PositionThrottle(private val minDeltaMs: Long = 250L) {

    private var lastEmittedMs: Long = Long.MIN_VALUE

    @Synchronized
    fun shouldEmit(positionMs: Long): Boolean {
        if (lastEmittedMs == Long.MIN_VALUE ||
            abs(positionMs - lastEmittedMs) >= minDeltaMs
        ) {
            lastEmittedMs = positionMs
            return true
        }
        return false
    }

    @Synchronized
    fun reset() {
        lastEmittedMs = Long.MIN_VALUE
    }
}
