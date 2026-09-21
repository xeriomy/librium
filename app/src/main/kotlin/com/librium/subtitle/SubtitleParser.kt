package com.librium.subtitle

/**
 * Parses a subtitle file's text into a [SubtitleDocument].
 * Implementations must be pure Kotlin (no Android dependencies) so they
 * stay unit-testable and independent of the playback backend.
 */
interface SubtitleParser {
    val supportedFormats: Set<SubtitleFormat>
    fun parse(sourceName: String?, text: String): SubtitleDocument
}

/** Returns the parser for [format], or null when Phase 1 has no parser for it. */
fun parserFor(format: SubtitleFormat): SubtitleParser? = when (format) {
    SubtitleFormat.SRT -> SrtSubtitleParser()
    SubtitleFormat.VTT -> VttSubtitleParser()
    SubtitleFormat.ASS, SubtitleFormat.SSA -> AssSubtitleParser()
    SubtitleFormat.UNKNOWN -> null
}

/** Infers format from a file name / URI string. Extension match only. */
fun formatForFileName(name: String): SubtitleFormat {
    val ext = name.substringAfterLast('.', "").substringBefore('?').lowercase()
    return when (ext) {
        "srt" -> SubtitleFormat.SRT
        "vtt" -> SubtitleFormat.VTT
        "ass" -> SubtitleFormat.ASS
        "ssa" -> SubtitleFormat.SSA
        else -> SubtitleFormat.UNKNOWN
    }
}
