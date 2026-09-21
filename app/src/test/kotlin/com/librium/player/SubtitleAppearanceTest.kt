package com.librium.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAppearanceTest {

    @Test
    fun white_converts_to_mpv_unit_color() {
        assertEquals("1.000/1.000/1.000/1.000", argbToMpvColor(0xFFFFFFFF.toInt()))
    }

    @Test
    fun black_with_80_percent_alpha() {
        assertEquals("0.000/0.000/0.000/0.800", argbToMpvColor(0xCC000000.toInt()))
    }

    @Test
    fun color_round_trip_within_one_step() {
        val original = 0xFF804020.toInt()
        val parsed = mpvColorToArgb(argbToMpvColor(original))!!
        assertTrue(maxChannelDiff(original, parsed) <= 1)
    }

    @Test
    fun malformed_colors_return_null() {
        assertNull(mpvColorToArgb(null))
        assertNull(mpvColorToArgb(""))
        assertNull(mpvColorToArgb("1.0/1.0/1.0"))
        assertNull(mpvColorToArgb("red/green/blue/alpha"))
    }

    @Test
    fun out_of_range_channels_clamp() {
        assertEquals(0xFFFFFFFF.toInt(), mpvColorToArgb("2.0/2.0/2.0/2.0"))
        assertEquals(0x00000000, mpvColorToArgb("-1.0/-1.0/-1.0/-1.0"))
    }

    @Test
    fun defaults_match_mpv_factory_values() {
        val d = DEFAULT_SUBTITLE_APPEARANCE
        assertEquals(55f, d.fontSize)
        assertEquals(0xFFFFFFFF.toInt(), d.textColor)
        assertEquals(3f, d.outlineSize)
        assertEquals(SubAlignX.CENTER, d.alignX)
        assertEquals(SubAlignY.BOTTOM, d.alignY)
        assertEquals(100, d.position)
    }

    @Test
    fun align_parsing_is_case_insensitive_with_fallback() {
        assertEquals(SubAlignX.LEFT, parseAlignX("LEFT"))
        assertEquals(SubAlignX.RIGHT, parseAlignX("right"))
        assertNull(parseAlignX("diagonal"))
        assertEquals(SubAlignY.TOP, parseAlignY("Top"))
        assertEquals(SubAlignY.BOTTOM, parseAlignY("bottom"))
        assertNull(parseAlignY(""))
    }

    private fun maxChannelDiff(a: Int, b: Int): Int {
        var max = 0
        for (shift in listOf(24, 16, 8, 0)) {
            val diff = kotlin.math.abs((a ushr shift and 0xFF) - (b ushr shift and 0xFF))
            if (diff > max) max = diff
        }
        return max
    }
}
