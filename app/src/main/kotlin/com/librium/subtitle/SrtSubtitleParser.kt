package com.librium.subtitle

private val SRT_TIMESTAMP = Regex("""(\d{2}):(\d{2}):(\d{2}),(\d{3})""")
private val SRT_RANGE = Regex("""(.+?)\s*-->\s*(.+)""")

class SrtSubtitleParser : SubtitleParser {
    override val supportedFormats: Set<SubtitleFormat> = setOf(SubtitleFormat.SRT)

    override fun parse(sourceName: String?, text: String): SubtitleDocument {
        val events = mutableListOf<SubtitleEvent>()
        val blocks = text.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
        var index = 0
        for (raw in blocks) {
            val lines = raw.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            // First line may be a numeric counter; drop it when present.
            val body = if (lines[0].trim().matches(Regex("\\d+")) && lines.size >= 3) {
                lines.drop(1)
            } else if (lines.size >= 2) {
                lines
            } else {
                continue
            }
            val range = SRT_RANGE.matchEntire(body[0].trim()) ?: continue
            val start = parseTimestamp(range.groupValues[1].trim()) ?: continue
            val end = parseTimestamp(range.groupValues[2].trim().split(" ").first()) ?: continue
            val caption = body.drop(1).joinToString("\n").trim()
            if (caption.isEmpty() || end <= start) continue
            events.add(
                SubtitleEvent(
                    index = index++,
                    startMs = start,
                    endMs = end,
                    text = caption,
                ),
            )
        }
        return SubtitleDocument(
            format = SubtitleFormat.SRT,
            events = events.sortedBy { it.startMs },
            sourceName = sourceName,
        )
    }

    internal fun parseTimestamp(value: String): Long? {
        val m = SRT_TIMESTAMP.matchEntire(value.trim()) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: return null
        val min = m.groupValues[2].toLongOrNull() ?: return null
        val s = m.groupValues[3].toLongOrNull() ?: return null
        val ms = m.groupValues[4].toLongOrNull() ?: return null
        return h * 3_600_000L + min * 60_000L + s * 1_000L + ms
    }
}
