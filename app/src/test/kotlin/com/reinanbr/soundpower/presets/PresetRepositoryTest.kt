// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.presets

import com.reinanbr.soundpower.data.PresetStore
import com.reinanbr.soundpower.data.SettingsStore
import com.reinanbr.soundpower.effect.EffectCodes
import com.reinanbr.soundpower.effect.EffectController
import com.reinanbr.soundpower.effect.EffectTransport
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.eq.EqRepository
import com.reinanbr.soundpower.model.AudioOutput
import com.reinanbr.soundpower.model.AudioSettings
import com.reinanbr.soundpower.model.AutoEq
import com.reinanbr.soundpower.model.AutoEqEntry
import com.reinanbr.soundpower.model.AutoEqIndex
import com.reinanbr.soundpower.protocol.BandType
import com.reinanbr.soundpower.model.BuiltInPresets
import com.reinanbr.soundpower.model.DynamicsCompressor
import com.reinanbr.soundpower.model.DynamicsLimiter
import com.reinanbr.soundpower.model.EqMode
import com.reinanbr.soundpower.model.EqSettings
import com.reinanbr.soundpower.model.OutputKey
import com.reinanbr.soundpower.model.OutputKind
import com.reinanbr.soundpower.model.ParametricBand
import com.reinanbr.soundpower.model.PresetKind
import com.reinanbr.soundpower.model.PresetLibrary
import com.reinanbr.soundpower.protocol.Capability
import com.reinanbr.soundpower.root.ModuleManager
import com.reinanbr.soundpower.root.RootShell
import com.reinanbr.soundpower.root.ShellResult
import com.reinanbr.soundpower.rootless.DynamicsEffect
import com.reinanbr.soundpower.rootless.RootlessEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class) // virtual-time helpers of kotlinx-coroutines-test
class PresetRepositoryTest {
    private class FakeEffect : EffectTransport {
        override fun setParameter(param: ByteArray, value: ByteArray) = EffectCodes.SUCCESS

        override fun getParameter(param: ByteArray, value: ByteArray): Int {
            ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).putShort(1).putShort(1).putInt(0x100).putInt(32)
                .putInt(3 or EqSettings.ALL_CAPABILITIES or Capability.LIMITER_SWITCH)
            return 16
        }

        override fun release() {}
    }

    private class FakeShell : RootShell {
        override suspend fun requestRoot() = true
        override suspend fun run(script: String, onLine: ((String) -> Unit)?) = ShellResult(0, listOf("written"))
    }

    private class NoDynamics : DynamicsEffect {
        override val hasControl = true
        override fun setEnabled(enabled: Boolean) {}
        override fun setInputGain(channel: Int, db: Float) {}
        override fun setBand(channel: Int, band: Int, cutoffHz: Float, gainDb: Float) {}
        override fun setCompressor(channel: Int, compressor: DynamicsCompressor) {}
        override fun setLimiter(channel: Int, limiter: DynamicsLimiter) {}
        override fun release() {}
    }

    private val dir: File = Files.createTempDirectory("sp-presets").toFile()
    private val speaker = AudioOutput(OutputKey(OutputKind.SPEAKER), null)
    private val buds = AudioOutput(OutputKey(OutputKind.BLUETOOTH, "AA:BB"), "Sony WH-1000XM4")
    private val car = AudioOutput(OutputKey(OutputKind.BLUETOOTH, "CC:DD"), "Car audio")
    private val output = MutableStateFlow(speaker)
    private val sony = AutoEqEntry("Sony WH-1000XM4", "oratory1990", "over-ear", true, listOf(ParametricBand(BandType.PEAKING, 100f, 3f, 1f)))
    private val index = AutoEqIndex(listOf(sony))

    private lateinit var eq: EqRepository

    private fun TestScope.repository(): PresetRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        eq = EqRepository(
            store = SettingsStore(File(dir, "audio.json"), backgroundScope),
            effect = EffectController({ FakeEffect() }, dispatcher),
            rootless = RootlessEngine({ _, _ -> NoDynamics() }, { 48000 }, dispatcher),
            modules = ModuleManager(FakeShell(), io = dispatcher),
            engine = MutableStateFlow(EngineMode.ROOT),
            isModuleActive = { false },
            staging = File(dir, "staging.bin"),
            scope = backgroundScope,
        )
        return PresetRepository(PresetStore(File(dir, "presets.json"), backgroundScope), eq, output, { index }, backgroundScope)
    }

    /*
     * DataStore does its file I/O on its own thread, so the tests wait for what it publishes
     * instead of draining the scheduler.
     */

    private suspend fun TestScope.started(repo: PresetRepository): PresetRepository {
        repo.start()
        eq.settings.first { it != null }
        repo.library.first { it != null }
        repo.autoEq.first { it != null }
        runCurrent()
        return repo
    }

    private suspend fun PresetRepository.lib(until: (PresetLibrary) -> Boolean = { true }): PresetLibrary =
        library.first { it != null && until(it) }!!

    private suspend fun settings(until: (AudioSettings) -> Boolean): AudioSettings = eq.settings.first { it != null && until(it) }!!

    private fun EqRepository.setGain(band: Int, db: Float) =
        update { it.copy(eq = it.eq.copy(mode = EqMode.GRAPHIC, graphic = it.eq.graphic.withGain(band, db))) }

    @Test
    fun savedPresetsFollowEditsUnlessLocked() = runTest {
        val repo = started(repository())
        eq.setGain(0, 2f)
        settings { it.eq.graphic.gainsDb[0] == 2f }
        val p = assertNotNull(repo.save("Mine"))
        assertEquals(p.id, settings { it.presetId != null }.presetId)
        assertEquals(PresetKind.USER, p.kind)

        eq.setGain(0, 5f)
        advanceTimeBy(PresetRepository.AUTOSAVE_DELAY_MS + 1)
        assertEquals(5f, repo.lib { it.preset(p.id)?.eq?.graphic?.gainsDb?.get(0) == 5f }.preset(p.id)!!.eq.graphic.gainsDb[0])
        assertFalse(repo.current.first { it != null }!!.edited, "an unlocked preset is never edited")

        repo.setLocked(p.id, true)
        repo.lib { it.preset(p.id)?.locked == true }
        eq.setGain(0, -4f)
        advanceTimeBy(PresetRepository.AUTOSAVE_DELAY_MS + 1)
        runCurrent()
        assertTrue(repo.current.first { it?.edited == true }!!.edited)
        assertEquals(5f, repo.lib().preset(p.id)!!.eq.graphic.gainsDb[0], "locked presets keep their EQ")
    }

    @Test
    fun switchingKeepsEditsMadeJustBefore() = runTest {
        val repo = started(repository())
        val p = assertNotNull(repo.save("Mine"))
        settings { it.presetId == p.id }
        eq.setGain(1, 3f)
        settings { it.eq.graphic.gainsDb[1] == 3f }
        // Before the autosave delay runs out.
        repo.apply(BuiltInPresets.ALL.first { it.id == "builtin:rock" })
        assertEquals(3f, repo.lib { it.preset(p.id)?.eq?.graphic?.gainsDb?.get(1) == 3f }.preset(p.id)!!.eq.graphic.gainsDb[1])
        assertEquals("builtin:rock", settings { it.presetId == "builtin:rock" }.presetId)
    }

    @Test
    fun builtInPresetsAreMarkedEditedWhenChanged() = runTest {
        val repo = started(repository())
        val rock = BuiltInPresets.ALL.first { it.id == "builtin:rock" }
        repo.apply(rock)
        assertFalse(repo.current.first { it?.preset?.id == rock.id }!!.edited)
        eq.setGain(0, rock.eq.graphic.gainsDb[0] + 1f)
        assertTrue(repo.current.first { it?.edited == true }!!.edited)
    }

    @Test
    fun outputsSwitchPresetsAndNamedDevicesWin() = runTest {
        val repo = started(repository())
        val mine = assertNotNull(repo.save("Mine"))
        repo.assign(mine.id, mapOf(OutputKey(OutputKind.BLUETOOTH) to null))
        repo.assign("builtin:rock", mapOf(buds.key to buds.name))
        repo.lib { it.assignments.size == 2 }
        repo.apply(BuiltInPresets.ALL.first { it.id == "builtin:flat" })

        output.value = car
        assertEquals(mine.id, settings { it.presetId == mine.id }.presetId, "a generic Bluetooth preset")
        output.value = buds
        assertEquals("builtin:rock", settings { it.presetId == "builtin:rock" }.presetId, "the device's own preset")
        assertEquals(buds.key, repo.lib { it.lastOutput == buds.key }.lastOutput)

        // Back on an output with no preset: the EQ stays.
        output.value = speaker
        repo.lib { it.lastOutput == speaker.key }
        assertEquals("builtin:rock", eq.settings.value?.presetId)
    }

    @Test
    fun switchingByOutputCanBeTurnedOff() = runTest {
        val repo = started(repository())
        repo.assign("builtin:rock", mapOf(buds.key to buds.name))
        repo.setSwitchByOutput(false)
        repo.lib { !it.switchByOutput && it.assignments.isNotEmpty() }
        output.value = buds
        repo.lib { it.lastOutput == buds.key }
        runCurrent()
        assertNull(eq.settings.value?.presetId)
    }

    @Test
    fun savingOverAPresetKeepsItsOutputs() = runTest {
        val repo = started(repository())
        val p = assertNotNull(repo.save("Mine"))
        repo.assign(p.id, mapOf(OutputKey(OutputKind.WIRED) to null))
        repo.lib { it.assignments.isNotEmpty() }
        val again = assertNotNull(repo.save("mine", mapOf(OutputKey(OutputKind.USB) to null)))
        assertEquals(p.id, again.id, "same name, same preset")
        val lib = repo.lib { it.assignments.size == 2 }
        assertEquals(setOf(OutputKind.WIRED, OutputKind.USB), lib.outputsOf(p.id).map { it.output.kind }.toSet())
        assertEquals(1, lib.presets.size)
    }

    @Test
    fun deletingCanBeUndone() = runTest {
        val repo = started(repository())
        val p = assertNotNull(repo.save("Mine", mapOf(OutputKey(OutputKind.WIRED) to null)))
        settings { it.presetId == p.id }
        val gone = assertNotNull(repo.delete(p.id))
        assertTrue(repo.lib().presets.isEmpty() && repo.lib().assignments.isEmpty())
        assertNull(settings { it.presetId == null }.presetId)

        repo.undelete(gone)
        val lib = repo.lib { it.presets.isNotEmpty() }
        assertEquals(p.id, lib.outputsOf(p.id).single().presetId)
        assertEquals(p.id, settings { it.presetId == p.id }.presetId)
    }

    @Test
    fun importsAutoEqFilesAndPresetFiles() = runTest {
        val repo = started(repository())
        val txt = "Preamp: -6.2 dB\nFilter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70\nFilter 2: ON PK Fc 3000 Hz Gain -3.0 dB Q 2.00\n"
        val imported = repo.import(txt, "Sennheiser HD 600 ParametricEQ.txt").single()
        assertEquals("Sennheiser HD 600", imported.name)
        assertEquals(EqMode.PARAMETRIC, imported.mode)
        assertEquals(2, imported.eq.parametricBands.size)

        val file = repo.export(listOf(imported, BuiltInPresets.ALL.first()))
        val again = repo.import(file, "x.json")
        assertEquals(listOf("Sennheiser HD 600 2", BuiltInPresets.ALL.first().name), again.map { it.name })
        assertTrue(again.all { it.kind == PresetKind.USER })
        assertEquals(3, repo.lib { it.presets.size == 3 }.presets.size)

        runCatching { repo.import("hello", "notes.txt") }.also { assertTrue(it.exceptionOrNull() is IllegalArgumentException) }
    }

    @Test
    fun backupsRestoreEverything() = runTest {
        val repo = started(repository())
        eq.setGain(2, 4f)
        val p = assertNotNull(repo.save("Mine", mapOf(OutputKey(OutputKind.WIRED) to null)))
        settings { it.presetId == p.id && it.eq.graphic.gainsDb[2] == 4f }
        repo.lib { it.presets.isNotEmpty() }
        val backup = assertNotNull(repo.backup())

        repo.delete(p.id)
        eq.setGain(2, 0f)
        repo.lib { it.presets.isEmpty() }
        settings { it.eq.graphic.gainsDb[2] == 0f }

        repo.restore(backup)
        val lib = repo.lib { it.presets.isNotEmpty() }
        assertEquals(p.id, lib.outputsOf(p.id).single().presetId)
        assertEquals(4f, settings { it.eq.graphic.gainsDb[2] == 4f }.eq.graphic.gainsDb[2])
        assertEquals(p.id, eq.settings.value?.presetId)
    }

    @Test
    fun headphonesGetAutoEqSuggestions() = runTest {
        val repo = started(repository())
        output.value = buds
        val s = repo.suggestion.first { it != null }!!
        assertEquals(listOf(sony.id), s.entries.map { it.id })

        // Applying one of them answers it.
        repo.apply(sony.preset())
        repo.suggestion.first { it == null }
        repo.apply(BuiltInPresets.ALL.first())
        repo.suggestion.first { it != null }

        repo.dismissSuggestion(buds.key)
        assertNull(repo.suggestion.first { it == null })

        // Not where the user assigned a preset to the device.
        val other = AudioOutput(OutputKey(OutputKind.BLUETOOTH, "EE:FF"), sony.name)
        repo.assign("builtin:rock", mapOf(other.key to other.name))
        repo.lib { it.assignments.isNotEmpty() }
        output.value = other
        settings { it.presetId == "builtin:rock" }
        runCurrent()
        assertNull(repo.suggestion.value)
    }

    @Test
    fun theBundledDatabaseReadsWhole() {
        val file = File("src/main/assets/autoeq.tsv")
        val lines = file.readLines()
        val data = lines.count { it.isNotBlank() && !it.startsWith("#") }
        val entries = AutoEq.readDatabase(lines.asSequence())
        assertEquals(data, entries.size, "every line is an entry")
        assertEquals(8850, entries.size)
        assertEquals(6032, entries.count { it.recommended })
        assertEquals(entries.size, entries.map { it.id }.toSet().size, "ids are unique")
        assertTrue(entries.all { e -> e.bands.isNotEmpty() && e.bands.all { it.gainDb in -15f..15f && it.freqHz in 10f..24000f && it.q > 0f } })
        val index = AutoEqIndex(entries)
        assertTrue(index.match("WH-1000XM4").isNotEmpty())
        assertEquals("Sennheiser HD 600", index.search("hd 600").first().name)
    }
}
