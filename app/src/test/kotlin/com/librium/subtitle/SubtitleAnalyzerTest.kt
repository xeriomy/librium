package com.librium.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAnalyzerTest {

    private val analyzer = DefaultSubtitleAnalyzer()

    private fun doc(vararg events: SubtitleEvent): SubtitleDocument =
        SubtitleDocument(format = SubtitleFormat.SRT, events = events.toList())

    private fun ev(id: Int, start: Long, end: Long, text: String): SubtitleEvent =
        SubtitleEvent(id = id, startMs = start, endMs = end, text = text)

    @Test
    fun clean_document_has_no_issues() {
        val result = analyzer.analyze(doc(ev(0, 1000, 3000, "Hello world")))
        assertFalse(result.hasErrors)
        assertTrue(result.warnings.isEmpty())
        assertEquals(1, result.statistics.eventCount)
    }

    @Test
    fun overlap_is_error_with_both_ids() {
        val result = analyzer.analyze(
            doc(ev(0, 1000, 3000, "First"), ev(1, 2500, 4000, "Second")),
        )
        assertTrue(result.hasErrors)
        val overlap = result.errors.first { it.type == IssueType.OVERLAP }
        assertEquals(1, overlap.eventId)
        assertTrue(overlap.message.contains("#0"))
        assertEquals(1, result.statistics.overlapCount)
    }

    @Test
    fun zero_and_negative_durations_are_errors() {
        val result = analyzer.analyze(
            doc(ev(0, 1000, 1000, "Zero"), ev(1, 5000, 4000, "Back")),
        )
        assertTrue(result.errors.any { it.type == IssueType.ZERO_DURATION && it.eventId == 0 })
        assertTrue(result.errors.any { it.type == IssueType.NEGATIVE_DURATION && it.eventId == 1 })
    }

    @Test
    fun too_short_and_too_long_warn() {
        val result = analyzer.analyze(
            doc(ev(0, 1000, 1500, "Brief"), ev(1, 5000, 15000, "Dragging on")),
        )
        assertTrue(result.warnings.any { it.type == IssueType.TOO_SHORT && it.eventId == 0 })
        assertTrue(result.warnings.any { it.type == IssueType.TOO_LONG && it.eventId == 1 })
    }

    @Test
    fun empty_text_is_error() {
        val result = analyzer.analyze(doc(ev(0, 1000, 3000, "   ")))
        assertTrue(result.errors.any { it.type == IssueType.EMPTY_TEXT })
    }

    @Test
    fun duplicate_text_warns_on_repeat() {
        val result = analyzer.analyze(
            doc(ev(0, 1000, 3000, "Same words"), ev(1, 5000, 7000, "same  WORDS")),
        )
        val dup = result.warnings.first { it.type == IssueType.DUPLICATE_TEXT }
        assertEquals(1, dup.eventId)
        assertTrue(dup.suggestedFix?.isNotBlank() == true)
    }

    @Test
    fun long_line_and_many_lines_warn() {
        val long = "This line is definitely longer than forty-two characters for sure"
        val result = analyzer.analyze(
            doc(ev(0, 1000, 4000, "$long\nsecond\nthird")),
        )
        assertTrue(result.warnings.any { it.type == IssueType.LONG_LINE })
        assertTrue(result.warnings.any { it.type == IssueType.TOO_MANY_LINES })
    }

    @Test
    fun high_reading_speed_warns_with_cps_stats() {
        val text = "Sixty characters packed into a single second of screen time here!"
        val result = analyzer.analyze(doc(ev(0, 1000, 2000, text)))
        assertTrue(result.warnings.any { it.type == IssueType.HIGH_CPS })
        assertTrue(result.statistics.maxCps > 20.0)
        assertEquals(0, result.statistics.maxCpsEventId)
        assertTrue(result.statistics.avgCps > 0.0)
    }

    @Test
    fun beyond_video_end_warns_only_when_provided() {
        val d = doc(ev(0, 1000, 120_000, "Long tail"))
        assertTrue(analyzer.analyze(d, videoDurationMs = 60_000L).warnings
            .any { it.type == IssueType.BEYOND_VIDEO_END })
        assertTrue(analyzer.analyze(d, videoDurationMs = null).warnings
            .none { it.type == IssueType.BEYOND_VIDEO_END })
    }

    @Test
    fun short_gap_warns_and_long_gap_is_info() {
        val result = analyzer.analyze(
            doc(
                ev(0, 0, 1000, "A"),
                ev(1, 1050, 2000, "B"),
                ev(2, 60_000, 61_000, "C"),
            ),
        )
        assertTrue(result.warnings.any { it.type == IssueType.SHORT_GAP })
        assertTrue(result.info.any { it.type == IssueType.LONG_GAP })
    }

    @Test
    fun statistics_cover_counts_and_words() {
        val result = analyzer.analyze(
            doc(ev(0, 0, 2000, "Hello world"), ev(1, 3000, 6000, "Another test case")),
        )
        val stats = result.statistics
        assertEquals(2, stats.eventCount)
        assertEquals(6000L, stats.spanMs)
        assertEquals(5000L, stats.visibleMs)
        assertEquals(7L, stats.totalWords)
        assertTrue(stats.totalCharacters > 0)
        assertTrue(result.issueCount == result.all.size)
    }

    @Test
    fun empty_document_analyzes_cleanly() {
        val result = analyzer.analyze(doc())
        assertEquals(0, result.statistics.eventCount)
        assertTrue(result.all.isEmpty())
    }

    @Test
    fun issues_carry_severity_and_fix() {
        val result = analyzer.analyze(doc(ev(0, 5000, 4000, "")))
        for (issue in result.all) {
            assertTrue(issue.message.isNotBlank())
        }
        assertTrue(result.errors.all { it.severity == IssueSeverity.ERROR })
    }
}
