package com.librium.player

import kotlin.math.abs

/**
 * Exact mpv seek commands. Kept as pure construction so the flags the
 * engine sends are pinned by unit tests:
 *
 * - skip buttons use `relative+keyframes`: the demuxer jumps to the nearest
 *   keyframe without the exact-seek forward-decode pass, which is the fast
 *   path for ±10s jumps on local files;
 * - slider scrubbing keeps setting absolute `time-pos` (exact), so frame
 *   accuracy is preserved where the user aims precisely.
 */
object SeekCommands {

    fun relativeSkip(deltaMs: Long): Array<String> =
        arrayOf("seek", (deltaMs / 1000.0).toString(), "relative+keyframes")
}

/** A seek landing confirmed by a later position update. */
data class SeekLanding(
    val targetMs: Long,
    val landedMs: Long,
    val latencyMs: Long,
    val mode: String,
)

/**
 * Matches seek requests with later `time-pos` updates to measure real
 * on-device seek latency for development logging. Pure and unit-tested;
 * the engine feeds it from mpv callbacks.
 *
 * A new request replaces any unconfirmed one (rapid repeated seeks keep
 * only the latest target). Positions farther than [toleranceMs] from the
 * target never confirm — keyframe seeks may legitimately land seconds
 * away — and stale requests expire after [timeoutMs].
 */
class SeekTracker(
    private val clockMs: () -> Long = { android.os.SystemClock.uptimeMillis() },
    private val toleranceMs: Long = 2500L,
    private val timeoutMs: Long = 15000L,
) {

    private data class Pending(val targetMs: Long, val startedAtMs: Long, val mode: String)

    private var pending: Pending? = null

    fun onSeekRequested(targetMs: Long, mode: String) {
        pending = Pending(targetMs, clockMs(), mode)
    }

    fun onPositionChanged(positionMs: Long): SeekLanding? {
        val current = pending ?: return null
        val now = clockMs()
        if (now - current.startedAtMs > timeoutMs) {
            pending = null
            return null
        }
        return if (abs(positionMs - current.targetMs) <= toleranceMs) {
            pending = null
            SeekLanding(current.targetMs, positionMs, now - current.startedAtMs, current.mode)
        } else {
            null
        }
    }

    fun pendingTargetMs(): Long? = pending?.targetMs
}
