package com.embydog.app.core.media

import com.embydog.app.core.model.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticTrackSelectionTest {
    private fun subtitle(index: Int, tag: String, default: Boolean = false, forced: Boolean = false) =
        MediaTrackOption(index, MediaTrackKind.SUBTITLE, tag, tag, false, isDefault = default, isForced = forced,
            subtitleMetadata = SubtitleMetadata(index = index, language = tag))
    @Test fun simplifiedChineseIsPreferredWithoutIgnoringTrackIdentity() {
        assertEquals(4, automaticStreamIndex(listOf(subtitle(1, "eng", default = true), subtitle(2, "zh-Hant"),
            subtitle(4, "zh-Hans")), PlaybackTrackKind.SUBTITLE))
    }
    @Test fun ChineseFallsBackToTraditionalAndThenDefault() {
        assertEquals(2, automaticStreamIndex(listOf(subtitle(1, "eng", default = true), subtitle(2, "zh-Hant")), PlaybackTrackKind.SUBTITLE))
        assertEquals(1, automaticStreamIndex(listOf(subtitle(1, "eng", default = true)), PlaybackTrackKind.SUBTITLE))
    }
    @Test fun noMatchingLanguageOrFlagsDoesNotInventASubtitleChoice() {
        assertNull(automaticStreamIndex(listOf(subtitle(1, "eng")), PlaybackTrackKind.SUBTITLE))
        assertEquals(1, automaticStreamIndex(listOf(subtitle(1, "eng", forced = true)), PlaybackTrackKind.SUBTITLE))
    }
    @Test fun automaticAudioUsesTheServerDefaultRatherThanSubtitlePreference() {
        assertEquals(2, automaticStreamIndex(listOf(MediaTrackOption(1, MediaTrackKind.AUDIO, "国语", "zh", false),
            MediaTrackOption(2, MediaTrackKind.AUDIO, "日语", "ja", false, isDefault = true)), PlaybackTrackKind.AUDIO))
    }
}
