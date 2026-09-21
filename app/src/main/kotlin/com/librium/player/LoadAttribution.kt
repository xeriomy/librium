package com.librium.player

/**
 * Pure END_FILE / pause attribution policy behind the "bounce to home"
 * fix. mpv reports failures only as END_FILE with no loaded file, which
 * used to clear `isLoading` silently and drop the visibility gate. These
 * decisions make load failure visible instead, while a superseded file
 * (open B while A still tears down) keeps the spinner without a false
 * error — its own FILE_LOADED clears everything shortly after.
 */
internal enum class EndFileAction {
    /** END_FILE belongs to a superseded load; keep the spinner, say nothing. */
    IGNORE,
    /** Normal end (natural EOS, background stop): clear loading only. */
    CLEAR_LOADING,
    /** Our pending file died before/without loading: report failure. */
    REPORT_FAILURE,
}

/**
 * @param loadPending true when a loadfile has no FILE_LOADED yet.
 * @param currentPath mpv `path` at END_FILE time, or null if unreadable.
 * @param pendingUri the mpv URI string sent for the pending load.
 */
internal fun endFileAction(
    loadPending: Boolean,
    currentPath: String?,
    pendingUri: String?,
): EndFileAction {
    if (!loadPending) return EndFileAction.CLEAR_LOADING
    // Unknown path: fail visible (a stuck spinner is worse, and a later
    // FILE_LOADED clears the error if the load actually succeeds).
    if (currentPath != null && currentPath != pendingUri) return EndFileAction.IGNORE
    return EndFileAction.REPORT_FAILURE
}

/**
 * A `pause` property event must only clear a loading spinner for live
 * media. Clearing it pre-FILE_LOADED drops the visibility gate and
 * bounces to Home on slow loads.
 */
internal fun clearLoadingOnPause(hasMedia: Boolean): Boolean = hasMedia
