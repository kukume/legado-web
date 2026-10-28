package com.script.rhino

import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.VarScope

/**
 * 允许脚本访问对象，但屏蔽其中指定的方法名。
 * 用于 java.net.URL / URLConnection：允许解析地址（getHost、getPath 等），
 * 屏蔽 openConnection / openStream 等发起网络请求的方法，避免绕过内网防护。
 */
class ProtectedNativeJavaObject(
    scope: VarScope?,
    javaObject: Any,
    staticType: Class<*>?,
    private val protectedName: Set<String>,
) : CatchableNativeJavaObject(scope, javaObject, staticType) {

    override fun has(name: String, start: Scriptable): Boolean {
        if (name in protectedName) return false
        return super.has(name, start)
    }

    override fun get(name: String, start: Scriptable): Any? {
        if (name in protectedName) return NOT_FOUND
        return super.get(name, start)
    }
}
