package com.librium.ui.subtitle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.librium.subtitle.SubtitleDocument
import com.librium.subtitle.SubtitleEvent

/**
 * First usable cue editor. Rows are cheap (plain data, no per-row state
 * collection) and the list is lazy with stable keys, so large subtitle
 * files stay usable. Only the expanded row holds text-field state.
 */
@Composable
internal fun ToolkitEditTab(
    editor: SubtitleEditorViewModel,
    editorState: SubtitleEditorState,
) {
    val doc = editorState.document ?: return
    var expandedId by remember { mutableStateOf<Int?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    // Drop the expansion if its cue disappeared (delete/undo/transform).
    val visibleExpandedId = expandedId?.takeIf { id -> doc.eventById(id) != null }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = editor::undo,
            enabled = editorState.canUndo,
        ) {
            Text("Undo")
        }
        OutlinedButton(
            onClick = editor::redo,
            enabled = editorState.canRedo,
        ) {
            Text("Redo")
        }
        Button(onClick = { showAdd = true }) {
            Text("Add cue")
        }
    }
    Text(
        "${doc.events.size} cues. Edits apply to the document copy; " +
            "the original file is only touched on export.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp),
    ) {
        items(doc.events, key = { it.id }) { event ->
            val nextId = nextEventId(doc, event.id)
            CueRow(
                event = event,
                expanded = visibleExpandedId == event.id,
                nextId = nextId,
                onToggle = {
                    expandedId = if (visibleExpandedId == event.id) null else event.id
                },
                onCollapse = { expandedId = null },
                editor = editor,
            )
        }
    }

    if (showAdd) {
        AddCueDialog(
            defaultStartMs = doc.maxEndMs() + 500L,
            onConfirm = { start, end, text ->
                editor.addCue(start, end, text)
                showAdd = false
            },
            onDismiss = { showAdd = false },
        )
    }
}

private fun nextEventId(doc: SubtitleDocument, id: Int): Int? {
    val index = doc.events.indexOfFirst { it.id == id }
    if (index < 0 || index + 1 >= doc.events.size) return null
    return doc.events[index + 1].id
}

@Composable
private fun CueRow(
    event: SubtitleEvent,
    expanded: Boolean,
    nextId: Int?,
    onToggle: () -> Unit,
    onCollapse: () -> Unit,
    editor: SubtitleEditorViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
    ) {
        Text(
            "#${event.id}  ${SubtitleEditorViewModel.formatMs(event.startMs)} --> " +
                SubtitleEditorViewModel.formatMs(event.endMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!expanded) {
            Text(
                event.text.ifBlank { "(empty)" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            CueEditor(
                event = event,
                nextId = nextId,
                editor = editor,
                onDone = onCollapse,
            )
        }
    }
}

@Composable
private fun CueEditor(
    event: SubtitleEvent,
    nextId: Int?,
    editor: SubtitleEditorViewModel,
    onDone: () -> Unit,
) {
    var draftText by remember(event.id) { mutableStateOf(event.text) }
    var draftStart by remember(event.id) { mutableStateOf("${event.startMs}") }
    var draftEnd by remember(event.id) { mutableStateOf("${event.endMs}") }
    var splitAt by remember(event.id) {
        mutableStateOf("${(event.startMs + event.endMs) / 2}")
    }
    var localError by remember(event.id) { mutableStateOf<String?>(null) }

    OutlinedTextField(
        value = draftText,
        onValueChange = { draftText = it },
        label = { Text("Text") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draftStart,
            onValueChange = { draftStart = it },
            label = { Text("Start ms") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = draftEnd,
            onValueChange = { draftEnd = it },
            label = { Text("End ms") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val start = draftStart.toLongOrNull()
            val end = draftEnd.toLongOrNull()
            if (start == null || end == null || start < 0 || end <= start) {
                localError = "Enter a valid range with end after start."
                return@Button
            }
            localError = null
            editor.editText(event.id, draftText)
            editor.editTiming(event.id, start, end)
            onDone()
        }) {
            Text("Save")
        }
        OutlinedButton(onClick = {
            editor.deleteCue(event.id)
            onDone()
        }) {
            Text("Delete")
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = splitAt,
            onValueChange = { splitAt = it },
            label = { Text("Split at ms") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = {
            val at = splitAt.toLongOrNull()
            if (at == null || at <= event.startMs || at >= event.endMs) {
                localError = "Split point must be inside the cue."
                return@OutlinedButton
            }
            localError = null
            editor.splitCue(event.id, at)
            onDone()
        }) {
            Text("Split")
        }
        if (nextId != null) {
            OutlinedButton(onClick = {
                editor.mergeCues(event.id, nextId)
                onDone()
            }) {
                Text("Merge next")
            }
        }
    }
    localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun AddCueDialog(
    defaultStartMs: Long,
    onConfirm: (Long, Long, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("$defaultStartMs") }
    var end by remember { mutableStateOf("${defaultStartMs + 2000L}") }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add cue") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    minLines = 2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = start,
                        onValueChange = { start = it },
                        label = { Text("Start ms") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = end,
                        onValueChange = { end = it },
                        label = { Text("End ms") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = start.toLongOrNull()
                val e = end.toLongOrNull()
                if (text.isBlank() || s == null || e == null || s < 0 || e <= s) {
                    localError = "Enter text and a valid range with end after start."
                    return@TextButton
                }
                onConfirm(s, e, text)
            }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
