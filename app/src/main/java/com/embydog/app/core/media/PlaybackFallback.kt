package com.embydog.app.core.media

sealed interface PlaybackFallback {
    data class SwitchEngine(val kind: PlaybackEngineKind) : PlaybackFallback
    data object Transcode : PlaybackFallback
    data object Stop : PlaybackFallback
}

class PlaybackFallbackPolicy {
    private val attempted = mutableSetOf<PlaybackEngineKind>()
    private var triedTranscode = false

    fun record(kind: PlaybackEngineKind) {
        attempted.add(kind)
    }

    fun reset() {
        attempted.clear()
        triedTranscode = false
    }

    fun next(failure: PlaybackFailureKind, isTranscoded: Boolean): PlaybackFallback {
        if (failure == PlaybackFailureKind.NETWORK) return PlaybackFallback.Stop
        if (!isTranscoded) {
            if (PlaybackEngineKind.MPV !in attempted) {
                return PlaybackFallback.SwitchEngine(PlaybackEngineKind.MPV)
            }
            if (PlaybackEngineKind.MPV_SOFTWARE !in attempted &&
                failure in setOf(PlaybackFailureKind.DECODER, PlaybackFailureKind.UNKNOWN)
            ) {
                return PlaybackFallback.SwitchEngine(PlaybackEngineKind.MPV_SOFTWARE)
            }
        }
        if (!triedTranscode && !isTranscoded) {
            triedTranscode = true
            return PlaybackFallback.Transcode
        }
        return PlaybackFallback.Stop
    }
}
