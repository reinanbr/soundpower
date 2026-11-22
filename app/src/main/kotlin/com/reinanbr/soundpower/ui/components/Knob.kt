// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.reinanbr.soundpower.ui.theme.LocalEqColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val START_DEG = 135f
private const val SWEEP_DEG = 270f

/**
 * A rotary control. Drag up or down to turn it (the whole range over [dragSpan]); it sticks
 * briefly at [default] with a tick. Double-tap to spring back to [default], long-press to type
 * an exact value. The arc starts at [origin] (normally [default]), so boosts and cuts read at a
 * glance. Only the dial takes the gestures, so a page of knobs still scrolls from their labels.
 */
@Composable
fun Knob(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    valueText: String,
    onChange: (Float) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    default: Float = 0f,
    enabled: Boolean = true,
    size: Dp = 88.dp,
    dragSpan: Dp = 220.dp,
    /** Where the value arc starts: [default] for a value either side of it, the range start for a level. */
    origin: Float = default,
    /** Colour of the value arc; null uses the boost colour above [origin] and the cut colour below. */
    accent: Color? = null,
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val longPress by rememberUpdatedState(onLongPress)
    val haptics = rememberHaptics()
    var dragging by remember { mutableStateOf(false) }
    // Under the finger the value is drawn as is; otherwise it springs to each new value (and sweeps
    // in from its centre when the screen opens).
    val shown = remember { Animatable(default.coerceIn(range)) }
    LaunchedEffect(value, dragging) {
        if (dragging) shown.snapTo(value) else shown.animateTo(value, valueSpring())
    }
    val active by animateFloatAsState(if (dragging) 1f else 0f, valueSpring(), label = "knob-active")

    val colors = LocalEqColors.current
    val scheme = MaterialTheme.colorScheme
    val dim = scheme.onSurface.copy(alpha = 0.38f)
    val accent by animateColorAsState(
        when {
            !enabled -> dim
            accent != null -> accent
            value >= origin -> colors.boost
            else -> colors.cut
        },
        label = "knob-accent",
    )
    val cap = scheme.surfaceContainerHigh
    val capEdge = scheme.outlineVariant
    val pointer = if (enabled) scheme.onSurface else dim
    val span = range.endInclusive - range.start

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .semantics {
                    contentDescription = label
                    stateDescription = valueText
                    progressBarRangeInfo = ProgressBarRangeInfo(value, range)
                    if (enabled) {
                        setProgress { target ->
                            change(target.coerceIn(range))
                            true
                        }
                    }
                }
                .pointerInput(enabled, range, default) {
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
                        val raw = (start - dy / dragSpan.toPx() * span).coerceIn(range)
                        val v = stick(raw, default, span * DETENT_FRACTION)
                        haptics.drag(v, default, range)
                        change(v)
                    }
                }
                .pointerInput(enabled, default) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onDoubleTap = {
                            if (current != default) haptics.confirm()
                            change(default)
                        },
                        onLongPress = {
                            haptics.longPress()
                            longPress()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(size)) {
                val stroke = (5.5f + 1.5f * active).dp.toPx()
                val inset = 4.5.dp.toPx() + 1.dp.toPx()
                val arcSize = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
                val topLeft = Offset(inset, inset)
                val round = Stroke(stroke, cap = StrokeCap.Round)
                drawArc(colors.track, START_DEG, SWEEP_DEG, false, topLeft, arcSize, style = round)

                fun angleOf(v: Float) = START_DEG + SWEEP_DEG * ((v.coerceIn(range) - range.start) / span)
                val a0 = angleOf(origin)
                val a1 = angleOf(shown.value)
                if (abs(a1 - a0) > 0.01f) {
                    // A soft glow under the value arc, stronger while the knob is held.
                    drawArc(
                        accent.copy(alpha = 0.10f + 0.12f * active), minOf(a0, a1), abs(a1 - a0), false, topLeft, arcSize,
                        style = Stroke(stroke * 2.6f, cap = StrokeCap.Round),
                    )
                    drawArc(accent, minOf(a0, a1), abs(a1 - a0), false, topLeft, arcSize, style = round)
                }

                // The cap, with a dot that points at the value.
                val r = arcSize.width / 2
                val capR = r * 0.62f
                drawCircle(cap, capR, center)
                drawCircle(capEdge, capR, center, style = Stroke(1.dp.toPx()))
                val rad = Math.toRadians(a1.toDouble())
                val dir = Offset(cos(rad).toFloat(), sin(rad).toFloat())
                drawCircle(pointer, 2.8.dp.toPx(), center + dir * (capR - 7.dp.toPx()))
            }
            Text(
                valueText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) scheme.onSurface else dim,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}
