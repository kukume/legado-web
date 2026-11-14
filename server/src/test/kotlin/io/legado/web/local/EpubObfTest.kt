package io.legado.web.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 路径规范化：文件名里带 ":" 的混淆 EPUB 不能被当成带协议的绝对地址丢掉 */
class EpubObfTest {
    @Test fun keepsColonInFilename() {
        // 某些书城的混淆 EPUB，文件名里有 : | * 等字符
        assertEquals("OEBPS/Text/:|:*.xhtml", EpubParser.normalize("OEBPS/content.opf", "Text/:|:*.xhtml"))
        assertEquals("Styles/:|.css", EpubParser.normalize("OEBPS/content.opf", "../Styles/:|.css"))
        assertEquals("a.xhtml", EpubParser.normalize("content.opf", "a.xhtml"))
    }

    @Test fun rejectsRealSchemes() {
        assertNull(EpubParser.normalize("OEBPS/content.opf", "http://example.com/x"))
        assertNull(EpubParser.normalize("OEBPS/content.opf", "data:image/png;base64,AAAA"))
        assertNull(EpubParser.normalize("OEBPS/content.opf", "mailto:a@b.com"))
    }
}
