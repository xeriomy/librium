package com.librium.ui.subtitle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.librium.player.DEFAULT_SUBTITLE_APPEARANCE
import com.librium.player.SubAlignX
import com.librium.player.SubAlignY
import com.librium.player.SubtitleAppearance

/**
 * Live libass styling through mpv runtime properties. Changes apply to
 * the player immediately; the subtitle document itself is never touched.
 * Every option maps 1:1 to an `sub-*` mpv property.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolkitStyleTab(
    appearance: SubtitleAppearance,
    onChange: (SubtitleAppearance) -> Unit,
) {
    SectionTitle("Subtitle style")
    Text(
        "Rendered live by mpv while video plays. Changes apply instantly " +
            "and never modify the subtitle file.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    StyleSlider(
        label = "Font size",
        value = appearance.fontSize,
        range = 20f..120f,
        valueText = "${appearance.fontSize.toInt()}",
        onChange = { onChange(appearance.copy(fontSize = it)) },
    )

    Text("Text color", style = MaterialTheme.typography.labelLarge)
    ColorSwatches(
        options = listOf(
            "White" to 0xFFFFFFFF.toInt(),
            "Yellow" to 0xFFFFFF00.toInt(),
            "Cyan" to 0xFF00FFFF.toInt(),
            "Green" to 0xFF00FF00.toInt(),
        ),
        selected = appearance.textColor,
        onSelect = { onChange(appearance.copy(textColor = it)) },
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bold", modifier = Modifier.padding(end = 8.dp))
            Switch(
                checked = appearance.bold,
                onCheckedChange = { onChange(appearance.copy(bold = it)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Italic", modifier = Modifier.padding(end = 8.dp))
            Switch(
                checked = appearance.italic,
                onCheckedChange = { onChange(appearance.copy(italic = it)) },
            )
        }
    }

    StyleSlider(
        label = "Outline size",
        value = appearance.outlineSize,
        range = 0f..10f,
        valueText = "%.1f".format(appearance.outlineSize),
        onChange = { onChange(appearance.copy(outlineSize = it)) },
    )
    Text("Outline color", style = MaterialTheme.typography.labelLarge)
    ColorSwatches(
        options = listOf(
            "Black" to 0xFF000000.toInt(),
            "White" to 0xFFFFFFFF.toInt(),
        ),
        selected = appearance.outlineColor,
        onSelect = { onChange(appearance.copy(outlineColor = it)) },
    )

    Text("Background", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BackgroundChip("Transparent", 0x00000000, appearance.backgroundColor) {
            onChange(appearance.copy(backgroundColor = it))
        }
        BackgroundChip("Dimmed", 0xCC000000.toInt(), appearance.backgroundColor) {
            onChange(appearance.copy(backgroundColor = it))
        }
        BackgroundChip("Solid", 0xFF000000.toInt(), appearance.backgroundColor) {
            onChange(appearance.copy(backgroundColor = it))
        }
    }

    StyleSlider(
        label = "Shadow offset",
        value = appearance.shadowOffset,
        range = 0f..10f,
        valueText = "%.1f".format(appearance.shadowOffset),
        onChange = { onChange(appearance.copy(shadowOffset = it)) },
    )

    StyleSlider(
        label = "Vertical position",
        value = appearance.position.toFloat(),
        range = 0f..100f,
        valueText = "${appearance.position}",
        onChange = { onChange(appearance.copy(position = it.toInt())) },
    )

    Text("Horizontal alignment", style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SubAlignX.entries.forEachIndexed { index, align ->
            SegmentedButton(
                selected = appearance.alignX == align,
                onClick = { onChange(appearance.copy(alignX = align)) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = SubAlignX.entries.size,
                ),
            ) {
                Text(align.name.lowercase().replaceFirstChar { it.uppercase() })
            }
        }
    }

    Text("Vertical alignment", style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SubAlignY.entries.forEachIndexed { index, align ->
            SegmentedButton(
                selected = appearance.alignY == align,
                onClick = { onChange(appearance.copy(alignY = align)) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = SubAlignY.entries.size,
                ),
            ) {
                Text(align.name.lowercase().replaceFirstChar { it.uppercase() })
            }
        }
    }

    OutlinedButton(onClick = { onChange(DEFAULT_SUBTITLE_APPEARANCE) }) {
        Text("Reset to defaults")
    }
}

@Composable
private fun StyleSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(valueText, style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

@Composable
private fun ColorSwatches(
    options: List<Pair<String, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (name, argb) ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = "$name color"
                        role = Role.RadioButton
                    }
                    .clickable(onClick = { onSelect(argb) }),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .then(
                            if (selected == argb) {
                                Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary,
                                    CircleShape,
                                )
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun BackgroundChip(
    label: String,
    color: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    OutlinedButton(onClick = { onSelect(color) }) {
        Text(if (selected == color) "● $label" else label)
    }
}
