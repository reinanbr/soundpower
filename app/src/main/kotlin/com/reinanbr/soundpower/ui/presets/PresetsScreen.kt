// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.presets

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reinanbr.soundpower.R
import com.reinanbr.soundpower.model.EqMode
import com.reinanbr.soundpower.model.Preset
import com.reinanbr.soundpower.model.PresetKind
import com.reinanbr.soundpower.ui.components.SpIcons
import com.reinanbr.soundpower.ui.components.valueSpring
import kotlinx.coroutines.launch

/** A dialog the screen shows over the list. */
private sealed interface PresetDialog {
    class Rename(val preset: Preset) : PresetDialog
    class Assign(val preset: Preset) : PresetDialog
}

/** Every preset: search, filters, a curve per preset; tap applies, long press offers the rest. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetsScreen(vm: PresetsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<PresetDialog?>(null) }
    var exporting by remember { mutableStateOf<List<Preset>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }

    MessageEffect(vm, snackbar)

    val writeFailed = stringResource(R.string.file_write_failed)
    val readFailed = stringResource(R.string.file_read_failed)
    val shareTitle = stringResource(R.string.preset_share)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PresetFiles.JSON)) { uri ->
        val presets = exporting
        if (uri != null && presets.isNotEmpty()) {
            scope.launch { if (!PresetFiles.write(context, uri, vm.export(presets))) snackbar.showSnackbar(writeFailed) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val file = PresetFiles.read(context, uri)
                if (file == null) snackbar.showSnackbar(readFailed) else vm.import(file.first, file.second)
            }
        }
    }
    fun export(presets: List<Preset>, name: String) {
        exporting = presets
        exportLauncher.launch(PresetFiles.fileName(name))
    }

    val yours = stringResource(R.string.presets_yours)
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.presets_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(SpIcons.Back, stringResource(R.string.action_back)) } },
                actions = {
                    IconButton(onClick = { importLauncher.launch(PresetFiles.IMPORT_TYPES) }) {
                        Icon(SpIcons.Import, stringResource(R.string.presets_import))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(SpIcons.More, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.presets_export_yours)) },
                                enabled = !state.library?.presets.isNullOrEmpty(),
                                onClick = {
                                    menuOpen = false
                                    state.library?.presets?.let { export(it, yours) }
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "search") { SearchField(state.filters.query, vm::setQuery) }
            item(key = "filters") { Filters(state.filters, vm) }
            for (section in state.sections) {
                item(key = "header-${section.kind}") { SectionHeader(section, state, vm) }
                items(section.items, key = { "${section.kind}/${it.preset.id}" }) { item ->
                    PresetRow(
                        item,
                        onApply = { vm.apply(item.preset) },
                        onAction = { action ->
                            when (action) {
                                PresetAction.RENAME -> dialog = PresetDialog.Rename(item.preset)
                                PresetAction.LOCK -> vm.setLocked(item.preset, true)
                                PresetAction.UNLOCK -> vm.setLocked(item.preset, false)
                                PresetAction.ASSIGN -> dialog = PresetDialog.Assign(item.preset)
                                PresetAction.COPY -> vm.copy(item.preset)
                                PresetAction.SHARE -> scope.launch {
                                    PresetFiles.share(context, PresetFiles.fileName(item.preset.name), vm.export(listOf(item.preset)), shareTitle)
                                }
                                PresetAction.EXPORT -> export(listOf(item.preset), item.preset.name)
                                PresetAction.DELETE -> vm.delete(item.preset)
                            }
                        },
                    )
                }
                if (section.total > section.items.size) {
                    item(key = "more-${section.kind}") {
                        Hint(stringResource(R.string.presets_autoeq_more, section.items.size, section.total))
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        is PresetDialog.Rename -> RenameDialog(d.preset.name, onDismiss = { dialog = null }) {
            vm.rename(d.preset, it)
            dialog = null
        }
        is PresetDialog.Assign -> {
            val assigned = state.library?.outputsOf(d.preset.id).orEmpty()
            AssignDialog(
                d.preset.name,
                outputOptions(state.output, assigned),
                assigned.map { it.output }.toSet(),
                onDismiss = { dialog = null },
            ) {
                vm.assign(d.preset, it)
                dialog = null
            }
        }
        null -> {}
    }
}

/** Shows what the view model reports, with an undo for deletions. */
@Composable
internal fun MessageEffect(vm: PresetsViewModel, snackbar: SnackbarHostState) {
    val res = LocalResources.current
    val undo = stringResource(R.string.action_undo)
    LaunchedEffect(vm, res) {
        vm.messages.collect { m ->
            when (m) {
                is PresetMessage.Imported -> snackbar.showSnackbar(res.getQuantityString(R.plurals.presets_imported, m.count, m.count))
                is PresetMessage.Failed -> snackbar.showSnackbar(res.getString(R.string.presets_import_failed, m.reason))
                is PresetMessage.Saved -> snackbar.showSnackbar(res.getString(R.string.preset_saved, m.name))
                is PresetMessage.Copied -> snackbar.showSnackbar(res.getString(R.string.preset_copied, m.name))
                is PresetMessage.Deleted -> {
                    snackbar.currentSnackbarData?.dismiss()
                    val r = snackbar.showSnackbar(res.getString(R.string.preset_deleted, m.name), actionLabel = undo, duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) vm.undoDelete(m.undo)
                }
                PresetMessage.Restored -> snackbar.showSnackbar(res.getString(R.string.settings_restored))
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(stringResource(R.string.presets_search)) },
        leadingIcon = { Icon(SpIcons.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(SpIcons.Close, stringResource(R.string.action_clear)) }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Filters(f: PresetFilters, vm: PresetsViewModel) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((source, label) in listOf(
            PresetSource.ALL to R.string.presets_all,
            PresetSource.YOURS to R.string.presets_yours,
            PresetSource.BUILT_IN to R.string.presets_built_in,
            PresetSource.AUTOEQ to R.string.presets_autoeq,
        )) {
            FilterChip(selected = f.source == source, onClick = { vm.setSource(source) }, label = { Text(stringResource(label)) })
        }
        VerticalDivider(Modifier.padding(vertical = 4.dp).size(1.dp, 24.dp))
        for ((mode, label) in listOf(EqMode.GRAPHIC to R.string.eq_mode_graphic, EqMode.PARAMETRIC to R.string.eq_mode_parametric)) {
            FilterChip(selected = f.mode == mode, onClick = { vm.setMode(if (f.mode == mode) null else mode) }, label = { Text(stringResource(label)) })
        }
        FilterChip(selected = f.assigned, onClick = { vm.setAssigned(!f.assigned) }, label = { Text(stringResource(R.string.presets_assigned)) })
    }
}

@Composable
private fun SectionHeader(section: PresetSection, state: PresetsUiState, vm: PresetsViewModel) {
    val title = when (section.kind) {
        SectionKind.SUGGESTED -> stringResource(R.string.presets_suggested, state.suggestion?.output?.name.orEmpty())
        SectionKind.YOURS -> stringResource(R.string.presets_yours)
        SectionKind.BUILT_IN -> stringResource(R.string.presets_built_in)
        SectionKind.AUTOEQ -> stringResource(R.string.presets_autoeq)
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (section.kind == SectionKind.SUGGESTED) {
            state.suggestion?.let { s -> TextButton(onClick = { vm.dismissSuggestion(s.output.key) }) { Text(stringResource(R.string.action_dismiss)) } }
        }
    }
    when {
        section.kind == SectionKind.AUTOEQ && !state.autoEqLoaded -> Hint(stringResource(R.string.setup_checking))
        section.kind == SectionKind.AUTOEQ -> Hint(
            if (section.items.isEmpty()) stringResource(R.string.presets_autoeq_none) else stringResource(R.string.presets_autoeq_hint),
        )
        section.kind == SectionKind.YOURS && section.items.isEmpty() -> Hint(
            stringResource(if (state.library?.presets.isNullOrEmpty()) R.string.presets_yours_empty else R.string.presets_none_match),
        )
        else -> {}
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private enum class PresetAction { RENAME, LOCK, UNLOCK, ASSIGN, COPY, SHARE, EXPORT, DELETE }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetRow(item: PresetItem, onApply: () -> Unit, onAction: (PresetAction) -> Unit) {
    val p = item.preset
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val background by animateColorAsState(if (item.current) scheme.secondaryContainer else Color.Transparent, valueSpring(), label = "preset-current")
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .background(background, RoundedCornerShape(16.dp))
                .combinedClickable(onClick = onApply, onLongClick = { menu = true }, onLongClickLabel = stringResource(R.string.action_more))
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PresetThumbnail(
                p.eq,
                Modifier
                    .size(64.dp, 36.dp)
                    .background(scheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                    .padding(horizontal = 4.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = buildList {
                    add(
                        when {
                            p.kind == PresetKind.AUTOEQ -> p.source.orEmpty()
                            p.mode == EqMode.PARAMETRIC -> pluralStringResource(R.plurals.preset_parametric_bands, p.eq.parametricBands.size, p.eq.parametricBands.size)
                            else -> stringResource(R.string.eq_mode_graphic)
                        },
                    )
                    if (item.outputs.isNotEmpty()) add(item.outputs.map { assignmentLabel(it) }.joinToString(", "))
                }.filter { it.isNotEmpty() }.joinToString(" · ")
                Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (p.locked) Icon(SpIcons.Lock, stringResource(R.string.preset_locked), Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
            if (item.current) Icon(SpIcons.Check, stringResource(R.string.preset_current), tint = scheme.primary)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            fun pick(a: PresetAction) {
                menu = false
                onAction(a)
            }
            val user = p.kind == PresetKind.USER
            if (user) DropdownMenuItem(text = { Text(stringResource(R.string.preset_rename)) }, onClick = { pick(PresetAction.RENAME) })
            if (user && p.locked) DropdownMenuItem(text = { Text(stringResource(R.string.preset_unlock)) }, onClick = { pick(PresetAction.UNLOCK) })
            if (user && !p.locked) DropdownMenuItem(text = { Text(stringResource(R.string.preset_lock)) }, onClick = { pick(PresetAction.LOCK) })
            DropdownMenuItem(text = { Text(stringResource(R.string.preset_assign)) }, onClick = { pick(PresetAction.ASSIGN) })
            DropdownMenuItem(
                text = { Text(stringResource(if (user) R.string.preset_duplicate else R.string.preset_copy)) },
                onClick = { pick(PresetAction.COPY) },
            )
            DropdownMenuItem(text = { Text(stringResource(R.string.preset_share)) }, onClick = { pick(PresetAction.SHARE) })
            DropdownMenuItem(text = { Text(stringResource(R.string.preset_export)) }, onClick = { pick(PresetAction.EXPORT) })
            if (user) DropdownMenuItem(text = { Text(stringResource(R.string.preset_delete)) }, onClick = { pick(PresetAction.DELETE) })
        }
    }
}
