package com.librium.player

/**
 * Human-readable track labels, extracted from the engine so the exact
 * wording (including the bare-number fallback) is pinned by unit tests.
 * `mpvId` doubles as the displayed track number.
 */
fun audioTrackLabel(mpvId: Int, lang: String?, title: String?): String =
    trackLabel(mpvId, lang, title, external = false)

fun subtitleTrackLabel(mpvId: Int, lang: String?, title: String?, external: Boolean): String =
    trackLabel(mpvId, lang, title, external)

private fun trackLabel(mpvId: Int, lang: String?, title: String?, external: Boolean): String =
    buildString {
        append("#").append(mpvId)
        if (!title.isNullOrBlank()) append(" ").append(title)
        if (!lang.isNullOrBlank()) append(" (").append(lang).append(")")
        if (external) append(" [ext]")
    }
