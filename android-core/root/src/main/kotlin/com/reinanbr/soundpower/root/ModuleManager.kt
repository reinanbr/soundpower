// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.root

import com.reinanbr.soundpower.protocol.Protocol
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Everything the app does as root. [paths] and [io] only change in tests. */
class ModuleManager(
    private val shell: RootShell,
    private val paths: Paths = Paths(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Where things are on the device. */
    data class Paths(
        val moduleDir: String = "/data/adb/modules/$MODULE_ID",
        val stagedDir: String = "/data/adb/modules_update/$MODULE_ID",
        val dataDir: String = Protocol.DATA_DIR,
    )

    suspend fun requestRoot(): Boolean = shell.requestRoot()

    /** Null if the `magisk` binary is missing (no Magisk, or another root manager). */
    suspend fun magisk(): MagiskInfo? {
        val r = shell.run("magisk -v && magisk -V")
        return if (r.isSuccess) MagiskInfo.parse(r.out) else null
    }

    suspend fun module(): ModuleState = ModuleState.parse(shell.run(statusScript()).out)

    /** `magisk --install-module`, streaming its output to [onLine]. True if it succeeded. */
    suspend fun install(zip: File, onLine: (String) -> Unit): Boolean =
        shell.run("magisk --install-module ${shQuote(zip.absolutePath)} 2>&1", onLine).isSuccess

    /** Removes the `disable` flag (set by the user or by the boot watchdog); takes effect at boot. */
    suspend fun enable(): Boolean = shell.run("rm -f ${shQuote(paths.moduleDir + "/disable")}").isSuccess

    suspend fun reboot() {
        shell.run("/system/bin/svc power reboot || /system/bin/reboot")
    }

    /**
     * Replaces the state file with [bytes], atomically: the effect never reads a half-written
     * file, and the rename gives it a new inode, which makes the effect reload it.
     * [staging] is a file the app can write and root can read.
     */
    suspend fun writeStateFile(bytes: ByteArray, staging: File): StateWrite {
        withContext(io) { staging.writeBytes(bytes) }
        val r = shell.run(stateWriteScript(staging.absolutePath))
        val last = r.out.lastOrNull()?.trim()
        return when {
            r.isSuccess && last == "unchanged" -> StateWrite.Unchanged
            r.isSuccess && last == "written" -> StateWrite.Written
            else -> StateWrite.Failed((r.out + r.err).joinToString("\n").ifBlank { "exit code ${r.code}" })
        }
    }

    /** The effect's lines in `dumpsys media.audio_flinger`, or null without root. */
    suspend fun effectDumps(): List<EffectDump>? {
        val r = shell.run("dumpsys -t 5 media.audio_flinger 2>/dev/null | grep -F 'SoundPower '; true")
        return if (r.code == ShellResult.NO_ROOT) null else EffectDump.parse(r.out)
    }

    sealed interface StateWrite {
        data object Written : StateWrite
        data object Unchanged : StateWrite
        data class Failed(val message: String) : StateWrite
    }

    internal fun statusScript(): String {
        val m = shQuote(paths.moduleDir)
        val s = shQuote(paths.stagedDir)
        return """
            [ -f $m/module.prop ] && sed 's/^/installed./' $m/module.prop
            [ -f $s/module.prop ] && sed 's/^/staged./' $s/module.prop
            for f in disable remove update; do [ -e $m/${'$'}f ] && echo flag.${'$'}f; done
            true
        """.trimIndent()
    }

    internal fun stateWriteScript(source: String): String {
        val dir = shQuote(paths.dataDir)
        val src = shQuote(source)
        val tmp = shQuote(paths.dataDir + "/.state.bin.new")
        val dst = shQuote(paths.dataDir + "/state.bin")
        // A subshell, so that `exit` leaves the long-lived shell alone.
        return """
            (
              [ -d $dir ] || { echo "the data directory does not exist; is the module active?"; exit 3; }
              cmp -s $src $dst && { echo unchanged; exit 0; }
              cp $src $tmp && chmod 0644 $tmp && chcon $DATA_CONTEXT $tmp && mv -f $tmp $dst || { rm -f $tmp; exit 4; }
              echo written
            )
        """.trimIndent()
    }

    companion object {
        const val MODULE_ID = "soundpower"

        /** The SELinux type the module's sepolicy.rule gives the data directory. */
        const val DATA_CONTEXT = "u:object_r:soundpower_data_file:s0"
    }
}
