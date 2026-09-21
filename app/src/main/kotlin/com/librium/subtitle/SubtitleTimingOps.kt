package com.librium.subtitle

/**
 * Immutable timing transforms. Every operation returns a new
 * [SubtitleDocument]; the input is never mutated, so large documents can
 * be transformed without aliasing surprises for the caller holding the old one.
 */
sealed interface TimingTransform {
    /** Constant shift applied to every timestamp. */
    data class Offset(val offsetMs: Long) : TimingTransform

    /** Multiplicative scaling around [pivotMs] (FPS conversions use this). */
    data class Scale(val factor: Double, val pivotMs: Long = 0L) : TimingTransform

    /** Frame-rate conversion expressed as source and target rates. */
    data class Fps(val sourceFps: Double, val targetFps: Double) : TimingTransform

    /** Linear drift correction pinned at two subtitle-to-video mappings. */
    data class Drift(val start: TimeMapping, val end: TimeMapping) : TimingTransform
}

/** Maps one timestamp through [transform], clamped to zero or later. */
fun TimingTransform.map(timestampMs: Long): Long = when (this) {
    is TimingTransform.Offset -> (timestampMs + offsetMs).coerceAtLeast(0L)
    is TimingTransform.Scale -> (pivotMs + (timestampMs - pivotMs) * factor)
        .toLong().coerceAtLeast(0L)
    is TimingTransform.Fps -> {
        require(sourceFps > 0 && targetFps > 0) { "FPS rates must be positive" }
        (timestampMs * (sourceFps / targetFps)).toLong().coerceAtLeast(0L)
    }
    is TimingTransform.Drift -> driftMap(timestampMs, start, end)
}

/** Maps a range; ordering is preserved (end is never pulled before start). */
fun TimingTransform.mapRange(range: TimeRange): TimeRange {
    val start = map(range.startMs)
    val end = map(range.endMs).coerceAtLeast(start)
    return TimeRange(start, end)
}

private fun driftMap(t: Long, start: TimeMapping, end: TimeMapping): Long {
    val span = end.subtitleMs - start.subtitleMs
    if (span == 0L) return (t + (start.videoMs - start.subtitleMs)).coerceAtLeast(0L)
    val slope = (end.videoMs - start.videoMs).toDouble() / span
    if (!slope.isFinite() || slope <= 0.0) {
        return (t + (start.videoMs - start.subtitleMs)).coerceAtLeast(0L)
    }
    return (start.videoMs + (t - start.subtitleMs) * slope).toLong().coerceAtLeast(0L)
}

/** Applies [transform] to every event; returns a new document. */
fun SubtitleDocument.transformTimings(transform: TimingTransform): SubtitleDocument {
    if (events.isEmpty()) return this
    return copy(
        events = events.map { event ->
            val mapped = transform.mapRange(TimeRange(event.startMs, event.endMs))
            event.copy(startMs = mapped.startMs, endMs = mapped.endMs)
        },
    )
}

/** Shifts every event by [offsetMs] (negative moves earlier, clamped at zero). */
fun SubtitleDocument.shiftAll(offsetMs: Long): SubtitleDocument =
    transformTimings(TimingTransform.Offset(offsetMs))

/** Scales every timestamp by [factor] around [pivotMs]. */
fun SubtitleDocument.scaleTiming(factor: Double, pivotMs: Long = 0L): SubtitleDocument {
    require(factor.isFinite() && factor > 0) { "Scale factor must be positive and finite" }
    return transformTimings(TimingTransform.Scale(factor, pivotMs))
}

/**
 * Converts timings between frame rates with exact timestamp scaling:
 * `target = source * (sourceFps / targetFps)`. A constant offset is NOT
 * added — e.g. 25 fps content retimed to 23.976 runs longer by design.
 */
fun SubtitleDocument.convertFps(sourceFps: Double, targetFps: Double): SubtitleDocument =
    transformTimings(TimingTransform.Fps(sourceFps, targetFps))

/**
 * Linear drift correction through two anchor mappings. The anchors map
 * exactly; intermediate events interpolate, which preserves ordering.
 */
fun SubtitleDocument.correctDrift(start: TimeMapping, end: TimeMapping): SubtitleDocument =
    transformTimings(TimingTransform.Drift(start, end))
