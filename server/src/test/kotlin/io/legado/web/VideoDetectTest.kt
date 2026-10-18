package io.legado.web

import io.legado.web.service.detectVideoUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoDetectTest {

    @Test
    fun aggregateSourceShortDrama() {
        // 大灰狼等聚合书源的“短剧”正文（安卓分支）
        val content = "　　【右上角刷新】开启播放(下一集请切换下一章刷新)\n　　播放直链：\n　　https://cdn.example.com/v/abc123?sign=xyz"
        assertEquals("https://cdn.example.com/v/abc123?sign=xyz", detectVideoUrl(content))
    }

    @Test
    fun bareVideoUrl() {
        assertEquals("https://a.com/x/index.m3u8", detectVideoUrl("https://a.com/x/index.m3u8"))
        assertEquals("http://a.com/1.mp4?t=1", detectVideoUrl("　　http://a.com/1.mp4?t=1"))
        assertEquals("https://a.com/play.m3u8?x=1,{\"headers\":{\"Referer\":\"https://a.com\"}}",
            detectVideoUrl("https://a.com/play.m3u8?x=1,{\"headers\":{\"Referer\":\"https://a.com\"}}"))
    }

    @Test
    fun normalTextIsNotVideo() {
        assertNull(detectVideoUrl("　　这是正文。\n　　更多内容请访问 https://example.com/book/1"))
        // 只有一个普通网页地址、没有提示：不当作视频
        assertNull(detectVideoUrl("https://example.com/book/1.html"))
        // 两个地址
        assertNull(detectVideoUrl("播放直链：\nhttps://a.com/1.mp4\nhttps://a.com/2.mp4"))
        // 长正文
        assertNull(detectVideoUrl("正文".repeat(2000) + "\nhttps://a.com/1.mp4"))
        // 图片
        assertNull(detectVideoUrl("<img src=\"https://a.com/1.jpg\">"))
    }
}
