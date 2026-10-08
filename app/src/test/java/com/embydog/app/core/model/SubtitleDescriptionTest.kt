package com.embydog.app.core.model

import org.junit.Assert.*
import org.junit.Test

class SubtitleDescriptionTest {
    @Test fun languageTagsDistinguishSimplifiedAndTraditional() {
        assertEquals("简体中文", describeSubtitle(SubtitleMetadata(language = "zh-Hans")).label)
        assertEquals("繁体中文", describeSubtitle(SubtitleMetadata(language = "zh-Hant")).label)
        assertEquals("简体中文", describeSubtitle(SubtitleMetadata(language = "zh-CN")).label)
        assertEquals("繁体中文", describeSubtitle(SubtitleMetadata(language = "zh-TW")).label)
    }
    @Test fun genericChineseIsNeverAssumedToBeSimplified() {
        listOf("chi", "zho", "zh").forEach {
            assertEquals("中文（简繁未标明）", describeSubtitle(SubtitleMetadata(language = it)).label)
        }
    }
    @Test fun titleAndFilenameHintsRecognizeBilingualSubtitles() {
        assertEquals("简体中文 / 英文 · 双语", describeSubtitle(SubtitleMetadata(language = "chi", title = "简英双语 字幕组A")).label)
        assertEquals("繁体中文 / 英文 · 双语", describeSubtitle(SubtitleMetadata(fileName = "Movie.CHT&ENG.ass")).label)
        assertEquals("简体中文 / 日文 · 双语", describeSubtitle(SubtitleMetadata(title = "CHS_JPN")).label)
    }
    @Test fun conflictingScriptsAreDisplayedRatherThanSilentlyDiscarded() {
        assertEquals("简繁中文", describeSubtitle(SubtitleMetadata(language = "zh-CN", title = "繁體中文")).label)
    }
    @Test fun titleCodecFlagsAndStreamIdentityArePreserved() {
        val result = describeSubtitle(SubtitleMetadata(7, "chi", "字幕组B 简体 SDH", "Chinese", "movie.chs.ass",
            "ass", true, true, true, true))
        listOf("字幕组B", "ASS", "外挂", "默认", "强制", "听障 SDH", "轨道 #7").forEach { assertTrue(result.details.contains(it)) }
    }
    @Test fun tracksWithTheSameLanguageStillHaveDistinctDetails() {
        val a = describeSubtitle(SubtitleMetadata(index = 2, language = "zh", codec = "ass"))
        val b = describeSubtitle(SubtitleMetadata(index = 3, language = "zh", codec = "srt"))
        assertNotEquals(a.details, b.details)
    }
    @Test fun hearingImpairedAndGraphicalSubtitlesAreExplicit() {
        val result = describeSubtitle(SubtitleMetadata(language = "eng", title = "English SDH", codec = "hdmv_pgs_subtitle"))
        assertEquals("英文", result.label)
        assertTrue(result.details.contains("PGS 图形字幕"))
        assertTrue(result.details.contains("听障 SDH"))
    }
    @Test fun abbreviationsInsideUnrelatedNamesDoNotBecomeLanguageHints() {
        assertEquals("语言未标明", describeSubtitle(SubtitleMetadata(title = "TECHSTUDIO")).label)
    }
}
