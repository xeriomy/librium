package com.librium.subtitle

/**
 * Playback-side subtitle timing contract. Phase 2 keeps libmpv/libass as
 * the renderer: live nudges go through `sub-delay` on the player, while
 * these operations rewrite document timestamps for analysis, preview,
 * and export. Everything returns new documents; inputs are never mutated.
 */
interface SubtitleSynchronizer {
    fun shift(document: SubtitleDocument, offsetMs: Long): SubtitleDocument
    fun scale(document: SubtitleDocument, factor: Double, pivotMs: Long = 0L): SubtitleDocument
    fun convertFps(document: SubtitleDocument, sourceFps: Double, targetFps: Double): SubtitleDocument
    fun correctDrift(
        document: SubtitleDocument,
        start: TimeMapping,
        end: TimeMapping,
    ): SubtitleDocument

    /**
     * Corrected range for one event without touching the document,
     * for previewing a transform while video keeps playing.
     */
    fun preview(
        document: SubtitleDocument,
        transform: TimingTransform,
        eventId: Int,
    ): TimeRange?
}

class DefaultSubtitleSynchronizer : SubtitleSynchronizer {
    override fun shift(document: SubtitleDocument, offsetMs: Long): SubtitleDocument =
        document.shiftAll(offsetMs)

    override fun scale(document: SubtitleDocument, factor: Double, pivotMs: Long): SubtitleDocument =
        document.scaleTiming(factor, pivotMs)

    override fun convertFps(
        document: SubtitleDocument,
        sourceFps: Double,
        targetFps: Double,
    ): SubtitleDocument = document.convertFps(sourceFps, targetFps)

    override fun correctDrift(
        document: SubtitleDocument,
        start: TimeMapping,
        end: TimeMapping,
    ): SubtitleDocument = document.correctDrift(start, end)

    override fun preview(
        document: SubtitleDocument,
        transform: TimingTransform,
        eventId: Int,
    ): TimeRange? {
        val event = document.eventById(eventId) ?: return null
        return transform.mapRange(TimeRange(event.startMs, event.endMs))
    }
}
