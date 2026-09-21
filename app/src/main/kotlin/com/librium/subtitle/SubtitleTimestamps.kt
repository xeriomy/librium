package com.librium.subtitle

/** Shared timestamp formatters for analysis messages and export. */

internal fun formatSrtTimestamp(ms: Long): String {
    val t = ms.coerceAtLeast(0L)
    val h = t / 3_600_000L
    val m = (t % 3_600_000L) / 60_000L
    val s = (t % 60_000L) / 1_000L
    val milli = t % 1_000L
    return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
}

internal fun formatVttTimestamp(ms: Long): String {
    val t = ms.coerceAtLeast(0L)
    val h = t / 3_600_000L
    val m = (t % 3_600_000L) / 60_000L
    val s = (t % 60_000L) / 1_000L
    val milli = t % 1_000L
    return "%02d:%02d:%02d.%03d".format(h, m, s, milli)
}

internal fun formatAssTimestamp(ms: Long): String {
    val t = ms.coerceAtLeast(0L)
    val h = t / 3_600_000L
    val m = (t % 3_600_000L) / 60_000L
    val s = (t % 60_000L) / 1_000L
    val cs = (t % 1_000L) / 10L
    return "%d:%02d:%02d.%02d".format(h, m, s, cs)
}
