package com.librium.media

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoIntentTest {

    @Test
    fun content_uri_is_accepted() {
        val req = VideoIntent.parse(
            Intent.ACTION_VIEW,
            "content://com.example.provider/videos/42",
            "video/mp4",
        )
        assertTrue(req != null)
        assertEquals("content://com.example.provider/videos/42", req!!.uri)
    }

    @Test
    fun file_uri_is_accepted() {
        val req = VideoIntent.parse(Intent.ACTION_VIEW, "file:///sdcard/Movies/clip.mkv", null)
        assertTrue(req != null)
        assertEquals("clip.mkv", req!!.displayName)
    }

    @Test
    fun non_view_actions_rejected() {
        assertNull(
            VideoIntent.parse(Intent.ACTION_SEND, "content://x/1", "video/mp4"),
        )
        assertNull(VideoIntent.parse(Intent.ACTION_MAIN, "content://x/1", "video/mp4"))
        assertNull(VideoIntent.parse(null, "content://x/1", "video/mp4"))
    }

    @Test
    fun missing_or_unsupported_uris_rejected() {
        assertNull(VideoIntent.parse(Intent.ACTION_VIEW, null, "video/mp4"))
        assertNull(VideoIntent.parse(Intent.ACTION_VIEW, "", "video/mp4"))
        assertNull(VideoIntent.parse(Intent.ACTION_VIEW, "ftp://x/movie.mp4", null))
        assertNull(VideoIntent.parse(Intent.ACTION_VIEW, "customscheme://play/1", null))
    }

    @Test
    fun http_requires_video_type_or_extension() {
        assertTrue(
            VideoIntent.parse(Intent.ACTION_VIEW, "https://x.com/v/movie.mp4", null) != null,
        )
        assertTrue(
            VideoIntent.parse(Intent.ACTION_VIEW, "https://x.com/watch?page=1", "video/mp4") != null,
        )
        assertNull(VideoIntent.parse(Intent.ACTION_VIEW, "https://x.com/watch?page=1", null))
        assertNull(
            VideoIntent.parse(Intent.ACTION_VIEW, "https://x.com/page.html", "text/html"),
        )
    }

    @Test
    fun resolve_null_intent_is_null() {
        assertNull(VideoIntent.resolve(null))
    }

    @Test
    fun supported_video_extensions() {
        assertTrue(MediaResolver.isSupportedVideo("movie.mp4"))
        assertTrue(MediaResolver.isSupportedVideo("https://x/f.MKV"))
        assertTrue(MediaResolver.isSupportedVideo("clip.m2ts?token=abc"))
        assertTrue(!MediaResolver.isSupportedVideo("notes.txt"))
        assertTrue(!MediaResolver.isSupportedVideo("noextension"))
    }

    @Test
    fun registered_schemes_are_video_only() {
        // The manifest must never register a catch-all; the accepted set is
        // exactly content/file/http/https for video payloads.
        assertEquals(setOf("content", "file", "http", "https"), VideoIntent.VIDEO_SCHEMES)
    }
}
