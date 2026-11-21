// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StateFileTest {
    private val entries = listOf(
        StateFile.Entry(ParamId.MASTER, GoldenVectors.bytes("master_bypass")),
        StateFile.Entry(ParamId.EQ_STATE, GoldenVectors.bytes("eq_state_3band")),
        StateFile.Entry(ParamId.LIMITER, GoldenVectors.bytes("limiter_custom")),
    )

    @Test
    fun encodeMatchesTheGoldenVector() {
        assertContentEquals(GoldenVectors.bytes("state_file_example"), StateFile.encode(entries, minor = 0))
    }

    @Test
    fun decodesTheGoldenVector() {
        val contents = StateFile.decode(GoldenVectors.bytes("state_file_example"))
        assertEquals(1, contents.major)
        assertEquals(0, contents.minor)
        assertEquals(entries, contents.entries)
    }

    @Test
    fun keepsUnknownEntriesOfANewerMinorVersion() {
        val contents = StateFile.decode(GoldenVectors.bytes("state_file_unknown_entry"))
        assertEquals(7, contents.minor)
        assertEquals(listOf(ParamId.MASTER, 0x7777, ParamId.EQ_STATE, ParamId.LIMITER), contents.entries.map { it.id })
        assertContentEquals("abc".toByteArray(), contents.entries[1].payload)
    }

    @Test
    fun anEmptyFileIsValid() {
        val bytes = StateFile.encode(emptyList())
        assertEquals(StateFile.HEADER_SIZE, bytes.size)
        assertEquals(emptyList(), StateFile.decode(bytes).entries)
    }

    @Test
    fun everyTruncationIsRejected() {
        val bytes = GoldenVectors.bytes("state_file_example")
        for (n in 0 until bytes.size) {
            assertFailsWith<ProtocolException>("truncated to $n bytes") { StateFile.decode(bytes.copyOf(n)) }
        }
    }

    @Test
    fun rejectsTrailingBytesBadMagicAndAnotherMajor() {
        val bytes = GoldenVectors.bytes("state_file_example")
        assertFailsWith<ProtocolException> { StateFile.decode(bytes + byteArrayOf(0)) }
        assertFailsWith<ProtocolException> { StateFile.decode(bytes.copyOf().also { it[0] = 'X'.code.toByte() }) }
        assertFailsWith<ProtocolException> { StateFile.decode(bytes.copyOf().also { it[4] = 2 }) }
    }

    @Test
    fun rejectsOversizedEntries() {
        val header = Wire.writer(StateFile.HEADER_SIZE + 8)
            .put("SPST".toByteArray()).putShort(1).putShort(0).putInt(1)
            .putInt(ParamId.MASTER).putInt(Protocol.MAX_MESSAGE_BYTES + 4).array()
        assertFailsWith<ProtocolException> { StateFile.decode(header + ByteArray(Protocol.MAX_MESSAGE_BYTES + 4)) }
    }
}
