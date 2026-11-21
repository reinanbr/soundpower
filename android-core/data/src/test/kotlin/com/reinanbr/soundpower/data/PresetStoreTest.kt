// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.data

import com.reinanbr.soundpower.model.EqProfile
import com.reinanbr.soundpower.model.OutputKey
import com.reinanbr.soundpower.model.OutputKind
import com.reinanbr.soundpower.model.Preset
import com.reinanbr.soundpower.model.PresetLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PresetStoreTest {
    private val file = File(Files.createTempDirectory("sp-presets").toFile(), "presets.json")

    private suspend fun TestScope.withStore(block: suspend (PresetStore) -> Unit) {
        val job = Job()
        block(PresetStore(file, CoroutineScope(StandardTestDispatcher(testScheduler) + job)))
        job.cancelAndJoin()
    }

    @Test
    fun keepsPresetsAndAssignmentsAcrossInstances() = runTest {
        val mine = Preset.user("Mine", EqProfile(preampDb = -2f))
        withStore { store ->
            assertEquals(PresetLibrary.DEFAULT, store.library.first())
            store.update { it.put(mine).assign(mine.id, mapOf(OutputKey(OutputKind.WIRED) to null)) }
        }
        withStore { store ->
            val lib = store.library.first()
            assertEquals(listOf(mine), lib.presets)
            assertEquals(mine.id, lib.assignmentFor(OutputKey(OutputKind.WIRED))?.presetId)
        }
    }

    @Test
    fun concurrentUpdatesAreAllKept() = runTest {
        withStore { store ->
            (1..20).map { n -> async { store.update { it.put(Preset.user("P$n", EqProfile())) } } }.awaitAll()
            assertEquals(20, store.library.first().presets.size)
        }
    }

    @Test
    fun aCorruptFileStartsEmpty() = runTest {
        file.writeText("[1, 2")
        withStore { store -> assertEquals(PresetLibrary.DEFAULT, store.library.first()) }
    }
}
