package com.embydog.app.core.media

import com.embydog.app.core.model.MediaTrackKind
import com.embydog.app.core.model.MediaTrackOption
import org.junit.Assert.*
import org.junit.Test

class MediaTrackMappingTest {
    private val options = listOf(
        MediaTrackOption(1, MediaTrackKind.AUDIO, "日语", "ja", false),
        MediaTrackOption(2, MediaTrackKind.AUDIO, "国语", "zh", false),
        MediaTrackOption(4, MediaTrackKind.SUBTITLE, "内嵌中文", "zh", false),
        MediaTrackOption(5, MediaTrackKind.SUBTITLE, "外挂中文", "zh", true),
        MediaTrackOption(7, MediaTrackKind.SUBTITLE, "内嵌英文", "en", false),
    )

    @Test fun mpvFfmpegIndexTakesPriorityOverTrackOrder() {
        assertEquals(2, mediaTrackForNative(options, PlaybackTrackKind.AUDIO, 0, 2)?.index)
    }

    @Test fun nativeTrackOrderDoesNotIncludeExternalSubtitles() {
        assertEquals(7, mediaTrackForNative(options, PlaybackTrackKind.SUBTITLE, 1)?.index)
    }

    @Test fun trackOrderIsPerTypeRatherThanGlobalStreamIndex() {
        assertEquals(2, mediaTrackForNative(options, PlaybackTrackKind.AUDIO, 1)?.index)
        assertEquals(4, mediaTrackForNative(options, PlaybackTrackKind.SUBTITLE, 0)?.index)
        assertNull(mediaTrackForNative(options, PlaybackTrackKind.SUBTITLE, 2))
    }
}
