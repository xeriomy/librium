package com.librium.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackLabelsTest {

    @Test
    fun bare_number_when_no_metadata() {
        assertEquals("#3", audioTrackLabel(3, null, null))
        assertEquals("#3", audioTrackLabel(3, "", ""))
        assertEquals("#1", subtitleTrackLabel(1, null, null, external = false))
    }

    @Test
    fun title_and_language_compose() {
        assertEquals(
            "#1 Commentary (eng)",
            audioTrackLabel(1, "eng", "Commentary"),
        )
        assertEquals(
            "#2 Full Subtitles (ar) [ext]",
            subtitleTrackLabel(2, "ar", "Full Subtitles", external = true),
        )
    }

    @Test
    fun language_only_and_title_only() {
        assertEquals("#0 (jpn)", audioTrackLabel(0, "jpn", null))
        assertEquals("#5 Forced", subtitleTrackLabel(5, null, "Forced", external = false))
    }

    @Test
    fun external_flag_only_on_subtitles() {
        assertEquals(
            "#7 English (en) [ext]",
            subtitleTrackLabel(7, "en", "English", external = true),
        )
        assertEquals(
            "#7 English (en)",
            subtitleTrackLabel(7, "en", "English", external = false),
        )
    }
}
