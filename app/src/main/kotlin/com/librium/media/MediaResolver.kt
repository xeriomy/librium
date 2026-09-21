package com.librium.media

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.librium.core.LibLog
import com.librium.subtitle.SubtitleFormat
import com.librium.subtitle.formatForFileName
import java.io.File
import java.io.FileInputStream

/**
 * Storage Access Framework helpers. No broad filesystem access:
 * callers use `ActivityResultContracts.OpenDocument` and pass the
 * returned content URIs through here.
 */
data class MediaItem(
    val uri: String,
    val displayName: String,
)

/**
 * Result of resolving a picked video: the URI string for the backend plus
 * a display name for the UI. Produced by [MediaResolver.pickVideo].
 */
data class VideoPick(
    val uriString: String,
    val displayName: String,
)

object MediaResolver {

    val VIDEO_MIME_FILTER: Array<String> = arrayOf("video/*")

    /**
     * MIME types offered to Storage Access Framework providers for subtitle
     * picking. Mappings are inconsistent across providers (notably for
     * .ass/.ssa, often served as octet-stream), so this list is intentionally
     * broad — but it never admits image, video, audio, PDF, or archive
     * types, and the final extension check still happens in-app.
     */
    val SUBTITLE_MIME_TYPES: Array<String> = arrayOf(
        "text/plain",
        "text/vtt",
        "application/x-subrip",
        "text/x-ssa",
        "application/x-ass",
        "application/octet-stream",
    )

    val SUPPORTED_SUBTITLE_EXTENSIONS: Set<String> = setOf("srt", "ass", "ssa", "vtt")

    /** Common container extensions, for remote URLs and file-manager paths. */
    val SUPPORTED_VIDEO_EXTENSIONS: Set<String> = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov",
        "ts", "m2ts", "flv", "wmv", "mpg", "mpeg", "3gp", "ogv",
    )

    fun isSupportedVideo(nameOrUri: String): Boolean {
        val ext = nameOrUri.substringAfterLast('.', "").substringBefore('?').lowercase()
        return ext in SUPPORTED_VIDEO_EXTENSIONS
    }

    /**
     * Resolves a picked video URI: takes a persistable read grant (best
     * effort) and reads the display name. Makes binder/provider calls, so
     * callers must invoke this off the main thread. Never throws.
     */
    suspend fun pickVideo(resolver: ContentResolver, uri: Uri): VideoPick {
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { e ->
            LibLog.w(LibLog.SAF) { "persist permission failed: ${e.message}" }
        }
        return VideoPick(uri.toString(), displayName(resolver, uri))
    }

    fun displayName(resolver: ContentResolver, uri: Uri): String {        var name: String? = null
        val cursor: Cursor? = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        }.onFailure { e ->
            LibLog.w(LibLog.MEDIA) { "displayName query failed: ${e.message}" }
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

    /**
     * Opens a read descriptor for a `content://` URI, or null (e.g. a lost
     * URI grant). Blocking binder call — callers must invoke off the main
     * thread. The caller owns the returned descriptor and must close it.
     */
    fun openContentFd(resolver: ContentResolver, uri: Uri): ParcelFileDescriptor? =
        runCatching { resolver.openFileDescriptor(uri, "r") }
            .onFailure { e ->
                LibLog.w(LibLog.MEDIA) { "open content fd failed: ${e.message}" }
            }
            .getOrNull()

    /**
     * Best-effort real filesystem path for an open descriptor, mirroring
     * the proven mpv-android approach: file-backed providers resolve via
     * /proc/self/fd and then play as plain files (fast, fully seekable).
     * Returns null for non-file-backed providers. Cheap local IO only.
     */
    fun realPathOf(fd: Int): String? {
        var probe: FileInputStream? = null
        return try {
            val path = File("/proc/self/fd/$fd").canonicalPath
            if (path.startsWith("/proc")) return null
            if (!File(path).canRead()) return null
            // Prove readability before handing the path to native code.
            probe = FileInputStream(path)
            if (probe.read() < 0) return null
            LibLog.d(LibLog.MEDIA) { "content fd resolved to file" }
            path
        } catch (_: Exception) {
            null
        } finally {
            runCatching { probe?.close() }
        }
    }

    fun isSupportedSubtitle(nameOrUri: String): Boolean {
        val ext = nameOrUri.substringAfterLast('.', "").substringBefore('?').lowercase()
        return ext in SUPPORTED_SUBTITLE_EXTENSIONS
    }

    fun subtitleFormat(nameOrUri: String): SubtitleFormat = formatForFileName(nameOrUri)
}
