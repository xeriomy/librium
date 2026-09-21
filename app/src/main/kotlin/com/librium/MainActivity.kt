package com.librium

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.librium.core.LibLog
import com.librium.media.MediaResolver
import com.librium.media.VideoIntent
import com.librium.media.logTag
import com.librium.ui.player.PlayerScreen
import com.librium.ui.player.PlayerViewModel
import com.librium.ui.theme.ComposeEmptyActivityTheme

class MainActivity : ComponentActivity() {

    // Activity-scoped: survives rotation via configChanges handling and
    // singleTask relaunch, so exactly one MPV instance exists per task.
    private val playerVm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        handleVideoIntent(intent)
    }

    /**
     * External "Open with" path: resolves the video URI and forwards it to
     * the existing `PlayerController.openVideo`, same as the SAF picker.
     */
    private fun handleVideoIntent(intent: Intent?) {
        val request = VideoIntent.resolve(intent) ?: return
        val uri = runCatching { Uri.parse(request.uri) }.getOrNull() ?: return
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        val name = if (uri.scheme == "content") {
            MediaResolver.displayName(contentResolver, uri)
        } else {
            request.displayName
        }
        LibLog.i(LibLog.MEDIA) { "Opening external video ${uri.logTag()}" }
        playerVm.openVideo(request.uri, name)
    }
}
