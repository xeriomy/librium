package com.librium.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scale-oriented correctness checks. These assert behavior on large
 * inputs, not wall-clock time: no timing claims about devices are made.
 */
class SubtitlePerformanceTest {

    private fun largeSrt(count: Int, stepMs: Long = 2000L): String {
        val out = StringBuilder(count * 48)
        for (i in 0 until count) {
            val start = 100_000L + i * stepMs
            out.append(i + 1).append('\n')
                .append(formatSrtTimestamp(start))
                .append(" --> ")
                .append(formatSrtTimestamp(start + 1500L))
                .append('\n')
                .append("Cue number ").append(i).append('\n')
                .append('\n')
        }
        return out.toString()
    }

    private fun largeDoc(count: Int): SubtitleDocument {
        val events = ArrayList<SubtitleEvent>(count)
        for (i in 0 until count) {
            val start = 100_000L + i * 2000L
            events.add(SubtitleEvent(id = i, startMs = start, endMs = start + 1500L, text = "Cue $i"))
        }
        return SubtitleDocument(format = SubtitleFormat.SRT, events = events)
    }

    @Test
    fun large_subtitle_parses_completely() {
        val doc = SrtSubtitleParser().parse("large.srt", largeSrt(8000))
        assertEquals(8000, doc.events.size)
        assertEquals(100_000L, doc.events.first().startMs)
        assertEquals(100_000L + 7999L * 2000L + 1500L, doc.events.last().endMs)
        assertEquals("Cue number 7999", doc.events.last().text)
    }

    @Test
    fun large_subtitle_analyzes_with_expected_counts() {
        val events = ArrayList<SubtitleEvent>(2000)
        for (i in 0 until 2000) {
            val start = i * 3000L
            // Every 10th cue starts inside its predecessor.
            val overlap = i % 10 == 0 && i > 0
            events.add(
                SubtitleEvent(
                    id = i,
                    startMs = if (overlap) start - 2500L else start,
                    endMs = start + 2000L,
                    text = "Cue $i",
                ),
            )
        }
        val result = DefaultSubtitleAnalyzer().analyze(
            SubtitleDocument(format = SubtitleFormat.SRT, events = events),
        )
        assertEquals(199, result.statistics.overlapCount)
        assertEquals(2000, result.statistics.eventCount)
    }

    @Test
    fun repeated_shifts_equal_single_combined_shift() {
        val doc = largeDoc(1000)
        var shifted = doc
        repeat(100) { shifted = shifted.shiftAll(10L) }
        val combined = doc.shiftAll(1000L)
        assertEquals(combined.events.map { it.startMs }, shifted.events.map { it.startMs })
        assertEquals(combined.events.map { it.endMs }, shifted.events.map { it.endMs })
        // Original untouched by the whole chain.
        assertEquals(100_000L, doc.events.first().startMs)
    }

    @Test
    fun empty_document_transforms_do_not_copy() {
        val empty = SubtitleDocument(format = SubtitleFormat.SRT, events = emptyList())
        assertSame(empty, empty.shiftAll(500L))
        assertSame(empty, empty.transformTimings(TimingTransform.Offset(500L)))
        assertSame(empty, empty.removeEvent(42))
    }

    @Test
    fun large_export_round_trip_preserves_count() {
        val doc = largeDoc(3000)
        val reparsed = SrtSubtitleParser().parse("large.srt", doc.exportSrt())
        assertEquals(3000, reparsed.events.size)
        assertEquals(doc.events.first().startMs, reparsed.events.first().startMs)
        assertEquals(doc.events.last().endMs, reparsed.events.last().endMs)
    }
}
