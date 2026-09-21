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
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.librium.core.LibLog
import com.librium.media.MediaResolver
import com.librium.media.VideoIntent
import com.librium.media.logTag
import com.librium.ui.player.PlayerScreen
import com.librium.ui.player.PlayerViewModel
import com.librium.ui.theme.ComposeEmptyActivityTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    // Activity-scoped: survives rotation via configChanges handling and
    // singleTask relaunch, so exactly one MPV instance exists per task.
    private val playerVm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                PlayerScreen(viewModel = playerVm, modifier = Modifier.fillMaxSize())
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
        }
    }
}
