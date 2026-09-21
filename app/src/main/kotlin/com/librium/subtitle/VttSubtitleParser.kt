package com.librium.subtitle

private val VTT_RANGE = Regex("""(.+?)\s*-->\s*(.+)""")

class VttSubtitleParser : SubtitleParser {
    override val supportedFormats: Set<SubtitleFormat> = setOf(SubtitleFormat.VTT)

    override fun parse(sourceName: String?, text: String): SubtitleDocument {
        val normalized = text.replace("\r\n", "\n")
        val lines = normalized.lines()
        // Skip WEBVTT header and any NOTE/STYLE blocks up front.
        var cursor = 0
        while (cursor < lines.size && lines[cursor].trim().isEmpty()) cursor++
        if (cursor < lines.size && lines[cursor].startsWith("WEBVTT")) cursor++

        val events = mutableListOf<SubtitleEvent>()
        var index = 0
        var pendingId: String? = null
        var i = cursor
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                i++
                continue
            }
            if (line.startsWith("NOTE") || line.startsWith("STYLE") || line.startsWith("REGION")) {
                // Skip multi-line metadata blocks.
                i++
                while (i < lines.size && lines[i].trim().isNotEmpty()) i++
                continue
            }
            val range = VTT_RANGE.find(line)
            if (range == null) {
                // May be a cue identifier preceding the timestamp line.
                pendingId = line
                i++
                continue
            }
            val start = parseTimestamp(range.groupValues[1].trim()) ?: run { i++; pendingId = null; continue }
            val endToken = range.groupValues[2].trim().split(" ").first()
            val end = parseTimestamp(endToken) ?: run { i++; pendingId = null; continue }
            i++
            val textLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().isNotEmpty()) {
                textLines.add(lines[i])
                i++
            }
            pendingId = null
            val caption = textLines.joinToString("\n").trim()
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
            format = SubtitleFormat.VTT,
            events = events.sortedBy { it.startMs },
            sourceName = sourceName,
        )
    }

    /**
     * Accepts `MM:SS.mmm` and `HH:MM:SS.mmm` (dot or comma decimals).
     */
    internal fun parseTimestamp(value: String): Long? {
        val clean = value.trim().replace(',', '.')
        val parts = clean.split(":")
        if (parts.size != 2 && parts.size != 3) return null
        return try {
            if (parts.size == 3) {
                val h = parts[0].toLong()
                val m = parts[1].toLong()
                val sec = parts[2].toDouble()
                (h * 3_600_000L + m * 60_000L + (sec * 1000).toLong())
            } else {
                val m = parts[0].toLong()
                val sec = parts[1].toDouble()
                (m * 60_000L + (sec * 1000).toLong())
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
