// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.ui.input.pointer.PointerInputChange
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
 * A rotary control. Turn it by its rim like a real knob (the whole range over the arc), or drag
 * it up or down, or sideways, from its cap (the whole range over [dragSpan]); see [knobDrag]. It
 * sticks briefly at [default] with a tick. Double-tap to spring back to [default], long-press to type an exact
 * value. The arc starts at [origin] (normally [default]), so boosts and cuts read at a glance.
 * Only the dial takes the gestures, so a page of knobs still scrolls from their labels.
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
    /** Whether a drag that starts sideways moves the knob; false in a row that scrolls sideways. */
    sideways: Boolean = true,
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
                .pointerInput(enabled, range, default, sideways) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val centre = Offset(this@pointerInput.size.width / 2f, this@pointerInput.size.height / 2f)
                        val radius = minOf(this@pointerInput.size.width, this@pointerInput.size.height) / 2f
                        var from = down.position
                        var kind = KnobDrag.UP_DOWN
                        var over = Offset.Zero
                        val first = awaitTouchSlopOrCancellation(down.id) { c, overSlop ->
                            val k = knobDrag(from - centre, c.position - from, radius, sideways)
                            if (k == null) {
                                // Left to the row; the slop counts again from here.
                                from = c.position
                            } else {
                                kind = k
                                over = overSlop
                                c.consume()
                            }
                        } ?: return@awaitEachGesture

                        var pos = current
                        // A turn counts from where the finger started, so the dot stays under it.
                        var last = if (kind == KnobDrag.TURN) from else first.position - over
                        val slideSpan = dragSpan.toPx()
                        fun follow(c: PointerInputChange) {
                            val to = c.position
                            val delta = when (kind) {
                                KnobDrag.TURN -> turnDegrees(last - centre, to - centre, radius * TURN_MIN_FRACTION) / SWEEP_DEG * span
                                KnobDrag.UP_DOWN -> (last.y - to.y) / slideSpan * span
                                KnobDrag.SIDEWAYS -> (to.x - last.x) / slideSpan * span
                            }
                            last = to
                            // Past an end the knob stops, and comes straight back.
                            pos = (pos + delta).coerceIn(range)
                            val v = stick(pos, default, span * DETENT_FRACTION)
                            haptics.drag(v, default, range)
                            change(v)
                        }

                        dragging = true
                        haptics.begin(current)
                        try {
                            follow(first)
                            drag(first.id) { c ->
                                c.consume()
                                follow(c)
                            }
                        } finally {
                            dragging = false
                        }
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
                // Round even when a narrow cell squeezes the dial.
                val diameter = this.size.minDimension - 2 * inset
                val arcSize = Size(diameter, diameter)
                val topLeft = Offset((this.size.width - diameter) / 2, (this.size.height - diameter) / 2)
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
