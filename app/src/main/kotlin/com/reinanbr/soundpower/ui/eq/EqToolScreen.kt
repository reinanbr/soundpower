// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.eq

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reinanbr.soundpower.R
import com.reinanbr.soundpower.model.AudioSettings
import com.reinanbr.soundpower.model.BandLayout
import com.reinanbr.soundpower.model.Ranges
import com.reinanbr.soundpower.ui.components.Scale
import com.reinanbr.soundpower.ui.components.Section
import com.reinanbr.soundpower.ui.components.SliderRow
import com.reinanbr.soundpower.ui.components.SpIcons
import com.reinanbr.soundpower.ui.components.SwitchRow
import com.reinanbr.soundpower.ui.components.ValueRequest
import com.reinanbr.soundpower.ui.components.formatHz
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EqToolScreen(
    tool: EqTool,
    state: EqUiState,
    vm: EqViewModel,
    onBack: () -> Unit,
    onRequestValue: (ValueRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = when (tool) {
        EqTool.BANDS -> R.string.tool_bands
        // Shown by PresetsScreen, never here.
        EqTool.PRESETS -> R.string.presets_title
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(SpIcons.Back, stringResource(R.string.action_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
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
            val s = state.settings ?: return@Column
            when (tool) {
                EqTool.BANDS -> BandsTool(s, vm, onRequestValue)
                EqTool.PRESETS -> {}
            }
        }
    }
}

@Composable
private fun ColumnScope.BandsTool(s: AudioSettings, vm: EqViewModel, onRequestValue: (ValueRequest) -> Unit) {
    val graphic = s.eq.graphic
    val layout = graphic.layout
    Section(stringResource(R.string.bands_layout)) {
        for (fixed in BandLayout.FIXED) {
            LayoutRow(
                selected = layout.kind == fixed.kind,
                title = pluralStringResource(R.plurals.bands_fixed, fixed.size, fixed.size),
                detail = stringResource(R.string.bands_fixed_detail, formatHz(fixed.frequencies.first()), formatHz(fixed.frequencies.last())),
                onClick = { vm.setLayout(fixed) },
            )
        }
        val custom = layout.kind == BandLayout.Kind.CUSTOM
        LayoutRow(
            selected = custom,
            title = stringResource(R.string.bands_custom),
            detail = null,
            onClick = {
                if (!custom) vm.setLayout(BandLayout.custom(layout.size, layout.frequencies.first(), layout.frequencies.last()))
            },
        )
        if (custom) {
            val count = stringResource(R.string.bands_count)
            val start = stringResource(R.string.bands_start)
            val end = stringResource(R.string.bands_end)
            val hz = stringResource(R.string.unit_hz)
            val counts = BandLayout.MIN_BANDS.toFloat()..BandLayout.MAX_BANDS.toFloat()
            SliderRow(
                count, layout.size.toString(), layout.size.toFloat(), counts,
                onChange = { vm.setLayout(BandLayout.custom(it.roundToInt(), layout.startHz, layout.endHz)) },
                onRequestValue = {
                    onRequestValue(ValueRequest(count, layout.size.toFloat(), counts, "", 0) {
                        vm.setLayout(BandLayout.custom(it.roundToInt(), layout.startHz, layout.endHz))
                    })
                },
            )
            val starts = BandLayout.MIN_FREQ_HZ..1000f
            SliderRow(
                start, "${formatHz(layout.startHz)} $hz", layout.startHz, starts, scale = Scale.LOG,
                onChange = { vm.setLayout(BandLayout.custom(layout.size, it, layout.endHz)) },
                onRequestValue = {
                    onRequestValue(ValueRequest(start, layout.startHz, starts, hz, 0) { vm.setLayout(BandLayout.custom(layout.size, it, layout.endHz)) })
                },
            )
            val ends = 2000f..BandLayout.MAX_FREQ_HZ
            SliderRow(
                end, "${formatHz(layout.endHz)} $hz", layout.endHz, ends, scale = Scale.LOG,
                onChange = { vm.setLayout(BandLayout.custom(layout.size, layout.startHz, it)) },
                onRequestValue = {
                    onRequestValue(ValueRequest(end, layout.endHz, ends, hz, 0) { vm.setLayout(BandLayout.custom(layout.size, layout.startHz, it)) })
                },
            )
        }
    }
    Section {
        SwitchRow(
            stringResource(R.string.bands_overlap), graphic.overlap, vm::setOverlap,
            hint = stringResource(R.string.bands_overlap_hint),
        )
    }
    Hint(stringResource(R.string.bands_parametric_note))
}

@Composable
private fun LayoutRow(selected: Boolean, title: String, detail: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
