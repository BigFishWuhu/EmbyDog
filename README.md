# EmbyDog

Native Android Emby client built with Kotlin, Jetpack Compose, AndroidX Media3, and libmpv.

The interface follows Material Design 3, including semantic color roles, dark mode,
standard component states and Android 12+ dynamic color.

## Current milestone

- Connect to an Emby server through its public system API.
- Authenticate with an Emby username and password.
- Protect the access token with Android Keystore and persist session metadata with DataStore.
- Save multiple server/account profiles, migrate the legacy login automatically, restore the last active profile and switch without re-entering credentials.
- Isolate navigation/media ViewModels, authenticated poster cache keys and Media3 cache keys across profiles; removing a profile only removes its local login.
- Manually check the latest stable GitHub Release from application settings, select ARM64/universal APKs, download with progress/cancellation, and request system installation.
- Reject updates with missing/mismatched SHA256, incorrect package/version, non-increasing version codes or a different signing certificate; restrict GitHub redirects to trusted HTTPS hosts.
- Provide a 2 GiB LRU Media3 playback cache foundation.
- Preserve reverse-proxy base paths when creating API URLs.
- Browse media libraries, continue-watching items, and recently added media.
- Use larger adaptive home-shelf posters with readable two-line titles; search and favorites retain compact grids.
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
- Use 2:3 posters with adaptive grid density and inline 16:9 episode thumbnails beneath a season dropdown.
- Save player entry orientation in application settings: landscape by default, portrait, gravity sensor, or system orientation.
- Preserve video aspect ratio in both playback engines and inset controls away from system bars/cutouts.
- Set a session-local sleep timer (1–1440 minutes) to pause or stop and exit the player, with presets, remaining time and cancellation.
- Play episode cards directly and use a resume-aware detail play button (resumable episode, next-up, then first regular-season episode).
- Keep browse/favorite card covers uniformly portrait-shaped; landscape episode art is letterboxed instead of changing row height.
- Report authenticated Emby playback start, 10-second progress heartbeats and stopped positions; pause/background/seek send immediate updates.
- Send final playback reports on an application-scoped queue after navigation disposal, with bounded retries and a short account-isolated in-memory resume bridge.

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

## GitHub Release workflow

`.github/workflows/release.yml` builds signed, minified Release APKs when a `vX.Y.Z`
tag is pushed. Tags such as `v0.2.0-rc.1` create a prerelease. You can also select
**Actions → Android Release → Run workflow** and enter an existing tag; the workflow
builds that tag, not the current branch. An existing Release is never overwritten.

Configure these repository **Settings → Secrets and variables → Actions** secrets first:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded contents of your persistent release `.jks` keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Signing key password |

Generate and securely back up one release key using Android Studio's **Generate
Signed Bundle / APK** flow. Keep using the same key for updates. Keystores are
ignored by Git; never commit the key, its Base64 representation or passwords.
The workflow fails if a signing secret is missing and deletes the temporary key
after the build. Release-signed APKs cannot upgrade an installed debug-signed APK;
the initial switch requires an uninstall (which deletes application data).

On Windows, copy the keystore's Base64 representation to the clipboard without
printing it in the terminal (replace the example path with your own):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\secure\embydog-release.jks')) | Set-Clipboard
```

Push a version tag after committing and pushing the desired source:

```shell
git tag -a v0.1.0 -m "Release v0.1.0"
git push origin v0.1.0
```

The workflow runs unit tests and Release lint, verifies APK signatures, version
metadata and native ABIs, then uploads ARM64 and universal APKs, the R8 `mapping.txt`
and `SHA256SUMS.txt`. Release notes are generated from GitHub's change history.
Device/live-server tests are not run on hosted runners. `versionName` comes from
the tag; `versionCode` is the workflow run number plus one, so subsequent releases
increase it (do not recreate the workflow or reset its run-number history).

Local release signing uses `EMBYDOG_KEYSTORE_PATH`, `EMBYDOG_KEYSTORE_PASSWORD`,
`EMBYDOG_KEY_ALIAS` and `EMBYDOG_KEY_PASSWORD` environment variables. Version
overrides are `-PreleaseVersionName=0.1.0 -PreleaseVersionCode=2`. Without signing
environment variables, local Release builds remain unsigned. The workflow does
not publish unsigned APKs. Before public distribution, complete the native-library
license/source-distribution review described below; automation does not satisfy it.

## Profiles and in-app updates

Use the home account icon or **Settings → Profiles** to add/switch server accounts.
The server URL and Emby user ID identify a profile, so different servers/accounts
do not overwrite each other. Tokens remain encrypted using Android Keystore;
passwords are not persisted. Removing/logging out of the active profile selects
the most recently used remaining profile, or returns to login when none remain.
Playback orientation settings are application-wide, not per-profile.

**Settings → Check update** queries `BigFishWuhu/EmbyDog` on GitHub only when asked.
Only stable newer semantic versions are offered. Release APK names and checksums
must match the release workflow above. A repository without a stable Release shows
an explanatory message. APK downloads are bounded to 256 MiB, cancellable and do
not send any Emby credentials. Installation requires Android's permission to install
from this source and confirmation in the system installer; it is never silent.
Downloads do not resume after process death. Debug-signed installs cannot update
to a different release certificate; mismatched APKs are rejected without uninstalling
the existing app or clearing profiles. Publishing the first signed Release and
preserving its certificate is required before production in-app updates can work.

## Next milestone

Add automatic next-episode playback, search filters, durable offline progress synchronization, and offline downloads.

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

## 弹弹 Play 弹幕

- 应用设置 → 弹弹 Play 弹幕：填写自己申请的 AppId / AppSecret。接入申请见官方开发者中心 https://dev.dandanplay.com ，协议见 https://github.com/kaedei/dandanplay-doc/blob/master/docs/open/README.md 。没有内置其他应用的密钥，AppSecret 使用 Android Keystore 加密保存且不参与系统备份。
- 播放器的弹幕图标打开紧凑的 Material 面板。显示设置参考 B 站：不透明度、字号、速度、1/4／半屏／3/4／全屏区域、密度、滚动／顶部／底部类型屏蔽、彩色显示、避开底部字幕区域、时间偏移和恢复默认值。设置全应用共享并持久保存。
- “匹配弹幕”中可根据作品名、集数搜索并手动选择弹幕库；需核对季度与视频版本。关联按 Emby 服务器／用户／视频隔离，下次播放自动加载已关联弹幕。不进行猜测式自动匹配，也不下载视频的前 16 MiB 来计算文件哈希。
- 使用官方 API v2 路径签名和新版搜索；独立 HTTP 客户端不携带 Emby 凭据。初始 API 请求签名，CDN 重定向不转发接入凭据，并只接受弹弹 Play 域名的 HTTPS 地址。发送给第三方的数据仅为用户输入的作品名／集数、选择的弹幕库编号及接入签名。
- 支持滚动、顶部和底部弹幕。Canvas 覆盖在 Media3／mpv 视频层上，不拦截播放器手势；以播放器媒体时间驱动，暂停、缓冲、倍速与 seek 同步，不影响音轨和字幕。碰撞避让会舍弃过密弹幕，不承诺显示每条弹幕。
- 弹幕缓存按接入凭据隔离，有效期 7 天，最多 20 份／64 MiB；单次响应上限 12 MiB、最多保留 20000 条弹幕。支持手动刷新；关闭弹幕会取消正在加载的弹幕请求。此版本不支持发送弹幕、文件哈希自动匹配或第三方地址手动导入。

## 离线缓存

- 影片详情页可以添加原始文件缓存；剧集卡片中的“缓存”可下载指定单集。首页工具栏、应用设置和登录页均有离线缓存入口，服务器不可达或登录凭据失效不影响已完成缓存的播放。
- 轻量前台下载服务串行处理队列，显示通知、进度和网速。支持暂停、继续、重试和确认删除，默认仅 Wi-Fi；网络切换不符合设置时暂停。进程被系统结束、后台服务到时或手机重启后保留断点，重新打开应用可手动继续，不保证自动唤醒下载。
- 优先使用官方 `/Items/{Id}/Download` 原文件接口，服务器的账号下载权限仍然生效。当前仅支持默认媒体版本，不缓存直播、HLS、服务器在线转码或受保护媒体。断点需有可靠 ETag / Last-Modified 与 Content-Range；服务器忽略 Range 或无校验标识时从头下载，不会拼接不同版本文件。
- 原始文件和外置文本字幕保存在应用私有持久目录，不受播放器临时 LRU 缓存清理影响。内嵌音轨、字幕保留在原文件中；外置字幕单份上限 16 MiB，失败会在列表提示。仅接受同服务器下载地址和重定向，不将账号令牌转发给第三方。
- 缓存清单不保存令牌、密码或认证下载 URL；默认按当前 profile 筛选，也可选择其他本机缓存。移除原 profile 会停止其下载，但不会删除已缓存视频；恢复未完成任务需要重新添加原账号。
- 完成的缓存直接通过 mpv / Media3 读取本地视频和字幕，不调用详情、PlaybackInfo、服务器转码、预览图或播放进度上报接口。离线续播记录仅保存在本机，暂不补报服务器；离线拖动提供时间预览，弹幕只读取已存在的临时缓存，不联网搜索或刷新。
- 删除缓存会移除该视频的本机视频、字幕与离线续播位置，不影响 Emby 原文件。卸载应用或清除应用数据也会删除离线文件。

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
