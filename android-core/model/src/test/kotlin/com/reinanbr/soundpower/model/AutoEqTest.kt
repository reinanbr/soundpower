// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.model

import com.reinanbr.soundpower.protocol.BandType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoEqTest {
    /** A ParametricEQ.txt as AutoEQ writes it, plus an Equalizer APO line with no Q and one off. */
    private val parametric = """
        Preamp: -6.3 dB
        Filter 1: ON LSC Fc 105 Hz Gain 5.6 dB Q 0.70
        Filter 2: ON PK Fc 170 Hz Gain -3.9 dB Q 0.18
        Filter 3: ON PK Fc 5593 Hz Gain -20.0 dB Q 1.81
        Filter 4: ON HSC Fc 10000 Hz Gain -4.3 dB Q 0.70
        Filter 5: OFF PK Fc 40 Hz Gain 2 dB Q 2.22
        Filter: ON HS Fc 12000 Hz Gain 1.5 dB
        Filter 7: ON XYZ Fc 100 Hz Gain 1 dB Q 1
    """.trimIndent()

    @Test
    fun parametricFilesBecomeBandsWithinTheAppsRanges() {
        val bands = AutoEq.parseParametric(parametric)
        assertEquals(6, bands.size, "the unknown type is skipped")
        assertEquals(ParametricBand(BandType.LOW_SHELF, 105f, 5.6f, 0.7f), bands[0])
        assertEquals(ParametricBand(BandType.PEAKING, 170f, -3.9f, 0.18f), bands[1])
        assertEquals(-15f, bands[2].gainDb, "clamped to the app's ±15 dB")
        assertEquals(BandType.HIGH_SHELF, bands[3].type)
        assertFalse(bands[4].enabled)
        assertEquals(0.7071f, bands[5].q)
        assertEquals(EqMode.PARAMETRIC, AutoEq.parse(parametric)?.mode)
    }

    @Test
    fun graphicFilesAreResampledOntoThe31BandLayout() {
        val text = "GraphicEQ: 20 -6; 1000 0; 20000 6"
        val g = assertNotNull(AutoEq.parseGraphic(text))
        assertEquals(BandLayout.THIRTY_ONE, g.layout)
        assertEquals(-6f, g.gainsDb.first())
        assertEquals(0f, g.gain(BandLayout.THIRTY_ONE.frequencies.indexOf(1000f)))
        assertEquals(6f, g.gainsDb.last())
        assertEquals(EqMode.GRAPHIC, AutoEq.parse(text)?.mode)

        assertNull(AutoEq.parseGraphic("GraphicEQ:"))
        assertNull(AutoEq.parse("just some text"))
    }

    private fun entry(name: String, source: String = "oratory1990", recommended: Boolean = true) = AutoEqEntry(
        name, source, "over-ear", recommended,
        listOf(ParametricBand(BandType.LOW_SHELF, 105f, 5.6f, 0.7f), ParametricBand(BandType.PEAKING, 3388f, -4f, 2.96f)),
    )

    @Test
    fun databaseLinesRoundTrip() {
        val e = entry("ZMF Verite")
        val line = AutoEq.writeEntry(e)
        assertEquals("ZMF Verite\toratory1990\tover-ear\t1\tL105:5.6:0.7 P3388:-4:2.96", line)
        assertEquals(e, AutoEq.readEntry(line))
        assertEquals(listOf(e), AutoEq.readDatabase(sequenceOf("# comment", "", line, "broken\tline")))

        val preset = e.preset()
        assertEquals("autoeq:oratory1990/over-ear/ZMF Verite", preset.id)
        assertEquals(PresetKind.AUTOEQ, preset.kind)
        assertEquals("oratory1990 · over-ear", preset.source)
        assertEquals(e.bands, preset.eq.parametric)
        assertTrue(preset.eq.autoPreamp)
        assertTrue(preset.readOnly)
    }

    private val index = AutoEqIndex(
        listOf(
            entry("Sony WH-1000XM4 (ANC off)"),
            entry("Sony WH-1000XM4", source = "Rtings", recommended = false),
            entry("Sony WH-1000XM4"),
            entry("Samsung Galaxy Buds2 Pro"),
            entry("Apple AirPods Pro 2"),
            entry("Apple AirPods Pro"),
            entry("JBL Tune 760NC"),
            entry("Koss KSC75"),
        ),
    )

    @Test
    fun searchRanksNamesWhereTheQueryStartsAWord() {
        val hd = AutoEqIndex(listOf(entry("Denon AH-D600"), entry("Sennheiser HD 600"), entry("Sennheiser HD600 Custom", recommended = false)))
        assertEquals(listOf("Sennheiser HD 600", "Sennheiser HD600 Custom", "Denon AH-D600"), hd.search("hd 600").map { it.name })
        assertEquals(listOf("Sennheiser HD 600", "Sennheiser HD600 Custom", "Denon AH-D600"), hd.search("HD600").map { it.name })
        assertEquals(listOf("Denon AH-D600"), hd.search("denon").map { it.name })
    }

    @Test
    fun searchMatchesEveryWordAndRanksRecommendedFirst() {
        val hits = index.search("wh 1000xm4")
        assertEquals(listOf("oratory1990", "oratory1990", "Rtings"), hits.map { it.source })
        assertEquals("Sony WH-1000XM4", hits.first().name)
        assertEquals(listOf("Apple AirPods Pro", "Apple AirPods Pro 2"), index.search("AIRPODS pro").map { it.name })
        assertTrue(index.search("  ").isEmpty())
        assertTrue(index.search("sennheiser").isEmpty())
        assertEquals(1, index.search("sony", limit = 1).size)
        assertEquals("Rtings", index.find("autoeq:Rtings/over-ear/Sony WH-1000XM4")?.source)
        assertNull(index.find("autoeq:nobody/over-ear/Sony WH-1000XM4"))
    }

    @Test
    fun deviceNamesFindTheirHeadphones() {
        assertEquals("Sony WH-1000XM4", index.match("WH-1000XM4").first().name)
        assertEquals("Samsung Galaxy Buds2 Pro", index.match("Galaxy Buds2 Pro (1A2B)").first().name)
        assertEquals("Samsung Galaxy Buds2 Pro", index.match("LE-Galaxy Buds2 Pro").first().name)
        assertEquals("Apple AirPods Pro", index.match("AirPods Pro").first().name)
        assertEquals("JBL Tune 760NC", index.match("JBL TUNE760NC").first().name)
        assertTrue(index.match("BT").isEmpty(), "too short to mean anything")
        assertTrue(index.match("Car stereo").isEmpty())
    }
}
