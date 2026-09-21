package com.librium.media

import android.content.Intent
import android.net.Uri

/**
 * External "Open with / Play with" entry point. Librium registers
 * `ACTION_VIEW` for `video/*` only (never a catch-all filter); this object
 * turns a received intent into a [VideoRequest] for the existing
 * `PlayerController.openVideo` path.
 *
 * The `parse` overload is pure and unit-tested; [resolve] is a thin
 * Android wrapper around it.
 */
data class VideoRequest(
    /** URI string passed straight to the backend (`loadfile`). */
    val uri: String,
    val displayName: String?,
    val mimeType: String?,
)

object VideoIntent {

    /** URI schemes accepted from external launchers. */
    val VIDEO_SCHEMES: Set<String> = setOf("content", "file", "http", "https")

    /**
     * Returns a request when [action] is `ACTION_VIEW` and [uriString]
     * points at a playable video, else null. `content`/`file` URIs are
     * accepted as-is (the intent filter already matched `video/*`);
     * remote URLs additionally require a video MIME type or extension.
     */
    fun parse(action: String?, uriString: String?, mimeType: String?): VideoRequest? {
        if (action != Intent.ACTION_VIEW) return null
        if (uriString.isNullOrBlank()) return null
        val scheme = uriString.substringBefore(":", "").lowercase()
        if (scheme !in VIDEO_SCHEMES) return null
        if ((scheme == "http" || scheme == "https") && !isRemoteVideo(uriString, mimeType)) {
            return null
        }
        val name = uriString.substringAfterLast('/').substringBefore('?')
            .takeIf { it.isNotBlank() && it.contains('.') }
        return VideoRequest(uri = uriString, displayName = name, mimeType = mimeType)
    }

    /** Android entry point; delegates to [parse]. */
    fun resolve(intent: Intent?): VideoRequest? {
        if (intent == null) return null
        return parse(intent.action, intent.dataString, intent.type)
    }

    fun isRemoteVideo(uriString: String, mimeType: String?): Boolean {
        if (mimeType?.lowercase()?.startsWith("video/") == true) return true
        return MediaResolver.isSupportedVideo(uriString)
    }
}

/** Last path segment of a `content`/`file` URI, for logging only. */
fun Uri.logTag(): String = lastPathSegment?.takeLast(24) ?: toString().takeLast(24)
