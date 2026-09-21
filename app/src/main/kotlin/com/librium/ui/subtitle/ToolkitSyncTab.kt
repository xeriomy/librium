package com.librium.ui.subtitle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.librium.subtitle.TimeMapping
import com.librium.subtitle.TimingTransform

@Composable
internal fun ToolkitSyncTab(
    editor: SubtitleEditorViewModel,
    editorState: SubtitleEditorState,
    onApplyPlayerDelay: (Long) -> Unit,
) {
    var formError by remember { mutableStateOf<String?>(null) }

    SectionTitle("Global offset")
    Text(
        "Shifts every cue by the same amount. Apply to the player for a live " +
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
        Text("Offset: ${editorState.pendingOffsetMs} ms", modifier = Modifier.weight(1f))
        Button(onClick = {
            editor.previewTransform(TimingTransform.Offset(editorState.pendingOffsetMs))
        }) {
            Text("Preview")
        }
        Button(onClick = {
            editor.applyOffsetToDocument(editorState.pendingOffsetMs)
        }) {
            Text("Apply")
        }
    }
    OutlinedButton(onClick = { onApplyPlayerDelay(editorState.pendingOffsetMs) }) {
        Text("Apply offset to player (live preview)")
    }

    SectionTitle("FPS conversion")
    Text(
        "Exact rescaling between frame rates (no added offset). " +
            "Example: 25 fps content retimed to 23.976 runs longer by design.",
        style = MaterialTheme.typography.bodySmall,
    )
    var srcFps by remember(editorState.sourceFpsText) { mutableStateOf(editorState.sourceFpsText) }
    var tgtFps by remember(editorState.targetFpsText) { mutableStateOf(editorState.targetFpsText) }
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
    SyncPreviewApplyRow(
        onPreview = {
            val rates = parseFps(srcFps, tgtFps)
            if (rates == null) {
                formError = "Enter positive frame rates."
            } else {
                formError = null
                editor.previewTransform(TimingTransform.Fps(rates.first, rates.second))
            }
        },
        onApply = {
            val rates = parseFps(srcFps, tgtFps)
            if (rates == null) {
                formError = "Enter positive frame rates."
            } else {
                formError = null
                editor.applyFpsToDocument(rates.first, rates.second)
            }
        },
    )

    SectionTitle("Pivot rescaling")
    Text(
        "Multiplies every timestamp by a factor around a pivot point, for " +
            "rate changes not covered by the FPS presets above.",
        style = MaterialTheme.typography.bodySmall,
    )
    var factorText by remember { mutableStateOf("1.0") }
    var pivotText by remember { mutableStateOf("0") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = factorText,
            onValueChange = { factorText = it },
            label = { Text("Factor") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = pivotText,
            onValueChange = { pivotText = it },
            label = { Text("Pivot ms") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    SyncPreviewApplyRow(
        onPreview = {
            val parsed = parseScale(factorText, pivotText)
            if (parsed == null) {
                formError = "Enter a positive factor and pivot in ms."
            } else {
                formError = null
                editor.previewTransform(TimingTransform.Scale(parsed.first, parsed.second))
            }
        },
        onApply = {
            val parsed = parseScale(factorText, pivotText)
            if (parsed == null) {
                formError = "Enter a positive factor and pivot in ms."
            } else {
                formError = null
                editor.applyScaleToDocument(parsed.first, parsed.second)
            }
        },
    )

    SectionTitle("Drift correction")
    Text(
        "Two anchor points pin subtitle time to video time; everything " +
            "between them interpolates linearly, fixing offset and drift.",
        style = MaterialTheme.typography.bodySmall,
    )
    var dss by remember(editorState.driftStartSubText) { mutableStateOf(editorState.driftStartSubText) }
    var dsv by remember(editorState.driftStartVideoText) { mutableStateOf(editorState.driftStartVideoText) }
    var des by remember(editorState.driftEndSubText) { mutableStateOf(editorState.driftEndSubText) }
    var dev by remember(editorState.driftEndVideoText) { mutableStateOf(editorState.driftEndVideoText) }
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
    SyncPreviewApplyRow(
        onPreview = {
            val mapping = parseDrift(dss, dsv, des, dev)
            if (mapping == null) {
                formError = "Enter all four drift points in ms."
            } else {
                formError = null
                editor.previewTransform(TimingTransform.Drift(mapping.first, mapping.second))
            }
        },
        onApply = {
            val mapping = parseDrift(dss, dsv, des, dev)
            if (mapping == null) {
                formError = "Enter all four drift points in ms."
            } else {
                formError = null
                editor.applyDriftToDocument(mapping.first, mapping.second)
            }
        },
    )
    formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    editorState.previewLines.forEach { line ->
        Text(line, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SyncPreviewApplyRow(
    onPreview: () -> Unit,
    onApply: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPreview) {
            Text("Preview")
        }
        Button(onClick = onApply) {
            Text("Apply to document")
        }
    }
}

private fun parseFps(source: String, target: String): Pair<Double, Double>? {
    val s = source.toDoubleOrNull() ?: return null
    val t = target.toDoubleOrNull() ?: return null
    if (s <= 0 || t <= 0) return null
    return s to t
}

private fun parseScale(factor: String, pivot: String): Pair<Double, Long>? {
    val f = factor.toDoubleOrNull() ?: return null
    val p = pivot.toLongOrNull() ?: return null
    if (!f.isFinite() || f <= 0) return null
    return f to p
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
