// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.eq

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.reinanbr.soundpower.R
import com.reinanbr.soundpower.model.Compressor
import com.reinanbr.soundpower.model.Limiter
import com.reinanbr.soundpower.model.Ranges
import com.reinanbr.soundpower.model.Tone
import com.reinanbr.soundpower.ui.components.KnobGrid
import com.reinanbr.soundpower.ui.components.ParamKnob
import com.reinanbr.soundpower.ui.components.Scale
import com.reinanbr.soundpower.ui.components.SwitchRow
import com.reinanbr.soundpower.ui.components.ValueRequest
import com.reinanbr.soundpower.ui.components.formatDb
import com.reinanbr.soundpower.ui.components.formatHz
import com.reinanbr.soundpower.ui.components.formatNumber
import com.reinanbr.soundpower.ui.components.valueSpring
import kotlin.math.roundToInt

/** Tone, stereo and dynamics, all as knobs: one card per effect, its switch in the header. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EffectsPage(state: EqUiState, vm: EqViewModel, actions: EqActions) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.effects_title)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val s = state.settings ?: return@Column
            val on = s.enabled
            ToneCard(s.eq.tone, on, vm, actions.requestValue)
            StereoCard(s.stereo, on, vm, actions.requestValue)
            CompressorCard(s.compressor, on, vm, actions.requestValue)
            LimiterCard(s.limiter, on, vm, actions.requestValue)
            Text(
                stringResource(R.string.eq_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A card for one effect. With [checked] the header carries the effect's switch, and the
 * controls fade back while it is off.
 */
@Composable
private fun EffectCard(
    title: String,
    hint: String?,
    checked: Boolean?,
    onChecked: (Boolean) -> Unit = {},
    content: @Composable ColumnScope.(active: Boolean) -> Unit,
) {
    val active = checked ?: true
    val fade by animateFloatAsState(if (active) 1f else 0.5f, valueSpring(), label = "effect-fade")
    Card(
        Modifier.fillMaxWidth().animateContentSize(valueSpring()),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (hint != null) {
                        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (checked != null) {
                    Switch(checked = checked, onCheckedChange = onChecked, modifier = Modifier.semantics { contentDescription = title })
                }
            }
            Column(Modifier.alpha(fade), verticalArrangement = Arrangement.spacedBy(12.dp)) { content(active) }
        }
    }
}

@Composable
private fun ToneCard(tone: Tone, on: Boolean, vm: EqViewModel, onRequestValue: (ValueRequest) -> Unit) {
    val defaults = Tone()
    val db = stringResource(R.string.unit_db)
    val hz = stringResource(R.string.unit_hz)
    val bass = stringResource(R.string.eq_bass)
    val bassFreq = stringResource(R.string.knob_bass_freq)
    val treble = stringResource(R.string.eq_treble)
    val trebleFreq = stringResource(R.string.knob_treble_freq)
    EffectCard(stringResource(R.string.tool_tone), stringResource(R.string.tone_hint), tone.enabled, { v -> vm.setTone { it.copy(enabled = v) } }) { active ->
        val enabled = on && active
        KnobGrid(
            { m ->
                ParamKnob(
                    bass, tone.bassDb, Ranges.TONE_DB, formatDb(tone.bassDb), vm::setBass,
                    { onRequestValue(ValueRequest(bass, tone.bassDb, Ranges.TONE_DB, db, 1, vm::setBass)) },
                    m, default = 0f, enabled = enabled,
                )
            },
            { m ->
                val set = { v: Float -> vm.setTone { it.copy(bassFreqHz = v) } }
                ParamKnob(
                    bassFreq, tone.bassFreqHz, Ranges.BASS_FREQ_HZ, "${formatHz(tone.bassFreqHz)} $hz", set,
                    { onRequestValue(ValueRequest(bassFreq, tone.bassFreqHz, Ranges.BASS_FREQ_HZ, hz, 0, set)) },
                    m, default = defaults.bassFreqHz, origin = Ranges.BASS_FREQ_HZ.start, scale = Scale.LOG, enabled = enabled,
                    accent = MaterialTheme.colorScheme.secondary,
                )
            },
            { m ->
                ParamKnob(
                    treble, tone.trebleDb, Ranges.TONE_DB, formatDb(tone.trebleDb), vm::setTreble,
                    { onRequestValue(ValueRequest(treble, tone.trebleDb, Ranges.TONE_DB, db, 1, vm::setTreble)) },
                    m, default = 0f, enabled = enabled,
                )
            },
            { m ->
                val set = { v: Float -> vm.setTone { it.copy(trebleFreqHz = v) } }
                ParamKnob(
                    trebleFreq, tone.trebleFreqHz, Ranges.TREBLE_FREQ_HZ, "${formatHz(tone.trebleFreqHz)} $hz", set,
                    { onRequestValue(ValueRequest(trebleFreq, tone.trebleFreqHz, Ranges.TREBLE_FREQ_HZ, hz, 0, set)) },
                    m, default = defaults.trebleFreqHz, origin = Ranges.TREBLE_FREQ_HZ.start, scale = Scale.LOG, enabled = enabled,
                    accent = MaterialTheme.colorScheme.secondary,
                )
            },
        )
    }
}

@Composable
private fun StereoCard(st: com.reinanbr.soundpower.model.Stereo, on: Boolean, vm: EqViewModel, onRequestValue: (ValueRequest) -> Unit) {
    val pct = stringResource(R.string.unit_percent)
    val balance = stringResource(R.string.stereo_balance)
    val width = stringResource(R.string.knob_width)
    val balanceText = when {
        st.balance <= -0.005f -> stringResource(R.string.balance_left_short, (-st.balance * 100).roundToInt())
        st.balance >= 0.005f -> stringResource(R.string.balance_right_short, (st.balance * 100).roundToInt())
        else -> stringResource(R.string.balance_centre_short)
    }
    EffectCard(stringResource(R.string.stereo_section), stringResource(R.string.stereo_width_hint), checked = null) {
        KnobGrid(
            { m ->
                val set = { v: Float -> vm.setStereo { it.copy(balance = v) } }
                ParamKnob(
                    balance, st.balance, Ranges.BALANCE, balanceText, set,
                    { onRequestValue(ValueRequest(balance, st.balance * 100, -100f..100f, pct, 0) { v -> set(v / 100) }) },
                    m, default = 0f, enabled = on, accent = MaterialTheme.colorScheme.secondary,
                )
            },
            { m ->
                val set = { v: Float -> vm.setStereo { it.copy(width = v) } }
                ParamKnob(
                    width, st.width, Ranges.WIDTH, stringResource(R.string.percent, (st.width * 100).roundToInt()), set,
                    { onRequestValue(ValueRequest(width, st.width * 100, 0f..200f, pct, 0) { v -> set(v / 100) }) },
                    m, default = 1f, enabled = on && !st.mono,
                )
            },
        )
        SwitchRow(
            stringResource(R.string.stereo_mono), st.mono, { v -> vm.setStereo { it.copy(mono = v) } },
            hint = stringResource(R.string.stereo_mono_hint), enabled = on,
        )
    }
}

@Composable
private fun CompressorCard(c: Compressor, on: Boolean, vm: EqViewModel, onRequestValue: (ValueRequest) -> Unit) {
    val d = Compressor()
    val db = stringResource(R.string.unit_db)
    val ms = stringResource(R.string.unit_ms)
    val threshold = stringResource(R.string.compressor_threshold)
    val ratio = stringResource(R.string.compressor_ratio)
    val attack = stringResource(R.string.compressor_attack)
    val release = stringResource(R.string.compressor_release)
    val knee = stringResource(R.string.compressor_knee)
    val makeup = stringResource(R.string.knob_makeup)
    EffectCard(stringResource(R.string.compressor), stringResource(R.string.compressor_hint), c.enabled, { v -> vm.setCompressor { it.copy(enabled = v) } }) { active ->
        val enabled = on && active
        val level = MaterialTheme.colorScheme.secondary
        KnobGrid(
            { m ->
                val set = { v: Float -> vm.setCompressor { it.copy(thresholdDb = v) } }
                ParamKnob(
                    threshold, c.thresholdDb, Ranges.COMP_THRESHOLD_DB, formatDb(c.thresholdDb), set,
                    { onRequestValue(ValueRequest(threshold, c.thresholdDb, Ranges.COMP_THRESHOLD_DB, db, 1, set)) },
                    m, default = d.thresholdDb, origin = Ranges.COMP_THRESHOLD_DB.endInclusive, enabled = enabled, accent = level,
                )
            },
            { m ->
                val set = { v: Float -> vm.setCompressor { it.copy(ratio = v) } }
                ParamKnob(
                    ratio, c.ratio, Ranges.COMP_RATIO, stringResource(R.string.ratio_value, formatNumber(c.ratio, 1)), set,
                    { onRequestValue(ValueRequest(ratio, c.ratio, Ranges.COMP_RATIO, ": 1", 1, set)) },
                    m, default = d.ratio, origin = Ranges.COMP_RATIO.start, scale = Scale.LOG, enabled = enabled, accent = level,
                )
            },
            { m -> TimeKnob(attack, c.attackMs, Ranges.COMP_ATTACK_MS, d.attackMs, ms, enabled, m, onRequestValue) { v -> vm.setCompressor { it.copy(attackMs = v) } } },
            { m -> TimeKnob(release, c.releaseMs, Ranges.COMP_RELEASE_MS, d.releaseMs, ms, enabled, m, onRequestValue) { v -> vm.setCompressor { it.copy(releaseMs = v) } } },
            { m ->
                val set = { v: Float -> vm.setCompressor { it.copy(kneeDb = v) } }
                ParamKnob(
                    knee, c.kneeDb, Ranges.COMP_KNEE_DB, formatNumber(c.kneeDb, 1), set,
                    { onRequestValue(ValueRequest(knee, c.kneeDb, Ranges.COMP_KNEE_DB, db, 1, set)) },
                    m, default = d.kneeDb, origin = 0f, enabled = enabled, accent = level,
                )
            },
            { m ->
                val set = { v: Float -> vm.setCompressor { it.copy(makeupDb = v) } }
                ParamKnob(
                    makeup, c.makeupDb, Ranges.COMP_MAKEUP_DB, formatDb(c.makeupDb), set,
                    { onRequestValue(ValueRequest(makeup, c.makeupDb, Ranges.COMP_MAKEUP_DB, db, 1, set)) },
                    m, default = 0f, enabled = enabled,
                )
            },
        )
    }
}

@Composable
private fun LimiterCard(l: Limiter, on: Boolean, vm: EqViewModel, onRequestValue: (ValueRequest) -> Unit) {
    val d = Limiter()
    val db = stringResource(R.string.unit_db)
    val ms = stringResource(R.string.unit_ms)
    val ceiling = stringResource(R.string.limiter_ceiling)
    val release = stringResource(R.string.limiter_release)
    EffectCard(stringResource(R.string.limiter), stringResource(R.string.limiter_hint), l.enabled, { v -> vm.setLimiter { it.copy(enabled = v) } }) { active ->
        val enabled = on && active
        KnobGrid(
            { m ->
                val set = { v: Float -> vm.setLimiter { it.copy(ceilingDb = v) } }
                ParamKnob(
                    ceiling, l.ceilingDb, Ranges.LIMITER_CEILING_DB, formatDb(l.ceilingDb), set,
                    { onRequestValue(ValueRequest(ceiling, l.ceilingDb, Ranges.LIMITER_CEILING_DB, db, 1, set)) },
                    m, default = d.ceilingDb, origin = Ranges.LIMITER_CEILING_DB.endInclusive, enabled = enabled,
                    accent = MaterialTheme.colorScheme.secondary,
                )
            },
            { m -> TimeKnob(release, l.releaseMs, Ranges.LIMITER_RELEASE_MS, d.releaseMs, ms, enabled, m, onRequestValue) { v -> vm.setLimiter { it.copy(releaseMs = v) } } },
        )
    }
}

/** Attack and release times turn evenly per decade. */
@Composable
private fun TimeKnob(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    unit: String,
    enabled: Boolean,
    modifier: Modifier,
    onRequestValue: (ValueRequest) -> Unit,
    onChange: (Float) -> Unit,
) {
    val text = "${formatNumber(value, if (value < 10f) 1 else 0)} $unit"
    ParamKnob(
        label, value, range, text, onChange,
        { onRequestValue(ValueRequest(label, value, range, unit, 1, onChange)) },
        modifier, default = default, origin = range.start, scale = Scale.LOG, enabled = enabled,
        accent = MaterialTheme.colorScheme.secondary,
    )
}
