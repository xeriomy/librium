package com.librium.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSynchronizerTest {

    private val synchronizer: SubtitleSynchronizer = DefaultSubtitleSynchronizer()

    private fun doc(): SubtitleDocument = SubtitleDocument(
        format = SubtitleFormat.SRT,
        events = listOf(
            SubtitleEvent(id = 0, startMs = 1000, endMs = 3000, text = "A"),
            SubtitleEvent(id = 1, startMs = 5000, endMs = 7000, text = "B"),
        ),
    )

    @Test
    fun global_offset_shifts_all() {
        val shifted = synchronizer.shift(doc(), 500L)
        assertEquals(1500L, shifted.events[0].startMs)
        assertEquals(3500L, shifted.events[0].endMs)
        assertEquals(5500L, shifted.events[1].startMs)
    }

    @Test
    fun negative_offset_clamps_at_zero() {
        val shifted = synchronizer.shift(doc(), -1500L)
        assertEquals(0L, shifted.events[0].startMs)
        assertEquals(1500L, shifted.events[0].endMs)
        assertEquals(3500L, shifted.events[1].startMs)
    }

    @Test
    fun fps_conversion_scales_without_offset() {
        // 25 fps content retimed to 23.976 runs longer by exactly 25/23.976.
        val converted = synchronizer.convertFps(doc(), 25.0, 23.976)
        assertEquals((1000L * 25.0 / 23.976).toLong(), converted.events[0].startMs)
        assertEquals((5000L * 25.0 / 23.976).toLong(), converted.events[1].startMs)
        assertEquals((7000L * 25.0 / 23.976).toLong(), converted.events[1].endMs)
        val before = 7000L - 5000L
        val after = converted.events[1].endMs - converted.events[1].startMs
        assertTrue(after > before)
    }

    @Test
    fun drift_maps_anchors_exactly_and_preserves_order() {
        val start = TimeMapping(subtitleMs = 1000, videoMs = 1500)
        val end = TimeMapping(subtitleMs = 7000, videoMs = 7500)
        val fixed = synchronizer.correctDrift(doc(), start, end)
        assertEquals(1500L, fixed.events[0].startMs)
        assertEquals(7500L, fixed.events[1].endMs)
        val starts = fixed.events.map { it.startMs }
        assertEquals(starts.sorted(), starts)
        for (event in fixed.events) {
            assertTrue(event.endMs >= event.startMs)
        }
    }

    @Test
    fun drift_midpoint_interpolates_linearly() {
        val start = TimeMapping(subtitleMs = 0, videoMs = 1000)
        val end = TimeMapping(subtitleMs = 10_000, videoMs = 12_000)
        val fixed = SubtitleDocument(
            format = SubtitleFormat.SRT,
            events = listOf(SubtitleEvent(id = 0, startMs = 5000, endMs = 6000, text = "M")),
        ).correctDrift(start, end)
        // slope 1.1: 5000 -> 1000 + 5000*1.1 = 6500
        assertEquals(6500L, fixed.events[0].startMs)
        assertEquals(7600L, fixed.events[0].endMs)
    }

    @Test
    fun degenerate_drift_falls_back_to_offset() {
        val anchor = TimeMapping(subtitleMs = 1000, videoMs = 1500)
        val fixed = synchronizer.correctDrift(doc(), anchor, anchor)
        assertEquals(1500L, fixed.events[0].startMs)
        assertEquals(5500L, fixed.events[1].startMs)
    }

    @Test
    fun preview_matches_apply_without_mutating() {
        val original = doc()
        val before = original.events.map { it.startMs to it.endMs }
        val preview = synchronizer.preview(original, TimingTransform.Offset(500L), 1)
        assertEquals(TimeRange(5500L, 7500L), preview)
        assertEquals(before, original.events.map { it.startMs to it.endMs })
        assertNull(synchronizer.preview(original, TimingTransform.Offset(500L), 99))
    }

    @Test
    fun shift_does_not_mutate_original() {
        val original = doc()
        val shifted = original.shiftAll(1000L)
        assertEquals(1000L, original.events[0].startMs)
        assertEquals(2000L, shifted.events[0].startMs)
    }

    @Test
    fun scale_around_pivot() {
        val scaled = SubtitleDocument(
            format = SubtitleFormat.SRT,
            events = listOf(SubtitleEvent(id = 0, startMs = 2000, endMs = 4000, text = "S")),
        ).scaleTiming(2.0, pivotMs = 1000L)
        assertEquals(3000L, scaled.events[0].startMs)
        assertEquals(7000L, scaled.events[0].endMs)
    }
}
