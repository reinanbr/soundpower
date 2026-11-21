// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.data

import com.reinanbr.soundpower.model.AudioSettings
import com.reinanbr.soundpower.model.EqMode
import com.reinanbr.soundpower.model.EqProfile
import com.reinanbr.soundpower.model.EqSettings
import com.reinanbr.soundpower.model.GraphicBands
import com.reinanbr.soundpower.model.Stereo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SettingsStoreTest {
    private val dir: File = Files.createTempDirectory("sp-store").toFile()
    private val file = File(dir, "audio.json")
    private val legacy = File(dir, "eq_settings.bin")

    /** One store at a time per file: DataStore refuses two live instances. */
    private suspend fun TestScope.withStore(block: suspend (SettingsStore) -> Unit) {
        val job = Job()
        block(SettingsStore(file, CoroutineScope(StandardTestDispatcher(testScheduler) + job), legacy))
        job.cancelAndJoin()
    }

    @Test
    fun startsWithTheDefaultsAndPersistsAsJson() = runTest {
        val custom = AudioSettings(
            eq = EqProfile(graphic = GraphicBands().withGain(0, 6f), preampDb = -3f).withMode(EqMode.PARAMETRIC),
            stereo = Stereo(mono = true),
        )
        withStore { store ->
            assertEquals(AudioSettings.DEFAULT, store.settings.first())
            store.save(custom)
        }
        assertEquals(custom.toJson(), file.readText())
        withStore { store -> assertEquals(custom, store.settings.first()) }
    }

    @Test
    fun aCorruptFileFallsBackToTheDefaults() = runTest {
        file.writeText("{ not json")
        withStore { store -> assertEquals(AudioSettings.DEFAULT, store.settings.first()) }
        assertEquals(AudioSettings.DEFAULT.toJson(), file.readText())
    }

    @Test
    fun importsTheFirstVersionsFileOnce() = runTest {
        val old = EqSettings(preampDb = -4f, bands = GraphicBands().withGain(7, 3.5f).bands())
        legacy.writeBytes(old.toStateFile())
        withStore { store ->
            val s = store.settings.first()
            assertEquals(3.5f, s.eq.graphic.gain(7))
            assertEquals(-4f, s.eq.preampDb)
        }
        assertFalse(legacy.exists())
        assertEquals(3.5f, AudioSettings.fromJson(file.readText()).eq.graphic.gain(7))
    }
}
