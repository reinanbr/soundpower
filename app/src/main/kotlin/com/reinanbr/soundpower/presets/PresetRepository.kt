// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.presets

import com.reinanbr.soundpower.data.PresetStore
import com.reinanbr.soundpower.eq.EqRepository
import com.reinanbr.soundpower.model.Assignment
import com.reinanbr.soundpower.model.AudioSettings
import com.reinanbr.soundpower.model.AudioOutput
import com.reinanbr.soundpower.model.AutoEq
import com.reinanbr.soundpower.model.AutoEqEntry
import com.reinanbr.soundpower.model.AutoEqIndex
import com.reinanbr.soundpower.model.BuiltInPresets
import com.reinanbr.soundpower.model.OutputKey
import com.reinanbr.soundpower.model.OutputKind
import com.reinanbr.soundpower.model.Preset
import com.reinanbr.soundpower.model.PresetFile
import com.reinanbr.soundpower.model.PresetLibrary
import com.reinanbr.soundpower.model.SettingsBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** The preset the equalizer came from, and whether it was edited since in a way it does not keep. */
data class CurrentPreset(val preset: Preset, val edited: Boolean)

/** Headphones music just moved to, and the AutoEQ presets that may fit them. */
data class AutoEqSuggestion(val output: AudioOutput, val entries: List<AutoEqEntry>)

/**
 * Presets: built-in, AutoEQ (loaded from the app's assets) and the user's. Applying one changes the
 * live settings through [EqRepository]. The current user preset follows the edits (autosave)
 * unless it is locked. When music moves to another output, the preset assigned to it is applied;
 * headphones with no preset of their own get AutoEQ suggestions.
 */
class PresetRepository(
    private val store: PresetStore,
    private val eq: EqRepository,
    private val output: StateFlow<AudioOutput>,
    private val loadAutoEq: suspend () -> AutoEqIndex,
    private val scope: CoroutineScope,
) {
    /** Null until read. */
    val library: StateFlow<PresetLibrary?> = store.library.stateIn(scope, SharingStarted.Eagerly, null)

    private val _autoEq = MutableStateFlow<AutoEqIndex?>(null)

    /** Null until the bundled database is read (a fraction of a second after [start]). */
    val autoEq: StateFlow<AutoEqIndex?> = _autoEq.asStateFlow()

    val current: Flow<CurrentPreset?> = combine(eq.settings, library, autoEq) { s, lib, index ->
        val preset = find(s?.presetId, lib, index) ?: return@combine null
        CurrentPreset(preset, preset.readOnly && !preset.matches(checkNotNull(s).eq))
    }.distinctUntilChanged()

    private val dismissed = MutableStateFlow<Set<OutputKey>>(emptySet())

    val suggestion: StateFlow<AutoEqSuggestion?> =
        combine(output, library, autoEq, dismissed, eq.settings) { out, lib, index, gone, s ->
            if (lib == null || index == null || !lib.suggestAutoEq || out.key in gone) return@combine null
            val name = out.name ?: return@combine null
            if (out.key.kind !in HEADPHONES) return@combine null
            // A preset of its own (not its kind's) means the user already chose.
            if (lib.assignments.any { it.output == out.key }) return@combine null
            val hits = index.match(name)
            if (hits.isEmpty() || hits.any { it.id == s?.presetId }) null else AutoEqSuggestion(out, hits)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    private val started = AtomicBoolean(false)

    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        eq.start()
        scope.launch { _autoEq.value = loadAutoEq() }
        // Autosave: the current user preset follows the EQ while it is unlocked (also right when it
        // is unlocked with edits pending).
        scope.launch {
            combine(eq.settings.filterNotNull(), library.filterNotNull()) { s, lib -> s to lib }
                .debounce(AUTOSAVE_DELAY_MS)
                .collect { (s, lib) ->
                    val p = lib.preset(s.presetId) ?: return@collect
                    if (!p.locked && !p.matches(s.eq)) keep(s)
                }
        }
        scope.launch {
            output.collect { out -> onOutput(out.key) }
        }
    }

    /** Applies the preset assigned to [key] if music moved there since the last switch. */
    private suspend fun onOutput(key: OutputKey) {
        val lib = library.filterNotNull().first()
        if (lib.lastOutput == key) return
        store.update { it.copy(lastOutput = key) }
        if (!lib.switchByOutput) return
        val assigned = lib.assignmentFor(key) ?: return
        val index = if (assigned.presetId.startsWith(AutoEqEntry.PREFIX)) autoEq.filterNotNull().first() else null
        find(assigned.presetId, lib, index)?.let(::apply)
    }

    fun find(id: String?, lib: PresetLibrary?, index: AutoEqIndex?): Preset? = when {
        id == null -> null
        id.startsWith(BuiltInPresets.PREFIX) -> BUILT_IN[id]
        id.startsWith(AutoEqEntry.PREFIX) -> index?.find(id)?.preset()
        else -> lib?.preset(id)
    }

    fun apply(preset: Preset) {
        // Edits made within the autosave delay would be lost with the switch.
        eq.settings.value?.let { s -> if (s.presetId != preset.id) scope.launch { keep(s) } }
        eq.update { it.copy(eq = preset.applyTo(it.eq), presetId = preset.id) }
    }

    /** Writes [s]'s EQ into its preset, if that is an unlocked user preset it no longer matches. */
    private suspend fun keep(s: AudioSettings) = store.update { l ->
        l.preset(s.presetId)?.takeIf { !it.locked && !it.matches(s.eq) }?.let { l.put(it.copy(eq = s.eq)) } ?: l
    }

    /**
     * Saves the current EQ as a user preset named [name] and makes it current: over the unlocked user
     * preset of that name if there is one, else as a new preset. It is also assigned to [outputs]
     * (output to device name), keeping those it had.
     */
    suspend fun save(name: String, outputs: Map<OutputKey, String?> = emptyMap()): Preset? {
        val s = eq.settings.value ?: return null
        var saved: Preset? = null
        store.update { lib ->
            val existing = lib.presets.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) && !it.locked }
            val p = existing?.copy(eq = s.eq) ?: Preset.user(lib.uniqueName(name.trim()), s.eq)
            saved = p
            val had = lib.outputsOf(p.id).associate { it.output to it.name }
            lib.put(p).let { if (outputs.isEmpty()) it else it.assign(p.id, had + outputs) }
        }
        val p = saved ?: return null
        eq.update { it.copy(presetId = p.id) }
        return p
    }

    /** A user preset with [preset]'s EQ (a built-in or AutoEQ one to edit, or a variant of the user's). */
    suspend fun copy(preset: Preset): Preset {
        var made: Preset? = null
        store.update { lib -> Preset.user(lib.uniqueName(preset.name), preset.eq).also { made = it }.let(lib::put) }
        return checkNotNull(made)
    }

    suspend fun rename(id: String, name: String) = store.update { lib ->
        val p = lib.preset(id) ?: return@update lib
        val clean = name.trim().ifEmpty { return@update lib }
        lib.put(p.copy(name = if (clean.equals(p.name, ignoreCase = true)) clean else lib.uniqueName(clean)))
    }

    suspend fun setLocked(id: String, locked: Boolean) = store.update { lib -> lib.preset(id)?.let { lib.put(it.copy(locked = locked)) } ?: lib }

    /** A deleted preset and its assignments, to undo the deletion. */
    class Deleted(val preset: Preset, val assignments: List<Assignment>, val wasCurrent: Boolean)

    suspend fun delete(id: String): Deleted? {
        var gone: Deleted? = null
        store.update { lib ->
            val p = lib.preset(id) ?: return@update lib
            gone = Deleted(p, lib.outputsOf(id), eq.settings.value?.presetId == id)
            lib.remove(id)
        }
        eq.update { if (it.presetId == id) it.copy(presetId = null) else it }
        return gone
    }

    suspend fun undelete(d: Deleted) {
        store.update { lib -> lib.put(d.preset).copy(assignments = lib.assignments.filter { a -> d.assignments.none { it.output == a.output } } + d.assignments) }
        if (d.wasCurrent) eq.update { if (it.presetId == null) it.copy(presetId = d.preset.id) else it }
    }

    suspend fun assign(presetId: String, outputs: Map<OutputKey, String?>) = store.update { it.assign(presetId, outputs) }

    suspend fun setSwitchByOutput(on: Boolean) = store.update { it.copy(switchByOutput = on) }

    suspend fun setSuggestAutoEq(on: Boolean) = store.update { it.copy(suggestAutoEq = on) }

    fun dismissSuggestion(key: OutputKey) = dismissed.update { it + key }

    fun export(presets: List<Preset>): String = PresetFile.encode(presets)

    /**
     * Adds the presets in [text]: a SoundPower preset file, or an AutoEQ / Equalizer APO
     * `ParametricEQ.txt` or `GraphicEQ.txt` (named after [fileName]). Throws
     * [IllegalArgumentException] if it is none of these.
     */
    suspend fun import(text: String, fileName: String?): List<Preset> {
        val presets = if (text.trimStart().startsWith("{")) {
            PresetFile.decode(text)
        } else {
            val profile = AutoEq.parse(text) ?: throw IllegalArgumentException("not a preset or AutoEQ file")
            listOf(Preset.user(nameFrom(fileName), profile))
        }
        require(presets.isNotEmpty()) { "the file has no presets" }
        var added: List<Preset> = emptyList()
        store.update { lib ->
            val next = lib.withImported(presets)
            added = next.presets.takeLast(presets.size)
            next
        }
        return added
    }

    /** Everything the user set, as a file. */
    fun backup(): String? {
        val s = eq.settings.value ?: return null
        val lib = library.value ?: return null
        return SettingsBackup(settings = s, library = lib.copy(lastOutput = null)).encode()
    }

    /** Replaces every setting, preset and assignment with those in a [backup] file; throws [IllegalArgumentException] if it is not one. */
    suspend fun restore(backup: String) {
        val b = SettingsBackup.decode(backup)
        store.update { b.library.copy(lastOutput = it.lastOutput) }
        eq.update { b.settings }
    }

    companion object {
        const val AUTOSAVE_DELAY_MS = 400L

        private val BUILT_IN = BuiltInPresets.ALL.associateBy { it.id }

        /** Outputs that headphones use. */
        private val HEADPHONES = setOf(OutputKind.BLUETOOTH, OutputKind.USB, OutputKind.WIRED)

        private val FILE_SUFFIX = Regex("""(\s*(ParametricEQ|GraphicEQ))?\.(txt|json)$""", RegexOption.IGNORE_CASE)

        /** "Sony WH-1000XM4 ParametricEQ.txt" -> "Sony WH-1000XM4". */
        fun nameFrom(fileName: String?): String = fileName?.replace(FILE_SUFFIX, "")?.trim()?.ifEmpty { null } ?: "Imported"
    }
}
