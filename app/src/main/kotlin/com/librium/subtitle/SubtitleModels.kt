package com.librium.subtitle

/**
 * Phase 1 foundation for the future advanced subtitle engine.
 *
 * Deliberately isolated from UI (`ui/`) and playback (`player/`):
 * the player renders subtitles through libmpv in Phase 1 and does not
 * depend on these parsers for display. They exist so Phase 2 (analyzer /
 * synchronizer / custom renderer) has stable models to build on.
 */

enum class SubtitleFormat {
    SRT,
    VTT,
    ASS,
    SSA,
    UNKNOWN,
}

data class SubtitleStyle(
    val name: String,
    val fontName: String? = null,
    val fontSize: Double? = null,
    val primaryColor: String? = null,
)

data class SubtitleEvent(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val styleName: String? = null,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

data class SubtitleDocument(
    val format: SubtitleFormat,
    val events: List<SubtitleEvent>,
    val styles: Map<String, SubtitleStyle> = emptyMap(),
    val sourceName: String? = null,
)

/**
 * A subtitle track as known to the app layer (embedded or external).
 * Playback selection itself goes through [com.librium.player.PlayerEngine]
 * using the backend track id; this model is for the future engine and UI labels.
 */
data class SubtitleTrack(
    val id: String,
    val label: String,
    val format: SubtitleFormat = SubtitleFormat.UNKNOWN,
    val uri: String? = null,
    val isExternal: Boolean = false,
    val language: String? = null,
)
