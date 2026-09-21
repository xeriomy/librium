package com.librium.player

import com.librium.core.LibLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Serializes native surface attach/detach through a single-threaded scope.
 *
 * Rotation destroys and recreates the SurfaceView surface on the UI thread
 * in strict order (destroy-old, then create-new), but the engine hop to a
 * background dispatcher used to lose that ordering: a delayed detach could
 * run after the fresh attach and leave video rendering to a dead surface.
 * Funneling both operations through one serial worker restores callback
 * order, so the last callback always wins. Re-attaching the identical
 * surface instance is a no-op.
 */
class SurfaceAttachment(
    private val scope: CoroutineScope,
    private val backend: Backend,
) {

    interface Backend {
        fun hasInstance(): Boolean
        fun attachNative(surface: Any)
        fun detachNative()
    }

    @Volatile
    private var attachedHandle: Any? = null

    fun attach(surface: Any): Job = scope.launch {
        if (!backend.hasInstance()) {
            LibLog.d(LibLog.MPV) { "attach ignored: no instance" }
            return@launch
        }
        if (attachedHandle === surface) return@launch
        if (attachedHandle != null) {
            runCatching { backend.detachNative() }
        }
        runCatching { backend.attachNative(surface) }
            .onSuccess { attachedHandle = surface }
            .onFailure { e ->
                attachedHandle = null
                LibLog.e(LibLog.MPV, e) { "attach failed" }
            }
    }

    fun detach(): Job = scope.launch {
        if (attachedHandle == null) return@launch
        attachedHandle = null
        runCatching { backend.detachNative() }
            .onFailure { e -> LibLog.e(LibLog.MPV, e) { "detach failed" } }
    }

    /** Drops bookkeeping (e.g. on release); the next attach starts clean. */
    fun reset() {
        attachedHandle = null
    }
}
