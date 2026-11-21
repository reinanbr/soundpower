// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.root

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Runs the root scripts in the host's /bin/sh against a temporary directory tree. `chcon` and
 * `magisk` are stubs on PATH that log their arguments.
 */
class ModuleManagerTest {
    private val root: File = Files.createTempDirectory("sp-root").toFile()
    private val bin = File(root, "bin").apply { mkdirs() }
    private val log = File(root, "calls.log")
    private val paths = ModuleManager.Paths(
        moduleDir = "$root/adb/modules/soundpower",
        stagedDir = "$root/adb/modules_update/soundpower",
        dataDir = "$root/vendor/soundpower",
    )
    private val shell = HostShell(bin)
    private val manager = ModuleManager(shell, paths)

    init {
        stub("chcon", "echo \"chcon \$*\" >>'$log'")
        stub("magisk", "case \"\$1\" in -v) echo 31.1:MAGISK:R ;; -V) echo 31100 ;; --install-module) echo \"- Installing \$2\"; echo \"- Done\" ;; esac")
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun stub(name: String, body: String) {
        File(bin, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }
    }

    private fun prop(dir: String, version: String, code: Int) {
        File(dir).mkdirs()
        File(dir, "module.prop").writeText("id=soundpower\nversion=$version\nversionCode=$code\n")
    }

    @Test
    fun readsTheModuleState() = runTest {
        assertEquals(ModuleState(), manager.module())

        prop(paths.moduleDir, "v0.1.0", 100)
        prop(paths.stagedDir, "v0.2.0", 200)
        File(paths.moduleDir, "update").createNewFile()
        File(paths.moduleDir, "disable").createNewFile()
        val s = manager.module()
        assertEquals(ModuleProp("v0.1.0", 100), s.installed)
        assertEquals(ModuleProp("v0.2.0", 200), s.staged)
        assertTrue(s.updateFlag && s.disabled)
        assertFalse(s.removing)

        assertTrue(manager.enable())
        assertFalse(manager.module().disabled)
    }

    @Test
    fun magiskAndInstall() = runTest {
        assertEquals(MagiskInfo("31.1", 31100), manager.magisk())
        val lines = ArrayList<String>()
        assertTrue(manager.install(File("/sdcard/a b.zip")) { lines += it })
        assertEquals(listOf("- Installing /sdcard/a b.zip", "- Done"), lines)
    }

    @Test
    fun writesTheStateFileAtomically() = runTest {
        val staging = File(root, "staging.bin")
        val missing = manager.writeStateFile(byteArrayOf(1, 2, 3), staging)
        assertIs<ModuleManager.StateWrite.Failed>(missing)
        assertTrue("does not exist" in missing.message)

        File(paths.dataDir).mkdirs()
        val state = File(paths.dataDir, "state.bin")
        assertEquals(ModuleManager.StateWrite.Written, manager.writeStateFile(byteArrayOf(1, 2, 3), staging))
        assertContentEquals(byteArrayOf(1, 2, 3), state.readBytes())
        val inode = Files.getAttribute(state.toPath(), "unix:ino")
        assertEquals("rw-r--r--", Files.getPosixFilePermissions(state.toPath()).let(java.nio.file.attribute.PosixFilePermissions::toString))
        assertEquals("chcon ${ModuleManager.DATA_CONTEXT} ${paths.dataDir}/.state.bin.new\n", log.readText())

        assertEquals(ModuleManager.StateWrite.Unchanged, manager.writeStateFile(byteArrayOf(1, 2, 3), staging))
        assertEquals(inode, Files.getAttribute(state.toPath(), "unix:ino"))

        assertEquals(ModuleManager.StateWrite.Written, manager.writeStateFile(byteArrayOf(4, 5), staging))
        assertContentEquals(byteArrayOf(4, 5), state.readBytes())
        assertFalse(File(paths.dataDir, ".state.bin.new").exists())
    }

    @Test
    fun aFailedLabelKeepsTheOldFile() = runTest {
        File(paths.dataDir).mkdirs()
        val state = File(paths.dataDir, "state.bin").apply { writeBytes(byteArrayOf(9)) }
        stub("chcon", "exit 1")
        assertIs<ModuleManager.StateWrite.Failed>(manager.writeStateFile(byteArrayOf(1), File(root, "staging.bin")))
        assertContentEquals(byteArrayOf(9), state.readBytes())
        assertFalse(File(paths.dataDir, ".state.bin.new").exists())
    }

    /** [RootShell] on a fresh host `sh` per script, with [bin] first on PATH. */
    private class HostShell(private val bin: File) : RootShell {
        override suspend fun requestRoot() = true

        override suspend fun run(script: String, onLine: ((String) -> Unit)?): ShellResult {
            val p = ProcessBuilder("/bin/sh", "-c", script)
                .apply { environment()["PATH"] = "$bin:/usr/bin:/bin" }
                .start()
            val out = p.inputStream.bufferedReader().readLines().onEach { onLine?.invoke(it) }
            val err = p.errorStream.bufferedReader().readLines()
            return ShellResult(p.waitFor(), out, err)
        }
    }
}
