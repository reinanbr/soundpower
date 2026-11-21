// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.presets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.reinanbr.soundpower.SoundPowerApplication
import com.reinanbr.soundpower.eq.EqRepository
import com.reinanbr.soundpower.model.Assignment
import com.reinanbr.soundpower.model.AudioOutput
import com.reinanbr.soundpower.model.AutoEqIndex
import com.reinanbr.soundpower.model.BuiltInPresets
import com.reinanbr.soundpower.model.EqMode
import com.reinanbr.soundpower.model.OutputKey
import com.reinanbr.soundpower.model.Preset
import com.reinanbr.soundpower.model.PresetLibrary
import com.reinanbr.soundpower.presets.AutoEqSuggestion
import com.reinanbr.soundpower.presets.CurrentPreset
import com.reinanbr.soundpower.presets.PresetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the listed presets come from. */
enum class PresetSource { ALL, YOURS, BUILT_IN, AUTOEQ }

data class PresetFilters(
    val query: String = "",
    val source: PresetSource = PresetSource.ALL,
    /** Only presets of this mode; null for both. */
    val mode: EqMode? = null,
    /** Only presets assigned to an output. */
    val assigned: Boolean = false,
)

class PresetItem(val preset: Preset, val outputs: List<Assignment>, val current: Boolean)

enum class SectionKind { SUGGESTED, YOURS, BUILT_IN, AUTOEQ }

/** A group of the list; [total] counts what matched, of which [items] are shown. */
class PresetSection(val kind: SectionKind, val items: List<PresetItem>, val total: Int = items.size)

data class PresetsUiState(
    val filters: PresetFilters = PresetFilters(),
    val sections: List<PresetSection> = emptyList(),
    val output: AudioOutput? = null,
    val suggestion: AutoEqSuggestion? = null,
    /** Null until the user's presets are read. */
    val library: PresetLibrary? = null,
    val autoEqLoaded: Boolean = false,
    val current: CurrentPreset? = null,
)

/** What the presets screen reports back, once. */
sealed interface PresetMessage {
    class Imported(val count: Int) : PresetMessage
    class Failed(val reason: String) : PresetMessage
    class Saved(val name: String) : PresetMessage
    class Copied(val name: String) : PresetMessage
    class Deleted(val name: String, val undo: PresetRepository.Deleted) : PresetMessage
    data object Restored : PresetMessage
}

/** The preset list: search, filters and what can be done with a preset. */
class PresetsViewModel(private val repo: PresetRepository, eq: EqRepository, output: StateFlow<AudioOutput>) : ViewModel() {
    private val filters = MutableStateFlow(PresetFilters())
    private val messageChannel = Channel<PresetMessage>(Channel.BUFFERED)
    val messages: Flow<PresetMessage> = messageChannel.receiveAsFlow()

    @OptIn(FlowPreview::class)
    private val sections: Flow<List<PresetSection>> =
        combine(filters.debounce(SEARCH_DELAY_MS), repo.library, repo.autoEq, eq.settings, repo.suggestion) { f, lib, index, s, sug ->
            sections(f, lib ?: PresetLibrary.DEFAULT, index, s?.presetId, sug)
        }.flowOn(Dispatchers.Default)

    private class Extra(val library: PresetLibrary?, val loaded: Boolean, val current: CurrentPreset?)

    val state: StateFlow<PresetsUiState> =
        combine(filters, sections, output, repo.suggestion, combine(repo.library, repo.autoEq, repo.current) { l, i, c -> Extra(l, i != null, c) }) { f, sec, out, sug, x ->
            PresetsUiState(f, sec, out, sug, x.library, x.loaded, x.current)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PresetsUiState())

    fun setQuery(q: String) = filters.update { it.copy(query = q) }

    /** Lists what matches [q] among all presets, as an AutoEQ suggestion asks. */
    fun search(q: String) = filters.update { PresetFilters(query = q) }

    fun setSource(s: PresetSource) = filters.update { it.copy(source = s) }

    fun setMode(m: EqMode?) = filters.update { it.copy(mode = m) }

    fun setAssigned(on: Boolean) = filters.update { it.copy(assigned = on) }

    fun apply(p: Preset) = repo.apply(p)

    fun save(name: String, outputs: Map<OutputKey, String?>) = launch {
        repo.save(name, outputs)?.let { send(PresetMessage.Saved(it.name)) }
    }

    fun copy(p: Preset) = launch { send(PresetMessage.Copied(repo.copy(p).name)) }

    fun rename(p: Preset, name: String) = launch { repo.rename(p.id, name) }

    fun setLocked(p: Preset, locked: Boolean) = launch { repo.setLocked(p.id, locked) }

    fun assign(p: Preset, outputs: Map<OutputKey, String?>) = launch { repo.assign(p.id, outputs) }

    fun delete(p: Preset) = launch { repo.delete(p.id)?.let { send(PresetMessage.Deleted(p.name, it)) } }

    fun undoDelete(d: PresetRepository.Deleted) = launch { repo.undelete(d) }

    fun dismissSuggestion(key: OutputKey) = repo.dismissSuggestion(key)

    fun setSwitchByOutput(on: Boolean) = launch { repo.setSwitchByOutput(on) }

    fun setSuggestAutoEq(on: Boolean) = launch { repo.setSuggestAutoEq(on) }

    /** Everything the user set, as a file; null until the settings are read. */
    fun backup(): String? = repo.backup()

    fun restore(text: String) = launch {
        try {
            repo.restore(text)
            send(PresetMessage.Restored)
        } catch (e: IllegalArgumentException) {
            send(PresetMessage.Failed(e.message ?: "unreadable file"))
        }
    }

    fun export(presets: List<Preset>): String = repo.export(presets)

    fun import(text: String, fileName: String?) = launch {
        try {
            send(PresetMessage.Imported(repo.import(text, fileName).size))
        } catch (e: IllegalArgumentException) {
            send(PresetMessage.Failed(e.message ?: "unreadable file"))
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun send(m: PresetMessage) {
        messageChannel.trySend(m)
    }

    companion object {
        private const val SEARCH_DELAY_MS = 120L

        /** AutoEQ results listed among all presets while searching; the AutoEQ filter lists them all. */
        const val AUTOEQ_IN_ALL = 50

        val Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as SoundPowerApplication
                PresetsViewModel(app.container.presets, app.container.eq, app.container.output.output)
            }
        }

        private fun words(query: String) = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

        internal fun sections(
            f: PresetFilters,
            lib: PresetLibrary,
            index: AutoEqIndex?,
            currentId: String?,
            suggestion: AutoEqSuggestion?,
        ): List<PresetSection> {
            val words = words(f.query)
            val assignedIds = lib.assignments.map { it.presetId }.toSet()
            fun keep(p: Preset): Boolean {
                if (f.mode != null && p.mode != f.mode) return false
                if (f.assigned && p.id !in assignedIds) return false
                val name = p.name.lowercase()
                return words.all { name.contains(it) }
            }
            fun item(p: Preset) = PresetItem(p, lib.outputsOf(p.id), p.id == currentId)

            val out = ArrayList<PresetSection>()
            if (suggestion != null && f.source in setOf(PresetSource.ALL, PresetSource.AUTOEQ) && words.isEmpty() && !f.assigned &&
                f.mode != EqMode.GRAPHIC
            ) {
                out += PresetSection(SectionKind.SUGGESTED, suggestion.entries.map { item(it.preset()) })
            }
            if (f.source == PresetSource.ALL || f.source == PresetSource.YOURS) {
                val mine = lib.presets.filter(::keep).sortedBy { it.name.lowercase() }
                // Empty, it says how to save one, unless a filter or search emptied it.
                val unfiltered = words.isEmpty() && f.mode == null && !f.assigned
                if (mine.isNotEmpty() || f.source == PresetSource.YOURS || (lib.presets.isEmpty() && unfiltered)) {
                    out += PresetSection(SectionKind.YOURS, mine.map(::item))
                }
            }
            if (f.source == PresetSource.ALL || f.source == PresetSource.BUILT_IN) {
                val built = BuiltInPresets.ALL.filter(::keep)
                if (built.isNotEmpty()) out += PresetSection(SectionKind.BUILT_IN, built.map(::item))
            }
            val listAutoEq = f.source == PresetSource.AUTOEQ || (f.source == PresetSource.ALL && (words.isNotEmpty() || f.assigned))
            if (listAutoEq && index != null && f.mode != EqMode.GRAPHIC) {
                val entries = when {
                    f.assigned -> assignedIds.mapNotNull(index::find).filter { e -> words.all { e.name.lowercase().contains(it) } }
                    words.isNotEmpty() -> index.search(f.query)
                    else -> index.entries.filter { it.recommended }
                }
                val limit = if (f.source == PresetSource.AUTOEQ) entries.size else AUTOEQ_IN_ALL
                val shown = entries.take(limit).map { item(it.preset()) }
                if (shown.isNotEmpty() || f.source == PresetSource.AUTOEQ) out += PresetSection(SectionKind.AUTOEQ, shown, entries.size)
            }
            return out
        }
    }
}
