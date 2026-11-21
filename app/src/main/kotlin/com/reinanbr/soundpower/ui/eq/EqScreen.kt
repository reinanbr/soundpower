// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.eq

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reinanbr.soundpower.R
import com.reinanbr.soundpower.eq.EqStatus
import com.reinanbr.soundpower.model.GraphicEq

@Composable
fun EqRoute(onOpenSetup: () -> Unit, vm: EqViewModel = viewModel(factory = EqViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    EqScreen(
        state = state,
        onEnabled = vm::setEnabled,
        onPreamp = vm::setPreamp,
        onAutoPreamp = vm::setAutoPreamp,
        onBand = vm::setBandGain,
        onReset = vm::reset,
        onRetry = vm::retry,
        onOpenSetup = onOpenSetup,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqScreen(
    state: EqUiState,
    onEnabled: (Boolean) -> Unit,
    onPreamp: (Float) -> Unit,
    onAutoPreamp: (Boolean) -> Unit,
    onBand: (Int, Float) -> Unit,
    onReset: () -> Unit,
    onRetry: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val settings = state.settings
    val loaded = settings != null
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = { TextButton(onClick = onOpenSetup) { Text(stringResource(R.string.eq_setup)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section {
                SwitchRow(stringResource(R.string.eq_enabled), settings?.bypass == false, loaded, onEnabled)
            }
            StatusCard(state, onRetry)
            Section(stringResource(R.string.eq_preamp)) {
                GainSlider(
                    label = "",
                    value = settings?.preampDb ?: 0f,
                    min = GraphicEq.PREAMP_MIN_DB,
                    max = GraphicEq.PREAMP_MAX_DB,
                    enabled = loaded,
                    description = stringResource(R.string.eq_preamp),
                    onChange = onPreamp,
                )
                SwitchRow(stringResource(R.string.eq_auto_preamp), settings?.autoPreamp == true, loaded, onAutoPreamp)
                Text(
                    stringResource(R.string.eq_auto_preamp_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Section(stringResource(R.string.eq_bands)) {
                if (settings != null && !GraphicEq.matches(settings.bands)) {
                    Text(stringResource(R.string.eq_not_graphic))
                    OutlinedButton(onClick = onReset) { Text(stringResource(R.string.eq_use_graphic)) }
                } else {
                    val bands = settings?.bands ?: GraphicEq.flatBands()
                    bands.forEachIndexed { i, band ->
                        val label = GraphicEq.label(band.freqHz)
                        GainSlider(
                            label = label,
                            value = band.gainDb,
                            min = GraphicEq.GAIN_MIN_DB,
                            max = GraphicEq.GAIN_MAX_DB,
                            enabled = loaded,
                            description = stringResource(R.string.eq_band_hz, label),
                            onChange = { onBand(i, it) },
                        )
                    }
                }
            }
            OutlinedButton(onClick = onReset, enabled = loaded) { Text(stringResource(R.string.eq_reset)) }
        }
    }
}

@Composable
private fun StatusCard(state: EqUiState, onRetry: () -> Unit) {
    Section(stringResource(R.string.eq_status)) {
        val dim = MaterialTheme.colorScheme.onSurfaceVariant
        state.effectError?.let {
            Text(stringResource(R.string.eq_effect_error, it), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
        when (val status: EqStatus? = state.status) {
            null -> Text(stringResource(R.string.setup_checking), color = dim)
            else -> {
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
        state.persistError?.let {
            Text(stringResource(R.string.eq_persist_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun GainSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    enabled: Boolean,
    description: String,
    onChange: (Float) -> Unit,
) {
    val slider = remember(min, max) {
        // SliderState(value, steps, range). Continuous: 47 tick marks would clutter the track,
        // and the ViewModel snaps to GraphicEq.GAIN_STEP_DB anyway.
        SliderState(value, 0, min..max)
    }
    // The ViewModel owns the value (it snaps and clamps); follow it.
    SideEffect { if (slider.value != value) slider.value = value }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (label.isNotEmpty()) {
            Text(label, Modifier.width(44.dp), style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            state = slider,
            onValueChange = {
                slider.value = it
                onChange(it)
            },
            modifier = Modifier.weight(1f).semantics { contentDescription = description },
            enabled = enabled,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.gain_db, value),
            Modifier.width(72.dp),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
        )
    }
}
