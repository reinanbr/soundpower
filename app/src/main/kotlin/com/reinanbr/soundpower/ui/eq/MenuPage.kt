// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.eq

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.reinanbr.soundpower.BuildConfig
import com.reinanbr.soundpower.R
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.engine.Precision
import com.reinanbr.soundpower.engine.RootlessOptions
import com.reinanbr.soundpower.engine.RootlessScope
import com.reinanbr.soundpower.eq.EqStatus
import com.reinanbr.soundpower.model.SpectralLayout
import com.reinanbr.soundpower.rootless.RootlessEngine
import com.reinanbr.soundpower.ui.components.Section
import com.reinanbr.soundpower.ui.components.SpIcons
import com.reinanbr.soundpower.ui.components.formatHz
import com.reinanbr.soundpower.ui.prefs.ThemeMode
import com.reinanbr.soundpower.ui.presets.PresetsMenuSection
import com.reinanbr.soundpower.ui.presets.PresetsUiState
import com.reinanbr.soundpower.ui.presets.PresetsViewModel
import kotlin.math.roundToInt

/** Band configuration, presets, setup, the live status, the theme and the app's version. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MenuPage(state: EqUiState, theme: ThemeMode, vm: EqViewModel, actions: EqActions, presets: PresetsUiState, presetsVm: PresetsViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.app_name)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section(stringResource(R.string.menu_equalizer)) {
                val layout = state.settings?.eq?.graphic?.layout
                MenuRow(
                    stringResource(R.string.tool_bands),
                    layout?.let {
                        stringResource(
                            R.string.menu_bands_summary,
                            pluralStringResource(R.plurals.bands_fixed, it.size, it.size),
                            formatHz(it.frequencies.first()),
                            formatHz(it.frequencies.last()),
                        )
                    },
                    enabled = layout != null,
                ) { actions.openTool(EqTool.BANDS) }
                MenuRow(stringResource(R.string.eq_reset_all), stringResource(R.string.menu_reset_summary), enabled = state.settings != null) {
                    actions.reset()
                }
                if (state.engine != EngineMode.ROOTLESS) {
                    MenuRow(stringResource(R.string.eq_setup_status), stringResource(R.string.menu_setup_summary), onClick = actions.openSetup)
                }
            }
            PresetsMenuSection(presets, presetsVm, onOpenPresets = { actions.openPresets(null) }, onMessage = actions.showMessage)
            EngineSection(state, vm, actions)
            StatusSection(state.status, state.rootless, vm)
            Section(stringResource(R.string.menu_look)) {
                Text(stringResource(R.string.eq_theme), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for ((mode, label) in listOf(
                    ThemeMode.SYSTEM to R.string.theme_system,
                    ThemeMode.LIGHT to R.string.theme_light,
                    ThemeMode.DARK to R.string.theme_dark,
                )) {
                    Row(
                        Modifier.fillMaxWidth().selectable(theme == mode, role = Role.RadioButton) { vm.setTheme(mode) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = theme == mode, onClick = null, modifier = Modifier.padding(12.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Section(stringResource(R.string.menu_about)) {
                Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.about_license),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.about_autoeq),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MenuRow(title: String, summary: String?, enabled: Boolean = true, onClick: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else dim)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(SpIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EngineSection(state: EqUiState, vm: EqViewModel, actions: EqActions) {
    Section(stringResource(R.string.menu_engine)) {
        val rootless = state.engine == EngineMode.ROOTLESS
        MenuRow(
            stringResource(R.string.menu_engine_mode),
            stringResource(if (rootless) R.string.engine_rootless else R.string.engine_root),
            onClick = actions.changeEngine,
        )
        if (!rootless) return@Section
        val options = state.rootless
        Label(stringResource(R.string.rootless_scope))
        RadioRow(stringResource(R.string.rootless_scope_global), stringResource(R.string.rootless_scope_global_hint), options.scope == RootlessScope.GLOBAL) {
            vm.setRootless(options.copy(scope = RootlessScope.GLOBAL))
        }
        RadioRow(stringResource(R.string.rootless_scope_sessions), stringResource(R.string.rootless_scope_sessions_hint), options.scope == RootlessScope.SESSIONS) {
            vm.setRootless(options.copy(scope = RootlessScope.SESSIONS))
        }
        Label(stringResource(R.string.rootless_precision))
        // The rate the engine designs its layout for; until it runs, the usual mixer rate.
        val rate = state.status?.rootless?.layout?.sampleRate ?: TYPICAL_RATE
        for ((precision, label) in listOf(
            Precision.FAST to R.string.rootless_precision_fast,
            Precision.BALANCED to R.string.rootless_precision_balanced,
            Precision.FINE to R.string.rootless_precision_fine,
        )) {
            val layout = SpectralLayout(rate, precision.frameMs)
            RadioRow(
                stringResource(label),
                stringResource(R.string.rootless_precision_detail, layout.binHz.roundToInt(), layout.latencyMs.roundToInt()),
                options.precision == precision,
            ) { vm.setRootless(options.copy(precision = precision)) }
        }
        NotificationPermissionRow()
    }
}

/** Android 13 and later hide the service's notification until the app may post notifications. */
@Composable
private fun NotificationPermissionRow() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    if (allowed) return
    MenuRow(stringResource(R.string.rootless_notification_permission), stringResource(R.string.rootless_notification_permission_hint)) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun RadioRow(title: String, summary: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusSection(status: EqStatus?, options: RootlessOptions, vm: EqViewModel) {
    Section(stringResource(R.string.eq_status)) {
        val dim = MaterialTheme.colorScheme.onSurfaceVariant
        if (status == null) {
            Text(stringResource(R.string.setup_checking), color = dim)
            return@Section
        }
        val rootless = status.rootless
        if (rootless != null) {
            RootlessStatus(rootless, options, status.moduleActive, vm)
            return@Section
        }
        val active = status.streams?.filter { it.isActive }
        when {
            active == null -> {}
            active.isEmpty() -> Text(stringResource(R.string.eq_no_streams))
            else -> {
                Text(pluralStringResource(R.plurals.eq_streams, active.size, active.size))
                active.forEach {
                    Text(
                        stringResource(R.string.eq_stream_detail, it.sampleRate, it.channels, it.cpuAvgPercent, it.cpuPeakPercent),
                        style = MaterialTheme.typography.bodySmall,
                        color = dim,
                    )
                }
            }
        }
        val effect = status.effect
        if (effect == null) {
            Text(stringResource(R.string.eq_status_unknown), color = dim)
        } else {
            Text(
                pluralStringResource(R.plurals.instances_in_service, effect.instances, effect.instances),
                style = MaterialTheme.typography.bodySmall,
                color = dim,
            )
            if (effect.bypassed) Text(stringResource(R.string.eq_bypassed), style = MaterialTheme.typography.bodySmall, color = dim)
        }
    }
}

@Composable
private fun RootlessStatus(status: RootlessEngine.Status, options: RootlessOptions, moduleActive: Boolean, vm: EqViewModel) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val error = MaterialTheme.colorScheme.error
    val attachments = status.attachments
    when {
        attachments.isEmpty() && options.scope == RootlessScope.SESSIONS -> Text(stringResource(R.string.rootless_status_waiting))
        attachments.isEmpty() -> Text(stringResource(R.string.rootless_status_off))
        else -> for (a in attachments) {
            val name = if (a.session == RootlessEngine.GLOBAL_SESSION) {
                stringResource(R.string.rootless_status_global)
            } else {
                stringResource(R.string.rootless_status_session, a.label ?: stringResource(R.string.rootless_status_unknown_app), a.session)
            }
            Text(name, style = MaterialTheme.typography.bodyLarge)
            val failure = a.error
            Text(
                when {
                    a.active -> stringResource(R.string.rootless_status_active)
                    failure == RootlessEngine.LOST_CONTROL -> stringResource(R.string.rootless_status_taken)
                    failure != null -> stringResource(R.string.rootless_status_failed, failure)
                    else -> stringResource(R.string.setup_checking)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (a.active) dim else error,
            )
        }
    }
    status.layout?.let {
        Text(
            pluralStringResource(R.plurals.rootless_status_layout, it.bandCount, it.bandCount, it.binHz.roundToInt(), it.latencyMs.roundToInt()),
            style = MaterialTheme.typography.bodySmall,
            color = dim,
        )
    }
    if (attachments.any { !it.active }) {
        TextButton(onClick = vm::retry) { Text(stringResource(R.string.action_retry)) }
    }
    if (moduleActive) Text(stringResource(R.string.rootless_status_module_active), style = MaterialTheme.typography.bodySmall, color = error)
}

private const val TYPICAL_RATE = 48000
