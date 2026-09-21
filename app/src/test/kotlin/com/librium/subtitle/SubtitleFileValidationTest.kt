package com.librium.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFileValidationTest {

    private fun accept(displayName: String?, uri: String): SubtitleFormat {
        val decision = SubtitleFileValidation.validate(displayName, uri)
        assertTrue(
            "expected Accept for $displayName / $uri, got $decision",
            decision is SubtitleFileDecision.Accept,
        )
        return (decision as SubtitleFileDecision.Accept).format
    }

    private fun reject(displayName: String?, uri: String): SubtitleRejectReason {
        val decision = SubtitleFileValidation.validate(displayName, uri)
        assertTrue(
            "expected Reject for $displayName / $uri, got $decision",
            decision is SubtitleFileDecision.Reject,
        )
        return (decision as SubtitleFileDecision.Reject).reason
    }

    @Test
    fun valid_lowercase_extensions_accepted() {
        assertEquals(SubtitleFormat.SRT, accept("movie.srt", "content://p/1"))
        assertEquals(SubtitleFormat.ASS, accept("movie.ass", "content://p/2"))
        assertEquals(SubtitleFormat.SSA, accept("movie.ssa", "content://p/3"))
        assertEquals(SubtitleFormat.VTT, accept("movie.vtt", "content://p/4"))
    }

    @Test
    fun valid_uppercase_extensions_accepted() {
        assertEquals(SubtitleFormat.SRT, accept("MOVIE.SRT", "content://p/1"))
        assertEquals(SubtitleFormat.ASS, accept("Movie.Ass", "content://p/2"))
        assertEquals(SubtitleFormat.SSA, accept("Movie.SSA", "content://p/3"))
        assertEquals(SubtitleFormat.VTT, accept("Movie.VTT", "content://p/4"))
    }

    @Test
    fun images_rejected() {
        assertEquals(
            SubtitleRejectReason.UNSUPPORTED_EXTENSION,
            reject("photo.jpg", "content://p/1"),
        )
        assertEquals(
            SubtitleRejectReason.UNSUPPORTED_EXTENSION,
            reject("screenshot.PNG", "content://p/2"),
        )
        assertEquals(
            SubtitleRejectReason.UNSUPPORTED_EXTENSION,
            reject("pic.webp", "content://p/3"),
        )
    }

    @Test
    fun video_audio_documents_archives_rejected() {
        assertEquals(
            SubtitleRejectReason.UNSUPPORTED_EXTENSION,
            reject("video.mp4", "content://p/1"),
        )
        assertEquals(
            SubtitleRejectReason.UNSUPPORTED_EXTENSION,
            reject("audio.mp3", "content://p/2"),
        )
        assertEquals(
            SubtitleRejectReason.UNSUPPORTED_EXTENSION,
            reject("document.pdf", "content://p/3"),
        )
        assertEquals(
            SubtitleRejectReason.UNSUPPORTED_EXTENSION,
            reject("archive.zip", "content://p/4"),
        )
        assertEquals(
            SubtitleRejectReason.UNSUPPORTED_EXTENSION,
            reject("notes.txt", "content://p/5"),
        )
        assertEquals(
            SubtitleRejectReason.UNSUPPORTED_EXTENSION,
            reject("unknown.xyz", "content://p/6"),
        )
    }

    @Test
    fun missing_or_extensionless_names_rejected() {
        assertEquals(
            SubtitleRejectReason.MISSING_NAME,
            reject(null, "content://com.example.provider/42"),
        )
        assertEquals(
            SubtitleRejectReason.MISSING_NAME,
            reject("", "content://com.example.provider/42"),
        )
        assertEquals(
            SubtitleRejectReason.MISSING_NAME,
            reject("README", "content://com.example.provider/43"),
        )
        assertEquals(
            SubtitleRejectReason.MISSING_NAME,
            reject("trailingdot.", "content://com.example.provider/44"),
        )
    }

    @Test
    fun uri_extension_used_when_name_has_none() {
        assertEquals(
            SubtitleFormat.SRT,
            accept(null, "content://com.example.provider/subs/movie.srt"),
        )
        assertEquals(
            SubtitleRejectReason.UNSUPPORTED_EXTENSION,
            reject(null, "content://com.example.provider/images/photo.jpg"),
        )
    }

    @Test
    fun tricky_filenames_handled() {
        // Spaces, multiple dots, query strings, mixed case.
        assertEquals(SubtitleFormat.SRT, accept("My Movie Part 1.srt", "content://p/1"))
        assertEquals(SubtitleFormat.ASS, accept("show.S01E02.final.ASS", "content://p/2"))
        assertEquals(
            SubtitleRejectReason.UNSUPPORTED_EXTENSION,
            reject("subs.srt.bak", "content://p/3"),
        )
    }

    @Test
    fun unicode_filenames_accepted_by_extension() {
        assertEquals(SubtitleFormat.SRT, accept("فيلم مترجم.srt", "content://p/1"))
        assertEquals(SubtitleFormat.ASS, accept("فيلم.ASS", "content://p/2"))
        assertEquals(SubtitleFormat.VTT, accept("üñîcode tëst.Vtt", "content://p/3"))
        assertEquals(
            SubtitleFormat.SSA,
            accept("日本語の字幕 episode 3.ssa", "content://p/4"),
        )
        assertEquals(
            SubtitleRejectReason.UNSUPPORTED_EXTENSION,
            reject("صورة.jpg", "content://p/5"),
        )
    }

    @Test
    fun user_messages_are_human_readable() {
        assertTrue(UNSUPPORTED_SUBTITLE_MESSAGE.contains("SRT"))
        assertTrue(UNSUPPORTED_SUBTITLE_MESSAGE.contains("ASS"))
        assertTrue(SUBTITLE_LOAD_FAILED_MESSAGE.contains("not changed"))
    }
}
