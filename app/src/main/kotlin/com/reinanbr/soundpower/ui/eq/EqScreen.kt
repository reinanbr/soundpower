// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.eq

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.reinanbr.soundpower.R
import com.reinanbr.soundpower.ui.presets.PresetTitle
import com.reinanbr.soundpower.ui.presets.PresetsUiState
import com.reinanbr.soundpower.ui.players.PlayersChip
import com.reinanbr.soundpower.ui.presets.PresetsViewModel
import com.reinanbr.soundpower.ui.presets.SuggestionBanner
import com.reinanbr.soundpower.model.AudioSettings
import com.reinanbr.soundpower.model.EqMode
import com.reinanbr.soundpower.model.Ranges
import com.reinanbr.soundpower.ui.components.BandSlider
import com.reinanbr.soundpower.ui.components.Knob
import com.reinanbr.soundpower.ui.components.ResponseCurveView
import com.reinanbr.soundpower.ui.components.SpIcons
import com.reinanbr.soundpower.ui.components.ValueRequest
import com.reinanbr.soundpower.ui.components.formatDb
import com.reinanbr.soundpower.ui.components.formatHz
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln

/** The equalizer page: switches, mode, the bands of the current mode, bass and treble. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EqPage(state: EqUiState, vm: EqViewModel, actions: EqActions, presets: PresetsUiState, presetsVm: PresetsViewModel) {
    val settings = state.settings
    val on = settings?.enabled ?: true
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { PresetTitle(presets.current) { actions.openPresets(null) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            actions = {
                IconButton(onClick = actions.savePreset, enabled = settings != null && presets.library != null) {
                    Icon(SpIcons.Save, stringResource(R.string.preset_save))
                }
                IconButton(onClick = actions.reset, enabled = settings != null) {
                    Icon(SpIcons.Reset, stringResource(R.string.eq_reset_all))
                }
                val switchLabel = stringResource(R.string.eq_switch)
                Switch(
                    checked = on,
                    onCheckedChange = vm::setEnabled,
                    enabled = settings != null,
                    modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = switchLabel },
                )
            },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (settings == null) {
                Text(stringResource(R.string.setup_checking))
                return@Column
            }
            // One child for all, so the error lines take no space (and no spacing) while there are none.
            Column {
                ErrorLines(state, vm::retry) { actions.openTool(EqTool.DIAGNOSTICS) }
                PlayersChip(onClick = { actions.openTool(EqTool.PLAYERS) }, modifier = Modifier.padding(bottom = 8.dp))
                ModeSelector(settings.eq.mode, vm::setMode)
            }
            presets.suggestion?.let { s ->
                SuggestionBanner(s, onShow = { actions.openPresets(null) }, onDismiss = { presetsVm.dismissSuggestion(s.output.key) })
            }
            QuickToggles(settings, state.curve?.effectivePreampDb, on, vm)
            val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
            val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            AnimatedContent(
                targetState = settings.eq.mode,
                transitionSpec = {
                    // Parametric sits to the right of graphic, as in the selector.
                    val dir = if (targetState == EqMode.PARAMETRIC) 1 else -1
                    (slideInHorizontally(spatial) { it / 5 * dir } + fadeIn(effects))
                        .togetherWith(slideOutHorizontally(spatial) { -it / 5 * dir } + fadeOut(effects))
                        .using(SizeTransform(clip = false))
                },
                label = "eq-mode",
            ) { mode ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (mode) {
                        EqMode.GRAPHIC -> GraphicEditor(state, settings, on, vm, actions.requestValue)
                        EqMode.PARAMETRIC -> ParametricEditor(state, settings, on, vm, actions.requestValue)
                    }
                }
            }
            ToneKnobs(settings, on, vm, actions.requestValue)
            Text(
                stringResource(R.string.eq_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Problems slide in and out of view instead of shifting the screen at once. */
@Composable
private fun ErrorLines(state: EqUiState, onRetry: () -> Unit, onDiagnose: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    AnimatedContent(state.effectError, transitionSpec = { fadeIn().togetherWith(fadeOut()) }, label = "effect-error") { e ->
        if (e != null) {
            Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.eq_effect_error, e), Modifier.weight(1f), color = error)
                TextButton(onClick = onDiagnose) { Text(stringResource(R.string.diag_open)) }
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
    AnimatedContent(state.persistError, transitionSpec = { fadeIn().togetherWith(fadeOut()) }, label = "persist-error") { e ->
        if (e != null) {
            Text(
                stringResource(R.string.eq_persist_error, e),
                Modifier.padding(bottom = 12.dp),
                color = error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ModeSelector(mode: EqMode, onMode: (EqMode) -> Unit) {
    val modes = listOf(EqMode.GRAPHIC to R.string.eq_mode_graphic, EqMode.PARAMETRIC to R.string.eq_mode_parametric)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { i, (m, label) ->
            SegmentedButton(
                selected = mode == m,
                onClick = { onMode(m) },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
            ) { Text(stringResource(label)) }
        }
    }
}

/** The switches used most while listening: the limiter and auto preamp, with the preamp applied. */
@Composable
private fun QuickToggles(settings: AudioSettings, appliedPreamp: Float?, on: Boolean, vm: EqViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = settings.limiter.enabled,
            onClick = { vm.setLimiter { it.copy(enabled = !it.enabled) } },
            label = { Text(stringResource(R.string.limiter)) },
        )
        FilterChip(
            selected = settings.eq.autoPreamp,
            onClick = { vm.setAutoPreamp(!settings.eq.autoPreamp) },
            label = { Text(stringResource(R.string.eq_auto_preamp)) },
            enabled = on,
        )
        if (appliedPreamp != null) {
            Text(
                stringResource(R.string.eq_applied_preamp, formatDb(appliedPreamp)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** The preamp as a fader of its own, left of the bands in both modes. */
@Composable
internal fun PreampFader(settings: AudioSettings, on: Boolean, vm: EqViewModel, onRequestValue: (ValueRequest) -> Unit) {
    val preamp = stringResource(R.string.eq_preamp)
    val db = stringResource(R.string.unit_db)
    val value = settings.eq.preampDb
    BandSlider(
        value = value,
        range = Ranges.PREAMP_DB,
        label = preamp,
        description = preamp,
        onChange = vm::setPreamp,
        onLongPress = { onRequestValue(ValueRequest(preamp, value, Ranges.PREAMP_DB, db, 1, vm::setPreamp)) },
        enabled = on,
        width = PREAMP_WIDTH,
        accent = MaterialTheme.colorScheme.secondary,
    )
}

@Composable
private fun GraphicEditor(
    state: EqUiState,
    settings: AudioSettings,
    on: Boolean,
    vm: EqViewModel,
    onRequestValue: (ValueRequest) -> Unit,
) {
    val graphic = settings.eq.graphic
    val freqs = graphic.layout.frequencies
    val curve = state.curve
    val db = stringResource(R.string.unit_db)
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val count = freqs.size
        val lanes = maxWidth - PREAMP_WIDTH - 9.dp
        val fit = lanes / count
        val scrolls = fit < 30.dp
        val width = if (scrolls) 36.dp else fit
        val density = LocalDensity.current
        val bandPx = with(density) { width.toPx() }
        val lanesPx = with(density) { lanes.toPx() }
        // The frequencies of the bands in view, for the strip above: fractional band positions,
        // interpolated on a log axis between band centres.
        val window by remember(freqs, bandPx, lanesPx, scrolls) {
            derivedStateOf {
                if (!scrolls) {
                    null
                } else {
                    val first = scroll.value / bandPx
                    val last = (scroll.value + lanesPx) / bandPx
                    frequencyAt(freqs, first)..frequencyAt(freqs, last)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ResponseCurveView(
                frequencies = curve?.frequencies ?: DoubleArray(0),
                db = curve?.db ?: FloatArray(0),
                description = stringResource(R.string.eq_curve),
                enabled = on,
                window = window,
                onWindow = if (scrolls) {
                    { f ->
                        // Centre the band nearest to the touched frequency.
                        val i = freqs.indices.minBy { abs(ln(freqs[it] / f)) }
                        val target = i * bandPx + bandPx / 2 - lanesPx / 2
                        scroll.dispatchRawDelta(target - scroll.value)
                    }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth().height(112.dp),
            )
            Row(Modifier.height(240.dp)) {
                PreampFader(settings, on, vm, onRequestValue)
                VerticalDivider(Modifier.fillMaxHeight().padding(horizontal = 4.dp, vertical = 20.dp))
                Row(Modifier.weight(1f).then(if (scrolls) Modifier.horizontalScroll(scroll) else Modifier)) {
                    freqs.forEachIndexed { i, f ->
                        val gain = graphic.gain(i)
                        val title = stringResource(R.string.eq_band_title, i + 1, formatHz(f))
                        BandSlider(
                            value = gain,
                            range = Ranges.BAND_GAIN_DB,
                            label = formatHz(f),
                            description = stringResource(R.string.eq_band_description, i + 1, formatHz(f)),
                            onChange = { vm.setGraphicGain(i, it) },
                            onLongPress = {
                                onRequestValue(ValueRequest(title, gain, Ranges.BAND_GAIN_DB, db, 1) { vm.setGraphicGain(i, it) })
                            },
                            enabled = on,
                            width = width,
                        )
                    }
                }
            }
            if (scrolls) {
                Text(
                    stringResource(R.string.eq_bands_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The frequency at fractional band position [x] (band i spans i..i+1, its centre at i + 0.5),
 * interpolated on a log axis and extended past the ends with the neighbouring spacing.
 */
private fun frequencyAt(freqs: List<Float>, x: Float): Float {
    if (freqs.size < 2) return freqs.firstOrNull() ?: 1000f
    val c = x - 0.5f
    val i = floor(c).toInt().coerceIn(0, freqs.size - 2)
    val t = c - i
    val a = ln(freqs[i])
    val b = ln(freqs[i + 1])
    return exp(a + (b - a) * t).coerceIn(20f, 20000f)
}

/** Bass and treble at hand on the equalizer page; their frequencies are on the effects page. */
@Composable
private fun ToneKnobs(settings: AudioSettings, on: Boolean, vm: EqViewModel, onRequestValue: (ValueRequest) -> Unit) {
    val tone = settings.eq.tone
    val bass = stringResource(R.string.eq_bass)
    val treble = stringResource(R.string.eq_treble)
    val db = stringResource(R.string.unit_db)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Knob(
            value = tone.bassDb, range = Ranges.TONE_DB, label = bass, valueText = formatDb(tone.bassDb),
            onChange = vm::setBass, enabled = on && tone.enabled, modifier = Modifier.width(120.dp),
            onLongPress = { onRequestValue(ValueRequest(bass, tone.bassDb, Ranges.TONE_DB, db, 1, vm::setBass)) },
        )
        Knob(
            value = tone.trebleDb, range = Ranges.TONE_DB, label = treble, valueText = formatDb(tone.trebleDb),
            onChange = vm::setTreble, enabled = on && tone.enabled, modifier = Modifier.width(120.dp),
            onLongPress = { onRequestValue(ValueRequest(treble, tone.trebleDb, Ranges.TONE_DB, db, 1, vm::setTreble)) },
        )
    }
}

internal val PREAMP_WIDTH = 48.dp
