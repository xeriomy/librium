# AGENTS.md – Librium

Librium is a Kotlin Android video player (Jetpack Compose) over libmpv. Phase 1 scaffold has landed — see structure below.

## Stack

- Kotlin 2.1.0 + AGP 8.13.0, Gradle wrapper (`./gradlew`). Do NOT upgrade/downgrade AGP, Kotlin, Gradle, compileSdk (36), targetSdk (34) without reason.
- Playback backend: `dev.jdtech.mpv:libmpv:1.0.0` (prebuilt AAR with native `.so`, Maven Central). No NDK build step in this repo.
- UI: single-screen Compose (`PlayerScreen`); state via `StateFlow` + `collectAsStateWithLifecycle`.

## Structure (`com.librium`)

- `player/` — `PlayerState`, `PlayerEngine` (interface), `MpvPlayerEngine` (libmpv impl), `PlayerController`/`DefaultPlayerController`. UI talks to `PlayerViewModel` → `PlayerController`, never to `MPVLib` directly.
- `media/` — `MediaResolver` (SAF helpers, no broad storage access).
- `subtitle/` — `SubtitleDocument/Event/Style/Track`, `SubtitleParser` (+ SRT/VTT/ASS impls), `SubtitleAnalyzer`/`SubtitleSynchronizer` (Phase 2 interfaces only, no impl).
- `ui/player/` — `PlayerScreen`, `MpvVideoSurface` (SurfaceView), `PlayerViewModel` (activity-scoped, owns engine).
- `MainActivity` hosts `PlayerScreen` only. No navigation yet.

## libmpv gotchas

- `MPVLib` is instance-based in 1.0.0: `MPVLib.create(ctx)` → `setOptionString(...)` → `init()`; all calls off the main thread (engine uses `Dispatchers.IO`).
- Track ids are mpv ids, `-1` = off. Subtitle on/off uses `sub-visibility`; selection uses `sid`.
- Content URIs from SAF are passed to mpv as strings (`loadfile`, `sub-add`).
- AAR is ~45 MB; do not commit `.so` files; no `abiFilters` set (App Bundle handles splits).

## Commands

- `./gradlew assembleDebug` — debug APK
- `./gradlew testDebugUnitTest` — unit tests (subtitle parsers)
- `./gradlew testDebugUnitTest --tests "com.librium.subtitle.SubtitleParserTest"` — single class
- CI: `.github/workflows/android.yml` runs assemble + unit tests. Local builds need Android SDK (`ANDROID_HOME`); never commit `local.properties`.

## Workflow

- No branch/PR/commit conventions. Keep changes small; keep `subtitle/` isolated from UI/playback; no advanced ASS renderer (libmpv/libass handles display).
