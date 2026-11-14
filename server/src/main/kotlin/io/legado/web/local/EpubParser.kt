package io.legado.web.local

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** 本地书籍格式错误或不安全（提示给用户） */
class LocalBookException(message: String) : RuntimeException(message)

/**
 * EPUB 解析（不解压到磁盘，按需读取 zip 中的条目）。
 *
 * 安全措施：
 * - 文件特征：zip 文件头（PK\x03\x04）、mimetype 为 application/epub+zip、存在 META-INF/container.xml
 * - 压缩炸弹：限制条目数量、单个条目和总的解压大小、压缩比
 * - 路径：只按规范化后的相对路径读取 zip 内条目，不允许 ../ 越界，不写入磁盘
 * - XML：jsoup 的 XML 解析器不解析外部实体（无 XXE）
 * - 内容：正文转纯文本（图片除外），去掉脚本、样式和事件属性
 */
class EpubParser(file: File) : Closeable {

    data class Chapter(
        val title: String,
        /** 章节所在文件（zip 内路径） */
        val href: String,
        /** 章节开始的锚点（同一文件中有多个章节时） */
        val anchor: String?,
        /** 下一章在同一文件中的锚点（本章到此结束） */
        val endAnchor: String?,
        /** 目录中的层级（0 为顶层） */
        val depth: Int,
    )

    data class Metadata(
        val title: String?,
        val author: String?,
        val intro: String?,
        val coverPath: String?,
    )

    companion object {
        private const val MAX_ENTRIES = 20_000
        private const val MAX_ENTRY_SIZE = 64L * 1024 * 1024
        private const val MAX_TOTAL_SIZE = 1024L * 1024 * 1024
        /** 压缩比超过此值且解压后较大时视为压缩炸弹 */
        private const val MAX_RATIO = 200

        private val IMAGE_TYPES = mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
            "webp" to "image/webp", "bmp" to "image/bmp", "svg" to "image/svg+xml",
        )

        /** zip 文件头（PK） */
        fun isZip(file: File): Boolean {
            val head = ByteArray(4)
            file.inputStream().use { if (it.read(head) != 4) return false }
            return head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()
        }

        /**
         * 打开 zip。条目名不是 UTF-8 时（国内工具打包的 EPUB 常用 GBK 文件名，且没有标记 UTF-8）
         * 按 UTF-8 打开会失败，改按 GBK 再试；标记了 UTF-8 的条目不受影响
         */
        fun openZip(file: File): ZipFile = try {
            ZipFile(file)
        } catch (e: Exception) {
            try {
                ZipFile(file, Charset.forName("GBK"))
            } catch (_: Exception) {
                throw e
            }
        }

        /** zip 内的条目名统一为 “/” 分隔、去掉开头的 “/”（Windows 工具打包的 EPUB 可能用 “\”） */
        private fun entryKey(name: String) = name.replace('\\', '/').trimStart('/')

        /**
         * 只检查文件特征（上传时先于完整解析）：zip，且是 EPUB——mimetype 为 application/epub+zip，
         * 或者有 META-INF/container.xml、.opf 文件（与 App 一样不强制要求 mimetype：缺失、带 BOM 的都很常见）。
         * 普通 zip（.zip、.docx、.cbz 等）返回 false，由调用方提示“不是电子书”
         */
        fun looksLikeEpub(file: File): Boolean = isZip(file) && runCatching {
            openZip(file).use { zip ->
                val mime = zip.getEntry("mimetype")?.let { e -> zip.getInputStream(e).use { String(it.readNBytes(64), Charsets.UTF_8) } }
                    ?.removePrefix("﻿")?.trim()
                if (mime.equals("application/epub+zip", ignoreCase = true)) return@use true
                zip.entries().asSequence().map { entryKey(it.name) }
                    .any { it.equals("META-INF/container.xml", ignoreCase = true) || it.endsWith(".opf", ignoreCase = true) }
            }
        }.getOrDefault(false)

        private val DECLARED_CHARSET = Regex("""(?i)(?:encoding|charset)\s*=\s*["']?([\w\-]+)""")

        /**
         * 解码 EPUB 内的文本文件（OPF、NCX、XHTML）。合法的 UTF-8 一律按 UTF-8（最可靠）；
         * 不是 UTF-8 时按 XML 声明或 meta 中声明的编码，没有声明时按 GB18030（老的中文 EPUB 常见 GBK 正文）
         */
        fun decodeText(bytes: ByteArray): String {
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            val off = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
            try {
                return Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, off, bytes.size - off)).toString()
            } catch (_: CharacterCodingException) {
            }
            val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
            val declared = DECLARED_CHARSET.find(head)?.groupValues?.get(1)
                ?.let { runCatching { Charset.forName(it) }.getOrNull() }
                ?.takeIf { it != Charsets.UTF_8 }
            return String(bytes, off, bytes.size - off, declared ?: Charset.forName("GB18030"))
        }

        /** 带协议的地址（http:、data:、file: 等）：冒号只有出现在第一个 "/" 之前、且是合法 scheme 时才算协议。
         * 文件名里本身带 ":"（某些书城的混淆 EPUB，如 Text/:|:*.xhtml）不算 */
        private val SCHEME_RE = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")

        /** zip 内路径规范化：解析 . 和 ..，越界时返回 null */
        fun normalize(base: String, href: String): String? {
            val clean = runCatching { URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8") }
                .getOrDefault(href.substringBefore('#'))
            if (clean.isEmpty() || SCHEME_RE.containsMatchIn(clean) || clean.startsWith("/")) {
                // 绝对路径或带协议的地址（http:、data: 等）不属于书内资源
                if (clean.startsWith("/")) return normalize("", clean.trimStart('/'))
                return null
            }
            val parts = ArrayList<String>()
            base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.forEach { parts.add(it) }
            for (seg in clean.split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                    else -> parts.add(seg)
                }
            }
            return parts.joinToString("/").ifEmpty { null }
        }
    }

    private val zip: ZipFile = try {
        openZip(file)
    } catch (e: Exception) {
        throw LocalBookException("不是有效的 EPUB 文件（无法按 zip 读取）")
    }

    /** zip 内所有文件（路径 -> 条目） */
    private val entries: Map<String, ZipEntry>
    /** 小写路径 -> 实际路径：OPF / 目录里写的路径与文件大小写不一致时按不区分大小写匹配 */
    private lateinit var lowerKeys: Map<String, String>

    private lateinit var opfPath: String
    private val manifest = LinkedHashMap<String, Pair<String, String>>() // id -> (path, media-type)，保持 OPF 中的顺序
    private val manifestProps = HashMap<String, String>() // id -> properties
    private val spine = ArrayList<String>() // 路径
    private var opf: Document? = null

    init {
        try {
            val list = zip.entries().toList()
            if (list.size > MAX_ENTRIES) throw LocalBookException("EPUB 中的文件过多")
            var total = 0L
            for (e in list) {
                val size = e.size
                if (size > MAX_ENTRY_SIZE) throw LocalBookException("EPUB 中有过大的文件：${e.name}")
                if (size > 0) total += size
                if (total > MAX_TOTAL_SIZE) throw LocalBookException("EPUB 解压后过大")
                val compressed = e.compressedSize
                if (size > 1024 * 1024 && compressed > 0 && size / compressed > MAX_RATIO) {
                    throw LocalBookException("EPUB 文件异常（压缩比过高）")
                }
            }
            entries = list.filter { !it.isDirectory }.associateBy { entryKey(it.name) }
            lowerKeys = entries.keys.groupBy { it.lowercase() }.mapValues { it.value.first() }
            // 与 App 一样不强制要求 mimetype（缺失、带 BOM、内容不标准的都很常见），能找到 OPF 就按 EPUB 解析
            parsePackage()
        } catch (e: Exception) {
            zip.close()
            throw e
        }
    }

    override fun close() = zip.close()

    // ---------------- zip 读取 ----------------

    private fun readBytes(entry: ZipEntry, limit: Long = MAX_ENTRY_SIZE): ByteArray =
        zip.getInputStream(entry).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) throw LocalBookException("EPUB 中的文件过大：${entry.name}")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }

    private fun readText(entry: ZipEntry, limit: Long = MAX_ENTRY_SIZE): String = decodeText(readBytes(entry, limit))

    /** 书内路径对应的实际条目名：先精确匹配，再不区分大小写；不存在时为 null */
    private fun key(path: String?): String? = path?.let { if (entries.containsKey(it)) it else lowerKeys[it.lowercase()] }

    private fun xml(path: String): Document? = key(path)?.let { entries[it] }?.let { Jsoup.parse(readText(it), "", Parser.xmlParser()) }

    // ---------------- 包信息 ----------------

    private fun Element.local(): String = tagName().substringAfter(':').lowercase()
    private fun Element.childrenNamed(name: String) = getAllElements().filter { it !== this && it.local() == name }
    private fun Element.firstNamed(name: String) = getAllElements().firstOrNull { it !== this && it.local() == name }

    private fun isHtml(path: String, mediaType: String) =
        mediaType.contains("html", ignoreCase = true) || path.substringAfterLast('.').lowercase() in setOf("xhtml", "html", "htm")

    private fun parsePackage() {
        // OPF：按 container.xml 的 full-path；container.xml 缺失或指向不存在的文件时，在 zip 里找 .opf
        // （App 在缺少 container.xml 时默认 OEBPS/content.opf，这里更进一步）
        val fromContainer = xml("META-INF/container.xml")?.getAllElements()
            ?.firstOrNull { it.local() == "rootfile" }?.attr("full-path")
            ?.let { normalize("", it) }?.let { key(it) }
        val rootfile = fromContainer
            ?: entries.keys.filter { it.endsWith(".opf", ignoreCase = true) }
                .sortedWith(compareBy({ !it.substringAfterLast('/').equals("content.opf", true) }, { it.count { c -> c == '/' } }, { it }))
                .firstOrNull()
            ?: throw LocalBookException("EPUB 格式错误（找不到 OPF）")
        opfPath = rootfile
        val doc = xml(rootfile) ?: throw LocalBookException("EPUB 格式错误（OPF 不存在）")
        opf = doc
        doc.getAllElements().filter { it.local() == "item" }.forEach { item ->
            val id = item.attr("id")
            val raw = normalize(opfPath, item.attr("href")) ?: return@forEach
            // 实际条目名（大小写不一致时按不区分大小写匹配）；文件不存在的保留原路径，阅读顺序中会跳过
            manifest[id] = (key(raw) ?: raw) to item.attr("media-type")
            item.attr("properties").takeIf { it.isNotBlank() }?.let { manifestProps[id] = it }
        }
        doc.getAllElements().filter { it.local() == "itemref" }.forEach { ref ->
            val idref = ref.attr("idref")
            // 与 App（epublib 的 getByIdOrHref）相同：id 找不到时把 idref 当作路径
            val path = manifest[idref]?.first ?: normalize(opfPath, idref)?.let { key(it) }
            if (path != null && entries.containsKey(path) && path !in spine) spine.add(path)
        }
        // 没有 spine 或 spine 全部无效：按清单顺序取所有网页文件；清单也没有时按文件名排序取 zip 里所有网页
        // （App 的 epublib 在没有 spine 时同样按网页文件生成阅读顺序）
        if (spine.isEmpty()) {
            manifest.filterKeys { manifestProps[it]?.split(' ')?.contains("nav") != true }.values
                .filter { (path, type) -> isHtml(path, type) && entries.containsKey(path) }
                .forEach { if (it.first !in spine) spine.add(it.first) }
        }
        if (spine.isEmpty()) {
            entries.keys.filter { isHtml(it, "") }.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { spine.add(it) }
        }
        if (spine.isEmpty()) throw LocalBookException("EPUB 中没有正文")
    }

    fun metadata(): Metadata {
        val doc = opf!!
        fun meta(name: String) = doc.getAllElements().firstOrNull { it.local() == name }?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val coverId = manifestProps.entries.firstOrNull { it.value.split(' ').contains("cover-image") }?.key
            ?: doc.getAllElements().firstOrNull { it.local() == "meta" && it.attr("name") == "cover" }?.attr("content")
        val cover = coverId?.let { manifest[it] }?.takeIf { it.second.startsWith("image/") }?.first
            ?: manifest.values.firstOrNull { it.second.startsWith("image/") && it.first.contains("cover", true) }?.first
        return Metadata(
            title = meta("title"),
            author = meta("creator"),
            intro = meta("description")?.let { Jsoup.parse(it).text() },
            coverPath = cover,
        )
    }

    // ---------------- 目录 ----------------

    fun chapters(): List<Chapter> {
        val toc = navToc() ?: ncxToc()
        val raw = toc?.takeIf { it.isNotEmpty() } ?: spineToc()
        // 同一文件中的相邻章节以锚点分割；目录中不存在的文件按阅读顺序并入前一章
        return raw.mapIndexed { i, c ->
            val next = raw.getOrNull(i + 1)
            c.copy(endAnchor = if (next != null && next.href == c.href) next.anchor else null)
        }
    }

    private data class RawEntry(val title: String, val href: String, val anchor: String?, val depth: Int)

    private fun chapterOf(e: RawEntry) = Chapter(e.title, e.href, e.anchor, null, e.depth)

    /** EPUB3 导航文档 */
    private fun navToc(): List<Chapter>? {
        val navId = manifestProps.entries.firstOrNull { it.value.split(' ').contains("nav") }?.key ?: return null
        val navPath = manifest[navId]?.first ?: return null
        val doc = entries[navPath]?.let { Jsoup.parse(readText(it)) } ?: return null
        val nav = doc.select("nav").firstOrNull { it.attr("epub:type") == "toc" || it.attr("type") == "toc" }
            ?: doc.selectFirst("nav") ?: return null
        val out = ArrayList<Chapter>()
        fun walk(ol: Element, depth: Int) {
            for (li in ol.children().filter { it.tagName() == "li" }) {
                val a = li.children().firstOrNull { it.tagName() == "a" || it.tagName() == "span" }
                val href = a?.attr("href").orEmpty()
                val path = if (href.isNotBlank()) key(normalize(navPath, href)) else null
                val title = a?.text()?.trim().orEmpty()
                if (path != null && title.isNotEmpty() && entries.containsKey(path)) {
                    out.add(chapterOf(RawEntry(title, path, href.substringAfter('#', "").ifEmpty { null }, depth)))
                }
                li.children().firstOrNull { it.tagName() == "ol" }?.let { walk(it, depth + 1) }
            }
        }
        val firstOl: Element? = nav.selectFirst("ol")
        if (firstOl != null) walk(firstOl, 0)
        return out
    }

    /** EPUB2 toc.ncx */
    private fun ncxToc(): List<Chapter>? {
        val ncxPath = opf!!.getAllElements().firstOrNull { it.local() == "spine" }?.attr("toc")?.let { manifest[it]?.first }
            ?: manifest.values.firstOrNull { it.second == "application/x-dtbncx+xml" }?.first
            ?: return null
        val doc = xml(ncxPath) ?: return null
        val navMap = doc.getAllElements().firstOrNull { it.local() == "navmap" } ?: return null
        val out = ArrayList<Chapter>()
        fun walk(parent: Element, depth: Int) {
            for (np in parent.children().filter { it.local() == "navpoint" }) {
                val title = np.firstNamed("navlabel")?.firstNamed("text")?.text()?.trim().orEmpty()
                val src = np.children().firstOrNull { it.local() == "content" }?.attr("src").orEmpty()
                val path = key(normalize(ncxPath, src))
                if (path != null && title.isNotEmpty() && entries.containsKey(path)) {
                    out.add(chapterOf(RawEntry(title, path, src.substringAfter('#', "").ifEmpty { null }, depth)))
                }
                walk(np, depth + 1)
            }
        }
        walk(navMap, 0)
        return out
    }

    /** 没有目录时按阅读顺序，每个文件一章 */
    private fun spineToc(): List<Chapter> = spine.mapIndexed { i, path ->
        val title = entries[path]?.let { Jsoup.parse(readText(it)) }?.let { d ->
            d.selectFirst("h1,h2,h3")?.text()?.takeIf { it.isNotBlank() } ?: d.title().takeIf { it.isNotBlank() }
        } ?: "第 ${i + 1} 节"
        chapterOf(RawEntry(title, path, null, 0))
    }

    // ---------------- 正文 ----------------

    /**
     * 章节正文的 HTML（图片地址替换为 [imageUrl] 生成的地址）。
     * 本章所在文件之后、下一章所在文件之前的阅读顺序中的文件一并并入本章（目录中未列出的插图页等）。
     */
    fun chapterHtml(chapters: List<Chapter>, index: Int, imageUrl: (String) -> String): String {
        val c = chapters[index]
        val next = chapters.getOrNull(index + 1)
        val files = ArrayList<String>()
        val start = spine.indexOf(c.href)
        if (start < 0) {
            files.add(c.href)
        } else {
            files.add(spine[start])
            if (next == null || next.href != c.href) {
                val end = next?.let { spine.indexOf(it.href) }?.takeIf { it > start } ?: if (next == null) spine.size else start + 1
                for (i in start + 1 until end) files.add(spine[i])
            }
        }
        return files.mapIndexed { i, path ->
            val entry = entries[path] ?: return@mapIndexed ""
            val doc = Jsoup.parse(readText(entry))
            val body = doc.body()
            // 同一文件中的多章：只取本章锚点到下一章锚点之间的部分
            if (i == 0 && (c.anchor != null || c.endAnchor != null)) sliceByAnchors(body, c.anchor, c.endAnchor)
            clean(body, path, imageUrl)
            body.html()
        }.joinToString("\n")
    }

    /** 保留 [from] 锚点（含）到 [to] 锚点（不含）之间的内容 */
    private fun sliceByAnchors(body: Element, from: String?, to: String?) {
        val all = body.getAllElements()
        val startEl = from?.let { id -> all.firstOrNull { it.id() == id || it.attr("name") == id } }
        val endEl = to?.let { id -> all.firstOrNull { it.id() == id || it.attr("name") == id } }
        if (startEl == null && endEl == null) return
        // 按文档顺序遍历：进入开始锚点后开始保留，进入结束锚点后停止保留；只删除叶子节点，保留外层容器
        var started = startEl == null
        var ended = false
        val toRemove = ArrayList<org.jsoup.nodes.Node>()
        org.jsoup.select.NodeTraversor.traverse({ node, _ ->
            if (node === startEl) started = true
            if (node === endEl) ended = true
            if (node.childNodeSize() == 0 && (!started || ended)) toRemove.add(node)
        }, body)
        toRemove.forEach { it.remove() }
    }

    private fun clean(body: Element, docPath: String, imageUrl: (String) -> String) {
        body.select("script, style, link, iframe, object, embed, form, input, button, noscript, audio, video").remove()
        // SVG 中的图片（常见于封面、插图页）
        body.select("svg").forEach { svg ->
            val href = svg.select("image").firstOrNull()?.let { it.attr("xlink:href").ifEmpty { it.attr("href") } }
            val path = href?.let { key(normalize(docPath, it)) }
            if (path != null && isImage(path)) svg.replaceWith(Element("img").attr("src", imageUrl(path))) else svg.remove()
        }
        body.select("img").forEach { img ->
            val path = key(normalize(docPath, img.attr("src")))
            if (path != null && isImage(path)) {
                img.clearAttributes().attr("src", imageUrl(path))
            } else {
                img.remove()
            }
        }
        // 去掉所有事件属性和链接
        body.getAllElements().forEach { el ->
            el.attributes().asList().map { it.key }.filter { it.startsWith("on", true) || it == "href" }.forEach { el.removeAttr(it) }
        }
    }

    // ---------------- 资源 ----------------

    private fun isImage(path: String): Boolean {
        if (!entries.containsKey(path)) return false
        val type = manifest.values.firstOrNull { it.first == path }?.second
        return type?.startsWith("image/") == true || IMAGE_TYPES.containsKey(path.substringAfterLast('.').lowercase())
    }

    /** 书内图片：只允许读取图片类型的条目；返回 (内容, Content-Type) */
    fun image(path: String): Pair<ByteArray, String>? {
        val clean = key(normalize("", path)) ?: return null
        if (!isImage(clean)) return null
        val type = IMAGE_TYPES[clean.substringAfterLast('.').lowercase()]
            ?: manifest.values.firstOrNull { it.first == clean }?.second?.takeIf { it in IMAGE_TYPES.values }
            ?: return null
        return readBytes(entries.getValue(clean), 32L * 1024 * 1024) to type
    }
}
