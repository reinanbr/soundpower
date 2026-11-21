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
) {
    val isActive: Boolean get() = state == "active"

    companion object {
        // effect-lib/src/android/effect_api.cpp, dump().
        private val LINE = Regex(
            """SoundPower (\d+\.\d+\.\d+) \(protocol \d+\.\d+\): session (-?\d+) io (-?\d+), (\w+), """ +
                """(\d+) Hz, (\d+) ch, format 0x([0-9a-f]+), cpu avg ([\d.]+)% peak ([\d.]+)%, """ +
                """limiter (-?[\d.]+) dB, (\d+) frames, flags 0x([0-9a-f]+), (\d+) instances""",
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
            )
        }
    }
}
