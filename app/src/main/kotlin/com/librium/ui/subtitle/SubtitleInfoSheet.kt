package com.librium.ui.subtitle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.librium.player.DEFAULT_SUBTITLE_APPEARANCE
import com.librium.player.PlayerState
import com.librium.player.SubtitleAppearance
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * External-subtitle toolkit. libmpv keeps rendering during playback; every
 * tab works on the immutable document in [SubtitleEditorViewModel], and
 * only explicit Apply/Save actions change anything.
 *
 * Embedded tracks live in the player "Subtitle tracks" dialog instead —
 * this sheet is strictly for external subtitle files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleInfoSheet(
    editor: SubtitleEditorViewModel,
    playerState: StateFlow<PlayerState>,
    onApplyPlayerDelay: (Long) -> Unit,
    onAppearanceChange: (SubtitleAppearance) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editorState by editor.state.collectAsStateWithLifecycle()
    // Only delay/duration reach the sheet; position ticks stay out.
    val playerSliceFlow = remember(playerState) {
        playerState.map { PlayerSlice(it.subtitleDelayMs, it.durationMs, it.subtitleAppearance) }
            .distinctUntilChanged()
    }
    val playerSlice by playerSliceFlow.collectAsStateWithLifecycle(PlayerSlice())
    var tab by remember { mutableIntStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
            Text("Subtitle toolkit", style = MaterialTheme.typography.titleLarge)
            editorState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            editorState.notice?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }

            val doc = editorState.document
            if (doc == null) {
                Text(
                    "No subtitle file loaded. Use “Load subtitle file” to open " +
                        "an SRT, ASS, SSA, or VTT file.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (editorState.isLoading) {
                    CircularProgressIndicator()
                }
            } else {
                TabRow(selectedTabIndex = tab) {
                    ToolkitTab.entries.forEachIndexed { index, entry ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = {
                                Text(
                                    entry.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (ToolkitTab.entries[tab]) {
                        ToolkitTab.INFO -> ToolkitInfoTab(
                            editorState = editorState,
                            playerDelayMs = playerSlice.delayMs,
                        )
                        ToolkitTab.SYNC -> ToolkitSyncTab(
                            editor = editor,
                            editorState = editorState,
                            onApplyPlayerDelay = onApplyPlayerDelay,
                        )
                        ToolkitTab.ANALYZE -> ToolkitAnalyzeTab(
                            editor = editor,
                            editorState = editorState,
                            videoDurationMs = playerSlice.durationMs,
                        )
                        ToolkitTab.EDIT -> ToolkitEditTab(
                            editor = editor,
                            editorState = editorState,
                        )
                        ToolkitTab.EXPORT -> ToolkitExportTab(
                            editor = editor,
                            editorState = editorState,
                        )
                        ToolkitTab.STYLE -> ToolkitStyleTab(
                            appearance = playerSlice.appearance,
                            onChange = onAppearanceChange,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private enum class ToolkitTab(val label: String) {
    INFO("Info"),
    SYNC("Sync"),
    ANALYZE("Analyze"),
    EDIT("Edit"),
    EXPORT("Export"),
    STYLE("Style"),
}

private data class PlayerSlice(
    val delayMs: Long = 0L,
    val durationMs: Long = 0L,
    val appearance: SubtitleAppearance = DEFAULT_SUBTITLE_APPEARANCE,
)

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun InfoRow(label: String, value: String) {
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
