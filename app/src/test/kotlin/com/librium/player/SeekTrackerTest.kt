package com.librium.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekCommandTest {

    @Test
    fun forward_skip_uses_relative_keyframes() {
        assertTrue(
            SeekCommands.relativeSkip(10_000).contentEquals(
                arrayOf("seek", "10.0", "relative+keyframes"),
            ),
        )
    }

    @Test
    fun backward_skip_uses_relative_keyframes() {
        assertTrue(
            SeekCommands.relativeSkip(-10_000).contentEquals(
                arrayOf("seek", "-10.0", "relative+keyframes"),
            ),
        )
    }
}

class SeekTrackerTest {

    @Test
    fun landing_within_tolerance_reports_latency() {
        var now = 0L
        val tracker = SeekTracker(clockMs = { now })
        tracker.onSeekRequested(20_000L, "relative+keyframes")
        assertNull(tracker.onPositionChanged(5_000L))
        now = 350L
        val landing = tracker.onPositionChanged(20_050L)
        assertTrue(landing != null)
        assertEquals(20_000L, landing!!.targetMs)
        assertEquals(350L, landing.latencyMs)
        // Consumed: further updates report nothing.
        assertNull(tracker.onPositionChanged(20_050L))
    }

    @Test
    fun far_landing_never_confirms() {
        var now = 0L
        val tracker = SeekTracker(clockMs = { now })
        tracker.onSeekRequested(20_000L, "relative+keyframes")
        now = 500L
        assertNull(tracker.onPositionChanged(40_000L))
        assertEquals(20_000L, tracker.pendingTargetMs())
    }

    @Test
    fun rapid_requests_keep_only_latest() {
        val tracker = SeekTracker(clockMs = { 0L })
        repeat(50) { i ->
            tracker.onSeekRequested(10_000L + i * 10_000L, "relative+keyframes")
        }
        assertEquals(10_000L + 49L * 10_000L, tracker.pendingTargetMs())
    }

    @Test
    fun stale_requests_expire() {
        var now = 0L
        val tracker = SeekTracker(clockMs = { now }, timeoutMs = 15_000L)
        tracker.onSeekRequested(20_000L, "absolute-exact")
        now = 15_001L
        assertNull(tracker.onPositionChanged(20_000L))
        assertNull(tracker.pendingTargetMs())
    }
}
