package com.librium

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.librium.core.LibLog
import com.librium.media.MediaResolver
import com.librium.media.VideoIntent
import com.librium.media.logTag
import com.librium.ui.home.HomeScreen
import com.librium.ui.player.PlayerScreen
import com.librium.ui.player.PlayerViewModel
import com.librium.ui.theme.ComposeEmptyActivityTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    // Activity-scoped: survives rotation via configChanges handling and
    // singleTask relaunch, so exactly one MPV instance exists per task.
    private val playerVm: PlayerViewModel by viewModels()

    // False on cold start (fresh engine, nothing to play) and after
    // process death, so the app can never open into an empty player.
    private var showPlayer by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VideoIntent.resolve(intent) != null) {
            // Launched from a file manager: skip home, play immediately.
            showPlayer = true
        }
        if (BuildConfig.DEBUG) {
            // Log-only: catches main-thread disk/binder work on-device
            // without ever crashing. Release builds skip this entirely.
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build(),
            )
        }
        LibLog.d(LibLog.LIFECYCLE) { "onCreate" }
        enableEdgeToEdge()
        setContent {
            ComposeEmptyActivityTheme {
                // Gate slice only: the root must not recompose on 4 Hz
                // position ticks, only on media/loading/error changes.
                val gateFlow = remember(playerVm) {
                    playerVm.state
                        .map { Triple(it.hasMedia, it.isLoading, it.error) }
                        .distinctUntilChanged()
                }
                val gate by gateFlow.collectAsStateWithLifecycle(Triple(false, false, null))
                // The player is only ever visible with real media state;
                // anything else falls back to home, never an empty player.
                val inPlayer = showPlayer && shouldShowPlayer(
                    hasMedia = gate.first,
                    isLoading = gate.second,
                    error = gate.third,
                )
                if (inPlayer) {
                    PlayerScreen(
                        viewModel = playerVm,
                        onBack = {
                            // No background-play UI exists, so leaving the
                            // player pauses instead of playing silent audio.
                            playerVm.pause()
                            showPlayer = false
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    HomeScreen(
                        onOpenVideo = { uriString, displayName ->
                            playerVm.openVideo(uriString, displayName)
                            showPlayer = true
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        handleVideoIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        LibLog.d(LibLog.LIFECYCLE) { "onNewIntent" }
        handleVideoIntent(intent)
    }

    override fun onDestroy() {
        LibLog.d(LibLog.LIFECYCLE) { "onDestroy(isFinishing=$isFinishing)" }
        super.onDestroy()
    }

    /**
     * External "Open with" path: resolves the video URI and forwards it to
     * the existing `PlayerController.openVideo`, same as the SAF picker.
     *
     * Provider queries can block, so they run on Dispatchers.IO; the final
     * `openVideo` call returns to the main thread. Rapid successive intents
     * are last-wins at the mpv `loadfile` layer.
     */
    private fun handleVideoIntent(intent: Intent?) {
        val request = VideoIntent.resolve(intent) ?: return
        LibLog.d(LibLog.LIFECYCLE) { "video intent received" }
        lifecycleScope.launch {
            val uri = runCatching { Uri.parse(request.uri) }.getOrNull() ?: return@launch
            if (uri.scheme == "content") {
                LibLog.timed(LibLog.SAF, "takePersistableUriPermission") {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }.onFailure { e ->
                            LibLog.w(LibLog.SAF) { "persist permission failed: ${e.message}" }
                        }
                    }
                }
            }
            val name = if (uri.scheme == "content") {
                LibLog.timed(LibLog.SAF, "intent displayName") {
                    withContext(Dispatchers.IO) {
                        MediaResolver.displayName(contentResolver, uri)
                    }
                }
            } else {
                request.displayName
            }
            LibLog.i(LibLog.MEDIA) { "Opening external video ${uri.logTag()}" }
            playerVm.openVideo(request.uri, name)
            showPlayer = true
        }
    }
}

/**
 * Player visibility gate: the player screen only shows with real media
 * state (loaded, loading, or a visible error). Pure and unit-tested.
 */
internal fun shouldShowPlayer(hasMedia: Boolean, isLoading: Boolean, error: String?): Boolean =
    hasMedia || isLoading || error != null
