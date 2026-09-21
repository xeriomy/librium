package com.librium.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtParserRobustnessTest {

    private val parser = SrtSubtitleParser()

    @Test
    fun bom_is_stripped() {
        val doc = parser.parse("bom.srt", "\uFEFF1\n00:00:01,000 --> 00:00:02,000\nHi\n")
        assertEquals(1, doc.events.size)
        assertEquals("Hi", doc.events[0].text)
    }

    @Test
    fun multiline_text_preserved() {
        val doc = parser.parse(null, "1\n00:00:01,000 --> 00:00:04,000\nOne\nTwo\nThree\n")
        assertEquals("One\nTwo\nThree", doc.events[0].text)
    }

    @Test
    fun missing_counter_still_parses() {
        val doc = parser.parse(null, "00:00:01,000 --> 00:00:02,000\nNo counter\n")
        assertEquals(1, doc.events.size)
        assertEquals("No counter", doc.events[0].text)
    }

    @Test
    fun dot_separator_and_short_ms_recover() {
        val doc = parser.parse(null, "1\n00:00:01.5 --> 00:00:02.50\nDot\n")
        assertEquals(1500L, doc.events[0].startMs)
        assertEquals(2500L, doc.events[0].endMs)
    }

    @Test
    fun mm_ss_fallback_parses() {
        val doc = parser.parse(null, "1\n01:02,500 --> 01:04,000\nShort\n")
        assertEquals(62_500L, doc.events[0].startMs)
        assertEquals(64_000L, doc.events[0].endMs)
    }

    @Test
    fun empty_text_is_kept_for_analysis() {
        val doc = parser.parse(null, "1\n00:00:01,000 --> 00:00:02,000\n")
        assertEquals(1, doc.events.size)
        assertEquals("", doc.events[0].text)
    }

    @Test
    fun invalid_durations_are_kept_for_analysis() {
        val doc = parser.parse(
            null,
            "1\n00:00:05,000 --> 00:00:02,000\nBackwards\n\n2\n00:00:03,000 --> 00:00:03,000\nZero\n",
        )
        assertEquals(2, doc.events.size)
        assertEquals(-3000L, doc.events[0].durationMs)
        assertEquals(0L, doc.events[1].durationMs)
    }

    @Test
    fun zero_timestamp_and_ms_precision() {
        val doc = parser.parse(null, "1\n00:00:00,000 --> 00:00:00,001\nBlink\n")
        assertEquals(0L, doc.events[0].startMs)
        assertEquals(1L, doc.events[0].endMs)
    }

    @Test
    fun file_order_preserved_with_ids() {
        val doc = parser.parse(
            null,
            "1\n00:00:10,000 --> 00:00:11,000\nSecond\n\n2\n00:00:01,000 --> 00:00:02,000\nFirst\n",
        )
        assertEquals(2, doc.events.size)
        assertEquals(0, doc.events[0].id)
        assertEquals("Second", doc.events[0].text)
        assertEquals(1, doc.events[1].id)
    }

    @Test
    fun unicode_arabic_and_mixed_text() {
        val arabic = "مرحبا بالعالم"
        val mixed = "Hello مرحبا world"
        val doc = parser.parse(
            null,
            "1\n00:00:01,000 --> 00:00:02,000\n$arabic\n\n2\n00:00:03,000 --> 00:00:04,000\n$mixed\n",
        )
        assertEquals(arabic, doc.events[0].text)
        assertEquals(mixed, doc.events[1].text)
    }

    @Test
    fun long_file_parses_completely() {
        val builder = StringBuilder()
        for (n in 0 until 1500) {
            val start = n * 2000L
            builder.append(n + 1).append('\n')
            builder.append(srtTs(start)).append(" --> ").append(srtTs(start + 1000)).append('\n')
            builder.append("Cue ").append(n).append("\n\n")
        }
        val doc = parser.parse("long.srt", builder.toString())
        assertEquals(1500, doc.events.size)
        assertEquals(0L, doc.events[0].startMs)
        assertEquals(1499L * 2000L + 1000L, doc.events[1499].endMs)
    }

    private fun srtTs(ms: Long): String {
        val h = ms / 3_600_000L
        val m = (ms % 3_600_000L) / 60_000L
        val s = (ms % 60_000L) / 1_000L
        val milli = ms % 1_000L
        return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
    }

    @Test
    fun inline_position_tags_preserved() {
        val doc = parser.parse(null, "1\n00:00:01,000 --> 00:00:02,000 X1:10 X2:20\nTag\n")
        assertEquals(1, doc.events.size)
        assertEquals(1000L, doc.events[0].startMs)
    }

    @Test
    fun blank_lines_between_blocks_tolerated() {
        val doc = parser.parse(null, "1\n00:00:01,000 --> 00:00:02,000\nA\n\n\n\n2\n00:00:03,000 --> 00:00:04,000\nB\n")
        assertEquals(2, doc.events.size)
        assertTrue(doc.events[1].text == "B")
    }
}
