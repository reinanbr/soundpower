// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.model

import com.reinanbr.soundpower.protocol.BandType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphicEqTest {
    @Test
    fun flatLayoutIsTenOctavePeakingBandsAtZeroGain() {
        val bands = GraphicEq.flatBands()
        assertEquals(10, bands.size)
        assertTrue(bands.all { it.type == BandType.PEAKING && it.enabled && it.gainDb == 0f && it.q == GraphicEq.Q })
        assertTrue(GraphicEq.matches(bands))
        assertTrue(GraphicEq.matches(EqSettings.DEFAULT.bands))
    }

    @Test
    fun gainsAreClampedAndSnapped() {
        val bands = GraphicEq.flatBands()
        assertEquals(12f, GraphicEq.withGain(bands, 0, 40f)[0].gainDb)
        assertEquals(-12f, GraphicEq.withGain(bands, 9, -13f)[9].gainDb)
        assertEquals(3.5f, GraphicEq.withGain(bands, 4, 3.6f)[4].gainDb)
        assertEquals(0f, GraphicEq.withGain(bands, 4, Float.NaN)[4].gainDb)
        assertEquals(bands.drop(1), GraphicEq.withGain(bands, 0, 5f).drop(1))
        assertTrue(GraphicEq.matches(GraphicEq.withGain(bands, 3, -7f)))
        assertFailsWith<IllegalArgumentException> { GraphicEq.withGain(bands, 10, 0f) }
    }

    @Test
    fun otherLayoutsDoNotMatch() {
        val bands = GraphicEq.flatBands()
        assertFalse(GraphicEq.matches(bands.drop(1)))
        assertFalse(GraphicEq.matches(bands.mapIndexed { i, b -> if (i == 2) b.copy(type = BandType.LOW_SHELF) else b }))
        assertFalse(GraphicEq.matches(bands.mapIndexed { i, b -> if (i == 2) b.copy(freqHz = 130f) else b }))
    }

    @Test
    fun labels() {
        assertEquals(listOf("31.5", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"), GraphicEq.FREQUENCIES_HZ.map(GraphicEq::label))
        assertEquals("1.5k", GraphicEq.label(1500f))
    }
}
