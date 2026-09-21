package com.librium.ui.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import com.librium.media.MediaResolver

/**
 * Subtitle file picker. Uses ACTION_OPEN_DOCUMENT with an explicit
 * category plus EXTRA_MIME_TYPES so providers that honor it only show
 * subtitle-like files — while providers that ignore extras still work,
 * because the mandatory extension check happens in-app afterwards
 * ([com.librium.subtitle.SubtitleFileValidation]).
 */
class OpenSubtitleDocument : ActivityResultContract<Unit, Uri?>() {

    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, MediaResolver.SUBTITLE_MIME_TYPES)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
