package com.embydog.app.feature.media

import com.embydog.app.core.model.MediaItem
import org.junit.Assert.*
import org.junit.Test

class DetailPlaybackTest {
    private fun item(positionMs: Long) = MediaItem("item", "Episode", "Episode", "", null, 60,
        null, null, null, null, positionMs)
    @Test fun neverPlayedButtonSaysPlayAndResumeShowsTheExactTime() {
        assertEquals("播放", detailPlayLabel(item(0)))
        assertEquals("从上次 02:05 继续播放", detailPlayLabel(item(125000)))
        assertEquals("从上次 1:01:01 继续播放", detailPlayLabel(item(3661000)))
    }
}
