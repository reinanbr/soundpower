// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.model

import com.reinanbr.soundpower.protocol.Band
import com.reinanbr.soundpower.protocol.BandType
import com.reinanbr.soundpower.protocol.Capability
import com.reinanbr.soundpower.protocol.LimiterParams
import com.reinanbr.soundpower.protocol.ParamId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioSettingsTest {
    @Test
    fun fixedLayouts() {
        assertEquals(listOf(5, 10, 15, 31), BandLayout.FIXED.map { it.size })
        assertEquals(1000f, BandLayout.TEN.frequencies[5])
        assertEquals(20f, BandLayout.THIRTY_ONE.frequencies.first())
        assertEquals(20000f, BandLayout.THIRTY_ONE.frequencies.last())
        for (layout in BandLayout.FIXED) {
            assertEquals(layout.frequencies.sorted(), layout.frequencies)
        }
    }

    @Test
    fun customLayoutsAreLogSpacedAndClamped() {
        val l = BandLayout.custom(8, 40f, 16000f)
        assertEquals(8, l.size)
        assertEquals(40f, l.frequencies.first())
        assertEquals(16000f, l.frequencies.last())
        val ratios = l.frequencies.zipWithNext { a, b -> b / a }
        assertTrue(ratios.all { kotlin.math.abs(it - ratios[0]) < 0.02f }, "ratios $ratios")

        assertEquals(BandLayout.MIN_BANDS, BandLayout.custom(2, 20f, 20000f).size)
        assertEquals(BandLayout.MAX_BANDS, BandLayout.custom(64, 20f, 20000f).size)
        val narrow = BandLayout.custom(5, 1000f, 1100f)
        assertEquals(1000f * BandLayout.MIN_SPAN, narrow.endHz)
        assertEquals(1230f, BandLayout.roundFrequency(1234.5f))
        assertEquals(31.5f, BandLayout.roundFrequency(31.5f))
    }

    @Test
    fun qFollowsTheBandSpacing() {
        // Octave spacing: Q = sqrt(2) / (2 - 1). ISO 160, 200, 250 Hz are 1.25x apart, a bit
        // under a third of an octave: Q = sqrt(1.25) / 0.25.
        assertEquals(1.4142f, BandLayout.TEN.q(4, overlap = false), 1e-3f)
        assertEquals(4.4721f, BandLayout.THIRTY_ONE.q(10, overlap = false), 1e-3f)
        // Overlap doubles the bandwidth: two octaves give Q = 2 / 3.
        assertEquals(0.6667f, BandLayout.TEN.q(4, overlap = true), 1e-3f)
        // The ends use their single neighbour.
        assertEquals(1.4142f, BandLayout.TEN.q(0, overlap = false), 2e-2f)
    }

    @Test
    fun graphicGainsAreSnappedAndClamped() {
        var g = GraphicBands()
        g = g.withGain(3, 4.26f).withGain(9, 40f).withGain(0, -15.04f)
        assertEquals(4.3f, g.gain(3))
        assertEquals(15f, g.gain(9))
        assertEquals(-15f, g.gain(0))
        assertEquals(10, g.bands().size)
        assertTrue(g.bands().all { it.type == BandType.PEAKING && it.enabled })
        assertTrue(g.flat().isFlat)
    }

    @Test
    fun changingTheLayoutResamplesTheCurve() {
        val ten = GraphicBands(BandLayout.TEN, listOf(0f, 0f, 0f, 0f, 0f, 6f, 6f, 6f, 6f, 6f))
        val thirtyOne = ten.withLayout(BandLayout.THIRTY_ONE)
        assertEquals(31, thirtyOne.gainsDb.size)
        assertEquals(0f, thirtyOne.gain(0)) // 20 Hz, below the first band: held
        assertEquals(6f, thirtyOne.gain(30)) // 20 kHz, above the last band: held
        assertEquals(6f, thirtyOne.gain(BandLayout.THIRTY_ONE.frequencies.indexOf(1000f)))
        assertEquals(2f, thirtyOne.gain(BandLayout.THIRTY_ONE.frequencies.indexOf(630f)), 0.05f) // 1/3 of the way
        // Back to ten bands: the octave points survive the round trip.
        assertEquals(ten.gainsDb, thirtyOne.withLayout(BandLayout.TEN).gainsDb)
        assertEquals(ten, ten.withLayout(BandLayout.TEN))
    }

    @Test
    fun switchingModesKeepsBothBandSets() {
        val graphic = GraphicBands().withGain(2, 5f)
        var eq = EqProfile(graphic = graphic)
        assertNull(eq.parametric)

        eq = eq.withMode(EqMode.PARAMETRIC)
        assertEquals(10, eq.parametric!!.size)
        assertEquals(5f, eq.parametric[2].gainDb) // starts as a copy of the graphic bands
        assertEquals(graphic.bands(), eq.bands())

        val edited = eq.copy(parametric = eq.parametric.take(1) + ParametricBand(BandType.NOTCH, 50f, 0f, 8f))
        val back = edited.withMode(EqMode.GRAPHIC)
        assertEquals(graphic, back.graphic)
        assertEquals(graphic.bands(), back.bands())
        assertEquals(2, back.withMode(EqMode.PARAMETRIC).bands().size) // parametric edits survive
    }

    @Test
    fun effectSettingsCarryEverySection() {
        val s = AudioSettings(
            enabled = false,
            eq = EqProfile(preampDb = -2f, autoPreamp = false, tone = Tone(bassDb = 3f)),
            stereo = Stereo(balance = 0.5f, width = 1.25f, mono = true),
            limiter = Limiter(enabled = false, ceilingDb = -3f, releaseMs = 80f),
            compressor = Compressor(enabled = true, ratio = 4f),
        )
        val e = s.effect()
        assertTrue(e.bypass)
        assertFalse(e.master.autoPreamp)
        assertFalse(e.master.limiterEnabled)
        assertEquals(LimiterParams(ceilingDb = -3f, releaseMs = 80f), e.limiter)
        assertEquals(3f, e.tone.bassDb)
        assertTrue(e.stereo.mono)
        assertEquals(4f, e.compressor.ratio)
        assertEquals(e, EqSettings.fromStateFile(e.toStateFile()))
    }

    @Test
    fun messagesFollowTheEffectsCapabilities() {
        val e = AudioSettings().effect()
        assertEquals(listOf(ParamId.MASTER, ParamId.EQ_STATE, ParamId.LIMITER), e.messages(0).map { it.id })
        assertEquals(
            listOf(ParamId.MASTER, ParamId.EQ_STATE, ParamId.LIMITER, ParamId.TONE, ParamId.STEREO, ParamId.COMPRESSOR),
            e.messages().map { it.id },
        )
        assertEquals(
            listOf(ParamId.MASTER, ParamId.EQ_STATE, ParamId.LIMITER, ParamId.STEREO),
            e.messages(Capability.STEREO).map { it.id },
        )
    }

    @Test
    fun jsonRoundTripAndForwardCompatibility() {
        val s = AudioSettings(
            eq = EqProfile(mode = EqMode.PARAMETRIC, parametric = listOf(ParametricBand(BandType.HIGH_SHELF, 9000f, 2f, 0.7f))),
            stereo = Stereo(width = 1.5f),
        )
        assertEquals(s, AudioSettings.fromJson(s.toJson()))
        // Keys from a newer version are ignored; missing keys take their defaults.
        val newer = """{"enabled":false,"future":{"x":1},"eq":{"mode":"GRAPHIC","later":[1,2]}}"""
        assertEquals(AudioSettings(enabled = false), AudioSettings.fromJson(newer))
    }

    @Test
    fun legacySettingsMigrate() {
        val tenBand = EqSettings(
            preampDb = -3f,
            bands = GraphicBands().withGain(4, 2.5f).bands(),
            limiter = LimiterParams(ceilingDb = -2f),
        )
        val migrated = AudioSettings.fromLegacy(tenBand)
        assertEquals(EqMode.GRAPHIC, migrated.eq.mode)
        assertEquals(2.5f, migrated.eq.graphic.gain(4))
        assertEquals(-3f, migrated.eq.preampDb)
        assertEquals(-2f, migrated.limiter.ceilingDb)

        val other = EqSettings(bands = listOf(Band(BandType.LOW_SHELF, true, 70f, 4f, 0.7f)))
        val p = AudioSettings.fromLegacy(other)
        assertEquals(EqMode.PARAMETRIC, p.eq.mode)
        assertEquals(listOf(ParametricBand(BandType.LOW_SHELF, 70f, 4f, 0.7f)), p.eq.parametric)
    }

    @Test
    fun stateFileOfTheDefaultsIsStable() {
        val bytes = AudioSettings.DEFAULT.effect().toStateFile()
        assertContentEquals(bytes, EqSettings.fromStateFile(bytes).toStateFile())
    }
}
