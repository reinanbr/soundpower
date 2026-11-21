// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.eq

import com.reinanbr.soundpower.data.SettingsStore
import com.reinanbr.soundpower.effect.EffectCodes
import com.reinanbr.soundpower.effect.EffectController
import com.reinanbr.soundpower.effect.EffectTransport
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.model.AudioSettings
import com.reinanbr.soundpower.model.DynamicsCompressor
import com.reinanbr.soundpower.model.DynamicsLimiter
import com.reinanbr.soundpower.model.DynamicsPlan
import com.reinanbr.soundpower.model.EqSettings
import com.reinanbr.soundpower.protocol.Capability
import com.reinanbr.soundpower.protocol.EqState
import com.reinanbr.soundpower.protocol.ParamId
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class) // virtual-time helpers of kotlinx-coroutines-test
class EqRepositoryTest {
    private companion object {
        val ALL_IDS = listOf(ParamId.MASTER, ParamId.EQ_STATE, ParamId.LIMITER, ParamId.TONE, ParamId.STEREO, ParamId.COMPRESSOR)
    }

    private class FakeEffect : EffectTransport {
        val sets = ArrayList<Pair<Int, ByteArray>>()
        var failWith: Int? = null

        override fun setParameter(param: ByteArray, value: ByteArray): Int {
            failWith?.let { return it }
            sets += ByteBuffer.wrap(param).order(ByteOrder.LITTLE_ENDIAN).getInt() to value.copyOf()
            return EffectCodes.SUCCESS
        }

        override fun getParameter(param: ByteArray, value: ByteArray): Int {
            // PROTOCOL_INFO: 1.1, library 0.1.0, 32 bands, every 1.1 capability.
            ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).putShort(1).putShort(1).putInt(0x100).putInt(32)
                .putInt(3 or EqSettings.ALL_CAPABILITIES or Capability.LIMITER_SWITCH)
            return 16
        }

        override fun release() {}
    }

    /** Answers every state write with [result] and counts the writes. */
    private class FakeShell(var result: ShellResult = ShellResult(0, listOf("written"))) : RootShell {
        val runs = MutableStateFlow(0)

        override suspend fun requestRoot() = result.code != ShellResult.NO_ROOT

        override suspend fun run(script: String, onLine: ((String) -> Unit)?): ShellResult {
            runs.value++
            return result
        }
    }

    /** Android's effect for the mode without root: records the input gains it is sent. */
    private class FakeDynamics(val plan: DynamicsPlan) : DynamicsEffect {
        val inputGains = ArrayList<Float>()
        override val hasControl = true
        override fun setEnabled(enabled: Boolean) {}
        override fun setInputGain(channel: Int, db: Float) {
            inputGains += db
        }
        override fun setBand(channel: Int, band: Int, cutoffHz: Float, gainDb: Float) {}
        override fun setCompressor(channel: Int, compressor: DynamicsCompressor) {}
        override fun setLimiter(channel: Int, limiter: DynamicsLimiter) {}
        override fun release() {}
    }

    private val dynamics = ArrayList<FakeDynamics>()
    private val engineMode = MutableStateFlow<EngineMode?>(EngineMode.ROOT)
    private lateinit var rootless: RootlessEngine

    private val dir: File = Files.createTempDirectory("sp-eq").toFile()
    private val effect = FakeEffect()
    private val shell = FakeShell()
    private val staging = File(dir, "staging.bin")
    private val storeFile = File(dir, "audio.json")

    private fun TestScope.repository(): EqRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        rootless = RootlessEngine({ _, plan -> FakeDynamics(plan).also { dynamics += it } }, { 48000 }, dispatcher)
        return EqRepository(
            store = SettingsStore(storeFile, backgroundScope),
            effect = EffectController({ effect }, dispatcher),
            rootless = rootless,
            modules = ModuleManager(shell, io = dispatcher),
            engine = engineMode,
            isModuleActive = { false },
            staging = staging,
            scope = backgroundScope,
        )
    }

    /*
     * The repository runs in backgroundScope, which advanceUntilIdle() does not drain, and
     * DataStore does its file I/O on its own thread. So the tests wait for observable events
     * and use runCurrent(), which runs background tasks too.
     */

    /** Starts [repo] and waits until the saved settings are loaded, applied and written out. */
    private suspend fun TestScope.started(repo: EqRepository): EqRepository {
        repo.start()
        repo.settings.first { it != null }
        shell.runs.first { it >= 1 }
        runCurrent()
        return repo
    }

    private fun AudioSettings.withGain(band: Int, db: Float) = copy(eq = eq.copy(graphic = eq.graphic.withGain(band, db)))

    private fun eqSent(): EqState = EqState.decode(effect.sets.last { it.first == ParamId.EQ_STATE }.second)

    @Test
    fun startsByApplyingAndPersistingTheSavedSettings() = runTest {
        val repo = started(repository())
        repo.start()
        runCurrent()
        assertEquals(AudioSettings.DEFAULT, repo.settings.value)
        assertEquals(ALL_IDS, effect.sets.map { it.first })
        // state.bin is written once at start, in case it is missing or stale.
        assertEquals(1, shell.runs.value)
        assertContentEquals(AudioSettings.DEFAULT.effect().toStateFile(), staging.readBytes())
    }

    @Test
    fun sendsChangesAtOnceAndPersistsThemWhenTheySettle() = runTest {
        val repo = started(repository())
        val atStart = effect.sets.size
        repo.update { it.withGain(0, 3f) }
        runCurrent()
        assertEquals(3f, eqSent().bands[0].gainDb)
        repo.update { it.withGain(0, 6f) }
        advanceTimeBy(EqRepository.PERSIST_DELAY_MS / 2)
        runCurrent()
        assertEquals(6f, eqSent().bands[0].gainDb)
        assertEquals(1, shell.runs.value)
        // While the band moves, only EQ_STATE goes out.
        assertEquals(listOf(ParamId.EQ_STATE, ParamId.EQ_STATE), effect.sets.drop(atStart).map { it.first })

        advanceTimeBy(EqRepository.PERSIST_DELAY_MS)
        runCurrent()
        shell.runs.first { it >= 2 }
        val settled = checkNotNull(repo.settings.value)
        assertEquals(2, shell.runs.value)
        assertContentEquals(settled.effect().toStateFile(), staging.readBytes())
        assertEquals(settled, AudioSettings.fromJson(storeFile.readText()))
        // Once saved, everything is sent again (a new stream may have reloaded an older state.bin).
        assertEquals(ALL_IDS, effect.sets.takeLast(ALL_IDS.size).map { it.first })
    }

    @Test
    fun reportsAnUnreachableEffectUntilARetryWorks() = runTest {
        effect.failWith = EffectCodes.ERROR_DEAD_OBJECT
        val repo = started(repository())
        assertEquals("setting parameter 0x0100 failed: ERROR_DEAD_OBJECT", repo.effectError.value)

        effect.failWith = null
        repo.retry()
        runCurrent()
        assertNull(repo.effectError.value)
        assertEquals(ALL_IDS, effect.sets.map { it.first })
    }

    @Test
    fun reportsWhenTheStateFileCannotBeWritten() = runTest {
        shell.result = ShellResult(ShellResult.NO_ROOT, emptyList(), listOf("no root shell"))
        val repo = started(repository())
        assertEquals("no root shell", repo.persistError.value)
        // Live control does not need root.
        assertNull(repo.effectError.value)
    }

    @Test
    fun withoutRootSettingsGoToAndroidsEffectAndStayInTheApp() = runTest {
        engineMode.value = EngineMode.ROOTLESS
        val repo = repository()
        repo.start()
        repo.settings.first { it != null }
        runCurrent()
        // What the service does once it runs.
        rootless.configure(mapOf(RootlessEngine.GLOBAL_SESSION to null), RootlessEngine.DEFAULT_FRAME_MS)
        runCurrent()
        val fx = dynamics.single()
        assertEquals(DynamicsPlan.of(AudioSettings.DEFAULT.effect(), fx.plan.layout).inputGainDb.toList(), fx.plan.inputGainDb.toList())

        repo.update { it.copy(eq = it.eq.copy(preampDb = -6f, autoPreamp = false)) }
        runCurrent()
        assertEquals(listOf(-6f, -6f), fx.inputGains)
        advanceTimeBy(EqRepository.PERSIST_DELAY_MS * 2)
        runCurrent()
        // Saved in the app only: no root, no state.bin, nothing sent to SoundPower's own effect.
        AudioSettings.fromJson(storeFile.readText()).let { assertEquals(-6f, it.eq.preampDb) }
        assertEquals(0, shell.runs.value)
        assertTrue(effect.sets.isEmpty())
        assertTrue(!staging.exists())
        assertTrue(repo.readStatus().rootless?.active == true)
    }
}
