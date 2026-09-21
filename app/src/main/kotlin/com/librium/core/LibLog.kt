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
}
