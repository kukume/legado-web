package io.legado.app

import io.legado.app.data.entities.BookSource
import io.legado.app.help.engine.Engine
import io.legado.app.help.engine.EngineContext
import io.legado.app.help.engine.MemoryEngineStore
import io.legado.app.help.source.exploreKinds
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test

/**
 * 用真实书源文件做兼容性检查（设置环境变量 LEGADO_TEST_SOURCE 指向书源 JSON 才会运行）
 */
class UserSourceCompatTest {

    @Test
    fun loadRealSource() {
        val path = System.getenv("LEGADO_TEST_SOURCE") ?: return
        Engine.store = MemoryEngineStore()
        val source = GSON.fromJsonArray<BookSource>(File(path).readText()).getOrThrow().first()
        runBlocking {
            withContext(Dispatchers.IO + EngineContext(1)) {
                println("jsLib -> " + source.evalJS("typeof getSubDomain + ',' + typeof CompatibilityUtils"))
                println("android.util.Base64 -> " + source.evalJS("Packages.android.util.Base64.encodeToString(java.strToBytes('abc'), 2)"))
                println("loginUi -> v2=" + source.isLoginUiV2() + " length=" + source.loginUi?.length)
                val kinds = source.exploreKinds()
                println("explore kinds -> ${kinds.size}")
                kinds.take(8).forEach { println("  ${it.title} :: ${it.url?.take(80)}") }
            }
        }
    }
}
