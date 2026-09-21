package com.librium.player

/**
 * Backend-agnostic playback state. The UI renders ONLY from this —
 * never from native mpv types directly.
 */
data class AudioTrack(
    val mpvId: Int,
    val label: String,
)

data class SubtitleTrackInfo(
    val mpvId: Int,
    val label: String,
    val isExternal: Boolean = false,
)

data class PlayerState(
    val isInitialized: Boolean = false,
    val hasMedia: Boolean = false,
    val isLoading: Boolean = false,
    val isPaused: Boolean = true,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Int = 100,
    val isMuted: Boolean = false,
    val mediaTitle: String? = null,
    val isFullscreen: Boolean = false,
    val error: String? = null,
    val audioTracks: List<AudioTrack> = emptyList(),
    val selectedAudioId: Int = -1,
    val subtitleTracks: List<SubtitleTrackInfo> = emptyList(),
    val selectedSubtitleId: Int = -1,
    val subtitlesEnabled: Boolean = true,
    /** Live mpv `sub-delay` in milliseconds (positive delays subtitles). */
    val subtitleDelayMs: Long = 0L,
) {
    val isPlaying: Boolean get() = hasMedia && !isPaused && !isLoading
    val canSeek: Boolean get() = hasMedia && durationMs > 0L
}
