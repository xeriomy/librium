package com.librium.player

import kotlin.math.roundToInt

/**
 * On-screen subtitle appearance, backed 1:1 by mpv/libass runtime
 * properties (`sub-font-size`, `sub-color`, ...). This is *player* state —
 * distinct from `subtitle.SubtitleStyle`, which models ASS file content.
 * libass keeps rendering; this only tunes it.
 */
enum class SubAlignX { LEFT, CENTER, RIGHT }

enum class SubAlignY { TOP, CENTER, BOTTOM }

data class SubtitleAppearance(
    val fontSize: Float = 55f,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val outlineSize: Float = 3f,
    val outlineColor: Int = 0xFF000000.toInt(),
    val backgroundColor: Int = 0xCC000000.toInt(),
    val shadowOffset: Float = 0f,
    val shadowColor: Int = 0x00000000,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val alignX: SubAlignX = SubAlignX.CENTER,
    val alignY: SubAlignY = SubAlignY.BOTTOM,
    val marginX: Int = 0,
    val marginY: Int = 0,
    /** Vertical position percent (mpv `sub-pos`); 100 is the bottom. */
    val position: Int = 100,
)

/** mpv factory defaults, used until the engine reads live values. */
val DEFAULT_SUBTITLE_APPEARANCE = SubtitleAppearance()

internal fun parseAlignX(value: String): SubAlignX? = when (value.lowercase()) {
    "left" -> SubAlignX.LEFT
    "center" -> SubAlignX.CENTER
    "right" -> SubAlignX.RIGHT
    else -> null
}

internal fun parseAlignY(value: String): SubAlignY? = when (value.lowercase()) {
    "top" -> SubAlignY.TOP
    "center" -> SubAlignY.CENTER
    "bottom" -> SubAlignY.BOTTOM
    else -> null
}

/**
 * Converts ARGB to mpv's `r/g/b/a` float color format.
 * Pure and unit-tested; the engine sends the result verbatim.
 */
fun argbToMpvColor(argb: Int): String {
    fun channel(shift: Int): String =
        "%.3f".format((argb ushr shift and 0xFF) / 255.0)
    return "${channel(16)}/${channel(8)}/${channel(0)}/${channel(24)}"
}

/** Parses mpv `r/g/b/a` floats back to ARGB; null when malformed. */
fun mpvColorToArgb(value: String?): Int? {
    val parts = value?.split("/") ?: return null
    if (parts.size != 4) return null
    val floats = parts.map { it.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: return null }
    val toByte = { f: Double -> (f * 255.0).roundToInt().coerceIn(0, 255) }
    return (toByte(floats[3]) shl 24) or
        (toByte(floats[0]) shl 16) or
        (toByte(floats[1]) shl 8) or
        toByte(floats[2])
}
