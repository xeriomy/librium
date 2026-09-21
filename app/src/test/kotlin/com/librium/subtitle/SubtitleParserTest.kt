package com.librium.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleParserTest {

    @Test
    fun srt_parsesBasicCues() {
        val text = """
            1
            00:00:01,000 --> 00:00:04,000
            Hello world

            2
            00:00:05,500 --> 00:00:07,000
            Second
            line
        """.trimIndent()
        val doc = SrtSubtitleParser().parse("test.srt", text)
        assertEquals(SubtitleFormat.SRT, doc.format)
        assertEquals(2, doc.events.size)
        assertEquals(1000L, doc.events[0].startMs)
        assertEquals(4000L, doc.events[0].endMs)
        assertEquals("Hello world", doc.events[0].text)
        assertEquals(5500L, doc.events[1].startMs)
        assertEquals("Second\nline", doc.events[1].text)
    }

    @Test
    fun srt_ignoresMalformedBlocks() {
        val text = "not a subtitle\n\n1\n00:00:01,000 --> 00:00:02,000\nOk\n"
        val doc = SrtSubtitleParser().parse(null, text)
        assertEquals(1, doc.events.size)
    }

    @Test
    fun vtt_parsesHeaderAndCues() {
        val text = """
            WEBVTT

            00:00.000 --> 00:04.000
            Hello

            00:05.000 --> 00:06.500
            World
        """.trimIndent()
        val doc = VttSubtitleParser().parse("test.vtt", text)
        assertEquals(SubtitleFormat.VTT, doc.format)
        assertEquals(2, doc.events.size)
        assertEquals(0L, doc.events[0].startMs)
        assertEquals(4000L, doc.events[0].endMs)
        assertEquals(5000L, doc.events[1].startMs)
        assertEquals(6500L, doc.events[1].endMs)
    }

    @Test
    fun vtt_parsesHourTimestamps() {
        val parser = VttSubtitleParser()
        assertEquals(3_723_000L, parser.parseTimestamp("01:02:03.000"))
    }

    @Test
    fun ass_extractsDialogueText() {
        val text = """
            [V4+ Styles]
            Format: Name, Fontname
            Style: Default,Arial

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,Hello {\b1}world
            Dialogue: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,Second\Nline
        """.trimIndent()
        val doc = AssSubtitleParser().parse("test.ass", text)
        assertEquals(SubtitleFormat.ASS, doc.format)
        assertEquals(2, doc.events.size)
        assertEquals(1000L, doc.events[0].startMs)
        assertEquals(4000L, doc.events[0].endMs)
        assertEquals("Hello world", doc.events[0].text)
        assertEquals("Second\nline", doc.events[1].text)
        assertEquals("Default", doc.events[0].styleName)
    }

    @Test
    fun formatForFileName_matchesExtensions() {
        assertEquals(SubtitleFormat.SRT, formatForFileName("content://x/subs/movie.srt"))
        assertEquals(SubtitleFormat.ASS, formatForFileName("movie.ass"))
        assertEquals(SubtitleFormat.SSA, formatForFileName("movie.SSA"))
        assertEquals(SubtitleFormat.VTT, formatForFileName("movie.vtt"))
        assertEquals(SubtitleFormat.UNKNOWN, formatForFileName("movie.srtx"))
    }

    @Test
    fun parserFor_returnsNullForUnknown() {
        assertNull(parserFor(SubtitleFormat.UNKNOWN))
        assertTrue(parserFor(SubtitleFormat.SRT) is SrtSubtitleParser)
    }
}
