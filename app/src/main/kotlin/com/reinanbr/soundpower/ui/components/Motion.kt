// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/*
 * How SoundPower moves and feels. A control under the finger follows it with no animation at
 * all; whenever a value changes on its own (reset, a typed value, switching mode or preset) the
 * control springs to it, so the change can be followed by eye. Values that stop on their
 * centre or at an end of their range give a light tick.
 */

/** Spring for values that change on their own: quick, settled, no visible overshoot. */
fun <T> valueSpring(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

/** Fraction of a control's range around its centre where a dragged value sticks to the centre. */
internal const val DETENT_FRACTION = 0.012f

/** Within [width] of [detent], a dragged value sticks to it. */
internal fun stick(raw: Float, detent: Float, width: Float): Float = if (abs(raw - detent) <= width) detent else raw

/** The vibration cues of the controls. */
internal class Haptics(private val feedback: HapticFeedback) {
    private var last = Float.NaN

    /** A drag starts at [value]. */
    fun begin(value: Float) {
        last = value
    }

    /** A dragged value: ticks when it lands on [detent] or on an end of [range]. */
    fun drag(value: Float, detent: Float, range: ClosedFloatingPointRange<Float>) {
        if (value != last && (value == detent || value == range.start || value == range.endInclusive)) {
            feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
        last = value
    }

    /** A value went back to its default (double tap, reset). */
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)

    /** A long press opened the exact-value dialog. */
    fun longPress() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
}

@Composable
internal fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}

/**
 * A value that morphs into each new target instead of jumping. [lerp] blends two values;
 * targets that [compatible] says cannot be blended (a different number of points) are shown at
 * once. Reading [value] inside a draw block redraws on every animation frame without
 * recomposing.
 */
internal class Morph<T>(
    initial: T,
    private val lerp: (from: T, to: T, fraction: Float) -> T,
    private val compatible: (from: T, to: T) -> Boolean,
) {
    private var from by mutableStateOf(initial)
    private var to by mutableStateOf(initial)
    private val progress = Animatable(1f)

    val value: T get() = lerp(from, to, progress.value)

    /** Moves to [next]: animated, or at once when [animate] is false (the finger is on it). */
    suspend fun retarget(next: T, animate: Boolean) {
        if (!animate || !compatible(value, next)) {
            from = next
            to = next
            progress.snapTo(1f)
            return
        }
        // Start from what is on screen, so a new target mid-animation continues smoothly.
        from = value
        to = next
        progress.snapTo(0f)
        progress.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
    }
}

/** Blends two curves point by point; an empty curve counts as flat, so a curve rises into view. */
internal fun lerpCurve(from: FloatArray, to: FloatArray, t: Float): FloatArray =
    if (t >= 1f || from === to) to else FloatArray(to.size) { i -> from.getOrElse(i) { 0f } * (1 - t) + to[i] * t }

internal fun curvesCompatible(from: FloatArray, to: FloatArray) = from.isEmpty() || from.size == to.size

/** Blends band markers: frequency on a log scale, gain linearly. */
internal fun lerpHandles(from: List<CurveHandle>, to: List<CurveHandle>, t: Float): List<CurveHandle> =
    if (t >= 1f || from === to || from.size != to.size) {
        to
    } else {
        to.mapIndexed { i, b ->
            val a = from[i]
            b.copy(
                freqHz = exp(ln(a.freqHz) * (1 - t) + ln(b.freqHz) * t),
                gainDb = a.gainDb * (1 - t) + b.gainDb * t,
            )
        }
    }
