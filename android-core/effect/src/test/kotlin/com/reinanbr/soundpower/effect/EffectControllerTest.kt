// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.effect

import com.reinanbr.soundpower.model.EqSettings
import com.reinanbr.soundpower.model.GraphicBands
import com.reinanbr.soundpower.protocol.Capability
import com.reinanbr.soundpower.protocol.EffectStatus
import com.reinanbr.soundpower.protocol.EqState
import com.reinanbr.soundpower.protocol.InputVolumeSessions
import com.reinanbr.soundpower.protocol.MasterParams
import com.reinanbr.soundpower.protocol.ParamId
import com.reinanbr.soundpower.protocol.StereoParams
import com.reinanbr.soundpower.protocol.ToneParams
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EffectControllerTest {
    /** An effect that stores what it is sent and answers GETs like effect-lib does. */
    private class FakeEffect(val major: Int = 1, val capabilities: Int = 3) : EffectTransport {
        val set = LinkedHashMap<Int, ByteArray>()
        val calls = ArrayList<Int>()
        var released = false
        var deadOnce = false
        var statusFlags = 0

        override fun setParameter(param: ByteArray, value: ByteArray): Int {
            if (deadOnce) {
                deadOnce = false
                return EffectCodes.ERROR_DEAD_OBJECT
            }
            set[id(param)] = value.copyOf()
            calls += id(param)
            return EffectCodes.SUCCESS
        }

        override fun getParameter(param: ByteArray, value: ByteArray): Int {
            val bytes = when (id(param)) {
                ParamId.PROTOCOL_INFO -> le(16).putShort(major.toShort()).putShort(0).putInt(0x100).putInt(32).putInt(capabilities)
                ParamId.STATUS -> le(48).putInt(48000).putInt(2).putInt(5).putInt(1).putFloat(0f).putFloat(0f)
                    .putInt(4).putInt(-9).putInt(statusFlags).putFloat(0f).putLong(0)
                else -> return EffectCodes.ERROR_BAD_VALUE
            }.array()
            if (value.size < bytes.size) return EffectCodes.ERROR_BAD_VALUE
            bytes.copyInto(value)
            return bytes.size
        }

        override fun release() {
            released = true
        }

        private fun id(param: ByteArray) = ByteBuffer.wrap(param).order(ByteOrder.LITTLE_ENDIAN).getInt()
        private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun TestScope.controller(vararg effects: FakeEffect): Pair<EffectController, List<FakeEffect>> {
        val queue = ArrayDeque(effects.toList())
        val opened = ArrayList<FakeEffect>()
        val c = EffectController({ queue.removeFirst().also { opened += it } }, StandardTestDispatcher(testScheduler))
        return c to opened
    }

    @Test
    fun connectsAndSendsTheSettings() = runTest {
        val (c, opened) = controller(FakeEffect())
        val connection = c.connect()
        assertIs<EffectController.Connection.Connected>(connection)
        assertEquals("0.1.0", connection.info.libVersionName)

        val settings = EqSettings(bypass = true, bands = GraphicBands().withGain(5, 3f).bands())
        c.apply(settings)
        val sent = opened.single().set
        assertEquals(listOf(ParamId.MASTER, ParamId.EQ_STATE, ParamId.LIMITER), sent.keys.toList())
        assertEquals(MasterParams(bypass = true), MasterParams.decode(sent.getValue(ParamId.MASTER)))
        assertEquals(3f, EqState.decode(sent.getValue(ParamId.EQ_STATE)).bands[5].gainDb)
        assertEquals(1, opened.size)
    }

    @Test
    fun sendsOnlyWhatChanged() = runTest {
        val (c, opened) = controller(FakeEffect(), FakeEffect())
        val first = EqSettings()
        c.apply(first)
        val effect = opened.single()
        assertEquals(3, effect.calls.size)

        c.apply(first.copy(bands = GraphicBands().withGain(1, 2f).bands()))
        assertEquals(listOf(ParamId.EQ_STATE), effect.calls.drop(3))
        c.apply(first.copy(bands = GraphicBands().withGain(1, 2f).bands()))
        assertEquals(4, effect.calls.size) // nothing changed

        c.apply(first, force = true)
        assertEquals(7, effect.calls.size)
    }

    @Test
    fun aReconnectResendsEverything() = runTest {
        val (c, opened) = controller(FakeEffect(), FakeEffect())
        c.apply(EqSettings())
        // audioserver restarts: the next call fails once, and the controller opens a new instance.
        opened[0].deadOnce = true
        c.apply(EqSettings(preampDb = -2f))
        assertEquals(2, opened.size)
        assertEquals(listOf(ParamId.MASTER, ParamId.EQ_STATE, ParamId.LIMITER), opened[1].calls)
        assertEquals(-2f, MasterParams.decode(opened[1].set.getValue(ParamId.MASTER)).preampDb)
    }

    @Test
    fun sendsTheProtocol11ParametersOnlyWhenTheEffectHasThem() = runTest {
        val all = Capability.TONE or Capability.STEREO or Capability.COMPRESSOR or Capability.LIMITER_SWITCH
        val (c, opened) = controller(FakeEffect(capabilities = 3 or all))
        val settings = EqSettings(tone = ToneParams(bassDb = 4f), stereo = StereoParams(mono = true))
        c.apply(settings) // connects on the first call
        val sent = opened.single().set
        assertEquals(
            listOf(ParamId.MASTER, ParamId.EQ_STATE, ParamId.LIMITER, ParamId.TONE, ParamId.STEREO, ParamId.COMPRESSOR),
            sent.keys.toList(),
        )
        assertEquals(4f, ToneParams.decode(sent.getValue(ParamId.TONE)).bassDb)
        assertEquals(true, StereoParams.decode(sent.getValue(ParamId.STEREO)).mono)
    }

    @Test
    fun sendsTheSessionsLeftOutOfDirectVolumeControl() = runTest {
        val caps = 3 or Capability.INPUT_VOLUME_SESSIONS
        val (c, opened) = controller(FakeEffect(capabilities = caps), FakeEffect(capabilities = caps))
        c.setInputVolumeSessions(listOf(1449, 7, 1449))
        val effect = opened.single()
        assertEquals(listOf(ParamId.INPUT_VOLUME_SESSIONS), effect.calls)
        assertEquals(listOf(1449, 7), InputVolumeSessions.decode(effect.set.getValue(ParamId.INPUT_VOLUME_SESSIONS)).sessions)
        c.setInputVolumeSessions(listOf(1449, 7))
        c.apply(EqSettings())
        assertEquals(1, effect.calls.count { it == ParamId.INPUT_VOLUME_SESSIONS }) // unchanged

        // A new connection sends the list again, even an empty one: the old list outlives the app.
        effect.deadOnce = true
        c.setInputVolumeSessions(emptyList())
        assertEquals(listOf(ParamId.INPUT_VOLUME_SESSIONS), opened[1].calls)
        c.apply(EqSettings())
        assertEquals(listOf(ParamId.INPUT_VOLUME_SESSIONS, ParamId.MASTER, ParamId.EQ_STATE, ParamId.LIMITER), opened[1].calls)
    }

    @Test
    fun anOlderEffectGetsNoSessionList() = runTest {
        val (c, opened) = controller(FakeEffect(capabilities = 3 or Capability.DIRECT_VOLUME))
        c.setInputVolumeSessions(listOf(5))
        c.apply(EqSettings())
        assertEquals(listOf(ParamId.MASTER, ParamId.EQ_STATE, ParamId.LIMITER), opened.single().calls)
    }

    @Test
    fun refusesAnotherMajorVersion() = runTest {
        val newer = FakeEffect(major = 2)
        val (c, _) = controller(newer)
        assertIs<EffectController.Connection.Incompatible>(c.connect())
        assertTrue(newer.released)
    }

    @Test
    fun reportsAnEffectThatCannotBeCreated() = runTest {
        val c = EffectController({ throw IllegalArgumentException("Effect type not supported") }, StandardTestDispatcher(testScheduler))
        assertEquals(EffectController.Connection.Failed("Effect type not supported"), c.connect())
        val e = assertFailsWith<EffectException> { c.apply(EqSettings.DEFAULT) }
        assertEquals("Effect type not supported", e.message)
    }

    @Test
    fun reconnectsOnceWhenAudioserverDied() = runTest {
        val first = FakeEffect().apply { deadOnce = true }
        val second = FakeEffect()
        val (c, opened) = controller(first, second)
        c.apply(EqSettings.DEFAULT)
        assertTrue(first.released)
        assertEquals(listOf(first, second), opened)
        assertEquals(3, second.set.size)
    }

    @Test
    fun readsTheStatus() = runTest {
        val (c, _) = controller(FakeEffect().apply { statusFlags = EffectStatus.FLAG_BYPASSED })
        val s = c.status()
        assertEquals(4, s.instances)
        assertEquals(-9, s.sessionId)
        assertTrue(s.bypassed)
    }

    @Test
    fun closeReleasesAndTheNextCallReopens() = runTest {
        val first = FakeEffect()
        val (c, opened) = controller(first, FakeEffect())
        c.connect()
        c.close()
        assertTrue(first.released)
        c.status()
        assertEquals(2, opened.size)
    }
}
