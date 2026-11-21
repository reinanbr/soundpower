// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.presets

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.reinanbr.soundpower.R
import com.reinanbr.soundpower.ui.components.Section
import com.reinanbr.soundpower.ui.components.SpIcons
import kotlinx.coroutines.launch

/** The menu's presets section: the list, switching by output, AutoEQ suggestions, backup. */
@Composable
internal fun PresetsMenuSection(state: PresetsUiState, vm: PresetsViewModel, onOpenPresets: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf<String?>(null) }
    val writeFailed = stringResource(R.string.file_write_failed)
    val readFailed = stringResource(R.string.file_read_failed)
    val exported = stringResource(R.string.settings_exported)
    val fileName = stringResource(R.string.settings_file_name)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PresetFiles.JSON)) { uri ->
        val text = vm.backup()
        if (uri != null && text != null) {
            scope.launch { onMessage(if (PresetFiles.write(context, uri, text)) exported else writeFailed) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val file = PresetFiles.read(context, uri)
                if (file == null) onMessage(readFailed) else restoring = file.first
            }
        }
    }
    val lib = state.library
    Section(stringResource(R.string.menu_presets)) {
        Row(stringResource(R.string.presets_title), presetsSummary(state), onClick = onOpenPresets)
        SwitchRow(
            stringResource(R.string.presets_switch_by_output),
            stringResource(R.string.presets_switch_by_output_hint),
            checked = lib?.switchByOutput ?: true,
            enabled = lib != null,
            onChange = vm::setSwitchByOutput,
        )
        SwitchRow(
            stringResource(R.string.presets_suggest_autoeq),
            stringResource(R.string.presets_suggest_autoeq_hint),
            checked = lib?.suggestAutoEq ?: true,
            enabled = lib != null,
            onChange = vm::setSuggestAutoEq,
        )
        Row(stringResource(R.string.settings_export), stringResource(R.string.settings_export_hint), enabled = lib != null) {
            exportLauncher.launch(fileName)
        }
        Row(stringResource(R.string.settings_import), stringResource(R.string.settings_import_hint)) {
            importLauncher.launch(arrayOf(PresetFiles.JSON, "application/octet-stream"))
        }
    }
    restoring?.let { text ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text(stringResource(R.string.settings_import_confirm_title)) },
            text = { Text(stringResource(R.string.settings_import_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    restoring = null
                    vm.restore(text)
                }) { Text(stringResource(R.string.settings_import_replace)) }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun presetsSummary(state: PresetsUiState): String {
    val lib = state.library ?: return stringResource(R.string.setup_checking)
    val mine = pluralStringResource(R.plurals.presets_count_yours, lib.presets.size, lib.presets.size)
    val assigned = lib.assignments.size
    return if (assigned == 0) mine else mine + " · " + pluralStringResource(R.plurals.presets_count_assigned, assigned, assigned)
}

@Composable
private fun Row(title: String, summary: String?, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(SpIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
