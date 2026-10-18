package io.legado.web

import io.legado.web.service.ReplaceRuleService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 正文处理：删除重复标题、重新分段 */
class ContentProcessTest {

    private val service = ReplaceRuleService()

    @Test
    fun removeSameTitle() {
        val book = "测试书"
        // 完全相同
        assertEquals("正文。", service.removeSameTitle(book, "第一章 开始", "第一章 开始\n正文。"))
        // 空白多少不同、前面有缩进和标点、带书名
        assertEquals("正文。", service.removeSameTitle(book, "第一章 开始", "　　第一章  开始\n\n正文。"))
        assertEquals("正文。", service.removeSameTitle(book, "第一章 开始", "【测试书】第一章开始\n正文。"))
        // 章节名在正文里被拆成两行（每行带全角空格缩进）
        assertEquals("　　The Caterpillar…", service.removeSameTitle(book, "CHAPTER V. Advice from a Caterpillar", "　　　　CHAPTER V.\n　　Advice from a Caterpillar\n　　The Caterpillar…"))
        // 章节名里的正则符号按字面匹配
        assertEquals("正文。", service.removeSameTitle(book, "第1章 (上)", "第1章 (上)\n正文。"))
        // 首行只是以章节名开头，不是同一行：不删除
        val text = "第一章 开始的时候，他还在睡觉。"
        assertEquals(text, service.removeSameTitle(book, "第一章 开始", text))
        // 正文中间出现章节名：不删除
        val mid = "前言\n第一章 开始\n正文。"
        assertEquals(mid, service.removeSameTitle(book, "第一章 开始", mid))
    }

    @Test
    fun reSegment() {
        // 一段话被拆成很多短行：重新黏合
        val broken = "他走进房间，\n看见桌上放着一封信。\n信是母亲写的，\n字迹有些潦草。"
        val out = service.reSegment(broken, "第一章")
        val lines = out.split('\n').filter { it.isNotBlank() }
        assertTrue(lines.size < 4, out)
        assertTrue(out.replace("\n", "").contains("他走进房间，看见桌上放着一封信。"), out)
        // 对话：引号前后分段
        val dialog = "他说：“你来了。”她点点头：“嗯。”然后两人沉默了。"
        val d = service.reSegment(dialog, "第一章").split('\n').filter { it.isNotBlank() }
        assertTrue(d.size >= 2, d.toString())
        // 图片标签保持原样、单独成段
        val img = "<img src=\"/api/shelf/1/local/image?p=a b.jpg\">\n第一段，\n接着第一段。"
        val i = service.reSegment(img, "第一章").split('\n')
        assertEquals("<img src=\"/api/shelf/1/local/image?p=a b.jpg\">", i.first())
        assertTrue(i.drop(1).joinToString("").contains("第一段，接着第一段。"), i.toString())
    }
}
