package com.librium.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the rotation attach/detach race: ordered callbacks
 * must stay ordered through the worker hop, so a delayed detach can never
 * kill a fresh attach.
 */
class SurfaceAttachmentTest {

    private class FakeBackend(var instance: Boolean = true) : SurfaceAttachment.Backend {
        val calls = mutableListOf<String>()
        var failNextAttach = false

        override fun hasInstance(): Boolean = instance

        override fun attachNative(surface: Any) {
            if (failNextAttach) {
                failNextAttach = false
                error("boom")
            }
            calls.add("attach:$surface")
        }

        override fun detachNative() {
            calls.add("detach")
        }
    }

    private fun attachment(backend: FakeBackend): Pair<SurfaceAttachment, CoroutineScope> {
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default.limitedParallelism(1),
        )
        return SurfaceAttachment(scope, backend) to scope
    }

    @Test
    fun rotation_order_destroy_then_create_ends_attached_to_new() {
        val backend = FakeBackend()
        val (attachment, scope) = attachment(backend)
        try {
            runBlocking {
                attachment.attach("old-surface").join()
                // Rapid destroy-old / create-new, as fired on rotation.
                val detach = attachment.detach()
                val attach = attachment.attach("new-surface")
                detach.join()
                attach.join()
            }
            assertEquals(listOf("attach:old-surface", "detach", "attach:new-surface"), backend.calls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun same_surface_reattach_is_noop() {
        val backend = FakeBackend()
        val (attachment, scope) = attachment(backend)
        try {
            runBlocking {
                attachment.attach("s").join()
                attachment.attach("s").join()
            }
            assertEquals(listOf("attach:s"), backend.calls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun detach_without_attach_does_nothing() {
        val backend = FakeBackend()
        val (attachment, scope) = attachment(backend)
        try {
            runBlocking { attachment.detach().join() }
            assertTrue(backend.calls.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun attach_without_instance_does_nothing() {
        val backend = FakeBackend(instance = false)
        val (attachment, scope) = attachment(backend)
        try {
            runBlocking { attachment.attach("s").join() }
            assertTrue(backend.calls.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failed_attach_recovers_and_never_crashes() {
        val backend = FakeBackend().apply { failNextAttach = true }
        val (attachment, scope) = attachment(backend)
        try {
            runBlocking {
                attachment.attach("s").join()
                attachment.attach("s").join()
            }
            // First attempt failed, second attached cleanly.
            assertEquals(listOf("attach:s"), backend.calls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun repeated_create_destroy_cycles_stay_consistent() {
        val backend = FakeBackend()
        val (attachment, scope) = attachment(backend)
        try {
            runBlocking {
                repeat(5) { i ->
                    attachment.detach().join()
                    attachment.attach("surface-$i").join()
                }
            }
            assertEquals("attach:surface-4", backend.calls.last())
            // Exactly one attach per cycle; detaches only between cycles.
            assertEquals(5, backend.calls.count { it.startsWith("attach:") })
            assertEquals(4, backend.calls.count { it == "detach" })
        } finally {
            scope.cancel()
        }
    }
}
