package com.librium.ui.subtitle

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.librium.subtitle.SubtitleFormat

@Composable
internal fun ToolkitExportTab(
    editor: SubtitleEditorViewModel,
    editorState: SubtitleEditorState,
) {
    val context = LocalContext.current
    var fileName by remember(editorState.sourceName, editorState.exportFormat) {
        mutableStateOf(editorState.exportFileName ?: editor.suggestedExportName())
    }

    SectionTitle("Convert and save")
    Text(
        "Pick the output format to convert between SRT, VTT, ASS, and SSA, " +
            "name the file, then save it through the system file picker. " +
            "The original file is never overwritten automatically.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExportChip("SRT", SubtitleFormat.SRT, editorState.exportFormat) {
            editor.setExportFormat(it)
        }
        ExportChip("VTT", SubtitleFormat.VTT, editorState.exportFormat) {
            editor.setExportFormat(it)
        }
        ExportChip("ASS", SubtitleFormat.ASS, editorState.exportFormat) {
            editor.setExportFormat(it)
        }
        ExportChip("SSA", SubtitleFormat.SSA, editorState.exportFormat) {
            editor.setExportFormat(it)
        }
    }
    OutlinedTextField(
        value = fileName,
        onValueChange = {
            fileName = it
            editor.setExportFileName(it)
        },
        label = { Text("File name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mimeFor(editorState.exportFormat)),
    ) { uri ->
        if (uri != null) editor.exportTo(context.contentResolver, uri)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { exportLauncher.launch(fileName.ifBlank { editor.suggestedExportName() }) },
            enabled = !editorState.isExporting,
        ) {
            Text("Save file")
        }
        if (editorState.isExporting) CircularProgressIndicator()
    }
    Text(
        "ASS export keeps header, styles, and override tags.",
        style = MaterialTheme.typography.bodySmall,
    )
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

private fun mimeFor(format: SubtitleFormat): String = when (format) {
    SubtitleFormat.VTT -> "text/vtt"
    else -> "text/plain"
}
