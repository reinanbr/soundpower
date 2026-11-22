// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.components

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/*
 * How a finger moves a knob. A drag that starts on the dial's rim and runs along it turns the
 * knob like a real one (clockwise raises it); any other drag slides it, up or right to raise it.
 * The knob decides once the finger has moved past the touch slop, and keeps the drag until the
 * finger lifts. In a row that scrolls sideways the knob leaves sideways drags to the row.
 */

/** How a drag moves a knob. */
internal enum class KnobDrag {
    /** Along the rim: the value follows the finger round the centre, the whole range over the arc. */
    TURN,

    /** Up raises the value, down lowers it. */
    UP_DOWN,

    /** Right raises the value, left lowers it. */
    SIDEWAYS,
}

/** From this fraction of the dial's radius out (about the cap's edge), a drag along the rim turns the knob. */
internal const val RIM_FRACTION = 0.5f

/** Nearer the centre than this fraction of the radius, the finger's angle is too jumpy to follow. */
internal const val TURN_MIN_FRACTION = 0.25f

/**
 * How a drag that started at [from] and has moved by [moved] since moves a dial of [radius];
 * [from] is relative to the dial's centre. Null for a drag left to the page: one that starts
 * sideways when [sideways] is false.
 */
internal fun knobDrag(from: Offset, moved: Offset, radius: Float, sideways: Boolean = true): KnobDrag? {
    val upDown = abs(moved.y) >= abs(moved.x)
    if (!upDown && !sideways) return null
    val d = from.getDistance()
    if (d >= radius * RIM_FRACTION) {
        val n = from / d
        val along = abs(moved.y * n.x - moved.x * n.y)
        val across = abs(moved.x * n.x + moved.y * n.y)
        if (along >= across) return KnobDrag.TURN
    }
    return if (upDown) KnobDrag.UP_DOWN else KnobDrag.SIDEWAYS
}

/**
 * Degrees the finger turned clockwise going from [from] to [to], both relative to the centre;
 * 0 when either is within [minDistance] of it.
 */
internal fun turnDegrees(from: Offset, to: Offset, minDistance: Float): Float {
    if (from.getDistance() < minDistance || to.getDistance() < minDistance) return 0f
    var deg = (atan2(to.y, to.x) - atan2(from.y, from.x)) * (180f / PI.toFloat())
    if (deg > 180f) deg -= 360f else if (deg <= -180f) deg += 360f
    return deg
}
