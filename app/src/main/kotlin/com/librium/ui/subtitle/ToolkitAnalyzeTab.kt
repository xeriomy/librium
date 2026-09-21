package com.librium.ui.subtitle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.librium.subtitle.IssueSeverity
import com.librium.subtitle.IssueType
import com.librium.subtitle.SubtitleDocument
import com.librium.subtitle.SubtitleIssue

@Composable
internal fun ToolkitAnalyzeTab(
    editor: SubtitleEditorViewModel,
    editorState: SubtitleEditorState,
    videoDurationMs: Long,
) {
    val doc = editorState.document ?: return
    SectionTitle("Analysis")
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = {
                editor.runAnalysis(videoDurationMs.takeIf { it > 0L })
            },
            enabled = !editorState.isAnalyzing,
        ) {
            Text("Run analysis")
        }
        if (editorState.isAnalyzing) CircularProgressIndicator()
    }
    val analysis = editorState.analysis ?: return
    InfoRow("Errors", "${analysis.errors.size}")
    InfoRow("Warnings", "${analysis.warnings.size}")
    InfoRow("Notes", "${analysis.info.size}")
    // Grouped by issue type in analyzer order (timing first), so each
    // problem family can be scanned as a whole.
    val groups = remember(analysis) {
        IssueType.entries.mapNotNull { type ->
            analysis.all.filter { it.type == type }.takeIf { it.isNotEmpty() }
                ?.let { type to it }
        }
    }
    groups.forEach { (type, issues) ->
        Text(
            "${typeLabel(type)} (${issues.size})",
            style = MaterialTheme.typography.titleSmall,
        )
        issues.forEach { issue ->
            IssueRow(issue = issue, document = doc)
        }
    }
}

@Composable
private fun IssueRow(
    issue: SubtitleIssue,
    document: SubtitleDocument,
) {
    val color = when (issue.severity) {
        IssueSeverity.ERROR -> MaterialTheme.colorScheme.error
        IssueSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
        IssueSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val cueText = issue.eventId?.let { document.eventById(it)?.text }
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            "Cue #${issue.eventId ?: "?"}: ${issue.message}",
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
        if (!cueText.isNullOrBlank()) {
            Text(
                "“$cueText”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        issue.suggestedFix?.let {
            Text(
                "Fix: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun typeLabel(type: IssueType): String = when (type) {
    IssueType.OVERLAP -> "Timing overlap"
    IssueType.ZERO_DURATION -> "Zero duration"
    IssueType.NEGATIVE_DURATION -> "Negative duration"
    IssueType.TOO_SHORT -> "Very short cue"
    IssueType.TOO_LONG -> "Very long cue"
    IssueType.SHORT_GAP -> "Short gap"
    IssueType.LONG_GAP -> "Long silence"
    IssueType.BEYOND_VIDEO_END -> "Beyond video duration"
    IssueType.EMPTY_TEXT -> "Empty cue"
    IssueType.DUPLICATE_TEXT -> "Duplicate cue"
    IssueType.LONG_LINE -> "Long line"
    IssueType.TOO_MANY_LINES -> "Too many lines"
    IssueType.HIGH_CPS -> "High reading speed (CPS)"
    IssueType.HIGH_WPM -> "High reading speed (WPM)"
}
