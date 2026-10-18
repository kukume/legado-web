package android.util;

import io.legado.app.utils.AndroidBase64;

/**
 * android.util.Base64 的兼容实现。
 * 很多书源直接在 JS 里调用 Packages.android.util.Base64，服务端没有 Android SDK，
 * 这里提供同名同签名的类，委托给 {@link AndroidBase64}。
 */
@SuppressWarnings("unused")
public final class Base64 {
    public static final int DEFAULT = AndroidBase64.DEFAULT;
    public static final int NO_PADDING = AndroidBase64.NO_PADDING;
    public static final int NO_WRAP = AndroidBase64.NO_WRAP;
    public static final int CRLF = AndroidBase64.CRLF;
    public static final int URL_SAFE = AndroidBase64.URL_SAFE;
    public static final int NO_CLOSE = AndroidBase64.NO_CLOSE;

    private Base64() {
    }

    public static byte[] decode(String str, int flags) {
        return AndroidBase64.decode(str, flags);
    }

    public static byte[] decode(byte[] input, int flags) {
        return AndroidBase64.decode(input, flags);
    }

    public static byte[] decode(byte[] input, int offset, int len, int flags) {
        byte[] part = new byte[len];
        System.arraycopy(input, offset, part, 0, len);
        return AndroidBase64.decode(part, flags);
    }

    public static String encodeToString(byte[] input, int flags) {
        return AndroidBase64.encodeToString(input, flags);
    }

    public static String encodeToString(byte[] input, int offset, int len, int flags) {
        byte[] part = new byte[len];
        System.arraycopy(input, offset, part, 0, len);
        return AndroidBase64.encodeToString(part, flags);
    }

    public static byte[] encode(byte[] input, int flags) {
        return AndroidBase64.encode(input, flags);
    }

    public static byte[] encode(byte[] input, int offset, int len, int flags) {
        byte[] part = new byte[len];
        System.arraycopy(input, offset, part, 0, len);
        return AndroidBase64.encode(part, flags);
    }
}
