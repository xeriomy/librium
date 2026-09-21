package com.librium.ui.player

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.librium.core.LibLog
import com.librium.media.MediaResolver
import com.librium.player.AudioTrack
import com.librium.player.PlayerState
import com.librium.player.SubtitleTrackInfo
import com.librium.subtitle.SUBTITLE_LOAD_FAILED_MESSAGE
import com.librium.subtitle.SubtitleFileDecision
import com.librium.subtitle.SubtitleFileValidation
import com.librium.subtitle.SubtitleRejectReason
import com.librium.subtitle.UNSUPPORTED_SUBTITLE_MESSAGE
import com.librium.ui.subtitle.SubtitleEditorViewModel
import com.librium.ui.subtitle.SubtitleInfoSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Player screen: video surface + overlay controls, dark UI.
 * Single screen, no navigation. All state comes from [PlayerViewModel].
 *
 * Recomposition discipline: position ticks arrive at ~4 Hz, so the root
 * only collects [PlayerChrome] (everything except the position), while
 * [PlaybackProgressRow] collects its own position slice. Nothing else
 * recomposes on ticks.
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = viewModel(),
    subtitleVm: SubtitleEditorViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val chromeFlow = remember(viewModel) {
        viewModel.state.map { PlayerChrome.from(it) }.distinctUntilChanged()
    }
    val chrome by chromeFlow.collectAsStateWithLifecycle(PlayerChrome())

    var controlsVisible by remember { mutableStateOf(true) }
    var audioDialogOpen by remember { mutableStateOf(false) }
    var subtitleDialogOpen by remember { mutableStateOf(false) }
    var subtitleToolsOpen by remember { mutableStateOf(false) }
    var subtitleBanner by remember { mutableStateOf<String?>(null) }

    // Picker callbacks run on the main thread, so every potentially
    // blocking provider call (query, permission) hops to IO first; only
    // state updates and ViewModel calls run back on the main scope.
    val ioWork = rememberCoroutineScope()

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            LibLog.d(LibLog.SAF) { "video picker cancelled" }
            return@rememberLauncherForActivityResult
        }
        ioWork.launch {
            LibLog.timed(LibLog.SAF, "video pick resolve") {
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
            val name = LibLog.timed(LibLog.SAF, "video displayName") {
                withContext(Dispatchers.IO) {
                    MediaResolver.displayName(context.contentResolver, uri)
                }
            }
            viewModel.openVideo(uri.toString(), name)
            controlsVisible = true
        }
    }
    val subtitlePicker = rememberLauncherForActivityResult(
        OpenSubtitleDocument(),
    ) { uri ->
        if (uri == null) {
            LibLog.d(LibLog.SAF) { "subtitle picker cancelled" }
            return@rememberLauncherForActivityResult
        }
        ioWork.launch {
            val name = LibLog.timed(LibLog.SAF, "subtitle displayName") {
                withContext(Dispatchers.IO) {
                    MediaResolver.displayName(context.contentResolver, uri)
                }
            }
            when (val decision = SubtitleFileValidation.validate(name, uri.toString())) {
                is SubtitleFileDecision.Accept -> {
                    // Persist access only for files we actually accept.
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                    }
                    subtitleBanner = null
                    viewModel.addExternalSubtitle(uri.toString())
                    subtitleVm.loadExternal(context.contentResolver, uri, name)
                    subtitleToolsOpen = true
                    controlsVisible = true
                }
                is SubtitleFileDecision.Reject -> {
                    // Invalid files never reach the parser, mpv, or any state:
                    // keep everything untouched and explain what happened.
                    LibLog.d(LibLog.SUB) {
                        "subtitle rejected (${decision.reason}): ${decision.detail}"
                    }
                    subtitleBanner = when (decision.reason) {
                        SubtitleRejectReason.MISSING_NAME -> SUBTITLE_LOAD_FAILED_MESSAGE
                        SubtitleRejectReason.UNSUPPORTED_EXTENSION -> UNSUPPORTED_SUBTITLE_MESSAGE
                    }
                    controlsVisible = true
                }
            }
        }
    }

    // Fullscreen drives orientation explicitly (landscape in, portrait out)
    // so UI state and Activity orientation can never disagree.
    val activity = context as? Activity
    LaunchedEffect(chrome.isFullscreen) {
        activity?.let {
            it.requestedOrientation =
                FullscreenOrientation.requestedOrientation(chrome.isFullscreen)
            val window = it.window
            val insets = WindowCompat.getInsetsController(window, window.decorView)
            if (chrome.isFullscreen) {
                insets.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insets.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Auto-hide controls while playing; they stay put while paused.
    val isPlaying = chrome.hasMedia && !chrome.isPaused && !chrome.isLoading
    LaunchedEffect(isPlaying, controlsVisible) {
        if (isPlaying && controlsVisible) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        MpvVideoSurface(
            onSurfaceCreated = viewModel::attachSurface,
            onSurfaceDestroyed = viewModel::detachSurface,
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { controlsVisible = !controlsVisible },
        )

        if (chrome.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }

        if (!chrome.hasMedia && !chrome.isLoading) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Librium", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text(
                    "Open a local video to start playback",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB0B0B0),
                )
                TextButton(onClick = { videoPicker.launch(MediaResolver.VIDEO_MIME_FILTER) }) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Open video", color = Color.White)
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chrome.error?.let { error ->
                Row(
                    modifier = Modifier
                        .background(Color(0xFF7F1D1D), MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = error,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::clearError) {
                        Text("Dismiss", color = Color.White)
                    }
                }
            }
            subtitleBanner?.let { banner ->
                Row(
                    modifier = Modifier
                        .background(Color(0xFF7F1D1D), MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = banner,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { subtitleBanner = null }) {
                        Text("Dismiss", color = Color.White)
                    }
                }
            }
        }

        // Top bar: title.
        AnimatedVisibility(
            visible = controlsVisible && chrome.hasMedia,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = chrome.mediaTitle ?: "Librium",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Bottom controls overlay.
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                PlaybackProgressRow(
                    stateFlow = viewModel.state,
                    onSeekTo = viewModel::seekTo,
                )
                TransportRow(
                    stateFlow = viewModel.state,
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onSeekBy = viewModel::seekBy,
                    onToggleFullscreen = viewModel::toggleFullscreen,
                    onOpenVideo = { videoPicker.launch(MediaResolver.VIDEO_MIME_FILTER) },
                    onLoadSubtitle = { subtitlePicker.launch(Unit) },
                    onOpenAudioTracks = { audioDialogOpen = true },
                    onOpenSubtitleTracks = { subtitleDialogOpen = true },
                    onOpenToolkit = { subtitleToolsOpen = true },
                )
                VolumeRow(
                    stateFlow = viewModel.state,
                    onToggleMute = viewModel::toggleMute,
                    onSetVolume = viewModel::setVolume,
                )
            }
        }
    }

    if (audioDialogOpen) {
        AudioTrackDialog(
            tracks = chrome.audioTracks,
            selectedId = chrome.selectedAudioId,
            onSelect = { viewModel.selectAudioTrack(it); audioDialogOpen = false },
            onDismiss = { audioDialogOpen = false },
        )
    }

    if (subtitleDialogOpen) {
        EmbeddedSubtitleDialog(
            tracks = chrome.subtitleTracks,
            selectedId = chrome.selectedSubtitleId,
            enabled = chrome.subtitlesEnabled,
            onToggleEnabled = viewModel::setSubtitlesEnabled,
            onSelect = { viewModel.selectSubtitleTrack(it); subtitleDialogOpen = false },
            onDismiss = { subtitleDialogOpen = false },
        )
    }

    if (subtitleToolsOpen) {
        SubtitleInfoSheet(
            editor = subtitleVm,
            playerState = viewModel.state,
            onApplyPlayerDelay = viewModel::setSubtitleDelay,
            onAppearanceChange = viewModel::setSubtitleAppearance,
            onDismiss = { subtitleToolsOpen = false },
        )
    }
}

/**
 * Everything the root screen reads except the position. Collected with
 * [distinctUntilChanged], so 4 Hz position ticks never recompose this
 * scope — only [PlaybackProgressRow] subscribes to those.
 */
private data class PlayerChrome(
    val hasMedia: Boolean = false,
    val isLoading: Boolean = false,
    val isPaused: Boolean = true,
    val isFullscreen: Boolean = false,
    val error: String? = null,
    val mediaTitle: String? = null,
    val volume: Int = 100,
    val isMuted: Boolean = false,
    val audioTracks: List<AudioTrack> = emptyList(),
    val selectedAudioId: Int = -1,
    val subtitleTracks: List<SubtitleTrackInfo> = emptyList(),
    val selectedSubtitleId: Int = -1,
    val subtitlesEnabled: Boolean = true,
) {
    companion object {
        fun from(state: PlayerState) = PlayerChrome(
            hasMedia = state.hasMedia,
            isLoading = state.isLoading,
            isPaused = state.isPaused,
            isFullscreen = state.isFullscreen,
            error = state.error,
            mediaTitle = state.mediaTitle,
            volume = state.volume,
            isMuted = state.isMuted,
            audioTracks = state.audioTracks,
            selectedAudioId = state.selectedAudioId,
            subtitleTracks = state.subtitleTracks,
            selectedSubtitleId = state.selectedSubtitleId,
            subtitlesEnabled = state.subtitlesEnabled,
        )
    }
}

private data class ProgressSlice(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val canSeek: Boolean = false,
)

@Composable
private fun PlaybackProgressRow(
    stateFlow: StateFlow<PlayerState>,
    onSeekTo: (Long) -> Unit,
) {
    val progressFlow = remember(stateFlow) {
        stateFlow.map {
            ProgressSlice(it.positionMs, it.durationMs, it.canSeek)
        }.distinctUntilChanged()
    }
    val progress by progressFlow.collectAsStateWithLifecycle(ProgressSlice())
    var scrubMs by remember { mutableStateOf<Long?>(null) }

    val shownPos = scrubMs ?: progress.positionMs
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatTimestamp(shownPos),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.width(8.dp))
        Slider(
            value = if (progress.durationMs > 0) {
                shownPos.toFloat().coerceIn(0f, progress.durationMs.toFloat())
            } else {
                0f
            },
            onValueChange = { scrubMs = it.toLong() },
            onValueChangeFinished = {
                scrubMs?.let(onSeekTo)
                scrubMs = null
            },
            valueRange = 0f..(progress.durationMs.coerceAtLeast(1L).toFloat()),
            enabled = progress.canSeek,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            formatTimestamp(progress.durationMs),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private data class TransportSlice(
    val isPaused: Boolean = true,
    val hasMedia: Boolean = false,
    val canSeek: Boolean = false,
    val isFullscreen: Boolean = false,
)

@Composable
private fun TransportRow(
    stateFlow: StateFlow<PlayerState>,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenVideo: () -> Unit,
    onLoadSubtitle: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onOpenSubtitleTracks: () -> Unit,
    onOpenToolkit: () -> Unit,
) {
    val transportFlow = remember(stateFlow) {
        stateFlow.map {
            TransportSlice(it.isPaused, it.hasMedia, it.canSeek, it.isFullscreen)
        }.distinctUntilChanged()
    }
    val transport by transportFlow.collectAsStateWithLifecycle(TransportSlice())
    var moreOpen by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {
            IconButton(onClick = { moreOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = Color.White)
            }
            DropdownMenu(
                expanded = moreOpen,
                onDismissRequest = { moreOpen = false },
            ) {
                PlayerMenuItem(
                    icon = Icons.Filled.FolderOpen,
                    label = "Open video",
                    onClick = { moreOpen = false; onOpenVideo() },
                )
                PlayerMenuItem(
                    icon = Icons.Filled.Subtitles,
                    label = "Load subtitle file",
                    onClick = { moreOpen = false; onLoadSubtitle() },
                )
                PlayerMenuItem(
                    icon = Icons.Filled.Audiotrack,
                    label = "Audio tracks",
                    onClick = { moreOpen = false; onOpenAudioTracks() },
                )
                PlayerMenuItem(
                    icon = Icons.Filled.ClosedCaption,
                    label = "Subtitle tracks",
                    onClick = { moreOpen = false; onOpenSubtitleTracks() },
                )
                PlayerMenuItem(
                    icon = Icons.Filled.Settings,
                    label = "Subtitle toolkit",
                    onClick = { moreOpen = false; onOpenToolkit() },
                )
            }
        }
        IconButton(
            onClick = onTogglePlayPause,
            enabled = transport.hasMedia,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                if (transport.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = if (transport.isPaused) "Play" else "Pause",
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = { onSeekBy(-10_000) }, enabled = transport.canSeek) {
            Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds", tint = Color.White)
        }
        IconButton(onClick = { onSeekBy(10_000) }, enabled = transport.canSeek) {
            Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds", tint = Color.White)
        }
        IconButton(onClick = onOpenSubtitleTracks, enabled = transport.hasMedia) {
            Icon(Icons.Filled.ClosedCaption, contentDescription = "Subtitle tracks", tint = Color.White)
        }
        IconButton(onClick = onOpenAudioTracks, enabled = transport.hasMedia) {
            Icon(Icons.Filled.Audiotrack, contentDescription = "Audio tracks", tint = Color.White)
        }
        IconButton(onClick = onToggleFullscreen) {
            Icon(
                if (transport.isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = if (transport.isFullscreen) {
                    "Exit fullscreen"
                } else {
                    "Enter fullscreen"
                },
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun PlayerMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

private data class VolumeSlice(
    val volume: Int = 100,
    val isMuted: Boolean = false,
    val audioCount: Int = 0,
    val subCount: Int = 0,
)

@Composable
private fun VolumeRow(
    stateFlow: StateFlow<PlayerState>,
    onToggleMute: () -> Unit,
    onSetVolume: (Int) -> Unit,
) {
    val volumeFlow = remember(stateFlow) {
        stateFlow.map {
            VolumeSlice(it.volume, it.isMuted, it.audioTracks.size, it.subtitleTracks.size)
        }.distinctUntilChanged()
    }
    val volumeState by volumeFlow.collectAsStateWithLifecycle(VolumeSlice())
    var expanded by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            if (expanded) onToggleMute() else expanded = true
        }) {
            Icon(
                if (volumeState.isMuted || volumeState.volume == 0) {
                    Icons.Filled.VolumeOff
                } else {
                    Icons.Filled.VolumeUp
                },
                contentDescription = "Volume",
                tint = Color.White,
            )
        }
        if (expanded) {
            var sliderValue by remember(volumeState.volume) {
                mutableFloatStateOf(volumeState.volume.toFloat())
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onSetVolume(sliderValue.toInt()) },
                valueRange = 0f..100f,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${volumeState.volume}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        } else {
            Text(
                "Audio: ${volumeState.audioCount} · Subs: ${volumeState.subCount}",
                color = Color(0xFFB0B0B0),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun AudioTrackDialog(
    tracks: List<AudioTrack>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio tracks") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TrackRow(
                    label = "Off",
                    selected = selectedId < 0,
                    onClick = { onSelect(-1) },
                )
                tracks.forEach { track ->
                    TrackRow(
                        label = track.label,
                        selected = track.mpvId == selectedId,
                        onClick = { onSelect(track.mpvId) },
                    )
                }
                if (tracks.isEmpty()) {
                    Text(
                        "No audio tracks reported for this media.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun EmbeddedSubtitleDialog(
    tracks: List<SubtitleTrackInfo>,
    selectedId: Int,
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subtitle tracks") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Embedded tracks from the current video, played by mpv.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Text("Enabled", modifier = Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = onToggleEnabled,
                    )
                }
                TrackRow(
                    label = "Off",
                    selected = selectedId < 0,
                    onClick = { onSelect(null) },
                )
                tracks.forEach { track ->
                    TrackRow(
                        label = track.label,
                        selected = track.mpvId == selectedId,
                        onClick = { onSelect(track.mpvId) },
                    )
                }
                if (tracks.isEmpty()) {
                    Text(
                        "No embedded subtitle tracks in this video. " +
                            "Use “Load subtitle file” for an external SRT, ASS, SSA, or VTT file.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private const val CONTROLS_TIMEOUT_MS = 3000L
