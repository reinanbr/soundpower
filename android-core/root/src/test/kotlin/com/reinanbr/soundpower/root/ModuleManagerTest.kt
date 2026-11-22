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
        procDir = "$root/proc",
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

    private fun process(pid: Int, vararg libs: String) {
        File("$root/proc/$pid").mkdirs()
        File("$root/proc/$pid/maps").writeText(libs.joinToString("") { "7000-8000 r-xp 00000000 fd:00 1 /vendor/lib/soundfx/$it\n" })
    }

    private fun services(vararg pids: Pair<String, Int>) {
        val props = pids.joinToString("\n") { (name, pid) -> "[init.svc_debug_pid.$name]: [$pid]" }
        stub("getprop", "cat <<'EOF'\n[ro.debuggable]: [0]\n$props\n[init.svc_debug_pid.other]: [999]\nEOF")
        stub("setprop", "echo \"setprop \$*\" >>'$log'")
    }

    @Test
    fun restartsTheServicesThatLoadedTheEffect() = runTest {
        prop(paths.moduleDir, "v0.3.0", 300)
        process(1200, "libsoundpower.so", "libother.so")
        process(1300, "libother.so")
        process(1400, "libsoundpower.so")
        services("vendor.audio-hal-2-0" to 1200, "audioserver" to 1300, "vendor.audio-hal-x" to 14000, "media.effects" to 1400)
        assertEquals(ModuleManager.AudioRestart.Restarted(listOf("vendor.audio-hal-2-0", "media.effects")), manager.restartAudio())
        assertEquals("setprop ctl.restart vendor.audio-hal-2-0\nsetprop ctl.restart media.effects\n", log.readText())
        // The boot watchdog skips a restart that was asked for.
        assertTrue(File(paths.moduleDir, "restart-requested").exists())
    }

    @Test
    fun aRestartWithoutTheEffectLoadedDoesNothing() = runTest {
        process(1300, "libother.so")
        services("audioserver" to 1300)
        assertEquals(ModuleManager.AudioRestart.NotLoaded, manager.restartAudio())
        assertFalse(log.exists())
        assertFalse(File(paths.moduleDir, "restart-requested").exists())
    }

    @Test
    fun aProcessThatIsNoServiceIsLeftAlone() = runTest {
        process(1500, "libsoundpower.so")
        services("audioserver" to 1300)
        val r = manager.restartAudio()
        assertIs<ModuleManager.AudioRestart.Failed>(r)
        assertTrue("process 1500" in r.message, r.message)
        assertFalse(log.exists())
    }

    @Test
    fun checksTheSystem() = runTest {
        process(1200, "libsoundpower.so")
        process(1300, "libother.so")
        File("$root/proc/1200/cmdline").writeBytes("/vendor/bin/hw/android.hardware.audio@2.0-service\u0000".toByteArray())
        services("vendor.audio-hal-2-0" to 1200)
        val patched = File(root, "vendor/etc/audio_effects.xml").apply { parentFile!!.mkdirs(); writeText("<effect name=\"soundpower\"/>") }
        val stock = File(root, "odm/etc/audio_effects.xml").apply { parentFile!!.mkdirs(); writeText("<effects/>") }
        File(paths.moduleDir).mkdirs()
        File(paths.moduleDir, "configs.list").writeText("$patched\n$stock\n")
        File(paths.moduleDir, "log").mkdirs()
        File(paths.moduleDir, "log/boot.log").writeText((1..8).joinToString("") { "line $it\n" })
        stub("dmesg", "echo '[ 12.5] audit: type=1400 avc:  denied  { read } for name=\"soundpower\" scontext=u:r:hal_audio_default:s0'")
        stub("logcat", "echo 'I auditd: avc:  denied  { read } for name=\"soundpower\" scontext=u:r:hal_audio_default:s0'; echo 'avc: denied { open } for name=\"other\"'")

        val s = manager.systemCheck()!!
        assertEquals(listOf(SystemCheck.LoadedProcess(1200, "vendor.audio-hal-2-0", "/vendor/bin/hw/android.hardware.audio@2.0-service")), s.loadedIn)
        assertEquals(mapOf(patched.path to true, stock.path to false), s.configs)
        // The fake selinuxfs node is missing, so the type counts as not loaded.
        assertEquals(false, s.policyLoaded)
        // The same denial from the kernel log and logcat, once; unrelated ones are left out.
        assertEquals(listOf("avc: denied  { read } for name=\"soundpower\" scontext=u:r:hal_audio_default:s0"), s.denials)
        assertEquals((3..8).map { "line $it" }, s.bootLog)
    }

    @Test
    fun aPolicyThatDefinesTheTypeIsReported() = runTest {
        val node = File(root, "selinux-context").apply { createNewFile() }
        val s = ModuleManager(shell, paths.copy(selinuxContext = node.path)).systemCheck()!!
        assertEquals(true, s.policyLoaded)
        assertTrue(s.loadedIn.isEmpty())
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
