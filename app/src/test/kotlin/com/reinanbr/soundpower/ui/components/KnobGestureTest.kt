// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.components

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KnobGestureTest {
    private val radius = 100f

    @Test
    fun fromTheCapItSlides() {
        assertEquals(KnobDrag.UP_DOWN, knobDrag(Offset(5f, -10f), Offset(0f, -12f), radius))
        assertEquals(KnobDrag.UP_DOWN, knobDrag(Offset.Zero, Offset(3f, 12f), radius))
        assertEquals(KnobDrag.SIDEWAYS, knobDrag(Offset.Zero, Offset(12f, 3f), radius))
        // Across the rim, at its top or bottom: still a slide.
        assertEquals(KnobDrag.UP_DOWN, knobDrag(Offset(0f, -80f), Offset(0f, -12f), radius))
        assertEquals(KnobDrag.UP_DOWN, knobDrag(Offset(10f, 90f), Offset(-2f, -12f), radius))
        assertEquals(KnobDrag.SIDEWAYS, knobDrag(Offset(80f, 0f), Offset(12f, 1f), radius))
    }

    @Test
    fun alongTheRimItTurns() {
        assertEquals(KnobDrag.TURN, knobDrag(Offset(-80f, 0f), Offset(0f, -12f), radius))
        assertEquals(KnobDrag.TURN, knobDrag(Offset(70f, 20f), Offset(-4f, 12f), radius))
        // Sideways along the top of the rim, as a turn usually starts.
        assertEquals(KnobDrag.TURN, knobDrag(Offset(0f, -80f), Offset(-12f, 2f), radius))
    }

    @Test
    fun inARowThatScrollsSidewaysTheRowGetsSidewaysDrags() {
        assertNull(knobDrag(Offset.Zero, Offset(12f, 3f), radius, sideways = false))
        assertNull(knobDrag(Offset(0f, -80f), Offset(-12f, 2f), radius, sideways = false))
        assertEquals(KnobDrag.TURN, knobDrag(Offset(-80f, 0f), Offset(0f, -12f), radius, sideways = false))
        assertEquals(KnobDrag.UP_DOWN, knobDrag(Offset.Zero, Offset(3f, 12f), radius, sideways = false))
    }

    @Test
    fun turnsAreClockwiseInDegrees() {
        // From 9 o'clock to 12 o'clock is a quarter turn clockwise (y grows downwards).
        assertEquals(90f, turnDegrees(Offset(-50f, 0f), Offset(0f, -50f), 10f), 1e-3f)
        assertEquals(-90f, turnDegrees(Offset(0f, -50f), Offset(-50f, 0f), 10f), 1e-3f)
        // Across 9 o'clock, where the angle wraps round.
        assertEquals(20f, turnDegrees(Offset(-50f, 8.8163f), Offset(-50f, -8.8163f), 10f), 1e-2f)
        assertEquals(-20f, turnDegrees(Offset(-50f, -8.8163f), Offset(-50f, 8.8163f), 10f), 1e-2f)
    }

    @Test
    fun theCentreDoesNotTurn() {
        assertEquals(0f, turnDegrees(Offset(2f, 1f), Offset(40f, 0f), 10f))
        assertEquals(0f, turnDegrees(Offset(40f, 0f), Offset(-1f, 3f), 10f))
    }
}
