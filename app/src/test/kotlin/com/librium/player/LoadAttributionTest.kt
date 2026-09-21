package com.librium.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * END_FILE attribution behind the silent-bounce fix: a pending load whose
 * file died must surface an error (keeping the player visible), while an
 * end event for a superseded file during replace must stay quiet.
 */
class LoadAttributionTest {

    @Test
    fun idle_end_file_only_clears_loading() {
        assertEquals(
            EndFileAction.CLEAR_LOADING,
            endFileAction(loadPending = false, currentPath = "/v/a.mp4", pendingUri = "/v/a.mp4"),
        )
        assertEquals(
            EndFileAction.CLEAR_LOADING,
            endFileAction(loadPending = false, currentPath = null, pendingUri = null),
        )
    }

    @Test
    fun pending_load_matching_path_reports_failure() {
        assertEquals(
            EndFileAction.REPORT_FAILURE,
            endFileAction(
                loadPending = true,
                currentPath = "/proc/self/fd/video.mp4",
                pendingUri = "/proc/self/fd/video.mp4",
            ),
        )
        assertEquals(
            EndFileAction.REPORT_FAILURE,
            endFileAction(loadPending = true, currentPath = "fd://42", pendingUri = "fd://42"),
        )
    }

    @Test
    fun pending_load_unknown_path_reports_failure() {
        // Cannot attribute: a stuck spinner is worse, and a later
        // FILE_LOADED clears the error if the load actually succeeds.
        assertEquals(
            EndFileAction.REPORT_FAILURE,
            endFileAction(loadPending = true, currentPath = null, pendingUri = "fd://42"),
        )
    }

    @Test
    fun pending_load_superseded_file_stays_quiet() {
        // Open B while A tears down: END_FILE names A's path, not B's.
        assertEquals(
            EndFileAction.IGNORE,
            endFileAction(
                loadPending = true,
                currentPath = "/v/a.mp4",
                pendingUri = "/v/b.mp4",
            ),
        )
    }

    @Test
    fun pause_clears_loading_only_for_live_media() {
        assertTrue(clearLoadingOnPause(hasMedia = true))
        assertFalse(clearLoadingOnPause(hasMedia = false))
    }
}
