package com.librium.core

import android.util.Log
import com.librium.BuildConfig

/**
 * Structured development logging. All output is gated on [BuildConfig.DEBUG],
 * so release builds stay silent and pay no string-building cost at call
 * sites that use lazy messages.
 *
 * Every sink call is guarded so logging from code paths exercised by local
 * JVM unit tests (where android.util.Log is a stub) is a silent no-op
 * instead of a crash.
 */
object LibLog {
    const val PLAYER = "Librium:Player"
    const val MPV = "Librium:Mpv"
    const val SUB = "Librium:Sub"
    const val MEDIA = "Librium:Media"
    const val SYNC = "Librium:Sync"
    const val UI = "Librium:Ui"
    const val SAF = "Librium:Saf"
    const val SURFACE = "Librium:Surface"
    const val LIFECYCLE = "Librium:Lifecycle"

    fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) runCatching { Log.d(tag, message()) }
    }

    fun i(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) runCatching { Log.i(tag, message()) }
    }

    fun w(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) runCatching { Log.w(tag, message()) }
    }

    fun e(tag: String, throwable: Throwable? = null, message: () -> String) {
        if (BuildConfig.DEBUG) runCatching { Log.e(tag, message(), throwable) }
    }

    /**
     * DEBUG timing probe: logs `[START] op` / `[END] op durationMs=...`.
     * A missing END line for an op pinpoints a blocking call on-device.
     * Safe to call from unit tests (clock read is guarded).
     */
    suspend fun <T> timed(tag: String, op: String, block: suspend () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        val start = nowMs()
        d(tag) { "[START] $op" }
        try {
            return block()
        } finally {
            val took = nowMs() - start
            d(tag) { "[END] $op durationMs=$took" }
        }
    }

    private fun nowMs(): Long =
        runCatching { android.os.SystemClock.uptimeMillis() }.getOrDefault(0L)
}
