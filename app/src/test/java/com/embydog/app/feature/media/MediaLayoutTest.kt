package com.embydog.app.feature.media

import org.junit.Assert.*
import org.junit.Test

class MediaLayoutTest {
    @Test fun homePostersAreComfortableWithoutMakingTabletsOversized() {
        assertEquals(120.57f, homePosterWidth(360f), 0.01f)
        assertEquals(131.89f, homePosterWidth(390f), 0.01f)
        assertEquals(143.21f, homePosterWidth(420f), 0.01f)
        assertEquals(152f, homePosterWidth(700f), 0f)
        assertEquals(152f, homePosterWidth(1400f), 0f)
    }
    @Test fun homePostersAdaptToLargeFontsAndNarrowContainers() {
        assertTrue(homePosterWidth(360f, 1.5f) > homePosterWidth(360f))
        assertEquals(homePosterWidth(360f, 1.35f), homePosterWidth(360f, 2f), 0f)
        assertEquals(homePosterWidth(360f), homePosterWidth(360f, 0.8f), 0f)
        assertEquals(76f, homePosterWidth(100f, 2f), 0f)
        assertEquals(1f, homePosterWidth(20f), 0f)
        // Only the home shelf changes; grids keep their existing density.
        assertEquals(100f, posterMinimumWidth(1f), 0f)
    }
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
