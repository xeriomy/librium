package com.librium.subtitle

private val SRT_RANGE = Regex("""(.+?)\s*-->\s*(.+)""")
private val SRT_FULL = Regex("""(\d{1,4}):(\d{1,2}):(\d{1,2})[,.](\d{1,3})""")
private val SRT_SHORT = Regex("""(\d{1,2}):(\d{1,2})[,.](\d{1,3})""")
private val SRT_COUNTER = Regex("""\d+""")

/**
 * Lenient SRT parser for realistic files: BOM, missing/out-of-order
 * counters, multiline cues, dot or comma decimals, 1-3 digit milliseconds,
 * and recoverable timestamps. File order is preserved (ids follow it);
 * broken events (empty text, non-positive duration) are kept so the
 * analyzer can report them instead of silently dropping content.
 */
class SrtSubtitleParser : SubtitleParser {
    override val supportedFormats: Set<SubtitleFormat> = setOf(SubtitleFormat.SRT)

    override fun parse(sourceName: String?, text: String): SubtitleDocument {
        val cleaned = stripBom(text).replace("\r\n", "\n").replace("\r", "\n")
        val events = mutableListOf<SubtitleEvent>()
        val blocks = cleaned.split(Regex("\n[ \t]*\n"))
        var id = 0
        for (raw in blocks) {
            val lines = raw.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            // Optional numeric counter first.
            val body = if (lines.size >= 2 && SRT_COUNTER.matches(lines[0].trim())) {
                lines.drop(1)
            } else {
                lines
            }
            if (body.isEmpty()) continue
            val range = SRT_RANGE.matchEntire(body[0].trim()) ?: continue
            val start = parseTimestamp(range.groupValues[1]) ?: continue
            val endToken = range.groupValues[2].trim().split(Regex("\\s+")).firstOrNull()
                ?: continue
            val end = parseTimestamp(endToken) ?: continue
            val caption = body.drop(1).joinToString("\n").trim()
            events.add(
                SubtitleEvent(
                    id = id++,
                    startMs = start,
                    endMs = end,
                    text = caption,
                ),
            )
        }
        return SubtitleDocument(
            format = SubtitleFormat.SRT,
            events = events,
            sourceName = sourceName,
        )
    }

    /**
     * Strict `HH:MM:SS,mmm` plus recoverable variants: dot decimals,
     * 1-3 digit milliseconds (right-padded), 1+ digit hours, and a
     * `MM:SS,mmm` fallback. Returns null only when nothing parses.
     */
    internal fun parseTimestamp(value: String): Long? {
        val v = value.trim()
        SRT_FULL.matchEntire(v)?.let { m ->
            val h = m.groupValues[1].toLongOrNull() ?: return null
            val min = m.groupValues[2].toLongOrNull() ?: return null
            val s = m.groupValues[3].toLongOrNull() ?: return null
            val ms = millis3(m.groupValues[4]) ?: return null
            return h * 3_600_000L + min * 60_000L + s * 1_000L + ms
        }
        SRT_SHORT.matchEntire(v)?.let { m ->
            val min = m.groupValues[1].toLongOrNull() ?: return null
            val s = m.groupValues[2].toLongOrNull() ?: return null
            val ms = millis3(m.groupValues[3]) ?: return null
            return min * 60_000L + s * 1_000L + ms
        }
        return null
    }

    private fun millis3(digits: String): Long? {
        if (digits.any { !it.isDigit() }) return null
        return digits.padEnd(3, '0').toLongOrNull()
    }
}
