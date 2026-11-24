package android.text;

import java.util.Iterator;

/**
 * 安卓 android.text.TextUtils 的常用方法，供书源 JS 调用（Packages.android.text.TextUtils.isEmpty 等），行为与安卓一致
 */
public final class TextUtils {

    private TextUtils() {
    }

    public static boolean isEmpty(CharSequence str) {
        return str == null || str.length() == 0;
    }

    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        int length = a.length();
        if (length != b.length()) return false;
        if (a instanceof String && b instanceof String) return a.equals(b);
        for (int i = 0; i < length; i++) {
            if (a.charAt(i) != b.charAt(i)) return false;
        }
        return true;
    }

    public static String join(CharSequence delimiter, Object[] tokens) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) sb.append(delimiter);
            sb.append(tokens[i]);
        }
        return sb.toString();
    }

    public static String join(CharSequence delimiter, Iterable<?> tokens) {
        StringBuilder sb = new StringBuilder();
        Iterator<?> it = tokens.iterator();
        if (it.hasNext()) {
            sb.append(it.next());
            while (it.hasNext()) {
                sb.append(delimiter);
                sb.append(it.next());
            }
        }
        return sb.toString();
    }

    public static String[] split(String text, String expression) {
        if (text.length() == 0) return new String[0];
        return text.split(expression, -1);
    }

    public static boolean isDigitsOnly(CharSequence str) {
        int len = str.length();
        for (int i = 0; i < len; ) {
            int cp = Character.codePointAt(str, i);
            if (!Character.isDigit(cp)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    public static int getTrimmedLength(CharSequence s) {
        int len = s.length();
        int start = 0;
        while (start < len && s.charAt(start) <= ' ') start++;
        int end = len;
        while (end > start && s.charAt(end - 1) <= ' ') end--;
        return end - start;
    }

    public static String htmlEncode(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '\'': sb.append("&#39;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
