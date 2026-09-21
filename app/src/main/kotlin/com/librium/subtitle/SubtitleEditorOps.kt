package com.librium.subtitle

/**
 * Safe, immutable subtitle editing primitives. Each operation returns a new
 * [SubtitleDocument]; the receiver is never mutated, so callers can keep
 * undo history or previews by holding previous instances.
 *
 * Event ids are stable identities: deleting never reindexes, and added
 * events receive `maxId + 1`.
 */

/** Replaces the text of one event. */
fun SubtitleDocument.withEventText(id: Int, newText: String): SubtitleDocument =
    mapEvent(id) { it.copy(text = newText, rawText = null) }

/** Replaces the timing of one event. */
fun SubtitleDocument.withEventTiming(id: Int, startMs: Long, endMs: Long): SubtitleDocument =
    mapEvent(id) { it.copy(startMs = startMs, endMs = endMs) }

/** Replaces the style reference of one event. */
fun SubtitleDocument.withEventStyle(id: Int, styleName: String?): SubtitleDocument =
    mapEvent(id) { it.copy(styleName = styleName) }

private inline fun SubtitleDocument.mapEvent(
    id: Int,
    transform: (SubtitleEvent) -> SubtitleEvent,
): SubtitleDocument {
    var changed = false
    val updated = events.map { event ->
        if (event.id == id) {
            changed = true
            transform(event)
        } else {
            event
        }
    }
    return if (changed) copy(events = updated) else this
}

/**
 * Adds an event, inserted in timeline order (stable for equal starts).
 * The new id is `maxId + 1`, or 0 for an empty document.
 */
fun SubtitleDocument.addEvent(
    startMs: Long,
    endMs: Long,
    text: String,
    styleName: String? = null,
): SubtitleDocument {
    val nextId = (events.maxOfOrNull { it.id } ?: -1) + 1
    val event = SubtitleEvent(
        id = nextId,
        startMs = startMs,
        endMs = endMs,
        text = text,
        styleName = styleName,
    )
    return copy(events = (events + event).sortedWith(compareBy({ it.startMs }, { it.id })))
}

/** Deletes one event; other ids are left untouched. */
fun SubtitleDocument.removeEvent(id: Int): SubtitleDocument {
    if (events.none { it.id == id }) return this
    return copy(events = events.filterNot { it.id == id })
}

/**
 * Splits an event at [atMs] into two adjacent events.
 *
 * Text distribution: multi-line text is divided by lines (first half /
 * second half, keeping at least one line each); single-line text is split
 * proportionally to the time fraction, snapped to a nearby space.
 * Returns the original document when the split point is outside the event.
 */
fun SubtitleDocument.splitEvent(id: Int, atMs: Long): SubtitleDocument {
    val event = eventById(id) ?: return this
    if (atMs <= event.startMs || atMs >= event.endMs) return this
    val nextId = (events.maxOfOrNull { it.id } ?: -1) + 1
    val (firstText, secondText) = splitText(event.text, atMs, event)
    val first = event.copy(endMs = atMs, text = firstText, rawText = null)
    val second = event.copy(
        id = nextId,
        startMs = atMs,
        text = secondText,
        rawText = null,
        identifier = null,
    )
    val updated = events.flatMap { if (it.id == id) listOf(first, second) else listOf(it) }
    return copy(events = updated)
}

private fun splitText(text: String, atMs: Long, event: SubtitleEvent): Pair<String, String> {
    val lines = text.split("\n")
    if (lines.size >= 2) {
        val half = lines.size / 2
        return lines.take(half).joinToString("\n") to lines.drop(half).joinToString("\n")
    }
    if (text.length < 2) return text to ""
    val fraction = (atMs - event.startMs).toDouble() / (event.endMs - event.startMs)
    val cut = (text.length * fraction).toInt().coerceIn(1, text.length - 1)
    val snapped = snapToSpace(text, cut)
    return text.substring(0, snapped).trimEnd() to text.substring(snapped).trimStart()
}

private fun snapToSpace(text: String, cut: Int): Int {
    if (cut <= 0 || cut >= text.length) return cut.coerceIn(1, text.length - 1)
    if (text[cut - 1].isWhitespace() || text[cut].isWhitespace()) return cut
    var left = cut
    var right = cut
    while (left > 1 && right < text.length - 1) {
        left--
        right++
        if (text[left].isWhitespace()) return left + 1
        if (text[right].isWhitespace()) return right + 1
    }
    return cut
}

/**
 * Merges two events into one spanning both, with texts joined by a newline.
 * Keeps the first event's id and style; returns the original document when
 * either id is missing.
 */
fun SubtitleDocument.mergeEvents(firstId: Int, secondId: Int): SubtitleDocument {
    val first = eventById(firstId) ?: return this
    val second = eventById(secondId) ?: return this
    if (firstId == secondId) return this
    val text = listOf(first.text.trim(), second.text.trim())
        .filter { it.isNotEmpty() }.joinToString("\n")
    val merged = first.copy(
        startMs = minOf(first.startMs, second.startMs),
        endMs = maxOf(first.endMs, second.endMs),
        text = text,
        rawText = null,
        extra = first.extra + second.extra.filterKeys { it !in first.extra },
    )
    val updated = events.mapNotNull { event ->
        when (event.id) {
            firstId -> merged
            secondId -> null
            else -> event
        }
    }
    return copy(events = updated)
}
