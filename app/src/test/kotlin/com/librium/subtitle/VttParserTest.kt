package com.librium.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VttParserTest {

    private val parser = VttSubtitleParser()

    @Test
    fun header_with_title_and_cue_settings() {
        val doc = parser.parse(
            "a.vtt",
            "WEBVTT - My Movie\n\n00:01.000 --> 00:02.000 align:start position:0%\nHi\n",
        )
        assertEquals(1, doc.events.size)
        assertEquals(1000L, doc.events[0].startMs)
        assertEquals("align:start position:0%", doc.events[0].cueSettings)
    }

    @Test
    fun cue_identifier_preserved() {
        val doc = parser.parse(
            null,
            "WEBVTT\n\ncue-1\n00:01.000 --> 00:02.000\nHello\n",
        )
        assertEquals("cue-1", doc.events[0].identifier)
        assertEquals("Hello", doc.events[0].text)
    }

    @Test
    fun multiline_cue_and_hour_timestamps() {
        val doc = parser.parse(
            null,
            "WEBVTT\n\n01:02:03.500 --> 01:02:05.000\nLine one\nLine two\n",
        )
        assertEquals(3_723_500L, doc.events[0].startMs)
        assertEquals("Line one\nLine two", doc.events[0].text)
    }

    @Test
    fun note_style_region_skipped_but_style_preserved() {
        val doc = parser.parse(
            null,
            "WEBVTT\n\nNOTE a comment\nspanning lines\n\nSTYLE\n::cue { color: white }\n\n" +
                "00:01.000 --> 00:02.000\nKept\n",
        )
        assertEquals(1, doc.events.size)
        assertEquals("Kept", doc.events[0].text)
        assertTrue(doc.rawHeader?.contains("::cue") == true)
    }

    @Test
    fun missing_header_still_parses() {
        val doc = parser.parse(null, "00:01.000 --> 00:02.000\nNo header\n")
        assertEquals(1, doc.events.size)
    }

    @Test
    fun empty_cue_kept_for_analysis() {
        val doc = parser.parse(null, "WEBVTT\n\n00:01.000 --> 00:02.000\n")
        assertEquals(1, doc.events.size)
        assertEquals("", doc.events[0].text)
    }

    @Test
    fun invalid_cue_end_skipped_identifier_reset() {
        val doc = parser.parse(
            null,
            "WEBVTT\n\nid-x\n00:01.000 --> bad\n\n00:03.000 --> 00:04.000\nOk\n",
        )
        assertEquals(1, doc.events.size)
        assertNull(doc.events[0].identifier)
    }

    @Test
    fun bom_handled() {
        val doc = parser.parse(null, "\uFEFFWEBVTT\n\n00:01.000 --> 00:02.000\nBOM\n")
        assertEquals(1, doc.events.size)
        assertEquals("BOM", doc.events[0].text)
    }
}
