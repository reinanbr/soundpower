// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.eq

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reinanbr.soundpower.R
import com.reinanbr.soundpower.engine.EngineFeatures
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.model.AudioSettings
import com.reinanbr.soundpower.model.Biquad
import com.reinanbr.soundpower.model.EqProfile
import com.reinanbr.soundpower.model.ParametricBand
import com.reinanbr.soundpower.model.Ranges
import com.reinanbr.soundpower.protocol.BandChannel
import com.reinanbr.soundpower.protocol.BandType
import com.reinanbr.soundpower.ui.components.ChannelCurve
import com.reinanbr.soundpower.ui.components.CurveHandle
import com.reinanbr.soundpower.ui.components.ParamKnob
import com.reinanbr.soundpower.ui.components.ResponseCurveView
import com.reinanbr.soundpower.ui.components.Scale
import com.reinanbr.soundpower.ui.components.SpIcons
import com.reinanbr.soundpower.ui.components.ValueRequest
import com.reinanbr.soundpower.ui.components.formatDb
import com.reinanbr.soundpower.ui.components.formatHz
import com.reinanbr.soundpower.ui.components.formatNumber
import com.reinanbr.soundpower.ui.components.valueSpring
import com.reinanbr.soundpower.ui.theme.EqColors
import com.reinanbr.soundpower.ui.theme.LocalEqColors

fun BandType.label(): Int = when (this) {
    BandType.PEAKING -> R.string.type_peaking
    BandType.LOW_SHELF -> R.string.type_low_shelf
    BandType.HIGH_SHELF -> R.string.type_high_shelf
    BandType.LOW_PASS -> R.string.type_low_pass
    BandType.HIGH_PASS -> R.string.type_high_pass
    BandType.NOTCH -> R.string.type_notch
    BandType.BAND_PASS -> R.string.type_band_pass
}

fun BandChannel.label(): Int = when (this) {
    BandChannel.ALL -> R.string.channel_all
    BandChannel.LEFT -> R.string.channel_left
    BandChannel.RIGHT -> R.string.channel_right
    BandChannel.MID -> R.string.channel_mid
    BandChannel.SIDE -> R.string.channel_side
}

fun BandChannel.shortLabel(): Int = when (this) {
    BandChannel.ALL -> R.string.channel_all_short
    BandChannel.LEFT -> R.string.channel_left_short
    BandChannel.RIGHT -> R.string.channel_right_short
    BandChannel.MID -> R.string.channel_mid_short
    BandChannel.SIDE -> R.string.channel_side_short
}

private fun EqColors.of(channel: BandChannel): Color = when (channel) {
    BandChannel.ALL -> boost
    BandChannel.LEFT -> left
    BandChannel.RIGHT -> right
    BandChannel.MID -> mid
    BandChannel.SIDE -> side
}

/**
 * The curve with a marker per band in the band's colour, a dashed curve for each channel that
 * has bands of its own, and a card per band below.
 */
@Composable
internal fun ParametricEditor(
    state: EqUiState,
    settings: AudioSettings,
    on: Boolean,
    vm: EqViewModel,
    onRequestValue: (ValueRequest) -> Unit,
) {
    val bands = settings.eq.parametricBands
    val sel = state.selectedBand
    val curve = state.curve
    val colors = LocalEqColors.current
    val gainLabel = stringResource(R.string.eq_gain)
    val freqLabel = stringResource(R.string.eq_frequency)
    val db = stringResource(R.string.unit_db)
    val hz = stringResource(R.string.unit_hz)
    val channelNames = BandChannel.entries.associateWith { stringResource(it.label()) }

    Row(Modifier.height(248.dp)) {
        PreampFader(settings, on, vm, onRequestValue)
        Spacer(Modifier.width(8.dp))
        ResponseCurveView(
            frequencies = curve?.frequencies ?: DoubleArray(0),
            db = curve?.db ?: FloatArray(0),
            description = stringResource(R.string.eq_curve),
            handles = bands.mapIndexed { i, b ->
                CurveHandle(
                    b.freqHz, if (Biquad.usesGain(b.type)) b.gainDb else 0f, b.enabled,
                    color = colors.band(i, b.color), locked = b.locked,
                )
            },
            channels = curve?.channels.orEmpty().map { (c, values) -> ChannelCurve(c.name, values, colors.of(c)) },
            selected = sel,
            enabled = on,
            onSelect = vm::selectBand,
            onDrag = vm::dragBand,
            onLongPress = { i ->
                vm.selectBand(i)
                val b = bands.getOrNull(i) ?: return@ResponseCurveView
                if (b.locked) return@ResponseCurveView
                if (Biquad.usesGain(b.type)) {
                    onRequestValue(ValueRequest(gainLabel, b.gainDb, Ranges.BAND_GAIN_DB, db, 1) { v -> vm.updateBand(i) { it.copy(gainDb = v) } })
                } else {
                    onRequestValue(ValueRequest(freqLabel, b.freqHz, Ranges.FREQ_HZ, hz, 0) { v -> vm.updateBand(i) { it.copy(freqHz = v) } })
                }
            },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }

    // Which dashed line is which channel.
    val routed = curve?.channels?.keys.orEmpty()
    AnimatedVisibility(routed.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            for (c in BandChannel.entries.filter { it in routed }) Legend(channelNames.getValue(c), colors.of(c))
        }
    }

    val list = rememberLazyListState()
    // A band picked on the curve scrolls its card into view.
    LaunchedEffect(sel) {
        val shown = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == sel }
        val viewport = list.layoutInfo.viewportEndOffset
        if (shown == null || shown.offset < 0 || shown.offset + shown.size > viewport) list.animateScrollToItem(sel)
    }
    LazyRow(
        state = list,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(end = 4.dp),
    ) {
        items(bands.size, key = { it }) { i ->
            BandCard(
                index = i,
                band = bands[i],
                selected = i == sel,
                color = colors.band(i, bands[i].color),
                on = on,
                canRemove = bands.size > 1,
                features = state.features,
                vm = vm,
                onRequestValue = onRequestValue,
                modifier = Modifier.animateItem(),
            )
        }
        if (bands.size < EqProfile.MAX_PARAMETRIC_BANDS) {
            item(key = "add") {
                OutlinedCard(
                    onClick = vm::addBand,
                    enabled = on,
                    modifier = Modifier.animateItem().width(112.dp).height(CARD_HEIGHT),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp).weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(SpIcons.Add, null)
                        Text(stringResource(R.string.eq_add_band), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend(name: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(width = 18.dp, height = 8.dp)) {
            drawLine(
                color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())),
            )
        }
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One band: colour, on/off, lock, type, channel, and knobs for frequency, gain and Q. A locked
 * card keeps its values until unlocked.
 */
@Composable
private fun BandCard(
    index: Int,
    band: ParametricBand,
    selected: Boolean,
    color: Color,
    on: Boolean,
    canRemove: Boolean,
    features: EngineFeatures,
    vm: EqViewModel,
    onRequestValue: (ValueRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editable = on && !band.locked
    val border by animateColorAsState(if (selected) color else Color.Transparent, valueSpring(), label = "card-border")
    val dim by animateFloatAsState(if (band.enabled) 1f else 0.55f, valueSpring(), label = "card-dim")
    val update = { transform: (ParametricBand) -> ParametricBand -> vm.updateBand(index, transform) }
    val freq = stringResource(R.string.eq_frequency)
    val freqShort = stringResource(R.string.eq_frequency_short)
    val gain = stringResource(R.string.eq_gain)
    val q = stringResource(R.string.eq_q)
    val hz = stringResource(R.string.unit_hz)
    val db = stringResource(R.string.unit_db)
    val usesGain = Biquad.usesGain(band.type)

    Card(
        onClick = { vm.selectBand(index) },
        modifier = modifier.width(CARD_WIDTH).height(CARD_HEIGHT),
        border = BorderStroke(2.dp, border),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorPicker(color, band.color, enabled = on) { c -> update { it.copy(color = c) } }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.eq_band_name, index + 1),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                val lockLabel = stringResource(if (band.locked) R.string.eq_band_unlock else R.string.eq_band_lock)
                IconToggleButton(
                    checked = band.locked,
                    onCheckedChange = { v -> update { it.copy(locked = v) } },
                    enabled = on,
                    modifier = Modifier.size(36.dp).semantics { contentDescription = lockLabel },
                ) { Icon(if (band.locked) SpIcons.Lock else SpIcons.LockOpen, null, Modifier.size(20.dp)) }
                val onLabel = stringResource(R.string.eq_band_on)
                Switch(
                    checked = band.enabled,
                    onCheckedChange = { v -> update { it.copy(enabled = v) } },
                    enabled = editable,
                    modifier = Modifier.semantics { contentDescription = onLabel },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TypeSelector(band.type, editable, Modifier.weight(1.3f)) { t -> update { it.copy(type = t) } }
                ChannelSelector(band.channel, editable, features::supports, Modifier.weight(1f)) { c -> update { it.copy(channel = c) } }
            }
            Row(Modifier.fillMaxWidth().alpha(dim), horizontalArrangement = Arrangement.SpaceBetween) {
                ParamKnob(
                    label = freqShort, value = band.freqHz, range = Ranges.FREQ_HZ, valueText = formatHz(band.freqHz),
                    onChange = { v -> update { it.copy(freqHz = v) } },
                    onLongPress = { onRequestValue(ValueRequest(freq, band.freqHz, Ranges.FREQ_HZ, hz, 0) { v -> update { it.copy(freqHz = v) } }) },
                    default = 1000f, scale = Scale.LOG, enabled = editable, size = KNOB_SIZE, accent = color,
                    modifier = Modifier.weight(1f),
                )
                ParamKnob(
                    label = gain, value = band.gainDb, range = Ranges.BAND_GAIN_DB, valueText = formatDb(band.gainDb),
                    onChange = { v -> update { it.copy(gainDb = v) } },
                    onLongPress = { onRequestValue(ValueRequest(gain, band.gainDb, Ranges.BAND_GAIN_DB, db, 1) { v -> update { it.copy(gainDb = v) } }) },
                    default = 0f, enabled = editable && usesGain, size = KNOB_SIZE,
                    modifier = Modifier.weight(1f),
                )
                ParamKnob(
                    label = q, value = band.q, range = Ranges.Q, valueText = formatNumber(band.q, 2),
                    onChange = { v -> update { it.copy(q = v) } },
                    onLongPress = { onRequestValue(ValueRequest(q, band.q, Ranges.Q, "", 2) { v -> update { it.copy(q = v) } }) },
                    default = 0.71f, scale = Scale.LOG, enabled = editable, size = KNOB_SIZE, accent = color,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val note = when {
                    !features.supports(band.channel) && features.mode == EngineMode.ROOTLESS -> stringResource(R.string.eq_routing_needs_root)
                    !features.supports(band.channel) -> stringResource(R.string.eq_routing_unsupported)
                    band.locked -> stringResource(R.string.eq_band_locked)
                    else -> null
                }
                Text(
                    note.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (band.locked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { vm.removeBand(index) }, enabled = editable && canRemove, modifier = Modifier.size(36.dp)) {
                    Icon(SpIcons.Remove, stringResource(R.string.eq_remove_band), Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun ColorPicker(color: Color, chosen: Int?, enabled: Boolean, onColor: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.eq_band_color)
    Box {
        Box(
            Modifier
                .size(22.dp)
                .background(color, CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                .clickable(enabled = enabled) { open = true }
                .semantics { contentDescription = label },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.eq_band_color_auto)) },
                onClick = {
                    open = false
                    onColor(null)
                },
            )
            val palette = LocalEqColors.current.bands
            for (row in palette.indices.chunked(4)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (i in row) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(palette[i], CircleShape)
                                .border(
                                    if (chosen == i) 3.dp else 0.dp,
                                    if (chosen == i) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    CircleShape,
                                )
                                .clickable {
                                    open = false
                                    onColor(i)
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeSelector(type: BandType, enabled: Boolean, modifier: Modifier = Modifier, onType: (BandType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.fillMaxWidth().height(36.dp),
        ) {
            Text(stringResource(type.label()), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (t in BandType.entries) {
                DropdownMenuItem(text = { Text(stringResource(t.label())) }, onClick = {
                    open = false
                    onType(t)
                })
            }
        }
    }
}

@Composable
private fun ChannelSelector(
    channel: BandChannel,
    enabled: Boolean,
    supports: (BandChannel) -> Boolean,
    modifier: Modifier = Modifier,
    onChannel: (BandChannel) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val colors = LocalEqColors.current
    val description = stringResource(R.string.eq_band_channel)
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.fillMaxWidth().height(36.dp).semantics { contentDescription = description },
        ) {
            Text(
                stringResource(channel.shortLabel()),
                style = MaterialTheme.typography.labelMedium,
                color = if (channel == BandChannel.ALL || !enabled) Color.Unspecified else colors.of(channel),
                maxLines = 1,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (c in BandChannel.entries) {
                val supported = supports(c)
                val hint = when {
                    !supported -> R.string.channel_needs_root
                    c == BandChannel.MID -> R.string.channel_mid_hint
                    c == BandChannel.SIDE -> R.string.channel_side_hint
                    else -> null
                }
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(c.label()))
                            if (hint != null) {
                                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    leadingIcon = { Box(Modifier.size(10.dp).background(if (c == BandChannel.ALL) Color.Transparent else colors.of(c), CircleShape)) },
                    enabled = supported || c == channel,
                    onClick = {
                        open = false
                        onChannel(c)
                    },
                )
            }
        }
    }
}

private val CARD_WIDTH = 232.dp
private val CARD_HEIGHT = 228.dp
private val KNOB_SIZE = 56.dp
