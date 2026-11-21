// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.model

import com.reinanbr.soundpower.protocol.Band
import com.reinanbr.soundpower.protocol.BandChannel
import com.reinanbr.soundpower.protocol.BandType
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's curve must be the effect's curve. The references were produced by the C++ DSP core
 * (`sp-cli response`, command line in each file's first line).
 */
class ResponseCurveTest {
    private class Reference(val header: String, val rows: List<DoubleArray>)

    private fun reference(name: String): Reference {
        val lines = javaClass.classLoader.getResource(name)!!.readText().lines().filter { it.isNotBlank() }
        val header = lines.first { it.startsWith("# fs=") }
        val rows = lines.filterNot { it.startsWith("#") || it.startsWith("freq") }
            .map { l -> l.split(",").map { it.toDouble() }.toDoubleArray() }
        return Reference(header, rows)
    }

    private fun peak(f: Float, g: Float, q: Float) = Band(BandType.PEAKING, true, f, g, q)

    @Test
    fun everyFilterTypeMatchesTheDspCore() {
        val bands = listOf(
            peak(250f, 5f, 1.5f),
            Band(BandType.LOW_SHELF, true, 80f, -4f, 0.7f),
            Band(BandType.HIGH_PASS, true, 30f, 0f, 0.7071f),
            Band(BandType.NOTCH, true, 3000f, 0f, 4f),
            peak(6000f, -7.5f, 2.5f),
            Band(BandType.BAND_PASS, true, 12000f, 0f, 0.8f),
        )
        val tone = Tone(bassDb = 6f, bassFreqHz = 120f, trebleDb = -3f, trebleFreqHz = 6000f)
        val curve = ResponseCurve(bands, tone, 48000.0)
        val ref = reference("response_reference.csv")
        assertEquals(64, ref.rows.size)
        for ((f, eqDb) in ref.rows) {
            if (eqDb < -60.0) continue // deep in the notch/band-pass skirts, float noise dominates
            assertEquals(eqDb, curve.db(f), 1e-3, "f=$f")
        }
        // Below 0 dB everywhere: auto-preamp leaves the +2 dB preamp alone.
        assertEquals(2f, curve.effectivePreampDb(2f, autoPreamp = true), 1e-4f)
    }

    @Test
    fun autoPreampMatchesTheDspCore() {
        val bands = listOf(peak(1000f, 9f, 1f), peak(1400f, 4f, 2f))
        val tone = Tone(bassDb = 4.5f, bassFreqHz = 90f, trebleDb = 3f)
        val curve = ResponseCurve(bands, tone, 44100.0)
        val ref = reference("response_autopreamp.csv")
        val expected = Regex("effective_preamp_db=(-?[0-9.]+)").find(ref.header)!!.groupValues[1].toFloat()
        assertEquals(expected, curve.effectivePreampDb(0f, autoPreamp = true), 1e-3f)
        assertEquals(0f, curve.effectivePreampDb(0f, autoPreamp = false))
        for ((f, eqDb) in ref.rows) assertEquals(eqDb, curve.db(f), 1e-3, "f=$f")
    }

    @Test
    fun routedBandsMatchTheDspCore() {
        val bands = listOf(
            Band(BandType.PEAKING, true, 250f, 4f, 1f, BandChannel.LEFT),
            Band(BandType.PEAKING, true, 250f, -2f, 1f, BandChannel.RIGHT),
            Band(BandType.HIGH_SHELF, true, 6000f, 3f, 0.7f, BandChannel.SIDE),
            Band(BandType.LOW_PASS, true, 120f, 0f, 0.707f, BandChannel.MID),
            peak(3000f, 1.5f, 2f),
            Band(BandType.PEAKING, true, 1000f, 6f, 1f, BandChannel.LEFT),
        )
        val curve = ResponseCurve(bands, Tone(bassDb = 2f), 48000.0)
        assertTrue(curve.isRouted)
        assertEquals(setOf(BandChannel.LEFT, BandChannel.RIGHT, BandChannel.MID, BandChannel.SIDE), curve.routedChannels)
        val ref = reference("response_channels.csv")
        val expected = Regex("effective_preamp_db=(-?[0-9.]+)").find(ref.header)!!.groupValues[1].toFloat()
        assertEquals(expected, curve.effectivePreampDb(0f, autoPreamp = true), 1e-3f)
        val paths = listOf(BandChannel.LEFT, BandChannel.RIGHT, BandChannel.MID, BandChannel.SIDE)
        for (row in ref.rows) {
            val f = row[0]
            if (row[1] > -60.0) assertEquals(row[1], curve.db(f), 1e-3, "all, f=$f")
            for ((i, path) in paths.withIndex()) {
                if (row[3 + i] > -60.0) assertEquals(row[3 + i], curve.db(f, path), 1e-3, "$path, f=$f")
            }
        }
    }

    @Test
    fun autoPreampCountsOneSideOfEachPair() {
        fun peakOf(vararg b: Band) = ResponseCurve(b.toList(), Tone()).peakDb()
        val left = Band(BandType.PEAKING, true, 1000f, 6f, 1f, BandChannel.LEFT)
        assertEquals(6.0, peakOf(left, left.copy(channel = BandChannel.RIGHT)), 1e-6)
        assertEquals(12.0, peakOf(left, left.copy(channel = BandChannel.ALL)), 1e-6)
        assertEquals(9.0, peakOf(left, left.copy(channel = BandChannel.SIDE, gainDb = 3f)), 1e-6)
    }

    @Test
    fun flatAndDisabledSectionsContributeNothing() {
        val curve = ResponseCurve(
            listOf(peak(1000f, 0f, 1f), Band(BandType.PEAKING, false, 500f, 12f, 1f)),
            Tone(enabled = false, bassDb = 10f),
        )
        assertEquals(0.0, curve.peakDb())
        assertTrue(ResponseCurve.logFrequencies(50).all { abs(curve.db(it)) < 1e-12 })
    }

    @Test
    fun logFrequenciesIncludeBothEnds() {
        val f = ResponseCurve.logFrequencies(3, 20.0, 20000.0)
        assertEquals(20.0, f[0], 1e-9)
        assertEquals(632.4555, f[1], 1e-3)
        assertEquals(20000.0, f[2], 1e-9)
    }
}
