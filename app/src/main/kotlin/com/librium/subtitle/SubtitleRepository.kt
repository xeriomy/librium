package com.librium.subtitle

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Storage Access Framework bridge for subtitles. All blocking I/O runs on
 * [Dispatchers.IO]; parsing, analysis, and editing stay pure and
 * unit-testable. Depends only on Android content APIs — never on UI or
 * playback classes.
 */
class SubtitleRepository {

    /** Reads a subtitle file and parses it, or returns null when unsupported. */
    suspend fun loadDocument(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String?,
    ): SubtitleDocument? = withContext(Dispatchers.IO) {
        val text = runCatching {
            resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.readText()
        }.getOrNull() ?: return@withContext null
        parseDocument(displayName ?: uri.toString(), text)
    }

    /**
     * Parses text, falling back across formats for misnamed files.
     *
     * Safe-result policy: blank input, parser exceptions, and documents
     * with zero usable cues all yield null, so callers keep their current
     * valid subtitle instead of adopting a broken one.
     */
    fun parseDocument(displayName: String?, text: String): SubtitleDocument? {
        if (text.isBlank()) return null
        return runCatching {
            val name = displayName ?: "subtitle"
            val format = formatForFileName(name)
            val direct = parserFor(format)
            if (direct != null) {
                return@runCatching direct.parse(name, text).takeIf { it.events.isNotEmpty() }
            }
            // Unknown extension: try every parser, keep the richest result.
            val candidates = listOf(
                SrtSubtitleParser().parse(name, text),
                VttSubtitleParser().parse(name, text),
                AssSubtitleParser().parse(name, text),
            )
            candidates.maxByOrNull { it.events.size }?.takeIf { it.events.isNotEmpty() }
        }.getOrNull()
    }

    /** Serializes [document] and writes it to [uri]. */
    suspend fun saveDocument(
        resolver: ContentResolver,
        uri: Uri,
        document: SubtitleDocument,
        format: SubtitleFormat,
    ) = withContext(Dispatchers.IO) {
        val text = document.exportAs(format)
        resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { out ->
            out.write(text)
        } ?: error("Could not open output stream")
    }
}
