package io.legado.web.service

import io.legado.web.auth.BadRequestException
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UserPrincipal
import io.legado.web.db.UserFonts
import io.legado.web.db.dbQuery
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.File

/**
 * 阅读字体：用户上传字体文件，阅读页用 FontFace 加载。按用户保存，在所有设备上都能用。
 */
class FontService(dataDir: File) {

    data class FontDto(val id: Long, val name: String, val format: String, val size: Long)

    companion object {
        const val MAX_SIZE = 30L * 1024 * 1024
        const val MAX_COUNT = 10

        /** 按文件头判断字体格式（不信任文件名），不是字体时返回 null */
        fun detectFormat(head: ByteArray): String? {
            if (head.size < 4) return null
            val b = head.copyOf(4)
            return when {
                b.contentEquals(byteArrayOf(0, 1, 0, 0)) || String(b, Charsets.ISO_8859_1) == "true" -> "ttf"
                String(b, Charsets.ISO_8859_1) == "OTTO" -> "otf"
                String(b, Charsets.ISO_8859_1) == "wOFF" -> "woff"
                String(b, Charsets.ISO_8859_1) == "wOF2" -> "woff2"
                String(b, Charsets.ISO_8859_1) == "ttcf" -> "ttc"
                else -> null
            }
        }

        fun contentType(format: String) = when (format) {
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttc" -> "font/collection"
            else -> "font/ttf"
        }
    }

    private val root = File(dataDir, "fonts")

    private fun file(userId: Long, id: Long) = File(File(root, userId.toString()), id.toString())

    suspend fun list(user: UserPrincipal): List<FontDto> = dbQuery {
        UserFonts.selectAll().where { UserFonts.userId eq user.id }
            .orderBy(UserFonts.id to SortOrder.ASC)
            .map { FontDto(it[UserFonts.id].value, it[UserFonts.name], it[UserFonts.format], it[UserFonts.size]) }
    }

    /** 保存上传的字体（[tmp] 为已接收完的临时文件），[fileName] 去掉扩展名后作为显示名称 */
    suspend fun add(user: UserPrincipal, tmp: File, fileName: String): FontDto {
        val format = tmp.inputStream().use { detectFormat(it.readNBytes(4)) }
            ?: throw BadRequestException("不是字体文件（支持 TTF、OTF、WOFF、WOFF2、TTC）")
        val count = dbQuery { UserFonts.selectAll().where { UserFonts.userId eq user.id }.count() }
        if (count >= MAX_COUNT) throw BadRequestException("最多上传 10 个字体，请先删除不用的")
        val name = fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
            .trim().ifEmpty { "字体" }.take(100)
        val size = tmp.length()
        val id = dbQuery {
            UserFonts.insertAndGetId {
                it[userId] = user.id
                it[UserFonts.name] = name
                it[UserFonts.format] = format
                it[UserFonts.size] = size
                it[createdAt] = System.currentTimeMillis()
            }.value
        }
        val target = file(user.id, id)
        target.parentFile.mkdirs()
        tmp.copyTo(target, overwrite = true)
        return FontDto(id, name, format, size)
    }

    /** 字体文件和格式；只能读取自己的 */
    suspend fun get(user: UserPrincipal, id: Long): Pair<File, String> {
        val row = dbQuery {
            UserFonts.selectAll().where { (UserFonts.id eq id) and (UserFonts.userId eq user.id) }.firstOrNull()
        } ?: throw NotFoundException("字体不存在")
        val f = file(user.id, id)
        if (!f.isFile) throw NotFoundException("字体不存在")
        return f to row[UserFonts.format]
    }

    suspend fun delete(user: UserPrincipal, id: Long) {
        val n = dbQuery { UserFonts.deleteWhere { (UserFonts.id eq id) and (UserFonts.userId eq user.id) } }
        if (n == 0) throw NotFoundException("字体不存在")
        file(user.id, id).delete()
    }
}
