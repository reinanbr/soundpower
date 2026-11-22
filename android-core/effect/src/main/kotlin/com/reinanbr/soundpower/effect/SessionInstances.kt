// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.effect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The root mode's per-session fallback: SoundPower instances the app attaches to players'
 * sessions itself, for devices where the system does not attach the module's effect to music.
 * The instances take their settings from the library's shared state, like every other instance,
 * so nothing is sent to them. Android removes them when the app's process dies.
 */
class SessionInstances(private val attach: (session: Int) -> AutoCloseable) {
    /** The sessions with an instance and those where attaching failed, with their players. */
    data class Status(val attached: Map<Int, String?> = emptyMap(), val failed: Map<Int, String> = emptyMap())

    private val instances = HashMap<Int, AutoCloseable>()
    private val labels = HashMap<Int, String?>()
    private val failures = HashMap<Int, String>()
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /**
     * Attaches to every session in [targets] (session to player) and releases the others. A
     * session that failed is not tried again until it leaves the targets or [retry] runs.
     */
    @Synchronized
    fun configure(targets: Map<Int, String?>) {
        for (session in instances.keys - targets.keys) instances.remove(session)?.close()
        failures.keys.retainAll(targets.keys)
        labels.clear()
        labels.putAll(targets)
        for (session in targets.keys) {
            if (session in instances || session in failures) continue
            try {
                instances[session] = attach(session)
            } catch (e: Exception) {
                failures[session] = e.message ?: e.javaClass.simpleName
            }
        }
        _status.value = Status(instances.keys.associateWith { labels[it] }, failures.toMap())
    }

    /** Tries the sessions that failed again. */
    @Synchronized
    fun retry() {
        if (failures.isEmpty()) return
        failures.clear()
        configure(labels.toMap())
    }

    @Synchronized
    fun stop() = configure(emptyMap())
}
