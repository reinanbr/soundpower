// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/** Kinds of audio output a preset can be assigned to. */
enum class OutputKind { SPEAKER, WIRED, BLUETOOTH, USB, OTHER }

/**
 * An output presets are assigned to: every output of a [kind], or one device of that kind when
 * [address] is set (a Bluetooth address, or a USB device's name). A device's own assignment
 * wins over its kind's.
 */
@Serializable
data class OutputKey(val kind: OutputKind, val address: String? = null) {
    val generic: OutputKey get() = if (address == null) this else OutputKey(kind)
}

/** The output music plays on now, with the name its device reports (null for the speaker). */
data class AudioOutput(val key: OutputKey, val name: String?)

@Serializable
data class Assignment(
    val output: OutputKey,
    val presetId: String,
    /** The device's name when it was assigned, to show it while it is away. */
    val name: String? = null,
)

/**
 * The user's presets and what the app does with them by itself: the preset assigned to each
 * output, applied when music moves to that output, and AutoEQ suggestions for new headphones.
 */
@Serializable
data class PresetLibrary(
    val presets: List<Preset> = emptyList(),
    val assignments: List<Assignment> = emptyList(),
    val switchByOutput: Boolean = true,
    val suggestAutoEq: Boolean = true,
    /**
     * The output the last switch was decided on: presets switch when music moves to another
     * output, not when the app starts again on the same one.
     */
    val lastOutput: OutputKey? = null,
) {
    fun preset(id: String?): Preset? = presets.firstOrNull { it.id == id }

    /** The assignment that applies to [output]: the device's own, else its kind's. */
    fun assignmentFor(output: OutputKey): Assignment? =
        assignments.firstOrNull { it.output == output } ?: assignments.firstOrNull { it.output == output.generic }

    fun outputsOf(presetId: String): List<Assignment> = assignments.filter { it.presetId == presetId }

    /** [presetId] on exactly [outputs] (output to device name); those outputs lose their previous preset. */
    fun assign(presetId: String, outputs: Map<OutputKey, String?>): PresetLibrary = copy(
        assignments = assignments.filter { it.presetId != presetId && it.output !in outputs } +
            outputs.map { (key, name) -> Assignment(key, presetId, name) },
    )

    /** Adds [preset], or replaces the user preset with its id. */
    fun put(preset: Preset): PresetLibrary {
        val i = presets.indexOfFirst { it.id == preset.id }
        return copy(presets = if (i < 0) presets + preset else presets.toMutableList().also { it[i] = preset })
    }

    /** Removes the user preset [id] and its assignments. */
    fun remove(id: String): PresetLibrary = copy(presets = presets.filter { it.id != id }, assignments = assignments.filter { it.presetId != id })

    /** [name], or the first of "[name] 2", "[name] 3"... no user preset has. */
    fun uniqueName(name: String): String {
        val taken = presets.map { it.name.lowercase() }.toSet()
        if (name.lowercase() !in taken) return name
        return generateSequence(2) { it + 1 }.map { "$name $it" }.first { it.lowercase() !in taken }
    }

    /** Adds [imported] user presets, renaming those whose names are taken. */
    fun withImported(imported: List<Preset>): PresetLibrary =
        imported.fold(this) { lib, p -> lib.put(p.copy(name = lib.uniqueName(p.name))) }

    companion object {
        val DEFAULT = PresetLibrary()

        fun fromJson(text: String): PresetLibrary = AudioSettings.JSON.decodeFromString(serializer(), text)

        fun toJson(library: PresetLibrary): String = AudioSettings.JSON.encodeToString(serializer(), library)
    }
}

/** Everything the user set, as one file: the audio settings, presets and assignments. */
@Serializable
data class SettingsBackup(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val settings: AudioSettings,
    val library: PresetLibrary,
) {
    fun encode(): String = AudioSettings.JSON.encodeToString(serializer(), this)

    companion object {
        const val FORMAT = "soundpower-settings"
        const val VERSION = 1

        /** Throws [IllegalArgumentException] if [text] is not a settings file this version can read. */
        fun decode(text: String): SettingsBackup {
            val backup = try {
                AudioSettings.JSON.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("not a SoundPower settings file", e)
            }
            require(backup.format == FORMAT) { "not a SoundPower settings file" }
            require(backup.version <= VERSION) { "made by a newer version of SoundPower" }
            return backup
        }
    }
}
