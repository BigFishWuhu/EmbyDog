package com.embydog.app.feature.media

import org.junit.Assert.*
import org.junit.Test

class MediaLayoutTest {
    @Test fun episodeGridFitsSmallPhonesAndExpandsOnLargerWindows() {
        assertEquals(1, episodeColumnCount(240f))
        assertEquals(2, episodeColumnCount(296f))
        assertEquals(2, episodeColumnCount(387f))
        assertEquals(4, episodeColumnCount(700f))
        assertEquals(5, episodeColumnCount(1400f))
    }
    @Test fun largeFontsReduceDensityAndNeverRemoveAllColumns() {
        assertEquals(1, episodeColumnCount(296f, 1.5f))
        assertEquals(1, episodeColumnCount(20f, 2f))
        assertEquals(100f, posterMinimumWidth(1f), 0f)
        assertEquals(150f, posterMinimumWidth(2f), 0f)
    }
    @Test fun episodeAndLibraryCoversRemainLandscapeWhileMoviePostersRemainPortrait() {
        assertEquals(16f / 9f, mediaCoverAspectRatio("Episode"), 0f)
        assertEquals(16f / 9f, mediaCoverAspectRatio("CollectionFolder"), 0f)
        assertEquals(2f / 3f, mediaCoverAspectRatio("Movie"), 0f)
        assertEquals(2f / 3f, mediaCoverAspectRatio("Series"), 0f)
    }
}
