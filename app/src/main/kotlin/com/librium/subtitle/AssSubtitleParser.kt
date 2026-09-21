package com.librium.subtitle

private const val EVENTS_SECTION = "events"
private const val COMMENTS_KEY = "events-comments"

/**
 * ASS/SSA parser that preserves information instead of destroying it.
 *
 * Kept verbatim or structured for later export and editing:
 * - `[Script Info]` entries land in [SubtitleDocument.metadata];
 * - everything before `[Events]` is also kept as [SubtitleDocument.rawHeader];
 * - `Style:` lines become [SubtitleStyle] with all fields plus the raw line;
 * - `Dialogue:` keeps style reference, raw text with override tags, and
 *   extra columns (layer, actor, margins, effect) on the event;
 * - `Comment:` lines and unknown sections (`[Fonts]`, `[Graphics]`, ...)
 *   are preserved in [SubtitleDocument.extraSections].
 *
 * Display text on the event is the override-stripped form; rendering
 * itself stays with libmpv/libass.
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
        val normalized = stripBom(text).replace("\r\n", "\n").replace("\r", "\n")
        val allLines = normalized.lines()

        var section = ""
        var styleFormat: List<String>? = null
        var eventFormat: List<String>? = null
        val metadata = linkedMapOf<String, String>()
        val styles = linkedMapOf<String, SubtitleStyle>()
        val events = mutableListOf<SubtitleEvent>()
        val extraSections = linkedMapOf<String, MutableList<String>>()
        val headerLines = mutableListOf<String>()
        var seenEvents = false
        var id = 0

        for (raw in allLines) {
            val line = raw.trimEnd()
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                section = trimmed.removePrefix("[").substringBefore("]").trim().lowercase()
                if (!seenEvents) headerLines.add(line)
                continue
            }
            if (trimmed.isEmpty() || trimmed.startsWith(";")) {
                if (!seenEvents) headerLines.add(line)
                else if (section == EVENTS_SECTION) {
                    // Keep blank/comment-ish lines inside Events for fidelity.
                    extraSections.getOrPut(COMMENTS_KEY) { mutableListOf() }.add(line)
                }
                continue
            }
            if (section == EVENTS_SECTION) seenEvents = true
            if (!seenEvents) headerLines.add(line)

            when (section) {
                "script info" -> {
                    val key = trimmed.substringBefore(":").trim()
                    val value = trimmed.substringAfter(":", "").trim()
                    if (key.isNotEmpty()) metadata[key] = value
                }
                "v4+ styles", "v4 styles" -> {
                    when {
                        trimmed.startsWith("Format:", ignoreCase = true) -> {
                            styleFormat = splitFormat(trimmed)
                        }
                        trimmed.startsWith("Style:", ignoreCase = true) -> {
                            parseStyle(trimmed, styleFormat)?.let { styles[it.name] = it }
                        }
                        else -> extraSections.getOrPut(section) { mutableListOf() }.add(line)
                    }
                }
                EVENTS_SECTION -> {
                    when {
                        trimmed.startsWith("Format:", ignoreCase = true) -> {
                            eventFormat = splitFormat(trimmed)
                        }
                        trimmed.startsWith("Dialogue:", ignoreCase = true) -> {
                            parseDialogue(trimmed, eventFormat, id)?.let {
                                events.add(it)
                                id++
                            }
                        }
                        trimmed.startsWith("Comment:", ignoreCase = true) -> {
                            extraSections.getOrPut(COMMENTS_KEY) { mutableListOf() }.add(line)
                        }
                        else -> extraSections.getOrPut(section) { mutableListOf() }.add(line)
                    }
                }
                "" -> Unit
                else -> extraSections.getOrPut(section) { mutableListOf() }.add(line)
            }
        }

        return SubtitleDocument(
            format = format,
            events = events,
            styles = styles,
            metadata = metadata,
            sourceName = sourceName,
            rawHeader = headerLines.joinToString("\n").trim().ifBlank { null },
            extraSections = extraSections,
            eventFormat = eventFormat?.joinToString(", "),
            styleFormat = styleFormat?.joinToString(", "),
        )
    }

    private fun splitFormat(line: String): List<String> =
        line.substringAfter(":").split(",").map { it.trim().lowercase() }

    private fun parseStyle(line: String, format: List<String>?): SubtitleStyle? {
        val payload = line.substringAfter(":")
        val values = payload.split(",").map { it.trim() }
        val fields = linkedMapOf<String, String>()
        if (format != null && values.size >= format.size) {
            for (k in format.indices) fields[format[k]] = values[k]
        } else {
            // Classic V4+/V4 field order fallback.
            val order = if (values.size > 20) V4PLUS_ORDER else V4_ORDER
            val shared = minOf(values.size, order.size)
            for (k in 0 until shared) fields[order[k]] = values[k]
        }
        val name = fields["name"] ?: return null
        return SubtitleStyle(
            name = name,
            fontName = fields["fontname"],
            fontSize = fields["fontsize"]?.toDoubleOrNull(),
            primaryColor = fields["primarycolour"],
            fields = fields,
            rawLine = line.trim(),
        )
    }

    private fun parseDialogue(line: String, format: List<String>?, id: Int): SubtitleEvent? {
        val payload = line.substringAfter(":")
        val columns = format ?: ASS_EVENT_ORDER
        // Split into at most columns.size parts so commas inside Text survive.
        val parts = payload.split(",", limit = columns.size)
        if (parts.size < columns.size) return null
        fun col(name: String): String {
            val idx = columns.indexOf(name)
            return if (idx >= 0) parts[idx].trim() else ""
        }
        val start = parseTimestamp(col("start")) ?: return null
        val end = parseTimestamp(col("end")) ?: return null
        val rawText = parts.last()
        val plain = stripAssText(rawText).trim()
        val extras = linkedMapOf<String, String>()
        for (name in columns) {
            if (name != "start" && name != "end" && name != "style" && name != "text") {
                extras[name] = col(name)
            }
        }
        return SubtitleEvent(
            id = id,
            startMs = start,
            endMs = end,
            text = plain,
            styleName = col("style").ifBlank { null },
            rawText = rawText,
            extra = extras,
        )
    }

    /** `H:MM:SS.cc` — hours may exceed two digits; fraction is centiseconds. */
    internal fun parseTimestamp(value: String): Long? {
        val parts = value.trim().split(":")
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

    internal fun stripAssText(value: String): String {
        var out = ASS_OVERRIDE_BLOCK.replace(value, "")
        out = out.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
        return out
    }

    companion object {
        private val ASS_EVENT_ORDER = listOf(
            "marked", "start", "end", "style", "name",
            "marginl", "marginr", "marginv", "effect", "text",
        )
        private val V4PLUS_ORDER = listOf(
            "name", "fontname", "fontsize", "primarycolour", "secondarycolour",
            "outlinecolour", "backcolour", "bold", "italic", "underline", "strikeout",
            "scalex", "scaley", "spacing", "angle", "borderstyle", "outline",
            "shadow", "alignment", "marginl", "marginr", "marginv", "encoding",
        )
        private val V4_ORDER = listOf(
            "name", "fontname", "fontsize", "primarycolour", "secondarycolour",
            "tertiarycolour", "backcolour", "bold", "italic", "borderstyle",
            "outline", "shadow", "alignment", "marginl", "marginr", "marginv",
            "alphalevel", "encoding",
        )
    }
}
