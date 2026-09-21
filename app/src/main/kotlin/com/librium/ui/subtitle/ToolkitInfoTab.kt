package com.librium.ui.subtitle

import androidx.compose.runtime.Composable

@Composable
internal fun ToolkitInfoTab(
    editorState: SubtitleEditorState,
    playerDelayMs: Long,
) {
    val doc = editorState.document ?: return
    SectionTitle("Information")
    InfoRow("File", editorState.sourceName ?: doc.sourceName ?: "-")
    InfoRow("Format", doc.format.name)
    doc.metadata["Title"]?.let { InfoRow("Title", it) }
    InfoRow("Cues", "${doc.events.size}")
    InfoRow(
        "Span",
        "${SubtitleEditorViewModel.formatMs(doc.events.minOfOrNull { it.startMs } ?: 0L)} --> " +
            SubtitleEditorViewModel.formatMs(doc.maxEndMs()),
    )
    InfoRow("Styles", "${doc.styles.size}")
    InfoRow("Player delay", "$playerDelayMs ms")

    val analysis = editorState.analysis
    if (analysis != null) {
        val stats = analysis.statistics
        InfoRow("Avg reading speed", "%.1f chars/sec".format(stats.avgCps))
        InfoRow(
            "Max reading speed",
            "%.1f chars/sec (cue #${stats.maxCpsEventId})".format(stats.maxCps),
        )
        InfoRow("Warnings", "${analysis.warnings.size}")
        InfoRow("Errors", "${analysis.errors.size}")
    } else {
        InfoRow("Analysis", "Not run yet — see the Analyze tab.")
    }
}
