// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.eq

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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.reinanbr.soundpower.BuildConfig
import com.reinanbr.soundpower.R
import com.reinanbr.soundpower.eq.EqStatus
import com.reinanbr.soundpower.ui.components.Section
import com.reinanbr.soundpower.ui.components.SpIcons
import com.reinanbr.soundpower.ui.components.formatHz
import com.reinanbr.soundpower.ui.prefs.ThemeMode

/** Band configuration, setup, the live status, the theme and the app's version. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MenuPage(state: EqUiState, theme: ThemeMode, vm: EqViewModel, actions: EqActions) {
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
                MenuRow(stringResource(R.string.eq_setup_status), stringResource(R.string.menu_setup_summary), onClick = actions.openSetup)
            }
            StatusSection(state.status)
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
private fun StatusSection(status: EqStatus?) {
    Section(stringResource(R.string.eq_status)) {
        val dim = MaterialTheme.colorScheme.onSurfaceVariant
        if (status == null) {
            Text(stringResource(R.string.setup_checking), color = dim)
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
