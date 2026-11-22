// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Protocol 1.3 (sessions left out of direct volume control) against the golden vectors. */
class Protocol13Test {
    @Test
    fun inputVolumeSessionsMatchTheGoldenVectors() {
        val example = InputVolumeSessions(listOf(1449, 33001, 7))
        assertContentEquals(GoldenVectors.bytes("input_volume_sessions_example"), example.encode())
        assertEquals(example, InputVolumeSessions.decode(GoldenVectors.bytes("input_volume_sessions_example")))
        assertContentEquals(GoldenVectors.bytes("input_volume_sessions_empty"), InputVolumeSessions(emptyList()).encode())
    }

    @Test
    fun inputVolumeSessionsAreBounded() {
        assertFailsWith<IllegalArgumentException> { InputVolumeSessions(List(Protocol.MAX_INPUT_VOLUME_SESSIONS + 1) { it }) }
        assertFailsWith<ProtocolException> { InputVolumeSessions.decode(byteArrayOf(2, 0, 0, 0, 1, 0, 0, 0)) }
        assertEquals(Protocol.MAX_INPUT_VOLUME_SESSIONS, InputVolumeSessions(List(Protocol.MAX_INPUT_VOLUME_SESSIONS) { it }).encode().size / 4 - 1)
    }

    @Test
    fun stateFile13MatchesTheGoldenVector() {
        // A 1.3 writer stores the same entries as 1.2 under its own version.
        val v12 = StateFile.decode(GoldenVectors.bytes("state_file_v1_2"))
        assertContentEquals(GoldenVectors.bytes("state_file_v1_3"), StateFile.encode(v12.entries))
        assertEquals(3, StateFile.decode(GoldenVectors.bytes("state_file_v1_3")).minor)
    }
}
