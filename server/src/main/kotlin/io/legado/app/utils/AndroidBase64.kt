package io.legado.app.utils

/**
 * 兼容 android.util.Base64 的实现（flags 语义保持一致），供移植代码与书源 JS 使用
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
object AndroidBase64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    @JvmStatic
    fun decode(str: String?, flags: Int = DEFAULT): ByteArray {
        if (str.isNullOrEmpty()) return ByteArray(0)
        val sb = StringBuilder(str.length + 3)
        for (c in str) {
            when (c) {
                ' ', '\n', '\r', '\t' -> Unit
                '-' -> sb.append('+')
                '_' -> sb.append('/')
                else -> sb.append(c)
            }
        }
        // 去掉多余的 padding 后重新补齐
        var end = sb.length
        while (end > 0 && sb[end - 1] == '=') end--
        sb.setLength(end)
        when (sb.length % 4) {
            2 -> sb.append("==")
            3 -> sb.append("=")
            1 -> throw IllegalArgumentException("bad base-64")
        }
        return java.util.Base64.getDecoder().decode(sb.toString())
    }

    @JvmStatic
    fun decode(bytes: ByteArray, flags: Int = DEFAULT): ByteArray {
        return decode(String(bytes, Charsets.ISO_8859_1), flags)
    }

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int = DEFAULT): String {
        var encoder = if (flags and URL_SAFE != 0) {
            java.util.Base64.getUrlEncoder()
        } else {
            java.util.Base64.getEncoder()
        }
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        val encoded = encoder.encodeToString(input)
        if (flags and NO_WRAP != 0) return encoded
        val lineSep = if (flags and CRLF != 0) "\r\n" else "\n"
        val sb = StringBuilder(encoded.length + encoded.length / 76 * 2 + 2)
        var i = 0
        while (i < encoded.length) {
            val e = minOf(i + 76, encoded.length)
            sb.append(encoded, i, e).append(lineSep)
            i = e
        }
        return sb.toString()
    }

    @JvmStatic
    fun encode(input: ByteArray, flags: Int = DEFAULT): ByteArray {
        return encodeToString(input, flags).toByteArray(Charsets.US_ASCII)
    }
}
