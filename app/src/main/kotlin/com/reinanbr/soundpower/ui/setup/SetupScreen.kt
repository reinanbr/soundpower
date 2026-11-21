// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.reinanbr.soundpower.R
import com.reinanbr.soundpower.effect.EffectController.Connection
import com.reinanbr.soundpower.protocol.Protocol
import com.reinanbr.soundpower.setup.SetupStep

private enum class Check { OK, PROBLEM, UNKNOWN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    state: SetupUiState,
    onRetry: () -> Unit,
    onInstall: () -> Unit,
    onReboot: () -> Unit,
    onEnable: () -> Unit,
    onOpenEq: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.setup_title)) }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium)
            Checklist(state)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            StepMessage(state.step)
            Actions(state, onRetry, onInstall, onReboot, onEnable, onOpenEq)
            if (state.installLog.isNotEmpty()) InstallLog(state.installLog)
            if (state.installSucceeded == false) {
                Text(stringResource(R.string.install_failed), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Checklist(state: SetupUiState) {
    val r = state.report
    val checking = stringResource(R.string.setup_checking)
    val dash = "—"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CheckRow(
                stringResource(R.string.setup_root),
                when (r.root) {
                    null -> checking
                    true -> stringResource(R.string.setup_root_granted)
                    false -> stringResource(R.string.setup_root_denied)
                },
                when (r.root) {
                    null -> Check.UNKNOWN
                    true -> Check.OK
                    false -> Check.PROBLEM
                },
            )
            val magisk = r.magisk
            CheckRow(
                stringResource(R.string.setup_magisk),
                when {
                    r.root != true -> if (r.root == null) checking else dash
                    magisk == null -> stringResource(R.string.setup_magisk_missing)
                    !magisk.isSupported -> stringResource(R.string.setup_magisk_old, magisk.versionName)
                    else -> stringResource(R.string.setup_magisk_version, magisk.versionName, magisk.versionCode)
                },
                when {
                    r.root != true -> Check.UNKNOWN
                    magisk?.isSupported == true -> Check.OK
                    else -> Check.PROBLEM
                },
            )
            ModuleRow(state)
            EffectRow(state)
        }
    }
}

@Composable
private fun ModuleRow(state: SetupUiState) {
    val m = state.report.module
    val label = stringResource(R.string.setup_module)
    if (m == null) {
        CheckRow(label, if (state.report.root == null) stringResource(R.string.setup_checking) else "—", Check.UNKNOWN)
        return
    }
    val next = m.nextVersion
    val (text, check) = when {
        m.removing -> stringResource(R.string.setup_module_removing, m.installed?.version.orEmpty()) to Check.PROBLEM
        next == null -> stringResource(R.string.setup_module_missing) to Check.PROBLEM
        next.versionCode < state.bundled.versionCode ->
            stringResource(R.string.setup_module_outdated, next.version, state.bundled.version) to Check.PROBLEM
        m.rebootPending -> stringResource(R.string.setup_module_pending, next.version) to Check.PROBLEM
        m.disabled -> stringResource(R.string.setup_module_disabled, next.version) to Check.PROBLEM
        else -> stringResource(R.string.setup_module_installed, next.version) to Check.OK
    }
    CheckRow(label, text, check)
}

@Composable
private fun EffectRow(state: SetupUiState) {
    val r = state.report
    val label = stringResource(R.string.setup_effect)
    if (r.module == null) {
        CheckRow(label, if (r.root == null) stringResource(R.string.setup_checking) else "—", Check.UNKNOWN)
        return
    }
    when (val c = r.connection) {
        is Connection.Connected -> {
            val loaded = stringResource(R.string.setup_effect_loaded, c.info.libVersionName, c.info.major, c.info.minor)
            val dumps = r.dumps
            val text = if (dumps == null) {
                loaded
            } else {
                loaded + "\n" + pluralStringResource(R.plurals.instances_in_dumpsys, dumps.size, dumps.size)
            }
            CheckRow(label, text, Check.OK)
        }
        is Connection.Incompatible -> CheckRow(
            label,
            stringResource(R.string.setup_effect_incompatible, c.info.major, c.info.minor, Protocol.VERSION_MAJOR),
            Check.PROBLEM,
        )
        is Connection.Failed -> CheckRow(label, stringResource(R.string.setup_effect_failed, c.reason), Check.PROBLEM)
        null -> CheckRow(label, stringResource(R.string.setup_effect_missing), Check.PROBLEM)
    }
}

@Composable
private fun CheckRow(label: String, value: String, check: Check) {
    val color = when (check) {
        Check.OK -> MaterialTheme.colorScheme.primary
        Check.PROBLEM -> MaterialTheme.colorScheme.error
        Check.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
    val description = when (check) {
        Check.OK -> stringResource(R.string.state_ok)
        Check.PROBLEM -> stringResource(R.string.state_problem)
        Check.UNKNOWN -> stringResource(R.string.setup_checking)
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Dot(color, Modifier.padding(top = 6.dp).semantics { contentDescription = description })
        Column {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).background(color, CircleShape))
}

@Composable
private fun StepMessage(step: SetupStep) {
    val text = when (step) {
        SetupStep.CHECKING -> return
        SetupStep.NO_ROOT -> R.string.step_no_root
        SetupStep.NO_MAGISK -> R.string.step_no_magisk
        SetupStep.INSTALL -> R.string.step_install
        SetupStep.UPDATE -> R.string.step_update
        SetupStep.REBOOT -> R.string.step_reboot
        SetupStep.DISABLED -> R.string.step_disabled
        SetupStep.NOT_LOADED -> R.string.step_not_loaded
        SetupStep.INCOMPATIBLE -> R.string.step_incompatible
        SetupStep.READY -> R.string.step_ready
    }
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Actions(
    state: SetupUiState,
    onRetry: () -> Unit,
    onInstall: () -> Unit,
    onReboot: () -> Unit,
    onEnable: () -> Unit,
    onOpenEq: () -> Unit,
) {
    val primary: Pair<Int, () -> Unit>? = when (state.step) {
        SetupStep.CHECKING, SetupStep.NO_MAGISK -> null
        SetupStep.NO_ROOT -> R.string.action_retry to onRetry
        SetupStep.INSTALL -> R.string.action_install to onInstall
        SetupStep.UPDATE, SetupStep.INCOMPATIBLE -> R.string.action_update to onInstall
        SetupStep.REBOOT, SetupStep.NOT_LOADED -> R.string.action_reboot to onReboot
        SetupStep.DISABLED -> R.string.action_enable to onEnable
        SetupStep.READY -> R.string.action_open_eq to onOpenEq
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        primary?.let { (label, action) ->
            Button(onClick = action, enabled = !state.busy) { Text(stringResource(label)) }
        }
        if (state.step != SetupStep.CHECKING && state.step != SetupStep.NO_ROOT) {
            OutlinedButton(onClick = onRetry, enabled = !state.busy) { Text(stringResource(R.string.action_recheck)) }
        }
    }
}

@Composable
private fun InstallLog(lines: List<String>) {
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { list.scrollToItem(lines.lastIndex) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.install_log), style = MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.heightIn(max = 280.dp), state = list) {
                items(lines) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
