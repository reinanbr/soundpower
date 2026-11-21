// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.data

import com.reinanbr.soundpower.model.EqSettings
import com.reinanbr.soundpower.model.GraphicEq
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SettingsStoreTest {
    private val dir: File = Files.createTempDirectory("sp-store").toFile()
    private val file = File(dir, "eq.bin")

    /** One store at a time per file: DataStore refuses two live instances. */
    private suspend fun TestScope.withStore(block: suspend (SettingsStore) -> Unit) {
        val job = Job()
        block(SettingsStore(file, CoroutineScope(StandardTestDispatcher(testScheduler) + job)))
        job.cancelAndJoin()
    }

    @Test
    fun startsWithTheDefaultsAndPersistsInTheStateFileFormat() = runTest {
        val custom = EqSettings(preampDb = -3f, bands = GraphicEq.withGain(GraphicEq.flatBands(), 0, 6f))
        withStore { store ->
            assertEquals(EqSettings.DEFAULT, store.settings.first())
            store.save(custom)
        }
        assertContentEquals(custom.toStateFile(), file.readBytes())
        withStore { store -> assertEquals(custom, store.settings.first()) }
    }

    @Test
    fun aCorruptFileFallsBackToTheDefaults() = runTest {
        file.writeBytes("not a state file".toByteArray())
        withStore { store -> assertEquals(EqSettings.DEFAULT, store.settings.first()) }
        assertContentEquals(EqSettings.DEFAULT.toStateFile(), file.readBytes())
    }
}
