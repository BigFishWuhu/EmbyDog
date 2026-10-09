package com.embydog.app.core.danmaku

import org.junit.Assert.*
import org.junit.Test

class DanmakuModelsTest {
    @Test fun signatureUsesUnixSecondsAndPathWithoutQuery() {
        assertEquals("a51XLR9H5lTOAAIvCCffnN3Qx7Qu1aP5YFUiqTal9Tk=", dandanSignature("test-app", 1735660800, "/api/v2/comment/42", "secret"))
    }
    @Test(expected = IllegalArgumentException::class) fun signatureRejectsQueryInPath() {
        dandanSignature("id", 1, "/api/v2/comment/42?withRelated=true", "secret")
    }
    @Test fun parsesRollingAndFixedComments() {
        val scroll = DandanCommentDto("12.34,1,16777215,user", "你好\n世界").parsed()!!
        assertEquals(12340, scroll.timeMs); assertEquals(DanmakuMode.SCROLL, scroll.mode)
        assertEquals(0xFFFFFF, scroll.color); assertEquals("你好 世界", scroll.text)
        assertEquals(DanmakuMode.BOTTOM, DandanCommentDto("0,4,0,u", "底部").parsed()!!.mode)
        assertEquals(DanmakuMode.TOP, DandanCommentDto("0,5,0,u", "顶部").parsed()!!.mode)
    }
    @Test fun ignoresMalformedUntrustedComments() {
        listOf("NaN,1,0,u", "Infinity,1,0,u", "-1,1,0,u", "1,7,0,u", "1,1,-1,u", "1,1,16777216,u", "1,1", "oops")
            .forEach { assertNull(it, DandanCommentDto(it, "内容").parsed()) }
        assertNull(DandanCommentDto("1,1,0,u", "\n\t").parsed())
        assertEquals(160, DandanCommentDto("1,1,0,u", "字".repeat(200)).parsed()!!.text.length)
    }
    @Test fun normalizesInvalidSettings() {
        val safe = DanmakuDisplay(opacity = Float.NaN, textSizeSp = Float.POSITIVE_INFINITY, speed = 0f, area = 9f, density = -1, offsetMs = Long.MAX_VALUE).normalized()
        assertEquals(0.75f, safe.opacity); assertEquals(20f, safe.textSizeSp)
        assertEquals(0.5f, safe.speed); assertEquals(1f, safe.area); assertEquals(1, safe.density); assertEquals(300000, safe.offsetMs)
    }
    @Test fun associationsAreScopedToServerAccountAndItem() {
        val first = danmakuScope("https://emby.example/", "u1", "42")
        assertEquals(first, danmakuScope("https://emby.example", "u1", "42"))
        assertNotEquals(first, danmakuScope("https://other.example", "u1", "42"))
        assertNotEquals(first, danmakuScope("https://emby.example", "u2", "42"))
        assertNotEquals(first, danmakuScope("https://emby.example", "u1", "43"))
    }
    @Test fun credentialsNeverExposeValuesInToString() {
        val value = DanmakuCredentials("private-id", "private-secret").toString()
        assertFalse(value.contains("private-id")); assertFalse(value.contains("private-secret"))
    }
}
