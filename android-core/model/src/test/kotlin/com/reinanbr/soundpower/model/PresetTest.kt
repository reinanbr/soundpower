// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.model

import com.reinanbr.soundpower.protocol.BandChannel
import com.reinanbr.soundpower.protocol.BandType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresetTest {
    private val rock = BuiltInPresets.ALL.first { it.id == "builtin:rock" }

    private val parametricBands = listOf(
        ParametricBand(BandType.LOW_SHELF, 105f, 5.6f, 0.7f),
        ParametricBand(BandType.PEAKING, 3388f, 4f, 2.96f, channel = BandChannel.LEFT),
    )

    @Test
    fun builtInPresetsAreGraphicCurvesWithinRange() {
        assertEquals(BuiltInPresets.ALL.size, BuiltInPresets.ALL.map { it.id }.toSet().size)
        assertEquals(BuiltInPresets.ALL.size, BuiltInPresets.ALL.map { it.name }.toSet().size)
        for (p in BuiltInPresets.ALL) {
            assertEquals(PresetKind.BUILT_IN, p.kind)
            assertTrue(p.readOnly)
            assertEquals(EqMode.GRAPHIC, p.mode)
            assertEquals(10, p.eq.graphic.gainsDb.size, p.name)
            assertTrue(p.eq.graphic.gainsDb.all { it in Ranges.BAND_GAIN_DB }, p.name)
        }
    }

    @Test
    fun aGraphicPresetKeepsTheBandConfigurationAndTheParametricBands() {
        val current = EqProfile(
            mode = EqMode.PARAMETRIC,
            graphic = GraphicBands(BandLayout.THIRTY_ONE, overlap = true),
            parametric = parametricBands,
            preampDb = 3f,
            autoPreamp = false,
            tone = Tone(bassDb = 4f),
        )
        val applied = rock.applyTo(current)
        assertEquals(EqMode.GRAPHIC, applied.mode)
        assertEquals(BandLayout.THIRTY_ONE, applied.graphic.layout)
        assertTrue(applied.graphic.overlap)
        assertEquals(parametricBands, applied.parametric)
        // Resampled: the 31-band curve passes through the preset's gains at its own frequencies.
        assertEquals(4.5f, applied.graphic.gain(BandLayout.THIRTY_ONE.frequencies.indexOf(31.5f)))
        assertEquals(-1.5f, applied.graphic.gain(BandLayout.THIRTY_ONE.frequencies.indexOf(500f)))
        assertEquals(0f, applied.preampDb)
        assertTrue(applied.autoPreamp)
        assertEquals(Tone(), applied.tone)

        assertTrue(rock.matches(applied))
        assertFalse(rock.matches(applied.copy(graphic = applied.graphic.withGain(3, 6f))))
        assertFalse(rock.matches(applied.copy(tone = Tone(trebleDb = 2f))))
        // The band configuration and the other mode are not part of the preset.
        assertTrue(rock.matches(applied.copy(parametric = null)))
    }

    @Test
    fun aParametricPresetKeepsTheGraphicBands() {
        val preset = Preset.user("Mine", EqProfile(mode = EqMode.PARAMETRIC, parametric = parametricBands, preampDb = -2f))
        val current = EqProfile(graphic = GraphicBands(BandLayout.FIVE, listOf(1f, 2f, 3f, 4f, 5f)))
        val applied = preset.applyTo(current)
        assertEquals(EqMode.PARAMETRIC, applied.mode)
        assertEquals(parametricBands, applied.parametric)
        assertEquals(current.graphic, applied.graphic)
        assertEquals(-2f, applied.preampDb)
        assertTrue(preset.matches(applied))
        assertFalse(preset.matches(applied.withMode(EqMode.GRAPHIC)))
    }

    @Test
    fun userPresetsAreWritableUntilLocked() {
        val p = Preset.user("  Mine ", EqProfile())
        assertEquals("Mine", p.name)
        assertTrue(p.id.startsWith(Preset.USER_PREFIX))
        assertNotEquals(p.id, Preset.user("Mine", EqProfile()).id)
        assertFalse(p.readOnly)
        assertTrue(p.copy(locked = true).readOnly)
    }

    @Test
    fun presetFilesImportAsNewUnlockedUserPresets() {
        val out = listOf(rock, Preset.user("Mine", EqProfile(mode = EqMode.PARAMETRIC, parametric = parametricBands)).copy(locked = true))
        val back = PresetFile.decode(PresetFile.encode(out))
        assertEquals(out.map { it.name }, back.map { it.name })
        assertEquals(out.map { it.eq }, back.map { it.eq })
        assertTrue(back.all { it.kind == PresetKind.USER && !it.locked && it.id.startsWith(Preset.USER_PREFIX) })
        assertTrue(back.none { b -> out.any { it.id == b.id } })

        // Values from elsewhere are brought into range.
        val wild = PresetFile.encode(listOf(Preset.user("Loud", EqProfile(graphic = GraphicBands(BandLayout.FIVE, listOf(40f, 0f, 0f, 0f, -40f)), preampDb = 30f))))
        val tamed = PresetFile.decode(wild).single().eq
        assertEquals(listOf(15f, 0f, 0f, 0f, -15f), tamed.graphic.gainsDb)
        assertEquals(15f, tamed.preampDb)

        assertFailsWith<IllegalArgumentException> { PresetFile.decode("{}") }
        assertFailsWith<IllegalArgumentException> { PresetFile.decode("""{"format":"other","presets":[]}""") }
        assertFailsWith<IllegalArgumentException> { PresetFile.decode("""{"format":"soundpower-presets","version":99,"presets":[]}""") }
        assertFailsWith<IllegalArgumentException> { PresetFile.decode("not json") }
    }

    @Test
    fun aDevicesOwnAssignmentWinsOverItsKinds() {
        val buds = OutputKey(OutputKind.BLUETOOTH, "AA:BB")
        val lib = PresetLibrary()
            .assign("a", mapOf(OutputKey(OutputKind.BLUETOOTH) to null, OutputKey(OutputKind.SPEAKER) to null))
            .assign("b", mapOf(buds to "Buds"))
        assertEquals("b", lib.assignmentFor(buds)?.presetId)
        assertEquals("Buds", lib.assignmentFor(buds)?.name)
        assertEquals("a", lib.assignmentFor(OutputKey(OutputKind.BLUETOOTH, "CC:DD"))?.presetId)
        assertEquals("a", lib.assignmentFor(OutputKey(OutputKind.SPEAKER))?.presetId)
        assertNull(lib.assignmentFor(OutputKey(OutputKind.WIRED)))

        // Reassigning replaces the preset's outputs, and takes the output from its previous preset.
        val moved = lib.assign("b", mapOf(OutputKey(OutputKind.SPEAKER) to null))
        assertEquals("b", moved.assignmentFor(OutputKey(OutputKind.SPEAKER))?.presetId)
        assertEquals("a", moved.assignmentFor(buds)?.presetId)
        assertEquals(listOf(OutputKey(OutputKind.BLUETOOTH)), moved.outputsOf("a").map { it.output })
    }

    @Test
    fun removingAPresetDropsItsAssignmentsAndNamesStayUnique() {
        val mine = Preset.user("Mine", EqProfile())
        var lib = PresetLibrary().put(mine).assign(mine.id, mapOf(OutputKey(OutputKind.WIRED) to null))
        assertEquals("Mine 2", lib.uniqueName("Mine"))
        assertEquals("mine 2", lib.uniqueName("mine"))
        assertEquals("Other", lib.uniqueName("Other"))
        lib = lib.withImported(listOf(mine.copy(id = Preset.newId()), mine.copy(id = Preset.newId())))
        assertEquals(listOf("Mine", "Mine 2", "Mine 3"), lib.presets.map { it.name })

        val renamed = lib.put(mine.copy(name = "Renamed"))
        assertEquals("Renamed", renamed.preset(mine.id)?.name)
        assertEquals(3, renamed.presets.size)

        val removed = lib.remove(mine.id)
        assertNull(removed.preset(mine.id))
        assertTrue(removed.assignments.isEmpty())
    }

    @Test
    fun settingsBackupRoundTrip() {
        val mine = Preset.user("Mine", EqProfile(preampDb = -3f))
        val backup = SettingsBackup(
            settings = AudioSettings(presetId = mine.id, stereo = Stereo(balance = 0.2f)),
            library = PresetLibrary(switchByOutput = false).put(mine).assign(mine.id, mapOf(OutputKey(OutputKind.USB, "DAC") to "DAC")),
        )
        assertEquals(backup, SettingsBackup.decode(backup.encode()))
        assertFailsWith<IllegalArgumentException> { SettingsBackup.decode(PresetFile.encode(listOf(mine))) }
        assertFailsWith<IllegalArgumentException> { SettingsBackup.decode("""{"format":"soundpower-settings","version":2,"settings":{},"library":{}}""") }
        // Settings from before presets existed have none.
        assertNull(AudioSettings.fromJson("""{"enabled":true}""").presetId)
    }
}
