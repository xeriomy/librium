package com.librium.ui.subtitle

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.librium.subtitle.IssueSeverity
import com.librium.subtitle.SubtitleFormat
import com.librium.subtitle.SubtitleIssue
import com.librium.subtitle.TimeMapping
import com.librium.subtitle.TimingTransform

/**
 * Subtitle information, synchronization, analysis, and export panel.
 * Functional, not polished: the future editor UI will build on the same
 * [SubtitleEditorViewModel] state. libmpv keeps rendering during playback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleInfoSheet(
    editor: SubtitleEditorViewModel,
    playerDelayMs: Long,
    videoDurationMs: Long,
    onApplyPlayerDelay: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by editor.state.collectAsStateWithLifecycle()
    var formError by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mimeFor(state.exportFormat)),
    ) { uri ->
        if (uri != null) editor.exportTo(context.contentResolver, uri)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Subtitles", style = MaterialTheme.typography.titleLarge)

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            state.notice?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }

            val doc = state.document
            if (doc == null) {
                Text(
                    "No subtitle file loaded. Use the subtitle button on the player " +
                        "to open an SRT, ASS, SSA, or VTT file.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.isLoading) {
                    CircularProgressIndicator()
                }
            } else {
                SectionTitle("Information")
                InfoRow("File", state.sourceName ?: doc.sourceName ?: "-")
                InfoRow("Format", doc.format.name)
                InfoRow("Events", "${doc.events.size}")
                InfoRow("Span", "${SubtitleEditorViewModel.formatMs(doc.events.minOfOrNull { it.startMs } ?: 0L)} --> " +
                    SubtitleEditorViewModel.formatMs(doc.maxEndMs()))
                InfoRow("Styles", "${doc.styles.size}")
                InfoRow("Player delay", "$playerDelayMs ms")

                val stats = state.analysis?.statistics
                if (stats != null) {
                    InfoRow("Avg reading speed", "%.1f chars/sec".format(stats.avgCps))
                    InfoRow("Max reading speed", "%.1f chars/sec (cue #${stats.maxCpsEventId})".format(stats.maxCps))
                }

                HorizontalDivider()
                SectionTitle("Synchronization")
                Text(
                    "Global offset shifts every cue. Apply to the player for a live " +
                        "preview, or bake it into the document before export.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editor.setPendingOffset(-500L) }) {
                        Text("-500")
                    }
                    OutlinedButton(onClick = { editor.setPendingOffset(0L) }) {
                        Text("0")
                    }
                    OutlinedButton(onClick = { editor.setPendingOffset(500L) }) {
                        Text("+500")
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Offset: ${state.pendingOffsetMs} ms", modifier = Modifier.weight(1f))
                    Button(onClick = { onApplyPlayerDelay(state.pendingOffsetMs) }) {
                        Text("Player")
                    }
                    Button(onClick = {
                        editor.applyOffsetToDocument(state.pendingOffsetMs)
                    }) {
                        Text("Document")
                    }
                }

                var srcFps by remember(state.sourceFpsText) { mutableStateOf(state.sourceFpsText) }
                var tgtFps by remember(state.targetFpsText) { mutableStateOf(state.targetFpsText) }
                Text("FPS conversion (exact rescaling, no added offset)", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = srcFps,
                        onValueChange = { srcFps = it; editor.setFpsTexts(srcFps, tgtFps) },
                        label = { Text("Source fps") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = tgtFps,
                        onValueChange = { tgtFps = it; editor.setFpsTexts(srcFps, tgtFps) },
                        label = { Text("Target fps") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val s = srcFps.toDoubleOrNull()
                        val t = tgtFps.toDoubleOrNull()
                        if (s == null || t == null || s <= 0 || t <= 0) {
                            formError = "Enter positive frame rates."
                        } else {
                            formError = null
                            editor.previewTransform(TimingTransform.Fps(s, t))
                        }
                    }) {
                        Text("Preview")
                    }
                    Button(onClick = {
                        val s = srcFps.toDoubleOrNull()
                        val t = tgtFps.toDoubleOrNull()
                        if (s == null || t == null || s <= 0 || t <= 0) {
                            formError = "Enter positive frame rates."
                        } else {
                            formError = null
                            editor.applyFpsToDocument(s, t)
                        }
                    }) {
                        Text("Apply to document")
                    }
                }

                Text("Drift correction (two anchor points)", style = MaterialTheme.typography.bodySmall)
                var dss by remember(state.driftStartSubText) { mutableStateOf(state.driftStartSubText) }
                var dsv by remember(state.driftStartVideoText) { mutableStateOf(state.driftStartVideoText) }
                var des by remember(state.driftEndSubText) { mutableStateOf(state.driftEndSubText) }
                var dev by remember(state.driftEndVideoText) { mutableStateOf(state.driftEndVideoText) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dss,
                        onValueChange = { dss = it; editor.setDriftTexts(dss, dsv, des, dev) },
                        label = { Text("Sub start ms") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = dsv,
                        onValueChange = { dsv = it; editor.setDriftTexts(dss, dsv, des, dev) },
                        label = { Text("Video start ms") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = des,
                        onValueChange = { des = it; editor.setDriftTexts(dss, dsv, des, dev) },
                        label = { Text("Sub end ms") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = dev,
                        onValueChange = { dev = it; editor.setDriftTexts(dss, dsv, des, dev) },
                        label = { Text("Video end ms") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val mapping = parseDrift(dss, dsv, des, dev)
                        if (mapping == null) {
                            formError = "Enter all four drift points in ms."
                        } else {
                            formError = null
                            editor.previewTransform(TimingTransform.Drift(mapping.first, mapping.second))
                        }
                    }) {
                        Text("Preview")
                    }
                    Button(onClick = {
                        val mapping = parseDrift(dss, dsv, des, dev)
                        if (mapping == null) {
                            formError = "Enter all four drift points in ms."
                        } else {
                            formError = null
                            editor.applyDriftToDocument(mapping.first, mapping.second)
                        }
                    }) {
                        Text("Apply to document")
                    }
                }
                formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.previewLines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }

                HorizontalDivider()
                SectionTitle("Analysis")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            editor.runAnalysis(videoDurationMs.takeIf { it > 0L })
                        },
                        enabled = !state.isAnalyzing,
                    ) {
                        Text("Run analysis")
                    }
                    if (state.isAnalyzing) CircularProgressIndicator()
                }
                val analysis = state.analysis
                if (analysis != null) {
                    InfoRow("Errors", "${analysis.errors.size}")
                    InfoRow("Warnings", "${analysis.warnings.size}")
                    InfoRow("Notes", "${analysis.info.size}")
                    IssueList("Errors", analysis.errors)
                    IssueList("Warnings", analysis.warnings)
                    IssueList("Notes", analysis.info)
                }

                HorizontalDivider()
                SectionTitle("Cues (first 20)")
                doc.events.take(20).forEach { event ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            "#${event.id} ${SubtitleEditorViewModel.formatMs(event.startMs)} --> " +
                                SubtitleEditorViewModel.formatMs(event.endMs),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            event.text.ifBlank { "(empty)" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                HorizontalDivider()
                SectionTitle("Export")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportChip("SRT", SubtitleFormat.SRT, state.exportFormat) {
                        editor.setExportFormat(it)
                    }
                    ExportChip("VTT", SubtitleFormat.VTT, state.exportFormat) {
                        editor.setExportFormat(it)
                    }
                    ExportChip("ASS", SubtitleFormat.ASS, state.exportFormat) {
                        editor.setExportFormat(it)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { exportLauncher.launch(editor.suggestedExportName()) },
                        enabled = !state.isExporting,
                    ) {
                        Text("Save file")
                    }
                    if (state.isExporting) CircularProgressIndicator()
                }
                Text(
                    "ASS export keeps header, styles, and override tags.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f),
        )
    }
}

@Composable
private fun IssueList(title: String, issues: List<SubtitleIssue>) {
    if (issues.isEmpty()) return
    Text("$title (${issues.size})", style = MaterialTheme.typography.titleSmall)
    issues.forEach { issue ->
        val color = when (issue.severity) {
            IssueSeverity.ERROR -> MaterialTheme.colorScheme.error
            IssueSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
            IssueSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Column(modifier = Modifier.padding(vertical = 2.dp)) {
            Text(
                "[${issue.type}] ${issue.message}",
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
            issue.suggestedFix?.let {
                Text(
                    "Fix: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExportChip(
    label: String,
    format: SubtitleFormat,
    selected: SubtitleFormat,
    onSelect: (SubtitleFormat) -> Unit,
) {
    FilterChip(
        selected = selected == format,
        onClick = { onSelect(format) },
        label = { Text(label) },
    )
}

private fun parseDrift(
    startSub: String,
    startVideo: String,
    endSub: String,
    endVideo: String,
): Pair<TimeMapping, TimeMapping>? {
    val ss = startSub.toLongOrNull() ?: return null
    val sv = startVideo.toLongOrNull() ?: return null
    val es = endSub.toLongOrNull() ?: return null
    val ev = endVideo.toLongOrNull() ?: return null
    return TimeMapping(ss, sv) to TimeMapping(es, ev)
}

private fun mimeFor(format: SubtitleFormat): String = when (format) {
    SubtitleFormat.VTT -> "text/vtt"
    else -> "text/plain"
}
