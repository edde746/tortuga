package com.edde746.tortuga.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShaderDirectoriesTest {
    private val root = Files.createTempDirectory("shaders").toFile().apply { deleteOnExit() }
    private val bundled = File(root, "bundled")
    private val custom = File(root, "custom")
    private var installs = 0

    private val files = ShaderDirectories(ShaderRenderer.LIBPLACEBO, bundled.path, custom.path, installBundled = {
        installs++
        File(bundled, "nvscaler").mkdirs()
        File(bundled, "nvscaler/NVScaler.glsl").writeText("//!HOOK MAIN")
    }, io = Dispatchers.Unconfined)

    @Test
    fun theAppsShadersAreInstalledOnceBeforeTheirFirstPath() = runBlocking<Unit> {
        assertEquals(emptyList(), files.paths(emptyList()))
        assertEquals(0, installs, "nothing bundled asked for yet")
        val nvscaler = listOf(ShaderFile.Bundled("nvscaler/NVScaler.glsl"))
        assertEquals(listOf(File(bundled, "nvscaler/NVScaler.glsl").path), files.paths(nvscaler))
        files.paths(nvscaler)
        assertEquals(1, installs)
    }

    @Test
    fun anImportedShaderIsKeptUnderANewNameUntilDeleted() = runBlocking<Unit> {
        val name = files.import("//!HOOK LUMA\n")
        val copy = File(custom, name)
        assertEquals("//!HOOK LUMA\n", copy.readText())
        assertEquals(listOf(copy.path), files.paths(listOf(ShaderFile.Custom(name))))
        assertTrue(files.import("//!HOOK LUMA\n") != name, "each import gets its own file")
        files.delete(name)
        assertFalse(copy.exists())
        assertFailsWith<IllegalArgumentException> { files.paths(listOf(ShaderFile.Custom(name))) }
    }

    @Test
    fun aNameThatIsntAnImportsReachesNoFile() = runBlocking<Unit> {
        File(root, "secret.glsl").writeText("x")
        assertFailsWith<IllegalArgumentException> { files.paths(listOf(ShaderFile.Custom("../secret.glsl"))) }
        assertFailsWith<IllegalArgumentException> { files.delete("../secret.glsl") }
        assertTrue(File(root, "secret.glsl").exists())
    }
}
