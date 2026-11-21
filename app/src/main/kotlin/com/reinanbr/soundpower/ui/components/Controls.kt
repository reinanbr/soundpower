// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.ln
import kotlin.math.pow

/** A titled card. */
@Composable
fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().animateContentSize(valueSpring()),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** How a slider position (0..1) maps to a value. */
enum class Scale { LINEAR, LOG }

internal fun toPosition(v: Float, range: ClosedFloatingPointRange<Float>, scale: Scale): Float = when (scale) {
    Scale.LINEAR -> (v - range.start) / (range.endInclusive - range.start)
    Scale.LOG -> (ln(v / range.start) / ln(range.endInclusive / range.start))
}.coerceIn(0f, 1f)

internal fun fromPosition(p: Float, range: ClosedFloatingPointRange<Float>, scale: Scale): Float = when (scale) {
    Scale.LINEAR -> range.start + p * (range.endInclusive - range.start)
    Scale.LOG -> range.start * (range.endInclusive / range.start).pow(p)
}

/**
 * A labelled slider with its value. Tapping the value, or long-pressing the label row, asks for
 * an exact value through [onRequestValue].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onRequestValue: () -> Unit,
    modifier: Modifier = Modifier,
    scale: Scale = Scale.LINEAR,
    enabled: Boolean = true,
) {
    val request by rememberUpdatedState(onRequestValue)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().pointerInput(enabled) {
                if (enabled) detectTapGestures(onLongPress = { request() })
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                Modifier.clickable(enabled = enabled) { request() }.padding(horizontal = 4.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val position = toPosition(value, range, scale)
        // SliderState(value, steps, range): the ViewModel snaps values, so the slider is continuous.
        val state = remember { SliderState(position, 0, 0f..1f) }
        SideEffect { if (state.value != position) state.value = position }
        Slider(
            state = state,
            onValueChange = {
                state.value = it
                onChange(fromPosition(it, range, scale))
            },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            enabled = enabled,
        )
    }
}
