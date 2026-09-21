package com.librium.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleExportTest {

    @Test
    fun srt_round_trip_preserves_timings_and_text() {
        val text = "1\n00:00:01,000 --> 00:00:04,500\nHello\nworld\n\n2\n01:02:03,004 --> 01:02:05,000\nBye\n"
        val first = SrtSubtitleParser().parse("a.srt", text)
        val second = SrtSubtitleParser().parse("a.srt", first.exportAs(SubtitleFormat.SRT))
        assertEquals(first.events.size, second.events.size)
        for (i in first.events.indices) {
            assertEquals(first.events[i].startMs, second.events[i].startMs)
            assertEquals(first.events[i].endMs, second.events[i].endMs)
            assertEquals(first.events[i].text, second.events[i].text)
        }
    }

    @Test
    fun vtt_round_trip_preserves_ids_and_settings() {
        val text = "WEBVTT\n\ncue-1\n00:01.000 --> 00:02.000 align:start\nHi\n\n" +
            "00:05.500 --> 00:06.000\nThere\n"
        val first = VttSubtitleParser().parse("a.vtt", text)
        val exported = first.exportAs(SubtitleFormat.VTT)
        assertTrue(exported.startsWith("WEBVTT"))
        val second = VttSubtitleParser().parse("a.vtt", exported)
        assertEquals(2, second.events.size)
        assertEquals("cue-1", second.events[0].identifier)
        assertEquals("align:start", second.events[0].cueSettings)
        assertEquals(first.events[1].startMs, second.events[1].startMs)
    }

    @Test
    fun ass_export_keeps_styles_header_and_overrides() {
        val text = """
            [Script Info]
            Title: Keep
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour
            Style: Default,Arial,20,&H00FFFFFF

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\i1}Em{\i0}
        """.trimIndent()
        val doc = AssSubtitleParser().parse("k.ass", text)
        val exported = doc.exportAs(SubtitleFormat.ASS)
        assertTrue(exported.contains("[Script Info]"))
        assertTrue(exported.contains("Title: Keep"))
        assertTrue(exported.contains("Style: Default,Arial,20,&H00FFFFFF"))
        assertTrue(exported.contains("{") && exported.contains("Em"))
        val reparsed = AssSubtitleParser().parse("k.ass", exported)
        assertEquals(1, reparsed.events.size)
        assertEquals("Em", reparsed.events[0].text)
        assertEquals("Keep", reparsed.metadata["Title"])
        assertEquals(1, reparsed.styles.size)
    }

    @Test
    fun ssa_export_uses_marked_form() {
        val doc = SubtitleDocument(
            format = SubtitleFormat.SSA,
            events = listOf(SubtitleEvent(id = 0, startMs = 1000, endMs = 2000, text = "S")),
        )
        assertTrue(doc.exportAs(SubtitleFormat.SSA).contains("Marked=0"))
    }

    @Test
    fun shifted_document_exports_shifted_times() {
        val doc = SubtitleDocument(
            format = SubtitleFormat.SRT,
            events = listOf(SubtitleEvent(id = 0, startMs = 1000, endMs = 2000, text = "X")),
        ).shiftAll(500L)
        assertTrue(doc.exportSrt().contains("00:00:01,500 --> 00:00:02,500"))
    }

    @Test
    fun unicode_and_mixed_text_round_trip() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nمرحبا Hello 世界\n"
        val doc = SrtSubtitleParser().parse("u.srt", text)
        val again = SrtSubtitleParser().parse("u.srt", doc.exportSrt())
        assertEquals("مرحبا Hello 世界", again.events[0].text)
    }

    @Test
    fun ass_newlines_become_hard_breaks() {
        val doc = SubtitleDocument(
            format = SubtitleFormat.ASS,
            events = listOf(SubtitleEvent(id = 0, startMs = 1000, endMs = 2000, text = "A\nB")),
        )
        val exported = doc.exportAss()
        assertTrue(exported.contains("A\\NB"))
        val reparsed = AssSubtitleParser().parse("n.ass", exported)
        assertEquals("A\nB", reparsed.events[0].text)
    }
}
