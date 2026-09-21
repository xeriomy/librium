package com.librium.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionThrottleTest {

    @Test
    fun first_update_always_emits() {
        val throttle = PositionThrottle()
        assertTrue(throttle.shouldEmit(0L))
    }

    @Test
    fun small_deltas_are_dropped() {
        val throttle = PositionThrottle(minDeltaMs = 250L)
        assertTrue(throttle.shouldEmit(10_000L))
        assertFalse(throttle.shouldEmit(10_100L))
        assertFalse(throttle.shouldEmit(10_249L))
        assertTrue(throttle.shouldEmit(10_250L))
    }

    @Test
    fun reset_forces_next_emit() {
        val throttle = PositionThrottle(minDeltaMs = 250L)
        assertTrue(throttle.shouldEmit(10_000L))
        throttle.reset()
        assertTrue(throttle.shouldEmit(10_050L))
    }

    @Test
    fun backwards_jumps_emit() {
        val throttle = PositionThrottle(minDeltaMs = 250L)
        assertTrue(throttle.shouldEmit(50_000L))
        assertTrue(throttle.shouldEmit(10_000L))
    }
}
