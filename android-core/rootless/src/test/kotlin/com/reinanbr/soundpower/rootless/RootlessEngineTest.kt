// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.rootless

import com.reinanbr.soundpower.model.DynamicsCompressor
import com.reinanbr.soundpower.model.DynamicsLimiter
import com.reinanbr.soundpower.model.DynamicsPlan
import com.reinanbr.soundpower.model.EqSettings
import com.reinanbr.soundpower.protocol.Band
import com.reinanbr.soundpower.protocol.BandType
import com.reinanbr.soundpower.protocol.CompressorParams
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootlessEngineTest {
    private class FakeEffect(val session: Int, val plan: DynamicsPlan) : DynamicsEffect {
        val calls = mutableListOf<String>()
        var released = false
        var failNext = false
        var on = plan.enabled
        override var hasControl = true

        private fun call(what: String) {
            check(!released) { "released" }
            if (failNext) throw RuntimeException("dead object")
            calls += what
        }

        override fun setEnabled(enabled: Boolean) {
            call("enabled=$enabled")
            on = enabled
        }

        override fun setInputGain(channel: Int, db: Float) = call("gain $channel")
        override fun setBand(channel: Int, band: Int, cutoffHz: Float, gainDb: Float) = call("band $channel $band")
        override fun setCompressor(channel: Int, compressor: DynamicsCompressor) = call("compressor $channel")
        override fun setLimiter(channel: Int, limiter: DynamicsLimiter) = call("limiter $channel")
        override fun release() {
            released = true
        }
    }

    private val created = mutableListOf<FakeEffect>()
    private var refuse: String? = null
    private val factory = DynamicsEffect.Factory { session, plan ->
        refuse?.let { throw UnsupportedOperationException(it) }
        FakeEffect(session, plan).also { created += it }
    }

    private fun peak(f: Float, g: Float) = Band(BandType.PEAKING, true, f, g, 1.4f)

    private fun engine(dispatcher: TestDispatcher) = RootlessEngine(factory, { 48000 }, dispatcher)

    @Test
    fun attachesWithTheWholePlanOnceSettingsAreKnown() = runTest {
        val e = engine(StandardTestDispatcher(testScheduler))
        e.configure(mapOf(0 to null), 40f)
        assertTrue(created.isEmpty(), "attached before the settings were known")
        val s = EqSettings(bands = listOf(peak(1000f, 6f)))
        e.update(s)
        val fx = created.single()
        assertEquals(0, fx.session)
        assertEquals(2048, fx.plan.layout.blockSize)
        assertTrue(fx.calls.isEmpty(), "the configuration carries the plan")
        assertTrue(e.status.value.active)
        assertEquals(2048, e.status.value.layout?.blockSize)
    }

    @Test
    fun aChangeSendsOnlyTheBandsThatMoved() = runTest {
        val e = engine(StandardTestDispatcher(testScheduler))
        // One step of a drag.
        e.update(EqSettings(bands = listOf(peak(1000f, 6f), peak(8000f, 0f)), autoPreamp = false))
        e.configure(mapOf(0 to null), 40f)
        val fx = created.single()
        e.update(EqSettings(bands = listOf(peak(1000f, 6.5f), peak(8000f, 0f)), autoPreamp = false))
        val bands = fx.calls.filter { it.startsWith("band ") }
        assertTrue(bands.isNotEmpty())
        val layout = fx.plan.layout
        val left = bands.count { it.startsWith("band 0 ") }
        assertTrue(left < layout.bandCount / 2, "$left of ${layout.bandCount} bands per channel sent")
        // Both channels, same bands; nothing else changed.
        assertEquals(bands.count { it.startsWith("band 0 ") }, bands.count { it.startsWith("band 1 ") })
        assertEquals(bands.size, fx.calls.size)
        fx.calls.clear()
        e.update(EqSettings(bands = listOf(peak(1000f, 6.5f), peak(8000f, 0f)), autoPreamp = false))
        assertTrue(fx.calls.isEmpty())
    }

    @Test
    fun bypassTogglesTheEffectAndDynamicsGoToBothChannels() = runTest {
        val e = engine(StandardTestDispatcher(testScheduler))
        e.update(EqSettings())
        e.configure(mapOf(0 to null), 40f)
        val fx = created.single()
        e.update(EqSettings(bypass = true))
        assertEquals(listOf("enabled=false"), fx.calls)
        fx.calls.clear()
        e.update(EqSettings(compressor = CompressorParams(enabled = true)))
        assertEquals(listOf("compressor 0", "compressor 1", "enabled=true"), fx.calls)
    }

    @Test
    fun aNewPrecisionRecreatesTheInstances() = runTest {
        val e = engine(StandardTestDispatcher(testScheduler))
        e.update(EqSettings())
        e.configure(mapOf(0 to null), 40f)
        e.configure(mapOf(0 to null), 80f)
        assertEquals(2, created.size)
        assertTrue(created[0].released)
        assertEquals(4096, created[1].plan.layout.blockSize)
    }

    @Test
    fun aFailingInstanceIsRecreatedWithTheLatestPlan() = runTest {
        val e = engine(StandardTestDispatcher(testScheduler))
        e.update(EqSettings())
        e.configure(mapOf(0 to null), 40f)
        created.single().failNext = true
        val s = EqSettings(bands = listOf(peak(250f, -5f)))
        e.update(s)
        assertEquals(2, created.size)
        assertTrue(created[0].released)
        assertEquals(DynamicsPlan.of(s, created[1].plan.layout).bandGainDb[0].toList(), created[1].plan.bandGainDb[0].toList())
        assertTrue(e.status.value.active)
    }

    @Test
    fun sessionsComeAndGoAndRefusalsWaitForARetry() = runTest {
        val e = engine(StandardTestDispatcher(testScheduler))
        e.update(EqSettings())
        e.configure(mapOf(41 to "org.example.player"), 40f)
        refuse = "no such session"
        e.configure(mapOf(41 to "org.example.player", 42 to null), 40f)
        assertEquals(1, created.size)
        val failed = e.status.value.attachments.single { it.session == 42 }
        assertEquals("no such session", failed.error)
        assertFalse(failed.active)
        e.update(EqSettings(preampDb = -2f, autoPreamp = false))
        assertEquals(1, created.size, "a refused session is not retried on every change")
        refuse = null
        e.retry()
        assertEquals(2, created.size)
        assertNull(e.status.value.attachments.single { it.session == 42 }.error)
        e.configure(mapOf(42 to null), 40f)
        assertTrue(created[0].released)
        assertEquals(listOf(42), e.status.value.attachments.map { it.session })
        e.stop()
        assertTrue(created[1].released)
        assertFalse(e.status.value.active)
    }

    @Test
    fun anInstanceTakenOverIsReported() = runTest {
        val e = engine(StandardTestDispatcher(testScheduler))
        e.update(EqSettings())
        e.configure(mapOf(0 to null), 40f)
        created.single().hasControl = false
        e.retry()
        val a = e.status.value.attachments.single()
        assertFalse(a.active)
        assertEquals(RootlessEngine.LOST_CONTROL, a.error)
    }
}
