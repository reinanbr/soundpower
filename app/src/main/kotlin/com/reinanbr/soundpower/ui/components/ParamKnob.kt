// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * A [Knob] for a value on a linear or logarithmic [scale]: frequencies and times turn evenly
 * per octave or decade. [default] is where a double tap returns and the knob sticks; [origin]
 * is where its arc starts.
 */
@Composable
fun ParamKnob(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    default: Float = range.start,
    origin: Float = default,
    scale: Scale = Scale.LINEAR,
    enabled: Boolean = true,
    size: Dp = 80.dp,
    accent: Color? = null,
) {
    if (scale == Scale.LINEAR) {
        Knob(
            value = value, range = range, label = label, valueText = valueText, onChange = onChange,
            onLongPress = onLongPress, modifier = modifier, default = default, enabled = enabled, size = size,
            origin = origin, accent = accent,
        )
    } else {
        Knob(
            value = toPosition(value, range, scale),
            range = 0f..1f,
            label = label,
            valueText = valueText,
            onChange = { onChange(fromPosition(it, range, scale)) },
            onLongPress = onLongPress,
            modifier = modifier,
            default = toPosition(default, range, scale),
            enabled = enabled,
            size = size,
            origin = toPosition(origin, range, scale),
            accent = accent,
        )
    }
}

/**
 * Lays [knobs] out in rows of as many as fit at [minCell] each, every cell the same width, so
 * knobs line up from row to row. Each knob gets the modifier of its cell.
 */
@Composable
fun KnobGrid(vararg knobs: @Composable (Modifier) -> Unit, modifier: Modifier = Modifier, minCell: Dp = 96.dp) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = max(1, (maxWidth / minCell).toInt()).coerceAtMost(knobs.size.coerceAtLeast(1))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (row in knobs.toList().chunked(columns)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    for (knob in row) knob(Modifier.weight(1f))
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
