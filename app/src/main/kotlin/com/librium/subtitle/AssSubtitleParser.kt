package com.librium.subtitle

/**
 * Minimal ASS/SSA foundation parser for Phase 1.
 *
 * NOT a renderer: Phase 1 display goes through libmpv (libass). This only
 * extracts `Dialogue:` events with plain text so the future analyzer /
 * synchronizer has something to work with. Styles, drawings, and override
 * tags are stripped, not interpreted.
 */
class AssSubtitleParser : SubtitleParser {
    override val supportedFormats: Set<SubtitleFormat> =
        setOf(SubtitleFormat.ASS, SubtitleFormat.SSA)

    override fun parse(sourceName: String?, text: String): SubtitleDocument {
        val format = if (sourceName?.lowercase()?.endsWith(".ssa") == true) {
            SubtitleFormat.SSA
        } else {
            SubtitleFormat.ASS
        }
        val normalized = text.replace("\r\n", "\n")
        var inEvents = false
        var formatColumns: List<String>? = null
        val events = mutableListOf<SubtitleEvent>()
        var index = 0
        for (raw in normalized.lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                formatColumns = null
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                formatColumns = line.substringAfter(":").split(",").map { it.trim().lowercase() }
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val payload = line.substringAfter(":")
            val columns = formatColumns
            val start: Long?
            val end: Long?
            val textValue: String
            val style: String?
            if (columns != null) {
                // Split into at most columns.size parts so commas inside Text survive.
                val parts = payload.split(",", limit = columns.size)
                if (parts.size < columns.size) continue
                fun col(name: String): String {
                    val idx = columns.indexOf(name)
                    return if (idx >= 0) parts[idx].trim() else ""
                }
                start = parseTimestamp(col("start"))
                end = parseTimestamp(col("end"))
                style = col("style").ifBlank { null }
                textValue = parts.last()
            } else {
                // Fallback for files without a Format line: classic field order.
                val parts = payload.split(",", limit = 10)
                if (parts.size < 10) continue
                start = parseTimestamp(parts[1].trim())
                end = parseTimestamp(parts[2].trim())
                style = parts[3].trim().ifBlank { null }
                textValue = parts[9]
            }
            if (start == null || end == null || end <= start) continue
            val plain = stripOverrides(textValue).trim()
            if (plain.isEmpty()) continue
            events.add(
                SubtitleEvent(
                    index = index++,
                    startMs = start,
                    endMs = end,
                    text = plain,
                    styleName = style,
                ),
            )
        }
        return SubtitleDocument(
            format = format,
            events = events.sortedBy { it.startMs },
            sourceName = sourceName,
        )
    }

    /** `H:MM:SS.cc` — hours may exceed 2 digits; centiseconds are fractional. */
    internal fun parseTimestamp(value: String): Long? {
        val clean = value.trim()
        val parts = clean.split(":")
        if (parts.size != 3) return null
        return try {
            val h = parts[0].toLong()
            val m = parts[1].toLong()
            val sec = parts[2].toDouble()
            (h * 3_600_000L + m * 60_000L + (sec * 1000).toLong())
        } catch (_: NumberFormatException) {
            null
        }
    }

    internal fun stripOverrides(value: String): String {
        // Remove {…} override blocks, convert hard breaks, drop drawing remnants.
        var out = OVERRIDE_BLOCK.replace(value, "")
        out = out.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
        return out.trim()
    }

    companion object {
        private val OVERRIDE_BLOCK = Regex("""\{[^}]*\}""")
    }
}
