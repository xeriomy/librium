package com.librium.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle rules without native code: no duplicate instances, release
 * exactly once, no use after release, retry allowed after failed init.
 */
class EngineLifecycleTest {

    @Test
    fun starts_new() {
        assertEquals(EngineLifecycle.State.NEW, EngineLifecycle().current())
    }

    @Test
    fun second_init_while_in_flight_or_ready_is_refused() {
        val lifecycle = EngineLifecycle()
        assertTrue(lifecycle.tryBeginInit())
        assertFalse(lifecycle.tryBeginInit())
        lifecycle.markReady()
        assertFalse(lifecycle.tryBeginInit())
    }

    @Test
    fun failed_init_allows_retry() {
        val lifecycle = EngineLifecycle()
        assertTrue(lifecycle.tryBeginInit())
        lifecycle.markInitFailed()
        assertEquals(EngineLifecycle.State.NEW, lifecycle.current())
        assertTrue(lifecycle.tryBeginInit())
    }

    @Test
    fun release_runs_exactly_once() {
        val lifecycle = EngineLifecycle()
        assertTrue(lifecycle.tryBeginInit())
        lifecycle.markReady()
        assertTrue(lifecycle.tryBeginRelease())
        assertFalse(lifecycle.tryBeginRelease())
        assertTrue(lifecycle.isReleased())
    }

    @Test
    fun init_after_release_is_refused() {
        val lifecycle = EngineLifecycle()
        assertTrue(lifecycle.tryBeginRelease())
        assertFalse(lifecycle.tryBeginInit())
    }

    @Test
    fun mark_ready_is_noop_outside_initializing() {
        val lifecycle = EngineLifecycle()
        lifecycle.markReady()
        assertEquals(EngineLifecycle.State.NEW, lifecycle.current())
        assertTrue(lifecycle.tryBeginInit())
        lifecycle.markReady()
        // Second markReady must not move away from READY.
        lifecycle.markReady()
        assertEquals(EngineLifecycle.State.READY, lifecycle.current())
    }
}
