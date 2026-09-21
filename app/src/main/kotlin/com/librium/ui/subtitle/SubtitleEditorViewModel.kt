package com.librium.ui.subtitle

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.librium.subtitle.AnalyzerOptions
import com.librium.subtitle.DefaultSubtitleAnalyzer
import com.librium.subtitle.DefaultSubtitleSynchronizer
import com.librium.subtitle.SubtitleAnalysisResult
import com.librium.subtitle.SubtitleAnalyzer
import com.librium.subtitle.SubtitleDocument
import com.librium.subtitle.SubtitleFormat
import com.librium.subtitle.SubtitleRepository
import com.librium.subtitle.SubtitleSynchronizer
import com.librium.subtitle.TimeMapping
import com.librium.subtitle.TimingTransform
import com.librium.subtitle.addEvent
import com.librium.subtitle.mapRange
import com.librium.subtitle.TimeRange
import com.librium.subtitle.mergeEvents
import com.librium.subtitle.removeEvent
import com.librium.subtitle.shiftAll
import com.librium.subtitle.splitEvent
import com.librium.subtitle.withEventText
import com.librium.subtitle.withEventTiming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val notice: String? = null,
    val error: String? = null,
)

class SubtitleEditorViewModel(
    private val repository: SubtitleRepository = SubtitleRepository(),
    private val analyzer: SubtitleAnalyzer =
        DefaultSubtitleAnalyzer(AnalyzerOptions()),
    private val synchronizer: SubtitleSynchronizer = DefaultSubtitleSynchronizer(),
) : ViewModel() {

    private val _state = MutableStateFlow(SubtitleEditorState())
    val state: StateFlow<SubtitleEditorState> = _state.asStateFlow()

    // --- loading ---

    fun loadExternal(resolver: ContentResolver, uri: Uri, displayName: String?) {
        _state.update {
            it.copy(isLoading = true, error = null, notice = null, analysis = null)
        }
        viewModelScope.launch {
            val doc = repository.loadDocument(resolver, uri, displayName)
            if (doc == null) {
                _state.update {
                    it.copy(isLoading = false, error = "Unsupported subtitle file.")
                }
            } else {
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
        _state.update { SubtitleEditorState() }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null, error = null) }
    }

    // --- analysis ---

    fun runAnalysis(videoDurationMs: Long? = null) {
        val doc = _state.value.document ?: return
        _state.update { it.copy(isAnalyzing = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                analyzer.analyze(doc, videoDurationMs)
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

    private inline fun updateDocument(
        notice: String?,
        transform: (SubtitleDocument) -> SubtitleDocument,
    ) {
        val doc = _state.value.document ?: return
        _state.update {
            it.copy(
                document = transform(doc),
                analysis = null,
                previewLines = emptyList(),
                notice = notice,
            )
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

    // --- export ---

    fun exportTo(resolver: ContentResolver, uri: Uri) {
        val doc = _state.value.document ?: return
        val format = _state.value.exportFormat
        _state.update { it.copy(isExporting = true, error = null, notice = null) }
        viewModelScope.launch {
            runCatching {
                repository.saveDocument(resolver, uri, doc, format)
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
