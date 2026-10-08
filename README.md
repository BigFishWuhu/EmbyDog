# EmbyDog

Native Android Emby client built with Kotlin, Jetpack Compose, AndroidX Media3, and libmpv.

The interface follows Material Design 3, including semantic color roles, dark mode,
standard component states and Android 12+ dynamic color.

## Current milestone

- Connect to an Emby server through its public system API.
- Authenticate with an Emby username and password.
- Protect the access token with Android Keystore and persist session metadata with DataStore.
- Provide a 2 GiB LRU Media3 playback cache foundation.
- Preserve reverse-proxy base paths when creating API URLs.
- Browse media libraries, continue-watching items, and recently added media.
- Cache authenticated poster images in memory and on disk.
- View media details and play through Media3 with embedded/external subtitle selection.
- Switch between Media3, mpv hardware decoding, and mpv software decoding without resetting playback position.
- Recover from local decoding failures through mpv, software decoding, and one server-transcoding attempt.
- Control both engines through a shared Material 3 overlay: seeking, pause/resume, speed, subtitles, and audio tracks.
- Pause when the player goes into the background and release native resources when leaving playback.
- Stream mpv video, HLS playlists, and subtitles through a private loopback gateway backed by OkHttp.
- Restrict the Emby authentication header to the server origin, including across redirects.
- Select audio/subtitle streams on the detail page before playback, and change them again in the player.
- Automatically rotate in the player or manually switch landscape/portrait; restore the prior orientation on exit.
- Swipe vertically on the left for player-only brightness, or on the right for media volume.
- Keep controls visible while dragging the seek bar or choosing settings.
- Load mpv external subtitles on demand on an independent IO thread, with cancellable downloads and failure messages.
- Search the Emby library with debounced requests, cancellation and pagination.
- Browse account favorites and add/remove favorites from detail pages through Emby's API.
- Choose seasons/episodes from series detail pages or from the player, with paginated episode lists.
- Start playback with mpv by default; Media3 remains a manually selectable alternative.
- Distinguish simplified/traditional/bilingual subtitles using language tags, titles and filenames; show format, source, flags and track index.
- Restore automatic audio/subtitle selection inside the player (Chinese subtitle preference, then server default/forced tracks).
- Swipe horizontally or drag the slider for target-time previews; commit the seek only on release.
- Show BIF thumbnails when the server provides them, using bounded Range requests and a small per-session cache.
- Show translucent brightness/volume/seek feedback and actual playback download speed.
- Use high-contrast icon-only player controls with smaller glyphs, 48+ dp touch targets and a compact single-row landscape layout.
- Show download speed only while the player controls are visible.
- Keep home/search/favorites as separate bottom-navigation tabs with retained state.
- Use smaller 2:3 posters with adaptive grid density and inline 16:9 episode thumbnails beneath a season dropdown.
- Save player entry orientation in application settings: landscape by default, portrait, gravity sensor, or system orientation.
- Preserve video aspect ratio in both playback engines and inset controls away from system bars/cutouts.
- Set a session-local sleep timer (1–1440 minutes) to pause or stop and exit the player, with presets, remaining time and cancellation.

## Requirements

- JDK 17 or newer
- Android SDK 36 (compile SDK; target SDK remains 35)
- Android 8.0 (API 26) or newer device/emulator

## Build

```shell
./gradlew testDebugUnitTest assembleDebug
```

To package only ARMv8 64-bit native libraries (Windows):

```shell
./gradlew.bat -PtargetAbi=arm64-v8a assembleDebug
```

The signed debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Omit `targetAbi` to keep the normal multi-architecture build.

Native initialization smoke tests can be built with `assembleDebugAndroidTest`.
Install both APKs on the intended test device, then run:

```shell
adb -s <device-serial> shell am instrument -w -r -e class com.embydog.app.core.media.MpvNativeSmokeTest com.embydog.app.test/androidx.test.runner.AndroidJUnitRunner
```

These tests verify native loading, configuration, and independent player instances;
they do not prove that a particular HEVC video or ASS subtitle renders correctly.

HTTP is currently permitted because many Emby servers are hosted on a local network without TLS. Internet-facing servers should use HTTPS.

## Next milestone

Add playback progress reporting, automatic next-episode playback, search filters, and offline downloads.

## Playback modes

Use the player settings button to select an engine. mpv is now the default.
Media3 uses the existing 2 GiB LRU playback cache. mpv uses bounded in-memory buffering;
it does not currently share Media3's disk cache. Neither buffer is a managed
offline download library.

mpv's compatibility mode tries Android MediaCodec through `mediacodec-copy` and
can fall back to FFmpeg decoding. The software mode disables hardware decoding.
Complex ASS subtitles use mpv's libass renderer, including embedded font attachments.
4K HEVC/Main10 software playback is not guaranteed to run smoothly on every device.
Native HDR/Dolby Vision output is not yet validated.

Decoder fallback is bounded. Network failures do not trigger repeated codec
switches or transcoding. Manual engine selection and retry reset the recovery
budget. Server-transcoding failures are shown to the user rather than retried
indefinitely. The current engine and decoding path are visible in the overlay.

Subtitle variants are metadata-based hints, not content/OCR analysis. Generic `zh`,
`chi` or `zho` is shown as Chinese with an unspecified script. Original titles,
subtitle filenames, codec, embedded/external source and the Emby stream index remain
visible so multiple Chinese tracks can be distinguished. Explicit selections bypass
the automatic policy and survive engine changes.

Horizontal gestures cover up to 120 seconds per screen width and clamp at media
boundaries. Previewing does not seek the main player until release. The BIF image is
a sampled frame, with its actual sample time shown separately from the target time.
Servers without BIF images (or multiple media versions without a reliable preview
mapping) retain time-only previews. Network failures in thumbnails never trigger
decoder fallback. Servers ignoring Range can provide one archive of at most 8 MiB;
larger responses are rejected. Only eight decoded thumbnail frames are cached.

Download speed is a one-second sample of bytes read for the current player's video,
HLS and subtitles, not the codec bitrate or system-wide network traffic. Cache hits
and periods where the buffer needs no additional data can correctly show zero.

## Native dependency and distribution

The pinned dependency is `dev.jdtech.mpv:libmpv:1.0.0` from Maven Central, maintained
at https://github.com/jarnedemeulemeester/libmpv-android.
The release includes mpv 0.41.0, FFmpeg 8.1, and libass 0.17.4.

The JNI wrapper's MIT license does not cover all bundled native libraries.
The upstream release build enables FFmpeg GPL/version3 components and does not
disable mpv's GPL features. Before publishing APKs, review the combined application's
license, source-distribution obligations, and notices. Do not describe this binary
as an LGPL-only build. A proprietary distribution would require a separately
audited compatible build and integration strategy.

## Playback acceptance checks

- Standard H.264/AAC playback still works through Media3 and its disk cache.
- The known failing HEVC/Main10 sample can be selected in both mpv modes.
- Embedded/external ASS subtitles load and appear in the subtitle selector.
- Switch engines after seeking and verify playback resumes at the same position.
- Pause, resume, speed, audio selection, and disabling subtitles work in each mode.
- Network loss and unsupported server transcoding produce a recoverable error.
- Leave playback, rotate, and background the app: no orphan audio, sockets, or native players.
- Verify actual hardware decoding, rendering, power consumption, and subtitle fonts on physical devices.
- Select a non-default audio/subtitle track on the detail page; verify both engines honor it, including after switching engines.
- Disable subtitles before playback; confirm no external subtitle is downloaded.
- Hold an external subtitle response open; verify video progress, seeking, pause, and resume still respond.
- Rotate automatically/manually during playback; verify playback survives and the seek bar remains usable.
- Swipe left/right video areas; verify brightness/volume feedback, and brightness restoration on exit.
- Swipe horizontally without releasing; verify the current video keeps playing while the target time changes.
- Confirm BIF images display their sampled time; a missing/invalid BIF must retain time-only seeking.
- Search Chinese keywords, load another results page, add/remove a favorite, and verify the account favorites list updates on return.
- Select a season and episode from detail/player; confirm the old player is released when changing episodes.
- Compare labels for simplified/traditional/bilingual/SDH subtitles and verify automatic selection can be restored after a manual choice.
