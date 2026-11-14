package io.legado.web.local

import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** 压缩包格式（按文件头判断，不看扩展名） */
object ArchiveFormat {
    private fun head(file: File, n: Int): ByteArray = runCatching { file.inputStream().use { it.readNBytes(n) } }.getOrDefault(ByteArray(0))

    private fun ByteArray.startsWith(vararg sig: Int) = size >= sig.size && sig.indices.all { (this[it].toInt() and 0xFF) == sig[it] }

    /** RAR 4 / RAR 5：Rar!\x1A\x07 */
    fun isRar(file: File) = head(file, 7).startsWith(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)

    /** 7z：37 7A BC AF 27 1C */
    fun is7z(file: File) = head(file, 6).startsWith(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)
}

/**
 * 从 zip 压缩包里安全地取出书籍文件，逐个交给 [ZipBookExtractor.forEachBook] 的回调导入。
 *
 * 安全措施：
 * - 不使用包内的文件名作为路径：每个文件解压到工作目录里随机生成的文件名，包内路径（可能带 ../、绝对路径）
 *   只用来显示书名，不会写到别处（防路径穿越 zip slip）
 * - 压缩炸弹：不信任包里声明的大小，解压时边读边计数；限制单个文件、总的解压大小、压缩比和文件数量
 * - 不递归：包里的压缩包不会再解压（EPUB 本身是 zip，按 EPUB 解析，EPUB 解析器有自己的限制）
 * - 只用 JDK 的 zip 实现，不用原生库；加密、损坏的条目跳过
 * - 取出的文件和直接上传的文件走同样的识别和解析（文件头、编码、大小、空间）
 * - 只处理书籍：先读文件头判断，图片、网页等其它文件不解压
 */
class ZipBookExtractor(
    private val archive: File,
    private val workDir: File,
    /** 单个文件的上限（与直接上传相同） */
    private val maxFileBytes: Long,
    /** 解压总量的上限 */
    private val maxTotalBytes: Long,
) {
    data class Skipped(val name: String, val reason: String)

    companion object {
        const val MAX_ENTRIES = 2000
        const val MAX_BOOKS = 50
        /** 压缩比超过此值且解压后较大时视为压缩炸弹 */
        private const val MAX_RATIO = 200
        private const val RATIO_CHECK_AFTER = 8L * 1024 * 1024
        /** 太小的 TXT 多是下载站附带的说明文件 */
        const val MIN_TXT_BYTES = 2 * 1024
        private val TEXT_EXT = setOf("txt", "")
    }

    private class TooLarge(message: String) : Exception(message)

    /** 包内路径 -> 显示用的文件名 */
    private fun displayName(entry: ZipEntry) = entry.name.replace('\\', '/').trimEnd('/').substringAfterLast('/')

    /** 是否是系统附带的垃圾文件（macOS 的 __MACOSX、._ 文件，Windows 的 Thumbs.db 等） */
    private fun isJunk(entry: ZipEntry): Boolean {
        val path = entry.name.replace('\\', '/')
        val name = path.substringAfterLast('/')
        return path.contains("__MACOSX/") || name.startsWith("._") || name.equals("Thumbs.db", true) || name.equals(".DS_Store", true)
    }

    private enum class Kind { BOOK, TEXT, PDF, ARCHIVE }

    /** 读文件头判断：EPUB/zip、MOBI、UMD、PDF、RAR/7z；没有特征的按扩展名，只有 .txt（或没有扩展名）当作文本 */
    private fun classify(zip: ZipFile, entry: ZipEntry, name: String): Kind? {
        val head = zip.getInputStream(entry).use { it.readNBytes(1024) }
        fun at(vararg sig: Int) = head.size >= sig.size && sig.indices.all { (head[it].toInt() and 0xFF) == sig[it] }
        return when {
            at(0x50, 0x4B, 0x03, 0x04) -> Kind.BOOK // EPUB（是不是 EPUB 解压后再判断）
            head.size >= 68 && String(head, 60, 8, Charsets.ISO_8859_1) == "BOOKMOBI" -> Kind.BOOK
            at(0x89, 0x9B, 0x9A, 0xDE) -> Kind.BOOK // UMD
            String(head, Charsets.ISO_8859_1).contains("%PDF-") -> Kind.PDF
            at(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) || at(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> Kind.ARCHIVE
            name.substringAfterLast('.', "").lowercase() in TEXT_EXT -> Kind.TEXT
            else -> null
        }
    }

    /** 解压一个条目到 [dest]，边读边计数（不信任包里声明的大小） */
    private fun extract(zip: ZipFile, entry: ZipEntry, dest: File, budget: Long): Long {
        val limit = minOf(maxFileBytes, budget)
        var total = 0L
        zip.getInputStream(entry).use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limit) {
                        throw TooLarge(if (total > maxFileBytes) "文件过大（最大 ${maxFileBytes / 1024 / 1024}MB）" else "压缩包解压后过大")
                    }
                    val compressed = entry.compressedSize
                    if (total > RATIO_CHECK_AFTER && compressed > 0 && total / compressed > MAX_RATIO) {
                        throw TooLarge("文件异常（压缩比过高）")
                    }
                    out.write(buf, 0, n)
                }
            }
        }
        return total
    }

    /**
     * 逐个取出书籍文件并调用 [block]（文件在回调后删除）。返回跳过的文件和原因。
     * [block] 抛出的 [LocalBookException]、带消息的异常记为跳过；其它异常继续向上抛
     */
    suspend fun forEachBook(block: suspend (name: String, file: File) -> Unit, onSkip: (Throwable) -> String?): List<Skipped> {
        val skipped = ArrayList<Skipped>()
        val zip = try {
            EpubParser.openZip(archive)
        } catch (e: Exception) {
            throw LocalBookException("压缩包已损坏，无法读取")
        }
        zip.use {
            val entries = zip.entries().asSequence().filter { !it.isDirectory && !isJunk(it) }.toList()
            if (entries.size > MAX_ENTRIES) throw LocalBookException("压缩包里的文件过多（最多 $MAX_ENTRIES 个）")
            workDir.mkdirs()
            var used = 0L
            var books = 0
            for (entry in entries) {
                val name = displayName(entry)
                if (name.isBlank()) continue
                val kind = try {
                    classify(zip, entry, name)
                } catch (e: Exception) {
                    // 加密（JDK 不支持）、损坏的条目
                    skipped += Skipped(name, if (e.message?.contains("encrypt", true) == true) "加密的文件，无法解压" else "无法读取（文件损坏）")
                    continue
                } ?: continue // 不是书籍（图片、网页等），忽略
                when (kind) {
                    Kind.PDF -> { skipped += Skipped(name, "压缩包里的 PDF 请解压后单独上传"); continue }
                    Kind.ARCHIVE -> { skipped += Skipped(name, "压缩包里的压缩包不会再解压"); continue }
                    else -> {}
                }
                if (books >= MAX_BOOKS) { skipped += Skipped(name, "一个压缩包最多导入 $MAX_BOOKS 本"); continue }
                val tmp = File(workDir, UUID.randomUUID().toString())
                try {
                    used += try {
                        extract(zip, entry, tmp, maxTotalBytes - used)
                    } catch (e: TooLarge) {
                        skipped += Skipped(name, e.message ?: "文件过大"); continue
                    } catch (e: Exception) {
                        skipped += Skipped(name, if (e.message?.contains("encrypt", true) == true) "加密的文件，无法解压" else "无法解压（文件损坏）"); continue
                    }
                    if (kind == Kind.TEXT && tmp.length() < MIN_TXT_BYTES) { skipped += Skipped(name, "文件太小，可能是说明文件"); continue }
                    if (kind == Kind.BOOK && EpubParser.isZip(tmp) && !EpubParser.looksLikeEpub(tmp)) {
                        skipped += Skipped(name, "压缩包里的压缩包不会再解压"); continue
                    }
                    try {
                        block(name, tmp)
                        books++
                    } catch (e: Throwable) {
                        val reason = onSkip(e) ?: throw e
                        skipped += Skipped(name, reason)
                    }
                } finally {
                    tmp.delete()
                }
            }
        }
        return skipped
    }
}
