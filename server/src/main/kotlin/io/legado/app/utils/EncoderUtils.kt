package io.legado.app.utils



/**
 * 编码工具 escape base64
 */
@Suppress("unused")
object EncoderUtils {

    fun escape(src: String): String {
        val tmp = StringBuilder()
        for (char in src) {
            val charCode = char.code
            if (charCode in 48..57 || charCode in 65..90 || charCode in 97..122) {
                tmp.append(char)
                continue
            }

            val prefix = when {
                charCode < 16 -> "%0"
                charCode < 256 -> "%"
                else -> "%u"
            }
            tmp.append(prefix).append(charCode.toString(16))
        }
        return tmp.toString()
    }

    @JvmOverloads
    fun base64Decode(str: String, flags: Int = AndroidBase64.DEFAULT): String {
        val bytes = AndroidBase64.decode(str, flags)
        return String(bytes)
    }

    @JvmOverloads
    fun base64Encode(str: String, flags: Int = AndroidBase64.NO_WRAP): String? {
        return AndroidBase64.encodeToString(str.toByteArray(), flags)
    }

    @JvmOverloads
    fun base64Encode(bytes: ByteArray, flags: Int = AndroidBase64.NO_WRAP): String {
        return AndroidBase64.encodeToString(bytes, flags)
    }
    
    @JvmOverloads
    fun base64DecodeToByteArray(str: String, flags: Int = AndroidBase64.DEFAULT): ByteArray {
        return AndroidBase64.decode(str, flags)
    }

}
