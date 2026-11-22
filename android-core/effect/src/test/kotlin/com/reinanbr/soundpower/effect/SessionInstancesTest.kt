// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.effect

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionInstancesTest {
    private val open = mutableListOf<Int>()
    private val attempts = mutableListOf<Int>()
    private var failing = setOf<Int>()

    private val instances = SessionInstances { session ->
        attempts += session
        if (session in failing) throw IllegalStateException("no init")
        open += session
        AutoCloseable { open -= session }
    }

    @Test
    fun followsTheTargets() {
        instances.configure(mapOf(11 to "com.example.a", 12 to null))
        assertEquals(listOf(11, 12), open)
        assertEquals(mapOf(11 to "com.example.a", 12 to null), instances.status.value.attached)

        instances.configure(mapOf(12 to "com.example.b", 13 to "com.example.c"))
        assertEquals(listOf(12, 13), open)
        // An instance stays; only its player's name is updated.
        assertEquals(listOf(11, 12, 13), attempts)
        assertEquals(mapOf(12 to "com.example.b", 13 to "com.example.c"), instances.status.value.attached)

        instances.stop()
        assertEquals(emptyList(), open)
        assertEquals(SessionInstances.Status(), instances.status.value)
    }

    @Test
    fun aFailedSessionWaitsForARetry() {
        failing = setOf(21)
        instances.configure(mapOf(21 to "com.example.a"))
        instances.configure(mapOf(21 to "com.example.a", 22 to null))
        assertEquals(listOf(21, 22), attempts)
        assertEquals(mapOf(21 to "no init"), instances.status.value.failed)

        failing = emptySet()
        instances.retry()
        assertEquals(listOf(22, 21), open)
        assertEquals(emptyMap(), instances.status.value.failed)

        // Leaving the targets forgets the failure too.
        failing = setOf(23)
        instances.configure(mapOf(23 to null))
        instances.configure(emptyMap())
        failing = emptySet()
        instances.configure(mapOf(23 to null))
        assertEquals(listOf(23), open)
    }
}
