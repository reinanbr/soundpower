// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.reinanbr.soundpower.ui.theme.LocalEqColors

/**
 * A vertical gain slider for one graphic band. Drag up or down (relative to where the finger
 * lands, so touching never jumps); it sticks briefly at 0 dB with a tick. Double-tap springs
 * back to 0 dB, long-press types a value.
 */
@Composable
fun BandSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    description: String,
    onChange: (Float) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    width: Dp = 40.dp,
    /** Colour of the value, for both directions; null uses the boost and cut colours. */
    accent: Color? = null,
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val longPress by rememberUpdatedState(onLongPress)
    val haptics = rememberHaptics()
    var dragging by remember { mutableStateOf(false) }
    // Under the finger the value is drawn as is; otherwise it springs to each new value (and sweeps
    // in from its centre when the screen opens).
    val shown = remember { Animatable(0f.coerceIn(range)) }
    LaunchedEffect(value, dragging) {
        if (dragging) shown.snapTo(value) else shown.animateTo(value, valueSpring())
    }
    val active by animateFloatAsState(if (dragging) 1f else 0f, valueSpring(), label = "band-active")

    val colors = LocalEqColors.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val fill by animateColorAsState(
        when {
            !enabled -> dim
            accent != null -> accent
            value >= 0f -> colors.boost
            else -> colors.cut
        },
        label = "band-fill",
    )
    val thumbFill = MaterialTheme.colorScheme.surface
    val span = range.endInclusive - range.start
    val zero = 0f.coerceIn(range)
    val valueText = formatDb(value)

    Column(modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            valueText,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else dim,
            maxLines = 1,
        )
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics {
                    contentDescription = description
                    stateDescription = valueText
                    progressBarRangeInfo = ProgressBarRangeInfo(value, range)
                    if (enabled) {
                        setProgress { target ->
                            change(target.coerceIn(range))
                            true
                        }
                    }
                }
                .pointerInput(enabled, range) {
                    if (!enabled) return@pointerInput
                    var start = 0f
                    var dy = 0f
                    detectVerticalDragGestures(
                        onDragStart = {
                            start = current
                            dy = 0f
                            dragging = true
                            haptics.begin(current)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { pointer, amount ->
                        pointer.consume()
                        dy += amount
                        val track = size.height - 2 * THUMB_PAD.toPx()
                        val raw = (start - dy / track * span).coerceIn(range)
                        val v = stick(raw, zero, span * DETENT_FRACTION)
                        haptics.drag(v, zero, range)
                        change(v)
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onDoubleTap = {
                            if (current != zero) haptics.confirm()
                            change(zero)
                        },
                        onLongPress = {
                            haptics.longPress()
                            longPress()
                        },
                    )
                },
        ) {
            val pad = THUMB_PAD.toPx()
            val x = size.width / 2
            val top = pad
            val bottom = size.height - pad
            fun yOf(v: Float) = bottom - (bottom - top) * ((v.coerceIn(range) - range.start) / span)
            val y0 = yOf(zero)
            val y = yOf(shown.value)
            val stroke = 4.dp.toPx()
            drawLine(colors.track, Offset(x, top), Offset(x, bottom), stroke, StrokeCap.Round)
            drawLine(colors.grid, Offset(x - 7.dp.toPx(), y0), Offset(x + 7.dp.toPx(), y0), 1.5.dp.toPx())
            if (kotlin.math.abs(y - y0) > 0.5f) {
                drawLine(fill.copy(alpha = 0.16f + 0.10f * active), Offset(x, y0), Offset(x, y), stroke * 3f, StrokeCap.Round)
                drawLine(fill, Offset(x, y0), Offset(x, y), stroke, StrokeCap.Round)
            }
            // The thumb grows a little while held.
            val r = (8f + 3f * active).dp.toPx()
            if (active > 0f) drawCircle(fill.copy(alpha = 0.18f * active), r * 2f, Offset(x, y))
            drawCircle(thumbFill, r, Offset(x, y))
            drawCircle(fill, r, Offset(x, y), style = Stroke(2.5.dp.toPx()))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

private val THUMB_PAD = 12.dp
