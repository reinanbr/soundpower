// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.reinanbr.soundpower.ui.theme.LocalEqColors
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow

/**
 * A band marker on the curve (parametric mode), in [color] (unspecified: the boost or cut
 * colour). A [locked] marker cannot be dragged.
 */
data class CurveHandle(
    val freqHz: Float,
    val gainDb: Float,
    val enabled: Boolean,
    val color: Color = Color.Unspecified,
    val locked: Boolean = false,
)

/** The curve of one channel, drawn as a thin line over the main curve. */
class ChannelCurve(val key: String, val db: FloatArray, val color: Color)

private const val F_LO = 20.0
private const val F_HI = 20000.0
private val GRID_HZ = listOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f)
private val LABEL_HZ = listOf(100f, 1000f, 10000f)
private const val GRID_DB = 12

/**
 * The frequency response, 20 Hz to 20 kHz on a log axis and ±[dbRange] dB, boosts above the
 * line in the boost colour and cuts below it in the cut colour. The curve and its markers morph
 * into each new state (and rise from flat when first shown), except while a marker is dragged,
 * when they follow the finger exactly.
 *
 * With [handles] it is also the parametric editor: tap a marker to select it, drag it to move
 * the band (frequency horizontally, gain vertically), long-press it for exact values.
 * [channels] adds the curves of channels that have bands of their own.
 *
 * Without handles, [window] marks the frequencies whose controls are in view (a mini-map of a
 * scrolled band list); tapping or dragging calls [onWindow] with the frequency under the finger.
 */
@Composable
fun ResponseCurveView(
    frequencies: DoubleArray,
    db: FloatArray,
    description: String,
    modifier: Modifier = Modifier,
    dbRange: Float = 18f,
    handles: List<CurveHandle> = emptyList(),
    selected: Int = -1,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit = {},
    onDrag: (index: Int, freqHz: Float, gainDb: Float) -> Unit = { _, _, _ -> },
    onLongPress: (Int) -> Unit = {},
    channels: List<ChannelCurve> = emptyList(),
    window: ClosedFloatingPointRange<Float>? = null,
    onWindow: ((Float) -> Unit)? = null,
) {
    val colors = LocalEqColors.current
    val scheme = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    val currentHandles by rememberUpdatedState(handles)
    val select by rememberUpdatedState(onSelect)
    val drag by rememberUpdatedState(onDrag)
    val longPress by rememberUpdatedState(onLongPress)
    val moveWindow by rememberUpdatedState(onWindow)
    val haptics = rememberHaptics()

    var dragging by remember { mutableStateOf(false) }
    val curve = remember { Morph(FloatArray(0), ::lerpCurve, ::curvesCompatible) }
    val markers = remember { Morph(emptyList<CurveHandle>(), ::lerpHandles) { a, b -> a.size == b.size } }
    // An empty array means "not computed yet"; keyed on null so a fresh empty array is not a change.
    LaunchedEffect(db.takeIf { it.isNotEmpty() }) { curve.retarget(db, animate = !dragging) }
    LaunchedEffect(handles) { markers.retarget(handles, animate = !dragging) }
    // Channel curves morph with the main one, and fade in and out as bands get routed.
    val channelMorphs = channels.map { c ->
        key(c.key) {
            val m = remember { Morph(FloatArray(0), ::lerpCurve, ::curvesCompatible) }
            LaunchedEffect(c.db) { m.retarget(c.db, animate = !dragging) }
            m to c.color
        }
    }
    val channelAlpha by animateFloatAsState(if (channels.isEmpty()) 0f else 1f, valueSpring(), label = "channel-curves")
    val on by animateFloatAsState(if (enabled) 1f else 0f, valueSpring(), label = "curve-enabled")
    // The selected marker pops in with a little bounce.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        pop.snapTo(0.4f)
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
    }

    fun xOf(f: Double, w: Float) = (ln(f / F_LO) / ln(F_HI / F_LO)).toFloat() * w
    fun yOf(d: Float, h: Float) = h / 2 - (d.coerceIn(-dbRange, dbRange) / dbRange) * (h / 2)

    var gestures = Modifier.semantics { contentDescription = description }
    if (handles.isEmpty() && onWindow != null) {
        fun freqAt(x: Float, w: Float) = (F_LO * (F_HI / F_LO).pow((x / w).coerceIn(0f, 1f).toDouble())).toFloat()
        gestures = gestures
            .pointerInput(Unit) { detectTapGestures { p -> moveWindow?.invoke(freqAt(p.x, size.width.toFloat())) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { pointer, _ ->
                    pointer.consume()
                    moveWindow?.invoke(freqAt(pointer.position.x, size.width.toFloat()))
                }
            }
    }
    if (handles.isNotEmpty()) {
        gestures = gestures
            .pointerInput(Unit) {
                fun nearest(p: Offset): Int? {
                    val hs = currentHandles
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    fun dist(i: Int) = hypot(xOf(hs[i].freqHz.toDouble(), w) - p.x, yOf(hs[i].gainDb, h) - p.y)
                    val best = hs.indices.minByOrNull(::dist) ?: return null
                    return if (dist(best) <= 36.dp.toPx()) best else null
                }
                detectTapGestures(
                    onTap = { p -> nearest(p)?.let(select) },
                    onLongPress = { p ->
                        nearest(p)?.let {
                            haptics.longPress()
                            longPress(it)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                var picked: Int? = null
                detectDragGestures(
                    onDragStart = { p ->
                        val hs = currentHandles
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        fun dist(i: Int) = hypot(xOf(hs[i].freqHz.toDouble(), w) - p.x, yOf(hs[i].gainDb, h) - p.y)
                        val near = hs.indices.minByOrNull(::dist)?.takeIf { dist(it) <= 36.dp.toPx() }
                        near?.let(select)
                        picked = near?.takeIf { !hs[it].locked }
                        picked?.let {
                            dragging = true
                            haptics.begin(hs[it].gainDb)
                        }
                    },
                    onDragEnd = {
                        picked = null
                        dragging = false
                    },
                    onDragCancel = {
                        picked = null
                        dragging = false
                    },
                ) { pointer, _ ->
                    val i = picked ?: return@detectDragGestures
                    pointer.consume()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val x = pointer.position.x.coerceIn(0f, w)
                    val y = pointer.position.y.coerceIn(0f, h)
                    val f = F_LO * (F_HI / F_LO).pow((x / w).toDouble())
                    val raw = (h / 2 - y) / (h / 2) * dbRange
                    val g = stick(raw, 0f, 2 * dbRange * DETENT_FRACTION)
                    haptics.drag(g, 0f, -dbRange..dbRange)
                    drag(i, f.toFloat(), g)
                }
            }
    }

    Canvas(modifier.then(gestures)) {
        val w = size.width
        val h = size.height
        drawGrid(measurer, labelStyle, w, h, colors.grid, ::xOf, ::yOf)

        val dim = scheme.onSurface.copy(alpha = 0.38f)
        val boost = lerp(dim, colors.boost, on)
        val cut = lerp(dim, colors.cut, on)

        if (window != null) {
            // Outside the window the strip is veiled; the window itself gets a soft frame.
            val x0 = xOf(window.start.toDouble(), w).coerceIn(0f, w)
            val x1 = xOf(window.endInclusive.toDouble(), w).coerceIn(x0, w)
            val veil = scheme.surface.copy(alpha = 0.55f)
            drawRect(veil, Offset.Zero, Size(x0, h))
            drawRect(veil, Offset(x1, 0f), Size(w - x1, h))
            drawRoundRect(
                scheme.primary.copy(alpha = 0.5f), Offset(x0, 1.dp.toPx()), Size(x1 - x0, h - 2.dp.toPx()),
                CornerRadius(8.dp.toPx()), style = Stroke(1.5.dp.toPx()),
            )
        }

        if (channelAlpha > 0f) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            for ((morph, color) in channelMorphs) {
                val v = morph.value
                if (v.size != frequencies.size) continue
                val path = Path()
                for (i in v.indices) {
                    val x = xOf(frequencies[i], w)
                    val y = yOf(v[i], h)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path, lerp(dim, color, on), alpha = channelAlpha,
                    style = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash),
                )
            }
        }

        val values = curve.value
        if (values.isNotEmpty() && values.size == frequencies.size) {
            val path = Path()
            for (i in values.indices) {
                val x = xOf(frequencies[i], w)
                val y = yOf(values[i], h)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val fill = Path().apply {
                addPath(path)
                lineTo(xOf(frequencies.last(), w), h / 2)
                lineTo(xOf(frequencies.first(), w), h / 2)
                close()
            }
            // Boosts fade from the boost colour at the top to nothing at 0 dB; cuts likewise
            // towards the bottom. The line itself changes colour where it crosses 0 dB.
            drawPath(
                fill,
                Brush.verticalGradient(
                    0f to boost.copy(alpha = 0.34f), 0.5f to boost.copy(alpha = 0.02f),
                    0.5f to cut.copy(alpha = 0.02f), 1f to cut.copy(alpha = 0.34f),
                ),
            )
            val line = Brush.verticalGradient(0f to boost, 0.5f to boost, 0.5f to cut, 1f to cut)
            drawPath(path, line, alpha = 0.18f, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        markers.value.forEachIndexed { i, hd ->
            val c = Offset(xOf(hd.freqHz.toDouble(), w), yOf(hd.gainDb, h))
            val own = if (hd.color.isSpecified) lerp(dim, hd.color, on) else if (hd.gainDb >= 0f) boost else cut
            val color = if (hd.enabled) own else dim
            if (i == selected) {
                drawCircle(color.copy(alpha = 0.22f), 20.dp.toPx() * pop.value, c)
                drawCircle(color, 9.dp.toPx(), c)
            } else {
                drawCircle(scheme.surface, 8.dp.toPx(), c)
                drawCircle(color, 8.dp.toPx(), c, style = Stroke(2.dp.toPx()))
            }
            val text = measurer.measure((i + 1).toString(), labelStyle)
            drawText(text, topLeft = Offset(c.x - text.size.width / 2, c.y - 13.dp.toPx() - text.size.height))
        }
    }
}

private fun DrawScope.drawGrid(
    measurer: TextMeasurer,
    style: TextStyle,
    w: Float,
    h: Float,
    color: Color,
    xOf: (Double, Float) -> Float,
    yOf: (Float, Float) -> Float,
) {
    val thin = 1.dp.toPx()
    for (f in GRID_HZ) {
        val x = xOf(f.toDouble(), w)
        drawLine(color, Offset(x, 0f), Offset(x, h), thin)
    }
    var level = -GRID_DB
    while (level <= GRID_DB) {
        val y = yOf(level.toFloat(), h)
        drawLine(color, Offset(0f, y), Offset(w, y), if (level == 0) 2 * thin else thin)
        level += GRID_DB / 2
    }
    for (f in LABEL_HZ) {
        val t = measurer.measure(formatHz(f), style)
        drawText(t, topLeft = Offset(xOf(f.toDouble(), w) + 3.dp.toPx(), h - t.size.height - 2.dp.toPx()))
    }
    for (level in listOf(GRID_DB, -GRID_DB)) {
        val t = measurer.measure(if (level > 0) "+$level" else "−${-level}", style)
        drawText(t, topLeft = Offset(3.dp.toPx(), yOf(level.toFloat(), h) - t.size.height - 2.dp.toPx()))
    }
}
