package com.librium.subtitle

/**
 * Phase 2 subtitle data model.
 *
 * Isolated from UI (`ui/`) and playback (`player/`): libmpv/libass stays
 * responsible for on-screen rendering. Times are `Long` milliseconds —
 * never floating point seconds — so millisecond precision survives
 * parse, transform, and export round trips.
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
    /** All parsed `Style:` fields (ASS/SSA), so nothing is lost. */
    val fields: Map<String, String> = emptyMap(),
    /** Original `Style:` definition line, used verbatim when exporting. */
    val rawLine: String? = null,
)

data class SubtitleEvent(
    /** Stable identity within a document; assigned in parse order. */
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    /** Plain display text (ASS override blocks stripped). */
    val text: String,
    val styleName: String? = null,
    /** VTT cue identifier line, preserved for round trips. */
    val identifier: String? = null,
    /** VTT cue settings after the timestamp (align, position, ...). */
    val cueSettings: String? = null,
    /** ASS/SSA original text including override tags; kept for export. */
    val rawText: String? = null,
    /** Extra Dialogue fields (layer, actor, margins, effect, marked, ...). */
    val extra: Map<String, String> = emptyMap(),
) {
    /** May be zero or negative for broken input; the analyzer flags those. */
    val durationMs: Long get() = endMs - startMs

    /** Text used for reading-speed metrics: overrides removed, whitespace excluded. */
    fun metricText(): String =
        text.replace(ASS_OVERRIDE_BLOCK, "").filter { !it.isWhitespace() }
}

internal val ASS_OVERRIDE_BLOCK = Regex("\\{[^}]*\\}")

data class SubtitleDocument(
    val format: SubtitleFormat,
    val events: List<SubtitleEvent>,
    val styles: Map<String, SubtitleStyle> = emptyMap(),
    /** ASS/SSA `[Script Info]` entries. */
    val metadata: Map<String, String> = emptyMap(),
    val sourceName: String? = null,
    /**
     * Lossless header preservation: ASS/SSA keeps everything before
     * `[Events]` (Script Info + Styles sections verbatim); VTT keeps
     * STYLE/REGION blocks and other header lines after `WEBVTT`.
     */
    val rawHeader: String? = null,
    /**
     * Other preserved raw material by section, e.g. ASS `[Fonts]`,
     * `[Graphics]`, or `Comment:` lines under key `"events-comments"`.
     */
    val extraSections: Map<String, List<String>> = emptyMap(),
    /** ASS/SSA `[Events]` Format line field order, when the file had one. */
    val eventFormat: String? = null,
    /** ASS/SSA Styles Format line field order, when the file had one. */
    val styleFormat: String? = null,
) {
    fun eventById(id: Int): SubtitleEvent? = events.firstOrNull { it.id == id }

    fun maxEndMs(): Long = events.maxOfOrNull { it.endMs } ?: 0L
}

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

/** Linear mapping of one subtitle timestamp onto video time. */
data class TimeMapping(
    val subtitleMs: Long,
    val videoMs: Long,
)

data class TimeRange(
    val startMs: Long,
    val endMs: Long,
)
