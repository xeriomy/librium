package com.librium.ui.subtitle

import com.librium.subtitle.SubtitleDocument
import com.librium.subtitle.SubtitleEvent
import com.librium.subtitle.SubtitleFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleEditorViewModelTest {

    private fun doc(): SubtitleDocument = SubtitleDocument(
        format = SubtitleFormat.SRT,
        events = listOf(
            SubtitleEvent(id = 0, startMs = 1000, endMs = 3000, text = "First"),
            SubtitleEvent(id = 1, startMs = 4000, endMs = 6000, text = "Second"),
        ),
    )

    private fun vm() = SubtitleEditorViewModel(
        workScope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun awaitState(
        vm: SubtitleEditorViewModel,
        predicate: (SubtitleEditorState) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 5000L
        while (!predicate(vm.state.value)) {
            if (System.currentTimeMillis() > deadline) {
                error("timed out waiting for editor state: ${vm.state.value}")
            }
            Thread.sleep(10)
        }
    }

    @Test
    fun loadDocument_seeds_state() {
        val vm = vm()
        vm.loadDocument(doc(), "test.srt")
        val state = vm.state.value
        assertEquals(2, state.document!!.events.size)
        assertEquals("test.srt", state.sourceName)
        assertEquals(SubtitleFormat.SRT, state.exportFormat)
        assertFalse(state.canUndo)
        assertFalse(state.canRedo)
    }

    @Test
    fun edit_text_and_timing_through_vm() {
        val vm = vm()
        vm.loadDocument(doc(), "test.srt")
        vm.editText(0, "Edited")
        awaitState(vm) { it.document?.eventById(0)?.text == "Edited" }
        vm.editTiming(0, 1500, 3500)
        awaitState(vm) { it.document?.eventById(0)?.startMs == 1500L }
        assertEquals(3500L, vm.state.value.document?.eventById(0)?.endMs)
        assertTrue(vm.state.value.canUndo)
    }

    @Test
    fun add_split_merge_delete_through_vm() {
        val vm = vm()
        vm.loadDocument(doc(), "test.srt")
        vm.addCue(7000, 8000, "Third")
        awaitState(vm) { (it.document?.events?.size ?: 0) == 3 }
        vm.splitCue(0, 2000)
        awaitState(vm) { (it.document?.events?.size ?: 0) == 4 }
        val ids = vm.state.value.document!!.events.map { it.id }
        vm.mergeCues(ids[0], ids[1])
        awaitState(vm) { (it.document?.events?.size ?: 0) == 3 }
        vm.deleteCue(ids[2])
        awaitState(vm) { (it.document?.events?.size ?: 0) == 2 }
    }

    @Test
    fun undo_redo_and_redo_clear() {
        val vm = vm()
        vm.loadDocument(doc(), "test.srt")
        vm.editText(0, "Changed")
        awaitState(vm) { it.document?.eventById(0)?.text == "Changed" }
        assertTrue(vm.state.value.canUndo)

        vm.undo()
        awaitState(vm) { it.document?.eventById(0)?.text == "First" }
        assertTrue(vm.state.value.canRedo)

        vm.redo()
        awaitState(vm) { it.document?.eventById(0)?.text == "Changed" }

        // A new edit after undo clears the redo stack.
        vm.undo()
        awaitState(vm) { it.document?.eventById(0)?.text == "First" }
        vm.editText(0, "Branched")
        awaitState(vm) { it.document?.eventById(0)?.text == "Branched" }
        assertTrue(vm.state.value.canUndo)
        assertFalse(vm.state.value.canRedo)
    }

    @Test
    fun sync_operations_rewrite_timings() {
        val vm = vm()
        vm.loadDocument(doc(), "test.srt")
        vm.applyOffsetToDocument(500L)
        awaitState(vm) { it.document?.eventById(0)?.startMs == 1500L }
        vm.applyFpsToDocument(25.0, 50.0)
        awaitState(vm) { it.document?.eventById(0)?.startMs == 750L }
        vm.applyScaleToDocument(2.0, 0L)
        awaitState(vm) { it.document?.eventById(0)?.startMs == 1500L }
    }

    @Test
    fun export_format_filename_and_suggestion() {
        val vm = vm()
        vm.loadDocument(doc(), "movie.srt")
        assertEquals("movie.srt", vm.suggestedExportName())
        vm.setExportFormat(SubtitleFormat.ASS)
        assertEquals("movie.ass", vm.suggestedExportName())
        vm.setExportFileName("custom.vtt")
        assertEquals("custom.vtt", vm.suggestedExportName())
        vm.setExportFileName("  ")
        assertEquals("movie.ass", vm.suggestedExportName())
    }

    @Test
    fun preview_lists_mapped_ranges() {
        val vm = vm()
        vm.loadDocument(doc(), "test.srt")
        vm.previewTransform(com.librium.subtitle.TimingTransform.Offset(1000L))
        val lines = vm.state.value.previewLines
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("#0"))
    }

    @Test
    fun analysis_reports_overlap_and_stats() {
        val vm = vm()
        vm.loadDocument(
            SubtitleDocument(
                format = SubtitleFormat.SRT,
                events = listOf(
                    SubtitleEvent(id = 0, startMs = 1000, endMs = 3000, text = "A"),
                    SubtitleEvent(id = 1, startMs = 2500, endMs = 4000, text = "B"),
                ),
            ),
            "overlap.srt",
        )
        vm.runAnalysis(videoDurationMs = 60_000L)
        awaitState(vm) { it.analysis != null }
        val result = vm.state.value.analysis!!
        assertTrue(result.errors.isNotEmpty())
        assertEquals(2, result.statistics.eventCount)
    }

    @Test
    fun empty_document_analyzes_cleanly() {
        val vm = vm()
        vm.loadDocument(
            SubtitleDocument(format = SubtitleFormat.SRT, events = emptyList()),
            "empty.srt",
        )
        vm.runAnalysis()
        awaitState(vm) { it.analysis != null }
        assertEquals(0, vm.state.value.analysis!!.statistics.eventCount)
    }

    @Test
    fun large_document_edit_and_analyze() {
        val events = (0 until 2000).map { i ->
            SubtitleEvent(id = i, startMs = i * 3000L, endMs = i * 3000L + 2000L, text = "Cue $i")
        }
        val vm = vm()
        vm.loadDocument(SubtitleDocument(format = SubtitleFormat.SRT, events = events), "big.srt")
        vm.applyOffsetToDocument(100L)
        awaitState(vm) { it.document?.eventById(1999)?.startMs == 1999L * 3000L + 100L }
        vm.runAnalysis()
        awaitState(vm) { it.analysis != null }
        assertEquals(2000, vm.state.value.analysis!!.statistics.eventCount)
    }
}
