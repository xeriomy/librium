package com.librium.ui.player

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.librium.core.LibLog

/**
 * libmpv renders into a [SurfaceView]. Surface lifecycle drives
 * engine attach/detach; Compose recompositions never recreate it.
 */
@Composable
fun MpvVideoSurface(
    onSurfaceCreated: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentCreated = rememberUpdatedState(onSurfaceCreated)
    val currentDestroyed = rememberUpdatedState(onSurfaceDestroyed)
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            LibLog.d(LibLog.SURFACE) { "surfaceCreated" }
                            currentCreated.value(holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            // Size changes (e.g. rotation) are handled inside
                            // mpv; logged for on-device rotation diagnosis.
                            LibLog.d(LibLog.SURFACE) { "surfaceChanged ${width}x$height" }
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            LibLog.d(LibLog.SURFACE) { "surfaceDestroyed" }
                            currentDestroyed.value()
                        }
                    },
                )
            }
        },
        modifier = modifier,
    )
}

fun formatTimestamp(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000).toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
