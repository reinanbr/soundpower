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
        val procDir: String = "/proc",
        /** selinuxfs validates a context written here against the loaded policy. */
        val selinuxContext: String = "/sys/fs/selinux/context",
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

    /** All of `dumpsys media.audio_flinger`, parsed; null without root. */
    suspend fun audioFlinger(): AudioFlingerDump? {
        val r = shell.run("dumpsys -t 5 media.audio_flinger 2>/dev/null; true")
        return if (r.code == ShellResult.NO_ROOT) null else withContext(io) { AudioFlingerDump.parse(r.out) }
    }

    /** What [systemScript] finds; null without root. Reads the logs, so it takes a moment. */
    suspend fun systemCheck(): SystemCheck? {
        val r = shell.run(systemScript())
        return if (r.code == ShellResult.NO_ROOT) null else SystemCheck.parse(r.out)
    }

    /** The output of the module's diag.sh, for a report; null without root or without the module. */
    suspend fun diagnosticsScript(): List<String>? {
        val script = shQuote(paths.moduleDir + "/diag.sh")
        val r = shell.run("[ -f $script ] && sh $script 2>&1")
        return if (r.code == ShellResult.NO_ROOT || r.out.isEmpty()) null else r.out
    }

    /**
     * Restarts the init services whose processes have the effect library loaded (the audio HAL;
     * audioserver follows it), so that they load it again. Playback stops for a moment. The
     * module's boot watchdog is told first, so that the restart does not count as a crash.
     */
    suspend fun restartAudio(): AudioRestart {
        val r = shell.run(restartAudioScript())
        val restarted = r.out.mapNotNull { it.trim().removePrefix("restarted ").takeIf { s -> s != it.trim() } }
        return when {
            r.code == ShellResult.NO_ROOT -> AudioRestart.Failed("no root")
            r.isSuccess && restarted.isNotEmpty() -> AudioRestart.Restarted(restarted)
            r.code == RESTART_NOT_LOADED -> AudioRestart.NotLoaded
            else -> AudioRestart.Failed((r.out + r.err).joinToString("\n").ifBlank { "exit code ${r.code}" })
        }
    }

    sealed interface AudioRestart {
        /** `ctl.restart` was sent for these init services. */
        data class Restarted(val services: List<String>) : AudioRestart

        /** No process has the library loaded: the module is not active. */
        data object NotLoaded : AudioRestart

        data class Failed(val message: String) : AudioRestart
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

    internal fun systemScript(): String {
        val m = shQuote(paths.moduleDir)
        val proc = shQuote(paths.procDir)
        val selinux = shQuote(paths.selinuxContext)
        // A process is found by its mapping of the library, not through lshal: after a restart
        // of the HAL service, lshal stops showing its pid on some devices.
        return """
            (
              # One grep over every maps file: a grep per process takes seconds on a busy phone.
              grep -lF $LIBRARY $proc/[0-9]*/maps 2>/dev/null | while read -r maps; do
                pid=${'$'}{maps%/maps}; pid=${'$'}{pid##*/}
                svc=${'$'}(getprop | sed -n "s/^\[init\.svc_debug_pid\.\(.*\)\]: \[${'$'}pid\]${'$'}/\1/p" | head -n 1)
                echo "loaded ${'$'}pid ${'$'}{svc:--} ${'$'}(tr '\0' ' ' <"${'$'}{maps%/maps}/cmdline" 2>/dev/null)"
              done
              if [ -f $m/configs.list ]; then
                while read -r f; do
                  [ -n "${'$'}f" ] || continue
                  if grep -q 'name="soundpower"' "${'$'}f" 2>/dev/null; then echo "config patched ${'$'}f"; else echo "config missing ${'$'}f"; fi
                done <$m/configs.list
              fi
              if { printf '%s' '$DATA_CONTEXT' >$selinux; } 2>/dev/null; then echo "policy loaded"; else echo "policy missing"; fi
              # The kernel log and the buffers logd copies denials to; all buffers take seconds to read.
              { dmesg 2>/dev/null; logcat -d -b main,system,kernel 2>/dev/null; } | grep 'avc: *denied' | grep -iE 'soundpower|soundfx' |
                sed 's/.*avc: *denied/avc: denied/' | sort -u | head -n $MAX_DENIALS | sed 's/^/denial /'
              [ -f $m/log/boot.log ] && tail -n $BOOT_LOG_LINES $m/log/boot.log | sed 's/^/boot /'
              true
            )
        """.trimIndent()
    }

    internal fun restartAudioScript(): String {
        val proc = shQuote(paths.procDir)
        val requested = shQuote(paths.moduleDir + "/restart-requested")
        // init publishes each running service's pid as init.svc_debug_pid.<name>. A process that
        // is not an init service is left alone: killing it blindly could take down anything.
        return """
            (
              found=; failed=
              for maps in $proc/[0-9]*/maps; do
                grep -qF $LIBRARY "${'$'}maps" 2>/dev/null || continue
                pid=${'$'}{maps%/maps}; pid=${'$'}{pid##*/}
                found=1
                svc=${'$'}(getprop | sed -n "s/^\[init\.svc_debug_pid\.\(.*\)\]: \[${'$'}pid\]${'$'}/\1/p" | head -n 1)
                if [ -n "${'$'}svc" ]; then
                  touch $requested
                  setprop ctl.restart "${'$'}svc" && echo "restarted ${'$'}svc" || failed=1
                else
                  echo "process ${'$'}pid has the effect loaded but is not an init service"; failed=1
                fi
              done
              [ -n "${'$'}found" ] || { echo "no process has the effect loaded"; exit $RESTART_NOT_LOADED; }
              [ -z "${'$'}failed" ] || exit 5
            )
        """.trimIndent()
    }

    companion object {
        const val MODULE_ID = "soundpower"

        /** The effect library's file name, as the audio HAL maps it. */
        const val LIBRARY = "libsoundpower.so"

        private const val RESTART_NOT_LOADED = 3
        private const val MAX_DENIALS = 10
        private const val BOOT_LOG_LINES = 6

        /** The SELinux type the module's sepolicy.rule gives the data directory. */
        const val DATA_CONTEXT = "u:object_r:soundpower_data_file:s0"
    }
}
