package com.embydog.app.core.media

internal fun mpvOptions(kind: PlaybackEngineKind, playWhenReady: Boolean): Map<String, String> = mapOf(
    "config" to "no",
    "vo" to "gpu",
    "gpu-context" to "android",
    "gpu-api" to "opengl",
    "keepaspect" to "yes",
    "panscan" to "0",
    "hwdec" to if (kind == PlaybackEngineKind.MPV_SOFTWARE) "no" else "mediacodec-copy",
    "ao" to "audiotrack",
    "cache" to "yes",
    "demuxer-max-bytes" to "32MiB",
    "demuxer-max-back-bytes" to "8MiB",
    "sub-fonts-dir" to "/system/fonts",
    "msg-level" to "all=no",
    "idle" to "yes",
    "keep-open" to "yes",
    "pause" to if (playWhenReady) "no" else "yes",
)
