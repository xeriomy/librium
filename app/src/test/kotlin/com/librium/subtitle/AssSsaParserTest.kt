package com.librium.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssSsaParserTest {

    private val parser = AssSubtitleParser()

    private val sample = """
        [Script Info]
        Title: Sample
        ScriptType: v4.00+
        WrapStyle: 0

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,2,2,10,10,10,1
        Style: Italic,Arial,18,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,-1,0,0,100,100,0,0,1,2,2,2,10,10,10,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,Hello world
        Comment: 0,0:00:02.00,0:00:03.00,Default,,0,0,0,,A note
        Dialogue: 0,0:00:05.00,0:00:06.50,Italic,,0,0,0,,{\b1}Bold{\b0} line\Nsecond

        [Fonts]
        fontname: Arial.ttf
    """.trimIndent()

    @Test
    fun script_info_becomes_metadata() {
        val doc = parser.parse("s.ass", sample)
        assertEquals(SubtitleFormat.ASS, doc.format)
        assertEquals("Sample", doc.metadata["Title"])
        assertEquals("v4.00+", doc.metadata["ScriptType"])
    }

    @Test
    fun styles_parsed_with_fields_and_raw() {
        val doc = parser.parse("s.ass", sample)
        assertEquals(2, doc.styles.size)
        val def = doc.styles["Default"]!!
        assertEquals("Arial", def.fontName)
        assertEquals(20.0, def.fontSize!!, 0.0)
        assertEquals("&H00FFFFFF", def.primaryColor)
        assertTrue(def.rawLine?.startsWith("Style: Default") == true)
        assertEquals("-1", doc.styles["Italic"]?.fields?.get("italic"))
    }

    @Test
    fun dialogue_events_and_comments() {
        val doc = parser.parse("s.ass", sample)
        assertEquals(2, doc.events.size)
        assertEquals("Hello world", doc.events[0].text)
        assertEquals("Default", doc.events[0].styleName)
        val comments = doc.extraSections["events-comments"].orEmpty()
        assertEquals(1, comments.size)
        assertTrue(comments[0].startsWith("Comment:"))
    }

    @Test
    fun override_tags_preserved_in_raw_and_stripped_in_text() {
        val doc = parser.parse("s.ass", sample)
        val second = doc.events[1]
        assertTrue(second.rawText?.contains("{") == true)
        assertEquals("Bold line\nsecond", second.text)
        assertEquals("Italic", second.styleName)
    }

    @Test
    fun unknown_sections_preserved() {
        val doc = parser.parse("s.ass", sample)
        assertEquals(listOf("fontname: Arial.ttf"), doc.extraSections["fonts"])
    }

    @Test
    fun header_preserved_for_export() {
        val doc = parser.parse("s.ass", sample)
        assertTrue(doc.rawHeader?.contains("[Script Info]") == true)
        assertTrue(doc.rawHeader?.contains("[V4+ Styles]") == true)
    }

    @Test
    fun reordered_format_columns_parse() {
        val text = """
            [Events]
            Format: Style, Start, Layer, End, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: Alt,0:00:01.00,0,0:00:04.00,,0,0,0,,Reordered
        """.trimIndent()
        val doc = parser.parse("r.ass", text)
        assertEquals(1, doc.events.size)
        assertEquals(1000L, doc.events[0].startMs)
        assertEquals(4000L, doc.events[0].endMs)
        assertEquals("Alt", doc.events[0].styleName)
        assertEquals("Reordered", doc.events[0].text)
    }

    @Test
    fun ssa_detected_by_extension() {
        val text = """
            [Script Info]
            ScriptType: v4.00

            [V4 Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, TertiaryColour, BackColour, Bold, Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, AlphaLevel, Encoding
            Style: Default,Arial,18,&H00FFFFFF,&H0000FFFF,&H00000000,&H80000000,-1,0,1,2,2,2,10,10,10,0,0

            [Events]
            Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: Marked=0,0:00:01.00,0:00:02.00,Default,,0000,0000,0000,,SSA line
        """.trimIndent()
        val doc = parser.parse("movie.ssa", text)
        assertEquals(SubtitleFormat.SSA, doc.format)
        assertEquals(1, doc.events.size)
        assertEquals("SSA line", doc.events[0].text)
        assertEquals("Marked=0", doc.events[0].extra["marked"])
        assertEquals("Default", doc.events[0].styleName)
    }

    @Test
    fun empty_and_invalid_kept() {
        val text = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,
            Dialogue: 0,0:00:05.00,0:00:04.00,Default,,0,0,0,,Backwards
        """.trimIndent()
        val doc = parser.parse("e.ass", text)
        assertEquals(2, doc.events.size)
        assertEquals("", doc.events[0].text)
        assertEquals(-1000L, doc.events[1].durationMs)
    }

    @Test
    fun file_order_not_sorted() {
        val text = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:10.00,0:00:11.00,Default,,0,0,0,,Later
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Earlier
        """.trimIndent()
        val doc = parser.parse("o.ass", text)
        assertEquals("Later", doc.events[0].text)
        assertEquals("Earlier", doc.events[1].text)
    }

    @Test
    fun unparseable_dialogue_skipped() {
        val text = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,not-a-time,0:00:02.00,Default,,0,0,0,,Bad
        """.trimIndent()
        val doc = parser.parse("b.ass", text)
        assertTrue(doc.events.isEmpty())
        assertNull(doc.eventById(0))
    }
}
