package com.librium.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository safe-result policy: blank input, parser failures, and
 * zero-cue documents yield null so callers keep their current valid
 * subtitle instead of adopting a broken one.
 */
class SubtitleRepositoryTest {

    private val repository = SubtitleRepository()

    @Test
    fun blank_input_is_rejected() {
        assertNull(repository.parseDocument("empty.srt", ""))
        assertNull(repository.parseDocument("empty.srt", "   \n  \n"))
    }

    @Test
    fun binary_garbage_yields_no_document() {
        // JPEG magic + random bytes decoded as text: no usable cues.
        val garbage = "\uFFFD\uFFFD\u0000\u0001JFIF\u0000garbage-bytes-#@!"
        assertNull(repository.parseDocument("photo.jpg", garbage))
        assertNull(repository.parseDocument("photo", garbage))
    }

    @Test
    fun malformed_but_cueless_subtitle_yields_no_document() {
        assertNull(repository.parseDocument("broken.srt", "no timestamps here\njust text\n"))
    }

    @Test
    fun valid_documents_still_parse() {
        val doc = repository.parseDocument(
            "ok.srt",
            "1\n00:00:01,000 --> 00:00:02,000\nHi\n",
        )
        assertTrue(doc != null)
        assertEquals(1, doc!!.events.size)
    }

    @Test
    fun misnamed_but_valid_content_is_recovered() {
        val doc = repository.parseDocument(
            "subtitles.txt",
            "1\n00:00:01,000 --> 00:00:02,000\nHi\n",
        )
        assertTrue(doc != null)
        assertEquals(SubtitleFormat.SRT, doc!!.format)
    }

    @Test
    fun empty_cue_file_yields_no_document() {
        // A structurally valid file with zero cues is a safe failure,
        // never an empty replacement document.
        assertNull(repository.parseDocument("empty.srt", "\n\n\n"))
    }
}
