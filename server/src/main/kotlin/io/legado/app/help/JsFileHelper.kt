package io.legado.app.help

import io.legado.app.help.engine.Engine
import io.legado.app.help.engine.EngineContext
import io.legado.app.utils.MD5Utils
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 书源 js 文件操作的服务端实现。
 * 每个用户有独立的沙箱目录，所有路径都是相对该目录的。
 */
object JsFileHelper {

    const val TEMP_FOLDER_NAME = "ArchiveTemp"

    /** 当前用户的 js 缓存根目录 */
    val cacheRoot: File
        get() = File(Engine.cacheDir, "js/${EngineContext.currentUserId}").apply { mkdirs() }

    fun resolve(path: String): File {
        val root = cacheRoot.canonicalFile
        val file = File(root, path.trimStart('/', '\\')).canonicalFile
        if (!file.path.startsWith(root.path)) {
            throw SecurityException("非法路径")
        }
        return file
    }

    fun relative(file: File): String {
        return file.canonicalPath.substring(cacheRoot.canonicalPath.length)
            .replace('\\', '/')
    }

    private val suffixRegex = Regex("\\.([a-zA-Z0-9]{1,5})(?:[?#,].*)?$")

    fun getSuffix(url: String, default: String = "ext"): String {
        val path = url.substringBefore("?").substringBefore(",{")
        return suffixRegex.find(path.substringAfterLast('/'))?.groupValues?.get(1) ?: default
    }

    /**
     * 解压 zip 到临时目录，返回目录
     */
    fun unzip(zipFile: File): File {
        val dest = resolve("$TEMP_FOLDER_NAME/${MD5Utils.md5Encode16(zipFile.name)}")
        dest.deleteRecursively()
        dest.mkdirs()
        val destPath = dest.canonicalPath
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(dest, entry.name).canonicalFile
                if (!out.path.startsWith(destPath)) continue // zip slip
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
            }
        }
        return dest
    }
}
