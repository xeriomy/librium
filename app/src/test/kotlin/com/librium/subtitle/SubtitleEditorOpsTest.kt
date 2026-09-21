package com.librium.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleEditorOpsTest {

    private fun doc(): SubtitleDocument = SubtitleDocument(
        format = SubtitleFormat.SRT,
        events = listOf(
            SubtitleEvent(id = 0, startMs = 1000, endMs = 3000, text = "First"),
            SubtitleEvent(id = 1, startMs = 4000, endMs = 6000, text = "Second"),
        ),
    )

    @Test
    fun update_text_and_timing() {
        val updated = doc().withEventText(0, "Edited").withEventTiming(0, 1500, 3500)
        assertEquals("Edited", updated.eventById(0)?.text)
        assertEquals(1500L, updated.eventById(0)?.startMs)
        assertEquals(3500L, updated.eventById(0)?.endMs)
        assertEquals("Second", updated.eventById(1)?.text)
    }

    @Test
    fun unknown_id_returns_same_instance() {
        val original = doc()
        assertSame(original, original.withEventText(99, "Nope"))
        assertSame(original, original.removeEvent(99))
        assertSame(original, original.splitEvent(99, 2000))
        assertSame(original, original.mergeEvents(0, 99))
    }

    @Test
    fun add_assigns_next_id_in_timeline_order() {
        val updated = doc().addEvent(3500, 3800, "Inserted")
        assertEquals(3, updated.events.size)
        assertEquals(2, updated.events[1].id)
        assertEquals("Inserted", updated.events[1].text)
        assertEquals(0, updated.events[0].id)
        assertEquals(1, updated.events[2].id)
    }

    @Test
    fun delete_keeps_other_ids_stable() {
        val updated = doc().removeEvent(0)
        assertEquals(1, updated.events.size)
        assertEquals(1, updated.events[0].id)
    }

    @Test
    fun split_multiline_event_by_lines() {
        val source = SubtitleDocument(
            format = SubtitleFormat.SRT,
            events = listOf(
                SubtitleEvent(id = 0, startMs = 1000, endMs = 5000, text = "One\nTwo\nThree\nFour"),
            ),
        )
        val split = source.splitEvent(0, 3000)
        assertEquals(2, split.events.size)
        assertEquals(1000L, split.events[0].startMs)
        assertEquals(3000L, split.events[0].endMs)
        assertEquals("One\nTwo", split.events[0].text)
        assertEquals(3000L, split.events[1].startMs)
        assertEquals(5000L, split.events[1].endMs)
        assertEquals("Three\nFour", split.events[1].text)
        assertEquals(1, split.events[1].id)
    }

    @Test
    fun split_single_line_proportionally() {
        val source = SubtitleDocument(
            format = SubtitleFormat.SRT,
            events = listOf(
                SubtitleEvent(id = 0, startMs = 0, endMs = 4000, text = "aaaa bbbb cccc dddd"),
            ),
        )
        val split = source.splitEvent(0, 2000)
        assertEquals(2, split.events.size)
        val joined = split.events[0].text + " " + split.events[1].text
        assertEquals("aaaa bbbb cccc dddd", joined)
        assertEquals(2000L, split.events[0].endMs)
        assertEquals(2000L, split.events[1].startMs)
    }

    @Test
    fun split_outside_range_is_noop() {
        val original = doc()
        assertSame(original, original.splitEvent(0, 1000))
        assertSame(original, original.splitEvent(0, 3000))
        assertSame(original, original.splitEvent(0, 99_000))
    }

    @Test
    fun merge_joins_span_and_text() {
        val merged = doc().mergeEvents(0, 1)
        assertEquals(1, merged.events.size)
        assertEquals(0, merged.events[0].id)
        assertEquals(1000L, merged.events[0].startMs)
        assertEquals(6000L, merged.events[0].endMs)
        assertEquals("First\nSecond", merged.events[0].text)
    }

    @Test
    fun operations_do_not_mutate_original() {
        val original = doc()
        original.withEventText(0, "Changed")
        original.addEvent(7000, 8000, "New")
        original.removeEvent(0)
        original.splitEvent(0, 2000)
        original.mergeEvents(0, 1)
        assertEquals("First", original.events[0].text)
        assertEquals(2, original.events.size)
    }

    @Test
    fun merged_text_skips_blanks() {
        val source = SubtitleDocument(
            format = SubtitleFormat.SRT,
            events = listOf(
                SubtitleEvent(id = 0, startMs = 1000, endMs = 2000, text = ""),
                SubtitleEvent(id = 1, startMs = 3000, endMs = 4000, text = "Only"),
            ),
        )
        assertEquals("Only", source.mergeEvents(0, 1).events[0].text)
    }

    @Test
    fun arabic_text_split_keeps_content() {
        val source = SubtitleDocument(
            format = SubtitleFormat.SRT,
            events = listOf(
                SubtitleEvent(id = 0, startMs = 0, endMs = 4000, text = "مرحبا بالعالم Hello world"),
            ),
        )
        val split = source.splitEvent(0, 2000)
        val joined = (split.events[0].text + " " + split.events[1].text)
            .replace("  ", " ")
        assertTrue(joined.contains("مرحبا"))
        assertTrue(joined.contains("world"))
    }
}
