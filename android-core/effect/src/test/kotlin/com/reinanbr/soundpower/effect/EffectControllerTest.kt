// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.effect

import com.reinanbr.soundpower.model.EqSettings
import com.reinanbr.soundpower.model.GraphicEq
import com.reinanbr.soundpower.protocol.EffectStatus
import com.reinanbr.soundpower.protocol.EqState
import com.reinanbr.soundpower.protocol.MasterParams
import com.reinanbr.soundpower.protocol.ParamId
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
    private class FakeEffect(val major: Int = 1) : EffectTransport {
        val set = LinkedHashMap<Int, ByteArray>()
        var released = false
        var deadOnce = false
        var statusFlags = 0

        override fun setParameter(param: ByteArray, value: ByteArray): Int {
            if (deadOnce) {
                deadOnce = false
                return EffectCodes.ERROR_DEAD_OBJECT
            }
            set[id(param)] = value.copyOf()
            return EffectCodes.SUCCESS
        }

        override fun getParameter(param: ByteArray, value: ByteArray): Int {
            val bytes = when (id(param)) {
                ParamId.PROTOCOL_INFO -> le(16).putShort(major.toShort()).putShort(0).putInt(0x100).putInt(32).putInt(3)
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

        val settings = EqSettings(bypass = true, bands = GraphicEq.withGain(GraphicEq.flatBands(), 5, 3f))
        c.apply(settings)
        val sent = opened.single().set
        assertEquals(listOf(ParamId.MASTER, ParamId.EQ_STATE, ParamId.LIMITER), sent.keys.toList())
        assertEquals(MasterParams(bypass = true), MasterParams.decode(sent.getValue(ParamId.MASTER)))
        assertEquals(3f, EqState.decode(sent.getValue(ParamId.EQ_STATE)).bands[5].gainDb)
        assertEquals(1, opened.size)
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
