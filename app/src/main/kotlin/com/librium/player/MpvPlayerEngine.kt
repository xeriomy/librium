package com.librium.player

import android.content.Context
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * libmpv backend over `dev.jdtech.mpv:libmpv` (prebuilt AAR with native
 * `.so` files — no NDK build step in this repo).
 *
 * All mpv calls run off the main thread. The UI never sees [MPVLib];
 * it only consumes [state].
 */
class MpvPlayerEngine(
    context: Context,
) : PlayerEngine, MPVLib.EventObserver {

    private val appContext = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    @Volatile
    private var mpv: MPVLib? = null

    @Volatile
    private var observeRegistered = false

    private var pollJob: Job? = null

    override fun initialize() {
        if (mpv != null) return
        scope.launch {
            try {
                val instance = MPVLib.create(appContext)
                if (instance == null) {
                    _state.update {
                        it.copy(error = "Could not create libmpv instance")
                    }
                    return@launch
                }
                applyInitialOptions(instance)
                instance.init()
                instance.addObserver(this@MpvPlayerEngine)
                observeRegistered = true
                observeProperties(instance)
                mpv = instance
                _state.update { it.copy(isInitialized = true, error = null) }
                startPolling()
            } catch (t: Throwable) {
                _state.update {
                    it.copy(error = "Player init failed: ${t.message}")
                }
            }
        }
    }

    override fun release() {
        pollJob?.cancel()
        pollJob = null
        scope.launch {
            try {
                mpv?.let {
                    if (observeRegistered) {
                        runCatching { it.removeObserver(this@MpvPlayerEngine) }
                        observeRegistered = false
                    }
                    runCatching { it.destroy() }
                }
            } catch (_: Throwable) {
                // Best effort during teardown.
            } finally {
                mpv = null
                scope.cancel()
            }
        }
    }

    override fun openVideo(uri: String) {
        _state.update {
            it.copy(
                isLoading = true,
                hasMedia = false,
                positionMs = 0L,
                durationMs = 0L,
                error = null,
            )
        }
        mpvCommand(arrayOf("loadfile", uri, "replace"))
    }

    override fun play() = setPaused(false)

    override fun pause() = setPaused(true)

    override fun togglePlayPause() {
        mpvCommand(arrayOf("cycle", "pause"))
    }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        scope.launch {
            val m = mpv ?: return@launch
            runCatching {
                m.setPropertyDouble("time-pos", target / 1000.0)
            }.onFailure { e ->
                _state.update { it.copy(error = "Seek failed: ${e.message}") }
            }
        }
    }

    override fun seekBy(deltaMs: Long) {
        seekTo((_state.value.positionMs + deltaMs).coerceAtLeast(0L))
    }

    override fun setVolume(volume01: Int) {
        val v = volume01.coerceIn(0, 100)
        scope.launch {
            val m = mpv ?: return@launch
            runCatching {
                m.setPropertyDouble("volume", v.toDouble())
                if (v > 0) m.setPropertyBoolean("mute", false)
            }
        }
    }

    override fun toggleMute() {
        scope.launch {
            val m = mpv ?: return@launch
            runCatching {
                val muted = m.getPropertyBoolean("mute") ?: false
                m.setPropertyBoolean("mute", !muted)
            }
        }
    }

    override fun selectAudioTrack(mpvId: Int) {
        scope.launch {
            val m = mpv ?: return@launch
            runCatching {
                if (mpvId < 0) m.setPropertyString("aid", "no")
                else m.setPropertyInt("aid", mpvId)
            }
        }
    }

    override fun selectSubtitleTrack(mpvId: Int?) {
        scope.launch {
            val m = mpv ?: return@launch
            runCatching {
                if (mpvId == null || mpvId < 0) {
                    m.setPropertyString("sid", "no")
                } else {
                    m.setPropertyInt("sid", mpvId)
                    m.setPropertyBoolean("sub-visibility", true)
                }
                refreshTracks(m)
            }
        }
    }

    override fun setSubtitlesEnabled(enabled: Boolean) {
        scope.launch {
            val m = mpv ?: return@launch
            runCatching {
                m.setPropertyBoolean("sub-visibility", enabled)
            }
        }
    }

    override fun addExternalSubtitle(uri: String) {
        mpvCommand(arrayOf("sub-add", uri, "select"))
    }

    override fun setSubtitleDelay(delayMs: Long) {
        scope.launch {
            val m = mpv ?: return@launch
            runCatching {
                m.setPropertyDouble("sub-delay", delayMs / 1000.0)
            }
        }
    }

    override fun attachSurface(surface: Surface) {
        scope.launch {
            runCatching { mpv?.attachSurface(surface) }
        }
    }

    override fun detachSurface() {
        scope.launch {
            runCatching { mpv?.detachSurface() }
        }
    }

    // --- MPVLib.EventObserver (called on mpv's event thread) ---

    override fun eventProperty(property: String) {
        // MPV_FORMAT_NONE notifications (e.g. track-list).
        if (property == "track-list") {
            mpv?.let { refreshTracks(it) }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        // Currently unused (time-pos/duration observed as double).
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> _state.update {
                it.copy(positionMs = (value * 1000).toLong().coerceAtLeast(0L))
            }
            "duration" -> _state.update {
                it.copy(durationMs = (value * 1000).toLong().coerceAtLeast(0L))
            }
            "volume" -> _state.update {
                it.copy(volume = value.toInt().coerceIn(0, 100))
            }
            "sub-delay" -> _state.update {
                it.copy(subtitleDelayMs = (value * 1000).toLong())
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> _state.update { it.copy(isPaused = value, isLoading = false) }
            "mute" -> _state.update { it.copy(isMuted = value) }
            "sub-visibility" -> _state.update { it.copy(subtitlesEnabled = value) }
            "paused-for-cache" -> _state.update {
                if (value) it.copy(isLoading = true) else it
            }
            "eof-reached" -> if (value) {
                _state.update { it.copy(isPaused = true) }
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        when (property) {
            "aid" -> _state.update { it.copy(selectedAudioId = value.toIntOrNull() ?: -1) }
            "sid" -> _state.update { it.copy(selectedSubtitleId = value.toIntOrNull() ?: -1) }
            "media-title" -> _state.update { it.copy(mediaTitle = value) }
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                _state.update { it.copy(hasMedia = true, isLoading = false, error = null) }
                mpv?.let { refreshTracks(it) }
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                _state.update { it.copy(isLoading = false) }
            }
            MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> {
                _state.update { it.copy(error = "Playback engine shut down") }
            }
        }
    }

    // --- internals ---

    private fun setPaused(paused: Boolean) {
        scope.launch {
            val m = mpv ?: return@launch
            runCatching { m.setPropertyBoolean("pause", paused) }
        }
    }

    private fun mpvCommand(cmd: Array<String>) {
        scope.launch {
            val m = mpv
            if (m == null) {
                _state.update { it.copy(error = "Player not initialized yet") }
                return@launch
            }
            runCatching { m.command(cmd) }
                .onFailure { e ->
                    _state.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                val m = mpv ?: continue
                runCatching {
                    m.getPropertyDouble("time-pos")?.let { pos ->
                        _state.update {
                            it.copy(positionMs = (pos * 1000).toLong().coerceAtLeast(0L))
                        }
                    }
                    m.getPropertyDouble("duration")?.let { dur ->
                        _state.update {
                            it.copy(durationMs = (dur * 1000).toLong().coerceAtLeast(0L))
                        }
                    }
                }
            }
        }
    }

    private fun refreshTracks(m: MPVLib) {
        runCatching {
            val count = m.getPropertyInt("track-list/count") ?: return
            val audio = mutableListOf<AudioTrack>()
            val subs = mutableListOf<SubtitleTrackInfo>()
            for (i in 0 until count) {
                val type = m.getPropertyString("track-list/$i/type") ?: continue
                val id = m.getPropertyInt("track-list/$i/id") ?: continue
                val lang = m.getPropertyString("track-list/$i/lang").orEmpty()
                val title = m.getPropertyString("track-list/$i/title").orEmpty()
                val external = m.getPropertyString("track-list/$i/external") == "yes"
                val label = buildString {
                    append("#").append(id)
                    if (title.isNotBlank()) append(" ").append(title)
                    if (lang.isNotBlank()) append(" (").append(lang).append(")")
                    if (external) append(" [ext]")
                }
                when (type) {
                    "audio" -> audio.add(AudioTrack(mpvId = id, label = label))
                    "sub" -> subs.add(
                        SubtitleTrackInfo(mpvId = id, label = label, isExternal = external),
                    )
                }
            }
            val aid = m.getPropertyString("aid")?.toIntOrNull() ?: -1
            val sid = m.getPropertyString("sid")?.toIntOrNull() ?: -1
            val subVis = m.getPropertyBoolean("sub-visibility") ?: true
            _state.update {
                it.copy(
                    audioTracks = audio,
                    subtitleTracks = subs,
                    selectedAudioId = aid,
                    selectedSubtitleId = sid,
                    subtitlesEnabled = subVis,
                )
            }
        }
    }

    companion object {
        private const val POLL_MS = 500L

        /**
         * Minimal phone defaults adapted from mpv-android's MPVView.
         * Must be set before [MPVLib.init].
         */
        private fun applyInitialOptions(mpv: MPVLib) {
            mpv.setOptionString("vo", "gpu")
            mpv.setOptionString("gpu-context", "android")
            mpv.setOptionString("opengl-es", "yes")
            mpv.setOptionString("hwdec", "mediacodec,mediacodec-copy")
            mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            mpv.setOptionString("ao", "audiotrack,opensles")
            mpv.setOptionString("demuxer-max-bytes", (64 * 1024 * 1024).toString())
            mpv.setOptionString("demuxer-max-back-bytes", (64 * 1024 * 1024).toString())
            mpv.setOptionString("save-position-on-quit", "no")
            mpv.setOptionString("keep-open", "yes")
            mpv.setOptionString("tls-verify", "yes")
            mpv.setOptionString("input-default-bindings", "yes")
        }

        private fun observeProperties(mpv: MPVLib) {
            val f = MPVLib.MpvFormat
            mpv.observeProperty("time-pos", f.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("duration", f.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("pause", f.MPV_FORMAT_FLAG)
            mpv.observeProperty("volume", f.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("mute", f.MPV_FORMAT_FLAG)
            mpv.observeProperty("aid", f.MPV_FORMAT_STRING)
            mpv.observeProperty("sid", f.MPV_FORMAT_STRING)
            mpv.observeProperty("sub-visibility", f.MPV_FORMAT_FLAG)
            mpv.observeProperty("sub-delay", f.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("track-list", f.MPV_FORMAT_NONE)
            mpv.observeProperty("media-title", f.MPV_FORMAT_STRING)
            mpv.observeProperty("eof-reached", f.MPV_FORMAT_FLAG)
            mpv.observeProperty("paused-for-cache", f.MPV_FORMAT_FLAG)
        }
    }
}
