package com.librium.media

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.librium.subtitle.SubtitleFormat
import com.librium.subtitle.formatForFileName

/**
 * Storage Access Framework helpers. No broad filesystem access:
 * callers use `ActivityResultContracts.OpenDocument` and pass the
 * returned content URIs through here.
 */
data class MediaItem(
    val uri: String,
    val displayName: String,
)

object MediaResolver {

    val VIDEO_MIME_FILTER: Array<String> = arrayOf("video/*")

    /**
     * Subtitle pickers use `*/*` because `.ass`/`.ssa` MIME mappings vary
     * by device; [isSupportedSubtitle] validates the extension instead.
     */
    val SUBTITLE_MIME_FILTER: Array<String> = arrayOf("*/*")

    val SUPPORTED_SUBTITLE_EXTENSIONS: Set<String> = setOf("srt", "ass", "ssa", "vtt")

    fun displayName(resolver: ContentResolver, uri: Uri): String {
        var name: String? = null
        val cursor: Cursor? = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        }.getOrNull()
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = it.getString(idx)
            }
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        }
        return name ?: "media"
    }

    fun isSupportedSubtitle(nameOrUri: String): Boolean {
        val ext = nameOrUri.substringAfterLast('.', "").substringBefore('?').lowercase()
        return ext in SUPPORTED_SUBTITLE_EXTENSIONS
    }

    fun subtitleFormat(nameOrUri: String): SubtitleFormat = formatForFileName(nameOrUri)
}
