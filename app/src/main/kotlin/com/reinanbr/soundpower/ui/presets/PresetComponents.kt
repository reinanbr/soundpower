// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.presets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.reinanbr.soundpower.R
import com.reinanbr.soundpower.model.Assignment
import com.reinanbr.soundpower.model.AudioOutput
import com.reinanbr.soundpower.model.EqProfile
import com.reinanbr.soundpower.model.OutputKey
import com.reinanbr.soundpower.model.OutputKind
import com.reinanbr.soundpower.model.ResponseCurve

private val THUMB_FREQS = ResponseCurve.logFrequencies(48)

/** Beyond this the thumbnail clips: it shows a shape, not values. */
private const val THUMB_RANGE_DB = 12f

/** A small drawing of [eq]'s curve. */
@Composable
internal fun PresetThumbnail(eq: EqProfile, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val db = remember(eq) { eq.curve().db(THUMB_FREQS) }
    val scheme = MaterialTheme.colorScheme
    val line = if (enabled) scheme.primary else scheme.onSurfaceVariant
    val zero = scheme.outlineVariant
    Canvas(modifier) {
        val mid = size.height / 2f
        val pad = 2.dp.toPx()
        drawLine(zero, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        val path = Path()
        for (i in db.indices) {
            val x = i * size.width / (db.size - 1)
            val y = mid - db[i].coerceIn(-THUMB_RANGE_DB, THUMB_RANGE_DB) / THUMB_RANGE_DB * (mid - pad)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** An output a preset can be assigned to, as the dialogs list it. */
internal class OutputOption(val key: OutputKey, val title: String, val detail: String?, val name: String?)

@Composable
internal fun outputLabel(kind: OutputKind): String = stringResource(
    when (kind) {
        OutputKind.SPEAKER -> R.string.output_speaker
        OutputKind.WIRED -> R.string.output_wired
        OutputKind.BLUETOOTH -> R.string.output_bluetooth
        OutputKind.USB -> R.string.output_usb
        OutputKind.OTHER -> R.string.output_other
    },
)

/** The name of an assignment's output: the device's own, or its kind. */
@Composable
internal fun assignmentLabel(a: Assignment): String {
    val address = a.output.address ?: return outputLabel(a.output.kind)
    return a.name ?: address
}

/**
 * What a preset can be assigned to: the device playing now (if it is a named one), devices it is
 * already assigned to, and each kind of output.
 */
@Composable
internal fun outputOptions(current: AudioOutput?, assigned: List<Assignment>): List<OutputOption> {
    val out = ArrayList<OutputOption>()
    val address = current?.key?.address
    if (current != null && address != null) {
        out += OutputOption(current.key, current.name ?: address, stringResource(R.string.output_this_device, outputLabel(current.key.kind)), current.name)
    }
    for (a in assigned) {
        if (a.output.address != null && out.none { it.key == a.output }) {
            out += OutputOption(a.output, assignmentLabel(a), outputLabel(a.output.kind), a.name)
        }
    }
    out += OutputOption(OutputKey(OutputKind.SPEAKER), outputLabel(OutputKind.SPEAKER), null, null)
    out += OutputOption(OutputKey(OutputKind.WIRED), outputLabel(OutputKind.WIRED), null, null)
    out += OutputOption(OutputKey(OutputKind.BLUETOOTH), stringResource(R.string.output_any_bluetooth), null, null)
    out += OutputOption(OutputKey(OutputKind.USB), stringResource(R.string.output_any_usb), null, null)
    return out
}

@Composable
private fun OutputChoices(options: List<OutputOption>, selected: Set<OutputKey>, onToggle: (OutputKey, Boolean) -> Unit) {
    Column {
        for (o in options) {
            val checked = o.key in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(checked, role = Role.Checkbox) { onToggle(o.key, it) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.title, style = MaterialTheme.typography.bodyLarge)
                    if (o.detail != null) {
                        Text(o.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Saves the current EQ under a name, optionally for some outputs. */
@Composable
internal fun SavePresetDialog(
    initialName: String,
    options: List<OutputOption>,
    onDismiss: () -> Unit,
    onSave: (name: String, outputs: Map<OutputKey, String?>) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var selected by remember { mutableStateOf(emptySet<OutputKey>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_save_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME) },
                    label = { Text(stringResource(R.string.preset_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.preset_use_on), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutputChoices(options, selected) { key, on -> selected = if (on) selected + key else selected - key }
                Text(stringResource(R.string.preset_use_on_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), options.filter { it.key in selected }.associate { it.key to it.name }) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The outputs [presetName] is applied on. */
@Composable
internal fun AssignDialog(
    presetName: String,
    options: List<OutputOption>,
    initial: Set<OutputKey>,
    onDismiss: () -> Unit,
    onAssign: (Map<OutputKey, String?>) -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_assign_title, presetName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutputChoices(options, selected) { key, on -> selected = if (on) selected + key else selected - key }
                Text(stringResource(R.string.preset_use_on_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAssign(options.filter { it.key in selected }.associate { it.key to it.name }) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun RenameDialog(initial: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME) },
                label = { Text(stringResource(R.string.preset_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onRename(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val MAX_NAME = 60
