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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.librium.core.LibLog
import com.librium.media.MediaResolver
import com.librium.subtitle.SUBTITLE_LOAD_FAILED_MESSAGE
import com.librium.subtitle.SubtitleFileDecision
import com.librium.subtitle.SubtitleFileValidation
import com.librium.subtitle.SubtitleRejectReason
import com.librium.subtitle.UNSUPPORTED_SUBTITLE_MESSAGE
import com.librium.ui.subtitle.SubtitleEditorViewModel
import com.librium.ui.subtitle.SubtitleInfoSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Player screen: video surface + overlay controls, dark UI.
 * Single screen, no navigation. All state comes from [PlayerViewModel].
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = viewModel(),
    subtitleVm: SubtitleEditorViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(true) }
    var audioDialogOpen by remember { mutableStateOf(false) }
    var subtitleDialogOpen by remember { mutableStateOf(false) }
    var subtitleToolsOpen by remember { mutableStateOf(false) }
    var volumeExpanded by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
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
    LaunchedEffect(state.isFullscreen) {
        activity?.let {
            it.requestedOrientation =
                FullscreenOrientation.requestedOrientation(state.isFullscreen)
            val window = it.window
            val insets = WindowCompat.getInsetsController(window, window.decorView)
            if (state.isFullscreen) {
                insets.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insets.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Auto-hide controls while playing.
    LaunchedEffect(state.isPlaying, controlsVisible) {
        if (state.isPlaying && controlsVisible) {
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

        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }

        if (!state.hasMedia && !state.isLoading) {
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
            state.error?.let { error ->
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
            visible = controlsVisible && state.hasMedia,
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
                    text = state.mediaTitle ?: "Librium",
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
                val duration = state.durationMs
                val shownPos = scrubMs ?: state.positionMs
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatTimestamp(shownPos),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Slider(
                        value = if (duration > 0) shownPos.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
                        onValueChange = { scrubMs = it.toLong() },
                        onValueChangeFinished = {
                            scrubMs?.let(viewModel::seekTo)
                            scrubMs = null
                        },
                        valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()),
                        enabled = state.canSeek,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatTimestamp(duration),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(onClick = { videoPicker.launch(MediaResolver.VIDEO_MIME_FILTER) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Open video", tint = Color.White)
                    }
                    IconButton(onClick = { subtitlePicker.launch(Unit) }) {
                        Icon(Icons.Filled.Subtitles, contentDescription = "Open subtitle file", tint = Color.White)
                    }
                    IconButton(onClick = { viewModel.seekBy(-10_000) }, enabled = state.canSeek) {
                        Icon(Icons.Filled.Replay10, contentDescription = "Back 10s", tint = Color.White)
                    }
                    IconButton(
                        onClick = viewModel::togglePlayPause,
                        enabled = state.hasMedia,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (state.isPaused) "Play" else "Pause",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    IconButton(onClick = { viewModel.seekBy(10_000) }, enabled = state.canSeek) {
                        Icon(Icons.Filled.Forward10, contentDescription = "Forward 10s", tint = Color.White)
                    }
                    IconButton(onClick = { audioDialogOpen = true }, enabled = state.hasMedia) {
                        Icon(Icons.Filled.Audiotrack, contentDescription = "Audio tracks", tint = Color.White)
                    }
                    IconButton(onClick = { subtitleDialogOpen = true }, enabled = state.hasMedia) {
                        Icon(Icons.Filled.ClosedCaption, contentDescription = "Subtitle tracks", tint = Color.White)
                    }
                    IconButton(onClick = viewModel::toggleFullscreen) {
                        Icon(
                            if (state.isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = "Fullscreen",
                            tint = Color.White,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (volumeExpanded) viewModel.toggleMute() else volumeExpanded = true
                    }) {
                        Icon(
                            if (state.isMuted || state.volume == 0) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                            contentDescription = "Volume",
                            tint = Color.White,
                        )
                    }
                    if (volumeExpanded) {
                        var sliderValue by remember(state.volume) { mutableFloatStateOf(state.volume.toFloat()) }
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            onValueChangeFinished = { viewModel.setVolume(sliderValue.toInt()) },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${state.volume}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    } else {
                        Text(
                            "Audio: ${state.audioTracks.size} · Subs: ${state.subtitleTracks.size}",
                            color = Color(0xFFB0B0B0),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }

    if (audioDialogOpen) {
        AlertDialog(
            onDismissRequest = { audioDialogOpen = false },
            title = { Text("Audio track") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TrackRow(
                        label = "Off",
                        selected = state.selectedAudioId < 0,
                        onClick = { viewModel.selectAudioTrack(-1); audioDialogOpen = false },
                    )
                    state.audioTracks.forEach { track ->
                        TrackRow(
                            label = track.label,
                            selected = track.mpvId == state.selectedAudioId,
                            onClick = { viewModel.selectAudioTrack(track.mpvId); audioDialogOpen = false },
                        )
                    }
                    if (state.audioTracks.isEmpty()) {
                        Text("No audio tracks reported for this media.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { audioDialogOpen = false }) { Text("Close") }
            },
        )
    }

    if (subtitleDialogOpen) {
        AlertDialog(
            onDismissRequest = { subtitleDialogOpen = false },
            title = { Text("Subtitles") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        Text("Enabled", modifier = Modifier.weight(1f))
                        Switch(
                            checked = state.subtitlesEnabled,
                            onCheckedChange = viewModel::setSubtitlesEnabled,
                        )
                    }
                    TrackRow(
                        label = "Off",
                        selected = state.selectedSubtitleId < 0,
                        onClick = { viewModel.selectSubtitleTrack(null); subtitleDialogOpen = false },
                    )
                    state.subtitleTracks.forEach { track ->
                        TrackRow(
                            label = track.label,
                            selected = track.mpvId == state.selectedSubtitleId,
                            onClick = { viewModel.selectSubtitleTrack(track.mpvId); subtitleDialogOpen = false },
                        )
                    }
                    if (state.subtitleTracks.isEmpty()) {
                        Text(
                            "No subtitle tracks. Use the subtitle button to load SRT / ASS / SSA / VTT.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = {
                        subtitleDialogOpen = false
                        subtitleToolsOpen = true
                    }) {
                        Text("Subtitle info, sync & export")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Supported: SRT, ASS, SSA, VTT (via libmpv)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { subtitleDialogOpen = false }) { Text("Close") }
            },
        )
    }

    if (subtitleToolsOpen) {
        SubtitleInfoSheet(
            editor = subtitleVm,
            playerDelayMs = state.subtitleDelayMs,
            videoDurationMs = state.durationMs,
            onApplyPlayerDelay = viewModel::setSubtitleDelay,
            onDismiss = { subtitleToolsOpen = false },
        )
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (selected) "● $label" else "○ $label",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val CONTROLS_TIMEOUT_MS = 3000L
