package com.librium.player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.librium.core.LibLog
import com.librium.media.MediaResolver
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

    private val lifecycle = EngineLifecycle()
    private val seekTracker = SeekTracker()
    private val positionThrottle = PositionThrottle()

    /**
     * True while a loadfile has no FILE_LOADED yet. Lets END_FILE tell a
     * failed open (report it) apart from a natural end or a superseded
     * file during replace (stay quiet).
     */
    @Volatile
    private var loadPending = false

    /** The exact mpv URI string sent for the pending load, for attribution. */
    @Volatile
    private var pendingUri: String? = null

    /**
     * Descriptors backing `fd://` playback. The previous file's descriptor
     * must stay open until mpv confirms the new file (or the load dies),
     * so closes happen only on FILE_LOADED, failure, or release — never on
     * open. Guarded by its own monitor; touched from IO coroutines and the
     * mpv event thread, never the main thread.
     */
    private val mediaPfds = ArrayDeque<ParcelFileDescriptor>()

    /** Descriptors backing `fd://` external subtitles; cleared per video. */
    private val subtitlePfds = ArrayDeque<ParcelFileDescriptor>()

    /**
     * Surface attach/detach runs on a dedicated serial worker so the
     * ordered SurfaceHolder callbacks (destroy-old, create-new) stay
     * ordered through the off-main-thread hop. Anything else would let a
     * delayed detach kill a fresh attach after rotation.
     */
    private val surfaceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val surfaces = SurfaceAttachment(
        surfaceScope,
        object : SurfaceAttachment.Backend {
            override fun hasInstance(): Boolean = mpv != null
            override fun attachNative(surface: Any) {
                (mpv ?: error("no mpv instance")).attachSurface(surface as Surface)
            }
            override fun detachNative() {
                (mpv ?: error("no mpv instance")).detachSurface()
            }
        },
    )

    private var pollJob: Job? = null

    override fun initialize() {
        if (!lifecycle.tryBeginInit()) {
            LibLog.d(LibLog.MPV) { "initialize refused (already started)" }
            return
        }
        LibLog.i(LibLog.MPV) { "initializing libmpv" }
        scope.launch {
            try {
                val instance = MPVLib.create(appContext)
                if (instance == null) {
                    lifecycle.markInitFailed()
                    _state.update {
                        it.copy(error = "Could not create libmpv instance")
                    }
                    return@launch
                }
                applyInitialOptions(instance)
                instance.init()
                if (lifecycle.isReleased()) {
                    // Released while starting: destroy the orphan, publish nothing.
                    runCatching { instance.destroy() }
                    LibLog.w(LibLog.MPV) { "init finished after release; orphan destroyed" }
                    return@launch
                }
                instance.addObserver(this@MpvPlayerEngine)
                observeRegistered = true
                observeProperties(instance)
                mpv = instance
                lifecycle.markReady()
                _state.update {
                    it.copy(
                        isInitialized = true,
                        error = null,
                        subtitleAppearance = readSubtitleAppearance(instance),
                    )
                }
                LibLog.i(LibLog.MPV) { "libmpv ready" }
                startPolling()
            } catch (t: Throwable) {
                lifecycle.markInitFailed()
                LibLog.e(LibLog.MPV, t) { "init failed" }
                _state.update {
                    it.copy(error = "Player init failed: ${t.message}")
                }
            }
        }
    }

    override fun release() {
        if (!lifecycle.tryBeginRelease()) {
            LibLog.d(LibLog.MPV) { "release refused (already released)" }
            return
        }
        LibLog.i(LibLog.MPV) { "releasing libmpv" }
        pollJob?.cancel()
        pollJob = null
        surfaceScope.cancel()
        surfaces.reset()
        loadPending = false
        pendingUri = null
        dropAllMediaPfds()
        dropAllSubtitlePfds()
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
        LibLog.i(LibLog.PLAYER) { "openVideo" }
        positionThrottle.reset()
        loadPending = true
        // Spinner first; media fields reset only once the target actually
        // resolves, so a failed resolve leaves a playing video untouched.
        _state.update { it.copy(isLoading = true, error = null) }
        // Resolution does provider IO, so it runs after the synchronous
        // state update above (the spinner shows immediately).
        scope.launch {
            if (mpv == null) {
                loadPending = false
                pendingUri = null
                _state.update {
                    it.copy(isLoading = false, error = "Player not initialized yet")
                }
                return@launch
            }
            val target = resolveMpvTarget(uri)
            if (target == null) {
                loadPending = false
                pendingUri = null
                LibLog.w(LibLog.MPV) { "unresolvable media uri" }
                _state.update { it.copy(isLoading = false, error = VIDEO_OPEN_FAILED_MESSAGE) }
                return@launch
            }
            // A new video drops previous external subtitles, descriptors
            // included; mpv forgets them on loadfile anyway.
            dropAllSubtitlePfds()
            _state.update {
                it.copy(hasMedia = false, positionMs = 0L, durationMs = 0L)
            }
            pendingUri = target.mpvUri
            adoptMediaPfd(target.pfd)
            mpvCommand(arrayOf("loadfile", target.mpvUri, "replace"))
        }
    }

    override fun play() = setPaused(false)

    override fun pause() = setPaused(true)

    override fun togglePlayPause() {
        mpvCommand(arrayOf("cycle", "pause"))
    }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        LibLog.i(LibLog.PLAYER) { "seek requested to ${target}ms (exact)" }
        seekTracker.onSeekRequested(target, "absolute-exact")
        positionThrottle.reset()
        scope.launch {
            val m = mpv ?: return@launch
            runCatching {
                m.setPropertyDouble("time-pos", target / 1000.0)
            }.onFailure { e ->
                LibLog.e(LibLog.MPV, e) { "seek failed" }
                _state.update { it.copy(error = "Seek failed: ${e.message}") }
            }
        }
    }

    override fun seekBy(deltaMs: Long) {
        // Relative keyframe seek: the demuxer jumps straight to the nearest
        // keyframe without the exact-seek forward-decode pass, which keeps
        // ±10s skips fast on local files. No base position is needed, so a
        // stale polled position can never compound across rapid presses.
        // Slider scrubbing still uses exact absolute seeks via seekTo.
        val command = SeekCommands.relativeSkip(deltaMs)
        LibLog.i(LibLog.PLAYER) { "seek requested by ${deltaMs}ms (relative+keyframes)" }
        seekTracker.onSeekRequested(
            (_state.value.positionMs + deltaMs).coerceAtLeast(0L),
            "relative+keyframes",
        )
        positionThrottle.reset()
        scope.launch {
            val m = mpv ?: return@launch
            LibLog.d(LibLog.MPV) { "seek command sent" }
            runCatching { m.command(command) }
                .onFailure { e ->
                    LibLog.e(LibLog.MPV, e) { "seek failed" }
                    _state.update { it.copy(error = "Seek failed: ${e.message}") }
                }
        }
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
        scope.launch {
            if (mpv == null) return@launch
            val target = resolveMpvTarget(uri)
            if (target == null) {
                LibLog.w(LibLog.MPV) { "unresolvable subtitle uri" }
                _state.update { it.copy(error = SUBTITLE_LOAD_FAILED_FALLBACK) }
                return@launch
            }
            // mpv parses the file when it executes sub-add, so a descriptor
            // must outlive this coroutine: hold it until the next video.
            if (target.pfd != null) {
                synchronized(subtitlePfds) {
                    subtitlePfds.addLast(target.pfd)
                    while (subtitlePfds.size > MAX_HELD_SUB_PFDS) {
                        runCatching { subtitlePfds.removeFirst().close() }
                    }
                }
            }
            mpvCommand(arrayOf("sub-add", target.mpvUri, "select"))
        }
    }

    override fun setSubtitleDelay(delayMs: Long) {
        scope.launch {
            val m = mpv ?: return@launch
            runCatching {
                m.setPropertyDouble("sub-delay", delayMs / 1000.0)
            }
        }
    }

    override fun setSubtitleAppearance(appearance: SubtitleAppearance) {
        scope.launch {
            val m = mpv ?: return@launch
            // Each property is applied independently: one unknown or
            // rejected property must not skip the rest.
            val ops: List<() -> Unit> = listOf(
                { m.setPropertyDouble("sub-font-size", appearance.fontSize.toDouble()) },
                { m.setPropertyString("sub-color", argbToMpvColor(appearance.textColor)) },
                { m.setPropertyDouble("sub-border-size", appearance.outlineSize.toDouble()) },
                { m.setPropertyString("sub-border-color", argbToMpvColor(appearance.outlineColor)) },
                { m.setPropertyString("sub-back-color", argbToMpvColor(appearance.backgroundColor)) },
                { m.setPropertyDouble("sub-shadow-offset", appearance.shadowOffset.toDouble()) },
                { m.setPropertyString("sub-shadow-color", argbToMpvColor(appearance.shadowColor)) },
                { m.setPropertyBoolean("sub-bold", appearance.bold) },
                { m.setPropertyBoolean("sub-italic", appearance.italic) },
                { m.setPropertyString("sub-align-x", appearance.alignX.name.lowercase()) },
                { m.setPropertyString("sub-align-y", appearance.alignY.name.lowercase()) },
                { m.setPropertyInt("sub-margin-x", appearance.marginX) },
                { m.setPropertyInt("sub-margin-y", appearance.marginY) },
                { m.setPropertyInt("sub-pos", appearance.position) },
            )
            ops.forEach { op ->
                runCatching(op).onFailure { e ->
                    LibLog.e(LibLog.MPV, e) { "subtitle appearance set failed" }
                }
            }
            _state.update { it.copy(subtitleAppearance = appearance) }
        }
    }

    override fun attachSurface(surface: Surface) {
        surfaces.attach(surface)
    }

    override fun detachSurface() {
        surfaces.detach()
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
            "time-pos" -> notePosition((value * 1000).toLong().coerceAtLeast(0L))
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
            // Only live media clears the spinner: a pause event arriving
            // before FILE_LOADED must not drop the visibility gate.
            "pause" -> _state.update {
                it.copy(isPaused = value, isLoading = if (it.hasMedia) false else it.isLoading)
            }
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
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                LibLog.d(LibLog.MPV) { "start-file received" }
                _state.update { it.copy(isLoading = true) }
            }
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                loadPending = false
                pendingUri = null
                commitMediaPfd()
                LibLog.d(LibLog.MPV) { "file loaded" }
                _state.update { it.copy(hasMedia = true, isLoading = false, error = null) }
                mpv?.let { refreshTracks(it) }
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                val action = endFileAction(
                    loadPending = loadPending,
                    currentPath = mpv?.getPropertyString("path"),
                    pendingUri = pendingUri,
                )
                when (action) {
                    EndFileAction.IGNORE -> {
                        LibLog.d(LibLog.MPV) { "end-file for superseded load; spinner stays" }
                    }
                    EndFileAction.CLEAR_LOADING -> {
                        _state.update { it.copy(isLoading = false) }
                    }
                    EndFileAction.REPORT_FAILURE -> {
                        loadPending = false
                        pendingUri = null
                        dropAllMediaPfds()
                        LibLog.w(LibLog.MPV) { "load failed before first frame" }
                        _state.update {
                            it.copy(isLoading = false, error = VIDEO_OPEN_FAILED_MESSAGE)
                        }
                    }
                }
            }
            MPVLib.MpvEvent.MPV_EVENT_SEEK -> {
                LibLog.d(LibLog.MPV) { "seek event received" }
            }
            MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> {
                _state.update { it.copy(error = "Playback engine shut down") }
            }
        }
    }

    // --- internals ---

    /** Resolved mpv-openable target plus an optionally held descriptor. */
    private data class ResolvedMedia(
        val mpvUri: String,
        val pfd: ParcelFileDescriptor?,
    )

    /**
     * Translates an app URI into something this mpv build can actually
     * open. Verified against the bundled native libraries: ffmpeg here
     * has no `content` protocol, while mpv core supports `fd://`.
     * Non-content URIs (file, http(s), ...) pass through untouched.
     * Runs on IO; never throws (null = unresolvable).
     */
    private fun resolveMpvTarget(uriString: String): ResolvedMedia? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return null
        if (uri.scheme != "content") return ResolvedMedia(uriString, null)
        val pfd = MediaResolver.openContentFd(appContext.contentResolver, uri)
            ?: return null
        val real = MediaResolver.realPathOf(pfd.fd)
        if (real != null) {
            // File-backed provider: play the real file directly (fast,
            // fully seekable) and drop the descriptor immediately.
            runCatching { pfd.close() }
            LibLog.i(LibLog.MPV) { "content uri resolved to file path" }
            return ResolvedMedia(real, null)
        }
        LibLog.i(LibLog.MPV) { "no real path; playing via fd" }
        return ResolvedMedia("fd://${pfd.fd}", pfd)
    }

    /**
     * Holds a media descriptor, evicting the oldest beyond the cap. Never
     * closes on open: the previous file's descriptor may still back mpv
     * until the new FILE_LOADED commits the switch.
     */
    private fun adoptMediaPfd(pfd: ParcelFileDescriptor?) {
        if (pfd == null) return
        synchronized(mediaPfds) {
            mediaPfds.addLast(pfd)
            while (mediaPfds.size > MAX_HELD_MEDIA_PFDS) {
                runCatching { mediaPfds.removeFirst().close() }
            }
        }
    }

    /**
     * New file confirmed playing: only the newest held descriptor can
     * still back mpv; everything older belongs to superseded loads.
     * Keyed by recency, never by URI string, so mpv path normalization
     * can never trick us into closing the active descriptor.
     */
    private fun commitMediaPfd() {
        synchronized(mediaPfds) {
            while (mediaPfds.size > 1) {
                runCatching { mediaPfds.removeFirst().close() }
            }
        }
    }

    private fun dropAllMediaPfds() {
        synchronized(mediaPfds) {
            mediaPfds.forEach { runCatching { it.close() } }
            mediaPfds.clear()
        }
    }

    private fun dropAllSubtitlePfds() {
        synchronized(subtitlePfds) {
            subtitlePfds.forEach { runCatching { it.close() } }
            subtitlePfds.clear()
        }
    }

    /**
     * Reads the live libass appearance once the instance is up, so state
     * starts from real mpv values. Every read falls back to the factory
     * default, so a missing property can never break initialization.
     */
    private fun readSubtitleAppearance(m: MPVLib): SubtitleAppearance {
        val d = DEFAULT_SUBTITLE_APPEARANCE
        return runCatching {
            d.copy(
                fontSize = m.getPropertyDouble("sub-font-size")?.toFloat() ?: d.fontSize,
                textColor = mpvColorToArgb(m.getPropertyString("sub-color")) ?: d.textColor,
                outlineSize = m.getPropertyDouble("sub-border-size")?.toFloat() ?: d.outlineSize,
                outlineColor = mpvColorToArgb(m.getPropertyString("sub-border-color")) ?: d.outlineColor,
                backgroundColor = mpvColorToArgb(m.getPropertyString("sub-back-color")) ?: d.backgroundColor,
                shadowOffset = m.getPropertyDouble("sub-shadow-offset")?.toFloat() ?: d.shadowOffset,
                shadowColor = mpvColorToArgb(m.getPropertyString("sub-shadow-color")) ?: d.shadowColor,
                bold = m.getPropertyBoolean("sub-bold") ?: d.bold,
                italic = m.getPropertyBoolean("sub-italic") ?: d.italic,
                alignX = m.getPropertyString("sub-align-x")?.let(::parseAlignX) ?: d.alignX,
                alignY = m.getPropertyString("sub-align-y")?.let(::parseAlignY) ?: d.alignY,
                marginX = m.getPropertyInt("sub-margin-x") ?: d.marginX,
                marginY = m.getPropertyInt("sub-margin-y") ?: d.marginY,
                position = m.getPropertyInt("sub-pos") ?: d.position,
            )
        }.getOrDefault(d)
    }

    /**
     * Single funnel for observed positions: confirms pending seeks for
     * latency logging, then throttles state emission so per-frame mpv
     * events cannot recompose the UI at refresh rate.
     */
    private fun notePosition(positionMs: Long) {
        seekTracker.onPositionChanged(positionMs)?.let { landing ->
            LibLog.i(LibLog.MPV) {
                "seek completed (${landing.mode}): target=${landing.targetMs}ms " +
                    "landed=${landing.landedMs}ms in ${landing.latencyMs}ms"
            }
        }
        if (positionThrottle.shouldEmit(positionMs)) {
            _state.update { it.copy(positionMs = positionMs) }
        }
    }

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
                    LibLog.e(LibLog.MPV, e) { "command ${cmd.firstOrNull()} failed" }
                    // Subtitle loading must never take playback down with it:
                    // keep the player alive on the current valid state.
                    val message = if (cmd.firstOrNull() == "sub-add") {
                        SUBTITLE_LOAD_FAILED_FALLBACK
                    } else {
                        e.message
                    }
                    _state.update { it.copy(isLoading = false, error = message) }
                }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                val m = mpv ?: continue
                // No JNI traffic while idle or paused: the observed
                // properties already cover those states, and StateFlow
                // dedupes identical values anyway.
                val snap = _state.value
                if (!snap.hasMedia || snap.isPaused) continue
                runCatching {
                    m.getPropertyDouble("time-pos")?.let { pos ->
                        notePosition((pos * 1000).toLong().coerceAtLeast(0L))
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
                when (type) {
                    "audio" -> audio.add(AudioTrack(mpvId = id, label = audioTrackLabel(id, lang, title)))
                    "sub" -> subs.add(
                        SubtitleTrackInfo(
                            mpvId = id,
                            label = subtitleTrackLabel(id, lang, title, external),
                            isExternal = external,
                        ),
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

        /** Bounds descriptors held across rapid replace churn. */
        private const val MAX_HELD_MEDIA_PFDS = 8
        private const val MAX_HELD_SUB_PFDS = 8

        /** Shown when a video cannot be opened at all (stays on player). */
        private const val VIDEO_OPEN_FAILED_MESSAGE =
            "Could not open this video. The file may be unsupported or unreadable."

        /**
         * Matches the subtitle error wording without coupling player to the
         * subtitle package: a failed sub-add keeps the current valid state.
         */
        private const val SUBTITLE_LOAD_FAILED_FALLBACK =
            "Could not load subtitle. Your current subtitle was not changed."

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
            // Never stretch: keep the source aspect ratio and letterbox /
            // pillarbox (this is also mpv's default; stated explicitly so a
            // future option change cannot silently break it).
            mpv.setOptionString("video-aspect-override", "no")
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
