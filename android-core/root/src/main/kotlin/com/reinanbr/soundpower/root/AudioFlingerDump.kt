// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.root

/**
 * What `dumpsys media.audio_flinger` shows of playback: the output threads, the tracks playing on
 * them and their effect chains, plus the processes AudioFlinger knows by pid. The text changes
 * between Android versions (columns are added, HAL dumps are printed in between), so tables are
 * read by their headers and anything not recognised is skipped.
 */
data class AudioFlingerDump(
    val threads: List<OutputThread>,
    /** Package or process name by pid, from the notification clients and the session references. */
    val processes: Map<Int, String>,
    /** Effect libraries the effects factory loaded, and the ones it could not load. */
    val librariesLoaded: List<String>,
    val librariesNotLoaded: List<String>,
) {
    /** One playback thread: a mixer, a direct or offloaded output, a duplicating one. */
    data class OutputThread(
        val io: Int,
        val name: String,
        /** MIXER, DIRECT, OFFLOAD, DUPLICATING, MMAP_PLAYBACK, SPATIALIZER, BIT_PERFECT... */
        val type: String,
        val sampleRate: Int?,
        /** As AudioFlinger prints them, e.g. "0x8 (AUDIO_DEVICE_OUT_WIRED_HEADPHONE)"; null if none. */
        val devices: String?,
        val tracks: List<Track>,
        val chains: List<EffectChain>,
    ) {
        /** The thread mixes in software, where effects that are not offloadable can run. */
        val mixesInSoftware: Boolean get() = type in SOFTWARE_THREADS

        fun chain(session: Int): EffectChain? = chains.firstOrNull { it.session == session }
    }

    data class Track(
        val id: Int,
        val active: Boolean,
        /** Mixed by the fast mixer: Android lets no processing effect into its session. */
        val fast: Boolean,
        val clientPid: Int,
        val session: Int,
        /** `audio_stream_type_t` (3 = music), if the table has the column. */
        val streamType: Int?,
    ) {
        val isMusic: Boolean get() = streamType == null || streamType == STREAM_MUSIC
    }

    data class EffectChain(val session: Int, val effects: List<ChainEffect>) {
        /**
         * The effect that controls this session's volume: AudioFlinger picks the last enabled
         * effect that implements volume control; the others are only told the volume.
         */
        val volumeController: ChainEffect? get() = effects.lastOrNull { it.enabled && it.controlsVolume }

        val soundPower: ChainEffect? get() = effects.firstOrNull { it.isSoundPower }
    }

    data class ChainEffect(
        val id: Int,
        val name: String,
        val implementor: String?,
        val uuid: String?,
        val enabled: Boolean,
        /** `effect_descriptor_t.flags`. */
        val flags: Int,
        /** The process that owns the effect handle (audioserver for effects the platform attaches). */
        val clientPid: Int?,
        /** SoundPower's own dump line, for SoundPower instances. */
        val dump: EffectDump? = null,
    ) {
        val isSoundPower: Boolean get() = dump != null || uuid == SOUNDPOWER_UUID

        /** `EFFECT_FLAG_VOLUME_CTRL`: it can apply the volume instead of the mixer. */
        val controlsVolume: Boolean get() = (flags shr VOLUME_SHIFT) and VOLUME_MASK == VOLUME_CTRL
    }

    /** A SoundPower instance with the thread and chain it sits in. */
    data class Placed(val thread: OutputThread, val chain: EffectChain, val effect: ChainEffect)

    val soundPowerInstances: List<Placed>
        get() = threads.flatMap { t ->
            t.chains.flatMap { c -> c.effects.filter { it.isSoundPower }.map { Placed(t, c, it) } }
        }

    companion object {
        /** effect-lib's implementation UUID (protocol.md, "Effect identity"). */
        const val SOUNDPOWER_UUID = "23e79ffb-ab65-4cd7-a768-b493ded3fabe"

        const val STREAM_MUSIC = 3

        private val SOFTWARE_THREADS = setOf("MIXER", "DUPLICATING", "SPATIALIZER")
        private const val VOLUME_SHIFT = 6
        private const val VOLUME_MASK = 7
        private const val VOLUME_CTRL = 1

        private val THREAD = Regex("""^Output thread 0x[0-9a-fA-F]+, name (\S+), tid \d+, type \d+ \((\w+)\)""")

        /** Top-level sections that come after the output threads. */
        private val AFTER_THREADS = listOf("Input thread", "Record thread", "Patches:", "Device Effects:")
        private val IO_HANDLE = Regex("""^ {2}I/O handle: (\d+)""")
        private val SAMPLE_RATE = Regex("""^ {2}Sample rate: (\d+) Hz""")
        private val DEVICES = Regex("""^ {2}Output devices?: (.*)$""")
        private val CHAIN = Regex("""^\s+\d+ effects for session (-?\d+)""")
        private val EFFECT = Regex("""^\s+Effect ID (\d+):\s*$""")
        private val EFFECT_HAL_DUMP = Regex("""^\s+Effect ID \d+ HAL dump:""")
        private val FLAGS = Regex("""^\s+- flags: ([0-9a-fA-F]+)""")
        private val NAME = Regex("""^\s+- name: (.*)$""")
        private val IMPLEMENTOR = Regex("""^\s+- implementor: (.*)$""")
        private val UUID = Regex("""^\s+- UUID: ([0-9a-fA-F-]+)""")
        private val CLIENTS = Regex("""^\s+\d+ Clients?:""")
        private val FIRST_NUMBER = Regex("""^\s+(\d+)\s""")
        private val PROCESS_ROW = Regex("""^\s+(\d+)\s+\d+\s+(\S+)\s*$""")
        private val SESSION_REF_ROW = Regex("""^\s+-?\d+\s+\d+\s+(\d+)\s+\d+\s+(\S+)\s*$""")
        private val LIBRARY = Regex("""^ Library (\S+)""")
        private val FAST_INDEX = Regex("""^F\d+$""")
        private val WORD = Regex("""\S+""")
        private val SPACES = Regex("""\s+""")

        fun parse(lines: List<String>): AudioFlingerDump = Parser().apply { lines.forEach { line(it.trimEnd('\r')) } }.finish()

        /** The end column of each word of a right-aligned table header (the first of repeated words). */
        private fun columnEnds(header: String): Map<String, Int> {
            val ends = HashMap<String, Int>()
            WORD.findAll(header).forEach { ends.putIfAbsent(it.value, it.range.last) }
            return ends
        }

        /**
         * A track row: values are right-aligned under their header words, and the type field in
         * front may be empty ("F1" for a fast track, "S" for a static one, "P" for a patch track).
         */
        private fun parseTrack(line: String, columns: Map<String, Int>): TrackRow? {
            val tokens = WORD.findAll(line).toList()
            fun at(column: String): String? {
                val end = columns[column] ?: return null
                return tokens.firstOrNull { it.range.last == end }?.value
            }
            val idEnd = columns["Id"] ?: return null
            val idIndex = tokens.indexOfFirst { it.range.last == idEnd }
            if (idIndex < 0) return null
            val id = tokens[idIndex].value.toIntOrNull() ?: return null
            val active = when (at("Active")) {
                "yes" -> true
                "no" -> false
                else -> return null
            }
            val client = at("Client")?.toIntOrNull() ?: return null
            val session = at("Session")?.toIntOrNull() ?: return null
            val lead = tokens.subList(0, idIndex).map { it.value }
            val track = Track(id, active, lead.any { FAST_INDEX.matches(it) }, client, session, at("ST")?.toIntOrNull())
            // A patch track carries another thread's audio.
            return TrackRow(track.takeUnless { "P" in lead })
        }

        /** A row of the track table; [track] is null for rows that are not a player's track. */
        private class TrackRow(val track: Track?)
    }

    private class Parser {
        val threads = ArrayList<OutputThread>()
        val processes = HashMap<Int, String>()
        val loaded = ArrayList<String>()
        val notLoaded = ArrayList<String>()

        private var thread: ThreadBuilder? = null
        private var chain: ChainBuilder? = null
        private var effect: EffectBuilder? = null
        private var libraries: MutableList<String>? = null
        private var table = Table.NONE

        /** Column ends of a track table header, while its rows follow. */
        private var trackColumns: Map<String, Int>? = null

        /** Header words of an effect's "Session State ... Enabled ..." row, whose values come next. */
        private var effectColumns: List<String>? = null
        private var clientsFollow = false
        private var halDumpFollows = false

        fun line(line: String) {
            if (continueTable(line)) return
            when {
                line.startsWith("Libraries NOT loaded:") -> libraries = notLoaded
                line.startsWith("Libraries loaded:") -> libraries = loaded
                line.startsWith("Notification Clients:") -> table = Table.PROCESSES
                line.startsWith("Global session refs:") -> table = Table.SESSION_REFS
                THREAD.containsMatchIn(line) -> {
                    endThread()
                    val m = THREAD.find(line)!!
                    thread = ThreadBuilder(m.groupValues[1], m.groupValues[2])
                }
                AFTER_THREADS.any { line.startsWith(it) } -> endThread()
                else -> threadLine(line)
            }
        }

        fun finish(): AudioFlingerDump {
            endThread()
            return AudioFlingerDump(threads.toList(), processes.toMap(), loaded.toList(), notLoaded.toList())
        }

        /** Rows of a table started on an earlier line; true if [line] was one. */
        private fun continueTable(line: String): Boolean {
            libraries?.let { list ->
                LIBRARY.find(line)?.let { list += it.groupValues[1] }
                if (line.startsWith(" ")) return true
                libraries = null
            }
            when (table) {
                Table.PROCESSES -> PROCESS_ROW.find(line)?.let { m ->
                    processes[m.groupValues[1].toInt()] = m.groupValues[2]
                    return true
                }
                Table.SESSION_REFS -> SESSION_REF_ROW.find(line)?.let { m ->
                    processes.putIfAbsent(m.groupValues[1].toInt(), m.groupValues[2])
                    return true
                }
                Table.NONE -> {}
            }
            if (table != Table.NONE) {
                val header = line.trim().let { it.startsWith("pid") || it.startsWith("session") }
                if (header) return true
                table = Table.NONE
            }
            trackColumns?.let { columns ->
                val row = parseTrack(line, columns)
                if (row != null) {
                    row.track?.let { thread?.tracks?.add(it) }
                    return true
                }
                trackColumns = null
            }
            effectColumns?.let { columns ->
                effectColumns = null
                val values = line.trim().split(SPACES)
                val i = columns.indexOf("Enabled")
                if (i in values.indices) effect?.enabled = values[i] == "y"
                return true
            }
            if (clientsFollow) {
                if (line.trim().startsWith("Pid")) return true
                clientsFollow = false
                FIRST_NUMBER.find(line)?.let { effect?.clientPid = it.groupValues[1].toInt() }
                return true
            }
            if (halDumpFollows) {
                halDumpFollows = false
                val dump = EffectDump.parse(listOf(line)).firstOrNull()
                val effects = chain?.effects
                if (dump != null && effects != null && effects.isNotEmpty() && effects.last().isSoundPower) {
                    effects[effects.lastIndex] = effects.last().copy(dump = dump)
                    return true
                }
            }
            return false
        }

        private fun threadLine(line: String) {
            val t = thread ?: return
            // Lines indented by exactly two spaces belong to the thread itself; they end a chain.
            val threadLevel = line.length > 2 && line.startsWith("  ") && !line[2].isWhitespace()
            if (threadLevel) {
                endChain()
                IO_HANDLE.find(line)?.let { t.io = it.groupValues[1].toInt() }
                SAMPLE_RATE.find(line)?.let { if (t.sampleRate == null) t.sampleRate = it.groupValues[1].toInt() }
                DEVICES.find(line)?.let { if (t.devices == null) t.devices = it.groupValues[1].trim() }
                return
            }
            if (line.contains(" Id Active ") && line.contains(" Session ")) {
                trackColumns = columnEnds(line)
                return
            }
            CHAIN.find(line)?.let { m ->
                endChain()
                chain = ChainBuilder(m.groupValues[1].toInt())
                return
            }
            if (chain == null) return
            EFFECT.find(line)?.let { m ->
                endEffect()
                effect = EffectBuilder(m.groupValues[1].toInt())
                return
            }
            if (EFFECT_HAL_DUMP.containsMatchIn(line)) {
                endEffect()
                halDumpFollows = true
                return
            }
            val e = effect ?: return
            when {
                line.contains("Session") && line.contains("Enabled") -> effectColumns = line.trim().trimEnd(':').split(SPACES)
                CLIENTS.containsMatchIn(line) -> clientsFollow = true
                else -> {
                    UUID.find(line)?.let { e.uuid = it.groupValues[1].lowercase() }
                    FLAGS.find(line)?.let { e.flags = it.groupValues[1].toLong(16).toInt() }
                    NAME.find(line)?.let { e.name = it.groupValues[1].trim() }
                    IMPLEMENTOR.find(line)?.let { e.implementor = it.groupValues[1].trim() }
                }
            }
        }

        private fun endEffect() {
            effect?.let { e -> chain?.effects?.add(e.build()) }
            effect = null
        }

        private fun endChain() {
            endEffect()
            chain?.let { c -> thread?.chains?.add(EffectChain(c.session, c.effects.toList())) }
            chain = null
            halDumpFollows = false
        }

        private fun endThread() {
            endChain()
            thread?.let { threads += it.build() }
            thread = null
            trackColumns = null
        }
    }

    private enum class Table { NONE, PROCESSES, SESSION_REFS }

    private class ThreadBuilder(val name: String, val type: String) {
        /** "AudioOut_D" is io handle 0xD; the "I/O handle" line, where there is one, says it too. */
        var io: Int = name.substringAfterLast('_').toIntOrNull(16) ?: -1
        var sampleRate: Int? = null
        var devices: String? = null
        val tracks = ArrayList<Track>()
        val chains = ArrayList<EffectChain>()

        fun build() = OutputThread(
            io, name, type, sampleRate,
            devices?.takeUnless { it.isEmpty() || it.contains("Empty device") || it.startsWith("0 (") },
            tracks.toList(), chains.toList(),
        )
    }

    private class ChainBuilder(val session: Int) {
        val effects = ArrayList<ChainEffect>()
    }

    private class EffectBuilder(val id: Int) {
        var name = ""
        var implementor: String? = null
        var uuid: String? = null
        var enabled = false
        var flags = 0
        var clientPid: Int? = null

        fun build() = ChainEffect(id, name, implementor, uuid, enabled, flags, clientPid)
    }
}
