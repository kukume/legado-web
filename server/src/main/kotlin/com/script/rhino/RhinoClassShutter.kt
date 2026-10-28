/*
 * Copyright (c) 2005, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.script.rhino

import org.htmlunit.corejs.javascript.ClassShutter
import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.VarScope
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.lang.reflect.Member
import java.nio.file.FileSystem
import java.nio.file.Path
import java.util.Collections

/**
 * 阻止脚本访问敏感类。
 * 服务端版本在阅读原有列表基础上，额外屏蔽了数据库、Web 框架以及本应用内部的类，
 * 防止书源 JS 读取其他用户数据或操作服务器。
 *
 * 注意：这是黑名单，不可能做到完美——JDK 和依赖里任何一个能读写文件、反射、起线程、发网络请求的类
 * 漏进来都是一次绕过，每次升级 JDK 或新增依赖都可能引入新的可利用类。真正的防线是“只有有‘添加书源’
 * 权限的用户才能把 js 送进引擎”（见 io.legado.web.security.ScriptUrls）。能添加书源的用户本就被信任可写 js，
 * 对这类用户此黑名单只是纵深防御。长期更稳妥的做法是改为白名单，但需用大量真实书源验证常用类后再切换。
 */
object RhinoClassShutter : ClassShutter {

    private val protectedClassNamesMatcher by lazy {
        listOf(
            "java.lang.Class",
            "java.lang.ClassLoader",
            "java.net.URLClassLoader",
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.ProcessImpl",
            "java.lang.UNIXProcess",
            "java.lang.ProcessHandle",
            "java.lang.ThreadGroup",
            "java.io.File",
            "java.io.FileDescriptor",
            "java.io.FileInputStream",
            "java.io.FileOutputStream",
            "java.io.PrintStream",
            "java.io.FileReader",
            "java.io.FileWriter",
            "java.io.PrintWriter",
            "java.io.UnixFileSystem",
            "java.io.WinNTFileSystem",
            "java.io.RandomAccessFile",
            "java.io.ObjectInputStream",
            "java.io.ObjectOutputStream",
            "java.security.AccessController",
            "java.nio.file.Paths",
            "java.nio.file.Files",
            "java.nio.file.FileSystems",
            "java.util.Formatter",
            "sun.misc.Unsafe",
            // 可实例化任意类、调用任意方法的“表达式/反序列化”类（XMLDecoder、Expression、Statement 等）
            "java.beans",
            // 起线程可绕过书源超时和取消；下面几类线程池同理
            "java.lang.Thread",
            "java.util.concurrent.Executors",
            "java.util.concurrent.ThreadPoolExecutor",
            "java.util.concurrent.ScheduledThreadPoolExecutor",
            "java.util.concurrent.ForkJoinPool",
            "java.util.concurrent.CompletableFuture",
            // 直接发网络请求会绕过内网防护（NetworkGuard 只作用于引擎的 OkHttp）。
            // 注意：java.net.URL / URLConnection 不整类屏蔽——书源常用 URL 解析地址（importClass(java.net.URL)），
            // 屏蔽整类会破坏书源；改为在包装实例时屏蔽 openConnection / openStream 等发请求的方法（见 RhinoWrapFactory）
            "java.net.Socket",
            "java.net.ServerSocket",
            "java.net.DatagramSocket",
            "java.net.MulticastSocket",
            "java.net.Proxy",
            "java.net.ProxySelector",
            "javax.net",

            "cn.hutool.core.lang.JarClassLoader",
            "cn.hutool.core.lang.Singleton",
            "cn.hutool.core.util.RuntimeUtil",
            "cn.hutool.core.util.ClassLoaderUtil",
            "cn.hutool.core.util.ReflectUtil",
            "cn.hutool.core.util.SerializeUtil",
            "cn.hutool.core.util.ClassUtil",
            "org.htmlunit.corejs.javascript.DefiningClassLoader",
            "okio.JvmSystemFileSystem",
            "okio.JvmFileHandle",
            "okio.NioSystemFileSystem",
            "okio.NioFileSystemFileHandle",
            "okio.Path",

            "cn.hutool.core.io",
            "cn.hutool.core.bean",
            "cn.hutool.core.lang.reflect",
            "java.nio.file",
            "java.nio.channels",
            "java.lang.reflect",
            "java.lang.invoke",
            "java.lang.management",
            "java.sql",
            "javax.sql",
            "javax.management",
            "javax.script",
            "javax.naming",
            "java.rmi",
            "jdk",
            "com.sun",
            "com.script",
            "org.mozilla",
            "sun",
            // 服务端内部
            "io.legado.web",
            "org.jetbrains.exposed",
            "com.zaxxer",
            "org.sqlite",
            "org.postgresql",
            "io.ktor",
            "io.netty",
            "ch.qos.logback",
            "org.slf4j",
            "com.auth0",
            "at.favre",
        ).let { ClassNameMatcher(it) }
    }

    private val systemClassProtectedName by lazy {
        Collections.unmodifiableSet(
            hashSetOf(
                "load", "loadLibrary", "exit", "getenv", "getProperty", "getProperties",
                "setProperty", "setProperties", "clearProperty", "setOut", "setErr", "setIn",
                "console", "inheritedChannel", "setSecurityManager", "getLogger"
            )
        )
    }

    private val protectedClasses by lazy {
        arrayOf(
            ClassLoader::class.java,
            Class::class.java,
            Member::class.java,
            Context::class.java,
            ObjectInputStream::class.java,
            ObjectOutputStream::class.java,
            okio.FileSystem::class.java,
            okio.FileHandle::class.java,
            okio.Path::class.java,
            FileSystem::class.java,
            Path::class.java,
            // 即使通过别的途径拿到实例（如 new java.lang.Thread(..)）也挡住。
            // URL / URLConnection 不在此列：它们由 RhinoWrapFactory 包装并屏蔽发请求的方法，解析仍可用
            java.net.Socket::class.java,
            java.net.ServerSocket::class.java,
            Thread::class.java,
        )
    }

    fun visibleToScripts(obj: Any): Boolean {
        when (obj) {
            is ClassLoader,
            is Class<*>,
            is Member,
            is Context,
            is ObjectInputStream,
            is ObjectOutputStream,
            is okio.FileSystem,
            is okio.FileHandle,
            is okio.Path,
            is FileSystem,
            is Path,
            is java.net.Socket,
            is java.net.ServerSocket,
            is Thread -> return false
        }
        return visibleToScripts(obj.javaClass.name)
    }

    fun visibleToScripts(clazz: Class<*>): Boolean {
        protectedClasses.forEach {
            if (it.isAssignableFrom(clazz)) {
                return false
            }
        }
        return true
    }

    fun wrapJavaClass(scope: VarScope, javaClass: Class<*>): Scriptable {
        return when (javaClass) {
            System::class.java -> {
                ProtectedNativeJavaClass(scope, javaClass, systemClassProtectedName)
            }

            else -> ProtectedNativeJavaClass(scope, javaClass)
        }
    }

    override fun visibleToScripts(fullClassName: String): Boolean {
        return !protectedClassNamesMatcher.match(fullClassName)
    }

}
