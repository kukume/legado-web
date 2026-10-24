package io.legado.web

import io.legado.web.service.UpdateService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateServiceTest {

    @Test
    fun parseImage() {
        assertEquals(Triple("ghcr.io", "kukume/legado-web", "latest"), UpdateService.parseImage("ghcr.io/kukume/legado-web"))
        assertEquals(Triple("ghcr.io", "kukume/legado-web", "dev"), UpdateService.parseImage("ghcr.io/kukume/legado-web:dev"))
        assertEquals(Triple("registry-1.docker.io", "kukume/legado-web", "latest"), UpdateService.parseImage("kukume/legado-web"))
        assertEquals(Triple("registry-1.docker.io", "library/nginx", "1.27"), UpdateService.parseImage("nginx:1.27"))
        assertEquals(Triple("localhost:5000", "a/b", "latest"), UpdateService.parseImage("localhost:5000/a/b"))
    }

    @Test
    fun hasUpdateOnlyWhenRemoteIsNewer() {
        val s = UpdateService(buildTime = 1000, image = "ghcr.io/x/y", enabled = { true })
        assertFalse(s.status().hasUpdate)
        // 本地开发：没有构建时间，不提示
        assertFalse(UpdateService(buildTime = null, image = "ghcr.io/x/y", enabled = { true }).status().hasUpdate)
        assertTrue(s.status().enabled)
    }
}
