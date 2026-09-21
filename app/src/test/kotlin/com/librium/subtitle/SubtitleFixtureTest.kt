package com.librium.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture-driven coverage: every file under `src/test/resources/subtitles`
 * is parsed, analyzed, transformed, and round-tripped here.
 */
class SubtitleFixtureTest {

    private val analyzer = DefaultSubtitleAnalyzer()

    @Test
    fun normal_srt_parses_and_analyzes_clean() {
        val doc = SrtSubtitleParser().parse("normal.srt", SubtitleFixtures.text("normal.srt"))
        assertEquals(3, doc.events.size)
        assertEquals(1000L, doc.events[0].startMs)
        assertEquals(62_250L, doc.events[2].startMs)
        val result = analyzer.analyze(doc)
        assertTrue(result.errors.isEmpty())
        assertEquals(3, result.statistics.eventCount)
    }

    @Test
    fun multiline_srt_keeps_line_breaks() {
        val doc = SrtSubtitleParser().parse("multiline.srt", SubtitleFixtures.text("multiline.srt"))
        assertEquals("First line\nSecond line\nThird line", doc.events[0].text)
        assertEquals("Another\nmultiline cue", doc.events[1].text)
    }

    @Test
    fun arabic_srt_is_verbatim() {
        val doc = SrtSubtitleParser().parse("arabic.srt", SubtitleFixtures.text("arabic.srt"))
        assertEquals("مرحبا بالعالم", doc.events[0].text)
        assertEquals("كيف حالك اليوم؟", doc.events[1].text)
        val words = doc.events[0].text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        assertEquals(2, words.size)
    }

    @Test
    fun mixed_arabic_english_round_trips() {
        val first = SrtSubtitleParser().parse("mixed-ar-en.srt", SubtitleFixtures.text("mixed-ar-en.srt"))
        assertEquals("Hello مرحبا world", first.events[0].text)
        val second = SrtSubtitleParser().parse("mixed-ar-en.srt", first.exportSrt())
        assertEquals(first.events[0].text, second.events[0].text)
        assertEquals(first.events[1].text, second.events[1].text)
    }

    @Test
    fun malformed_srt_recovers_and_flags() {
        val doc = SrtSubtitleParser().parse("malformed.srt", SubtitleFixtures.text("malformed.srt"))
        assertEquals(4, doc.events.size)
        assertEquals(1500L, doc.events[0].startMs)
        val result = analyzer.analyze(doc)
        assertTrue(result.errors.any { it.type == IssueType.ZERO_DURATION })
        assertTrue(result.errors.any { it.type == IssueType.OVERLAP })
    }

    @Test
    fun ass_fixture_styles_overrides_comments() {
        val doc = AssSubtitleParser().parse("styles.ass", SubtitleFixtures.text("styles.ass"))
        assertEquals("Fixture Styles", doc.metadata["Title"])
        assertEquals(2, doc.styles.size)
        assertEquals("-1", doc.styles["Narrator"]?.fields?.get("italic"))
        assertEquals(3, doc.events.size)
        assertEquals("Narrator", doc.events[1].styleName)
        assertTrue(doc.events[1].rawText?.contains("{\\i1}") == true)
        assertEquals("Emphasized line\nsecond row", doc.events[1].text)
        assertEquals("John", doc.events[2].extra["name"])
        assertEquals(1, doc.extraSections["events-comments"].orEmpty().size)
    }

    @Test
    fun ssa_fixture_format_and_marked() {
        val doc = AssSubtitleParser().parse("sample.ssa", SubtitleFixtures.text("sample.ssa"))
        assertEquals(SubtitleFormat.SSA, doc.format)
        assertEquals(2, doc.events.size)
        assertEquals("First SSA cue", doc.events[0].text)
        assertEquals("Marked=0", doc.events[0].extra["marked"])
        assertEquals(1, doc.styles.size)
    }

    @Test
    fun vtt_fixture_ids_settings_and_header() {
        val doc = VttSubtitleParser().parse("sample.vtt", SubtitleFixtures.text("sample.vtt"))
        assertEquals(2, doc.events.size)
        assertEquals("intro", doc.events[0].identifier)
        assertEquals("align:start position:0%", doc.events[0].cueSettings)
        assertEquals("Second cue\nwith two lines", doc.events[1].text)
        assertTrue(doc.rawHeader?.contains("::cue") == true)
        val again = VttSubtitleParser().parse("sample.vtt", doc.exportVtt())
        assertEquals("intro", again.events[0].identifier)
        assertEquals(2, again.events.size)
    }

    @Test
    fun sync_ops_apply_to_fixture_document() {
        val doc = SrtSubtitleParser().parse("normal.srt", SubtitleFixtures.text("normal.srt"))
        val shifted = doc.shiftAll(500L)
        assertEquals(1500L, shifted.events[0].startMs)
        // 25 -> 23.976 slows content down.
        val converted = doc.convertFps(25.0, 23.976)
        assertTrue(converted.events[0].startMs > doc.events[0].startMs)
        val drifted = doc.correctDrift(
            TimeMapping(doc.events.first().startMs, 2000L),
            TimeMapping(doc.events.last().endMs, doc.events.last().endMs + 1000L),
        )
        assertEquals(2000L, drifted.events.first().startMs)
    }

    @Test
    fun ass_fixture_export_keeps_everything() {
        val doc = AssSubtitleParser().parse("styles.ass", SubtitleFixtures.text("styles.ass"))
        val exported = doc.exportAs(SubtitleFormat.ASS)
        assertTrue(exported.contains("Title: Fixture Styles"))
        assertTrue(exported.contains("Style: Narrator"))
        assertTrue(exported.contains("{\\i1}"))
        assertTrue(exported.contains("Comment:"))
        val reparsed = AssSubtitleParser().parse("styles.ass", exported)
        assertEquals(3, reparsed.events.size)
        assertEquals("Fixture Styles", reparsed.metadata["Title"])
    }
}
