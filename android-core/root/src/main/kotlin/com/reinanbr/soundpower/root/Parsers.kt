// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.root

/** `magisk -v` and `magisk -V`. */
data class MagiskInfo(val versionName: String, val versionCode: Int) {
    val isSupported: Boolean get() = versionCode >= MIN_VERSION_CODE

    companion object {
        /** customize.sh refuses older versions. */
        const val MIN_VERSION_CODE = 24000

        /** Output of `magisk -v; magisk -V`, e.g. "31.1:MAGISK:R" and "31100". */
        fun parse(lines: List<String>): MagiskInfo? {
            val clean = lines.map { it.trim() }.filter { it.isNotEmpty() }
            if (clean.size < 2) return null
            val code = clean[1].toIntOrNull() ?: return null
            return MagiskInfo(clean[0].substringBefore(':'), code)
        }
    }
}

/** The `version` and `versionCode` of a module.prop. */
data class ModuleProp(val version: String, val versionCode: Int)

/** What Magisk has of the module: /data/adb/modules/soundpower and modules_update/soundpower. */
data class ModuleState(
    /** module.prop of the installed module; after an install, already the new one. */
    val installed: ModuleProp? = null,
    /** module.prop of a module waiting in modules_update for the next boot. */
    val staged: ModuleProp? = null,
    val disabled: Boolean = false,
    val removing: Boolean = false,
    /** Magisk's `update` flag: installed or updated since the last boot. */
    val updateFlag: Boolean = false,
) {
    val rebootPending: Boolean get() = staged != null || updateFlag || removing

    /** The version that runs after the next boot, if any. */
    val nextVersion: ModuleProp? get() = if (removing) null else staged ?: installed

    companion object {
        /** Output of [ModuleManager.STATUS_SCRIPT]. */
        fun parse(lines: List<String>): ModuleState {
            val installed = HashMap<String, String>()
            val staged = HashMap<String, String>()
            val flags = HashSet<String>()
            for (raw in lines) {
                val line = raw.trimEnd('\r')
                when {
                    line.startsWith("installed.") -> line.removePrefix("installed.").keyValue()?.let { installed += it }
                    line.startsWith("staged.") -> line.removePrefix("staged.").keyValue()?.let { staged += it }
                    line.startsWith("flag.") -> flags += line.removePrefix("flag.")
                }
            }
            return ModuleState(
                installed = installed.toProp(),
                staged = staged.toProp(),
                disabled = "disable" in flags,
                removing = "remove" in flags,
                updateFlag = "update" in flags,
            )
        }

        private fun String.keyValue(): Pair<String, String>? {
            val i = indexOf('=')
            return if (i <= 0) null else substring(0, i).trim() to substring(i + 1).trim()
        }

        private fun Map<String, String>.toProp(): ModuleProp? {
            val code = get("versionCode")?.toIntOrNull() ?: return null
            return ModuleProp(get("version").orEmpty(), code)
        }
    }
}

/** What [ModuleManager.systemScript] finds on the device. */
data class SystemCheck(
    /** Processes that have the effect library mapped: the audio HAL, when the module works. */
    val loadedIn: List<LoadedProcess> = emptyList(),
    /** The effects configurations the module patches, and whether the patched copy is in place. */
    val configs: Map<String, Boolean> = emptyMap(),
    /** The loaded SELinux policy defines the data directory's type; null if not checked. */
    val policyLoaded: Boolean? = null,
    /** Distinct SELinux denials that mention SoundPower or soundfx. */
    val denials: List<String> = emptyList(),
    /** The last lines of the module's boot log (post-fs-data and the watchdog). */
    val bootLog: List<String> = emptyList(),
) {
    data class LoadedProcess(val pid: Int, val service: String?, val command: String)

    companion object {
        fun parse(lines: List<String>): SystemCheck {
            val loaded = ArrayList<LoadedProcess>()
            val configs = LinkedHashMap<String, Boolean>()
            var policy: Boolean? = null
            val denials = ArrayList<String>()
            val boot = ArrayList<String>()
            for (raw in lines) {
                val line = raw.trimEnd('\r')
                val rest = line.substringAfter(' ', "")
                when (line.substringBefore(' ')) {
                    "loaded" -> {
                        val parts = rest.split(' ', limit = 3)
                        val pid = parts.getOrNull(0)?.toIntOrNull() ?: continue
                        loaded += LoadedProcess(pid, parts.getOrNull(1)?.takeUnless { it == "-" }, parts.getOrNull(2)?.trim().orEmpty())
                    }
                    "config" -> {
                        val state = rest.substringBefore(' ')
                        val path = rest.substringAfter(' ', "")
                        if (path.isNotEmpty() && (state == "patched" || state == "missing")) configs[path] = state == "patched"
                    }
                    "policy" -> policy = rest == "loaded"
                    "denial" -> denials += rest
                    "boot" -> boot += rest
                }
            }
            return SystemCheck(loaded, configs, policy, denials, boot)
        }
    }
}

/** One instance, as the effect's `EFFECT_CMD_DUMP` line in `dumpsys media.audio_flinger` shows it. */
data class EffectDump(
    val libVersion: String,
    val sessionId: Int,
    val ioHandle: Int,
    val state: String,
    val sampleRate: Int,
    val channels: Int,
    val format: Int,
    val cpuAvgPercent: Float,
    val cpuPeakPercent: Float,
    val limiterDb: Float,
    val frames: Long,
    val flags: Int,
    val instances: Int,
    /** Since protocol 1.2; null for older libraries. */
    val volume: StreamVolume? = null,
) {
    val isActive: Boolean get() = state == "active"

    /** The stream volume part of the line, and the input/output peaks since the previous dump. */
    data class StreamVolume(
        val leftDb: Float,
        val rightDb: Float,
        /** True when SoundPower applies the volume itself (direct volume control). */
        val applied: Boolean,
        val stageDb: Float,
        /** -999 means silence. */
        val inputPeakDb: Float,
        val outputPeakDb: Float,
    )

    companion object {
        // effect-lib/src/android/effect_api.cpp, dump().
        private val LINE = Regex(
            """SoundPower (\d+\.\d+\.\d+) \(protocol \d+\.\d+\): session (-?\d+) io (-?\d+), (\w+), """ +
                """(\d+) Hz, (\d+) ch, format 0x([0-9a-f]+), cpu avg ([\d.]+)% peak ([\d.]+)%, """ +
                """limiter (-?[\d.]+) dB, (\d+) frames, flags 0x([0-9a-f]+), (\d+) instances""" +
                """(?:, volume (-?[\d.]+)/(-?[\d.]+) dB (applied|by mixer) \(stage (-?[\d.]+) dB\), """ +
                """peak in (-?[\d.]+) out (-?[\d.]+) dBFS)?""",
        )

        fun parse(lines: List<String>): List<EffectDump> = lines.mapNotNull { line ->
            val g = LINE.find(line)?.groupValues ?: return@mapNotNull null
            EffectDump(
                libVersion = g[1],
                sessionId = g[2].toInt(),
                ioHandle = g[3].toInt(),
                state = g[4],
                sampleRate = g[5].toInt(),
                channels = g[6].toInt(),
                format = g[7].toInt(16),
                cpuAvgPercent = g[8].toFloat(),
                cpuPeakPercent = g[9].toFloat(),
                limiterDb = g[10].toFloat(),
                frames = g[11].toLong(),
                flags = g[12].toInt(16),
                instances = g[13].toInt(),
                volume = if (g[14].isEmpty()) {
                    null
                } else {
                    StreamVolume(g[14].toFloat(), g[15].toFloat(), g[16] == "applied", g[17].toFloat(), g[18].toFloat(), g[19].toFloat())
                },
            )
        }
    }
}
