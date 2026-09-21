package com.librium.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.librium.core.LibLog
import com.librium.media.MediaResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App entry screen. Shown whenever there is no media to play — never an
 * empty player. Video picking resolves off the main thread, then hands
 * the result to [onOpenVideo], which opens the player.
 */
@Composable
fun HomeScreen(
    onOpenVideo: (uriString: String, displayName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val ioWork = rememberCoroutineScope()

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            LibLog.d(LibLog.SAF) { "video picker cancelled" }
            return@rememberLauncherForActivityResult
        }
        ioWork.launch {
            val pick = LibLog.timed(LibLog.SAF, "video pick resolve") {
                withContext(Dispatchers.IO) {
                    MediaResolver.pickVideo(context.contentResolver, uri)
                }
            }
            onOpenVideo(pick.uriString, pick.displayName)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.Movie,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(64.dp),
            )
            Text(
                "Librium",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
            )
            Text(
                "A focused video player",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB0B0B0),
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { videoPicker.launch(MediaResolver.VIDEO_MIME_FILTER) }) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Open video")
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "No recent videos yet.\nOpen a video to start watching.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF808080),
                textAlign = TextAlign.Center,
            )
        }
    }
}
