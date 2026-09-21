package com.librium.ui.subtitle

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.librium.core.LibLog
import com.librium.subtitle.AnalyzerOptions
import com.librium.subtitle.DefaultSubtitleAnalyzer
import com.librium.subtitle.DefaultSubtitleSynchronizer
import com.librium.subtitle.SUBTITLE_LOAD_FAILED_MESSAGE
import com.librium.subtitle.SubtitleAnalysisResult
import com.librium.subtitle.SubtitleAnalyzer
import com.librium.subtitle.SubtitleDocument
import com.librium.subtitle.SubtitleFileDecision
import com.librium.subtitle.SubtitleFileValidation
import com.librium.subtitle.SubtitleFormat
import com.librium.subtitle.SubtitleRepository
import com.librium.subtitle.SubtitleSynchronizer
import com.librium.subtitle.TimeMapping
import com.librium.subtitle.TimingTransform
import com.librium.subtitle.UNSUPPORTED_SUBTITLE_MESSAGE
import com.librium.subtitle.UndoHistory
import com.librium.subtitle.addEvent
import com.librium.subtitle.mapRange
import com.librium.subtitle.TimeRange
import com.librium.subtitle.mergeEvents
import com.librium.subtitle.removeEvent
import com.librium.subtitle.shiftAll
import com.librium.subtitle.splitEvent
import com.librium.subtitle.withEventText
import com.librium.subtitle.withEventTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Phase 2 editor foundation: state and operations for subtitle analysis,
 * synchronization, editing, and export — without a polished editor UI.
 *
 * Heavy work (file I/O, analysis) runs off the main thread. Document
 * mutations go through the immutable ops in `SubtitleEditorOps`, so every
 * state holds a standalone snapshot and the UI layer stays thin.
 */
data class SubtitleEditorState(
    val sourceUri: String? = null,
    val sourceName: String? = null,
    val document: SubtitleDocument? = null,
    val isLoading: Boolean = false,
    val isAnalyzing: Boolean = false,
    val isExporting: Boolean = false,
    val analysis: SubtitleAnalysisResult? = null,
    val analyzedVideoDurationMs: Long? = null,
    val pendingOffsetMs: Long = 0L,
    val sourceFpsText: String = "25",
    val targetFpsText: String = "23.976",
    val driftStartSubText: String = "",
    val driftStartVideoText: String = "",
    val driftEndSubText: String = "",
    val driftEndVideoText: String = "",
    val previewLines: List<String> = emptyList(),
    val exportFormat: SubtitleFormat = SubtitleFormat.SRT,
    /** Null means "use the suggested name". Set once the user edits it. */
    val exportFileName: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

class SubtitleEditorViewModel(
    private val repository: SubtitleRepository = SubtitleRepository(),
    private val analyzer: SubtitleAnalyzer =
        DefaultSubtitleAnalyzer(AnalyzerOptions()),
    private val synchronizer: SubtitleSynchronizer = DefaultSubtitleSynchronizer(),
    workScope: CoroutineScope? = null,
) : ViewModel() {

    // Injectable for unit tests (which have no Main dispatcher);
    // production defaults to viewModelScope with identical behavior.
    private val scope: CoroutineScope = workScope ?: viewModelScope
    private val history = UndoHistory<SubtitleDocument>()
    private val editMutex = Mutex()

    private val _state = MutableStateFlow(SubtitleEditorState())
    val state: StateFlow<SubtitleEditorState> = _state.asStateFlow()

    // --- loading ---

    fun loadExternal(resolver: ContentResolver, uri: Uri, displayName: String?) {
        // Defense in depth: the picker validates first, but this entry point
        // enforces it again so no caller can smuggle an invalid file into
        // the document state.
        when (val decision = SubtitleFileValidation.validate(displayName, uri.toString())) {
            is SubtitleFileDecision.Reject -> {
                LibLog.w(LibLog.SUB) {
                    "rejected ${decision.reason}: ${decision.detail}"
                }
                _state.update {
                    it.copy(isLoading = false, error = UNSUPPORTED_SUBTITLE_MESSAGE)
                }
                return
            }
            is SubtitleFileDecision.Accept -> Unit
        }
        _state.update {
            it.copy(isLoading = true, error = null, notice = null, analysis = null)
        }
        scope.launch {
            val doc = LibLog.timed(LibLog.SUB, "subtitle load+parse") {
                repository.loadDocument(resolver, uri, displayName)
            }
            if (doc == null) {
                LibLog.w(LibLog.SUB) { "could not load subtitle: $displayName" }
                _state.update {
                    it.copy(isLoading = false, error = SUBTITLE_LOAD_FAILED_MESSAGE)
                }
            } else {
                LibLog.i(LibLog.SUB) {
                    "loaded ${doc.format} with ${doc.events.size} cues"
                }
                _state.update {
                    it.copy(
                        isLoading = false,
                        sourceUri = uri.toString(),
                        sourceName = displayName,
                        document = doc,
                        exportFormat = doc.format.takeIf { it != SubtitleFormat.UNKNOWN }
                            ?: SubtitleFormat.SRT,
                        notice = "Loaded ${doc.events.size} cues.",
                    )
                }
            }
        }
    }

    fun clear() {
        history.clear()
        _state.update { SubtitleEditorState() }
    }

    /**
     * Loads an already-parsed document (used by tests and future entry
     * points such as subtitle extras on video intents). Replaces history,
     * like any fresh load.
     */
    fun loadDocument(document: SubtitleDocument, sourceName: String?) {
        history.clear()
        _state.update {
            it.copy(
                isLoading = false,
                sourceUri = null,
                sourceName = sourceName,
                document = document,
                analysis = null,
                previewLines = emptyList(),
                exportFormat = document.format.takeIf { f -> f != SubtitleFormat.UNKNOWN }
                    ?: SubtitleFormat.SRT,
                exportFileName = null,
                canUndo = false,
                canRedo = false,
                notice = "Loaded ${document.events.size} cues.",
                error = null,
            )
        }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null, error = null) }
    }

    // --- analysis ---

    fun runAnalysis(videoDurationMs: Long? = null) {
        val doc = _state.value.document ?: return
        _state.update { it.copy(isAnalyzing = true, error = null) }
        scope.launch {
            val result = LibLog.timed(LibLog.SUB, "analysis of ${doc.events.size} cues") {
                withContext(Dispatchers.Default) {
                    analyzer.analyze(doc, videoDurationMs)
                }
            }
            LibLog.i(LibLog.SUB) {
                "analysis: ${result.errors.size} errors, " +
                    "${result.warnings.size} warnings over ${doc.events.size} cues"
            }
            _state.update {
                it.copy(
                    isAnalyzing = false,
                    analysis = result,
                    analyzedVideoDurationMs = videoDurationMs,
                )
            }
        }
    }

    // --- document transforms (immutable; analysis invalidated) ---

    fun applyOffsetToDocument(offsetMs: Long) {
        updateDocument("Shifted by $offsetMs ms.") { it.shiftAll(offsetMs) }
    }

    fun applyFpsToDocument(sourceFps: Double, targetFps: Double) {
        updateDocument("Converted $sourceFps fps to $targetFps fps.") {
            synchronizer.convertFps(it, sourceFps, targetFps)
        }
    }

    fun applyDriftToDocument(start: TimeMapping, end: TimeMapping) {
        updateDocument("Drift correction applied.") {
            synchronizer.correctDrift(it, start, end)
        }
    }

    fun applyScaleToDocument(factor: Double, pivotMs: Long) {
        updateDocument("Rescaled by $factor around $pivotMs ms.") {
            synchronizer.scale(it, factor, pivotMs)
        }
    }

    /** Restores the previous document snapshot, if any. */
    fun undo() {
        scope.launch {
            editMutex.withLock {
                val current = _state.value.document ?: return@withLock
                val previous = history.undo(current) ?: return@withLock
                _state.update {
                    it.copy(
                        document = previous,
                        analysis = null,
                        previewLines = emptyList(),
                        notice = null,
                        canUndo = history.canUndo,
                        canRedo = history.canRedo,
                    )
                }
            }
        }
    }

    /** Re-applies an undone change, if any. */
    fun redo() {
        scope.launch {
            editMutex.withLock {
                val current = _state.value.document ?: return@withLock
                val next = history.redo(current) ?: return@withLock
                _state.update {
                    it.copy(
                        document = next,
                        analysis = null,
                        previewLines = emptyList(),
                        notice = null,
                        canUndo = history.canUndo,
                        canRedo = history.canRedo,
                    )
                }
            }
        }
    }

    private fun updateDocument(
        notice: String?,
        transform: (SubtitleDocument) -> SubtitleDocument,
    ) {
        scope.launch {
            // Serialized so rapid edits (and undo/redo) apply in order;
            // each op pushes the pre-change snapshot for undo.
            editMutex.withLock {
                val doc = _state.value.document ?: return@withLock
                val next = LibLog.timed(LibLog.SUB, "document transform") {
                    withContext(Dispatchers.Default) { transform(doc) }
                }
                if (next !== doc) history.push(doc)
                _state.update {
                    it.copy(
                        document = next,
                        analysis = null,
                        previewLines = emptyList(),
                        notice = notice,
                        canUndo = history.canUndo,
                        canRedo = history.canRedo,
                    )
                }
            }
        }
    }

    fun previewTransform(transform: TimingTransform, maxEvents: Int = 5) {
        val doc = _state.value.document ?: return
        val lines = doc.events.take(maxEvents).map { event ->
            val mapped = transform.mapRange(TimeRange(event.startMs, event.endMs))
            "#${event.id} ${formatMs(event.startMs)} --> ${formatMs(event.endMs)}  =>  " +
                "${formatMs(mapped.startMs)} --> ${formatMs(mapped.endMs)}"
        }
        _state.update { it.copy(previewLines = lines) }
    }

    // --- editing primitives (foundation for the future editor UI) ---

    fun editText(id: Int, text: String) {
        updateDocument(null) { it.withEventText(id, text) }
    }

    fun editTiming(id: Int, startMs: Long, endMs: Long) {
        updateDocument(null) { it.withEventTiming(id, startMs, endMs) }
    }

    fun addCue(startMs: Long, endMs: Long, text: String) {
        updateDocument("Cue added.") { it.addEvent(startMs, endMs, text) }
    }

    fun deleteCue(id: Int) {
        updateDocument("Cue #$id deleted.") { it.removeEvent(id) }
    }

    fun splitCue(id: Int, atMs: Long) {
        updateDocument("Cue #$id split.") { it.splitEvent(id, atMs) }
    }

    fun mergeCues(firstId: Int, secondId: Int) {
        updateDocument("Cues merged.") { it.mergeEvents(firstId, secondId) }
    }

    // --- sync draft fields ---

    fun setPendingOffset(offsetMs: Long) {
        _state.update { it.copy(pendingOffsetMs = offsetMs, previewLines = emptyList()) }
    }

    fun setFpsTexts(source: String, target: String) {
        _state.update { it.copy(sourceFpsText = source, targetFpsText = target) }
    }

    fun setDriftTexts(startSub: String, startVideo: String, endSub: String, endVideo: String) {
        _state.update {
            it.copy(
                driftStartSubText = startSub,
                driftStartVideoText = startVideo,
                driftEndSubText = endSub,
                driftEndVideoText = endVideo,
            )
        }
    }

    fun setExportFormat(format: SubtitleFormat) {
        _state.update { it.copy(exportFormat = format) }
    }

    fun setExportFileName(name: String?) {
        _state.update { it.copy(exportFileName = name?.ifBlank { null }) }
    }

    // --- export ---

    fun exportTo(resolver: ContentResolver, uri: Uri) {
        val doc = _state.value.document ?: return
        val format = _state.value.exportFormat
        _state.update { it.copy(isExporting = true, error = null, notice = null) }
        scope.launch {
            runCatching {
                LibLog.timed(LibLog.SUB, "subtitle export") {
                    repository.saveDocument(resolver, uri, doc, format)
                }
            }.onSuccess {
                _state.update { it.copy(isExporting = false, notice = "Exported as $format.") }
            }.onFailure { e ->
                _state.update {
                    it.copy(isExporting = false, error = "Export failed: ${e.message}")
                }
            }
        }
    }

    fun suggestedExportName(): String {
        _state.value.exportFileName?.let { return it }
        val base = _state.value.sourceName?.substringBeforeLast('.')?.ifBlank { null }
            ?: "subtitles"
        val ext = when (_state.value.exportFormat) {
            SubtitleFormat.SRT -> "srt"
            SubtitleFormat.VTT -> "vtt"
            SubtitleFormat.ASS -> "ass"
            SubtitleFormat.SSA -> "ssa"
            SubtitleFormat.UNKNOWN -> "srt"
        }
        return "$base.$ext"
    }

    companion object {
        internal fun formatMs(ms: Long): String {
            val t = ms.coerceAtLeast(0L)
            val m = t / 60_000L
            val s = (t % 60_000L) / 1_000L
            val milli = t % 1_000L
            return "%02d:%02d.%03d".format(m, s, milli)
        }
    }
}
