// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.rootless

import com.reinanbr.soundpower.model.DynamicsCompressor
import com.reinanbr.soundpower.model.DynamicsLimiter
import com.reinanbr.soundpower.model.DynamicsPlan
import com.reinanbr.soundpower.model.EqSettings
import com.reinanbr.soundpower.model.SpectralLayout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The equalizer without root: a Dynamics Processing instance per audio session it is attached to
 * (session 0 is the global output mix), all set from the same [EqSettings].
 *
 * Instances live as long as this process holds them, so the app keeps a foreground service while
 * they are attached. Each change sends only what differs from what the instance last accepted: a
 * band drag moves a few dozen band gains, not the whole curve. An instance that fails (audioserver
 * restarted, another app took it over) is recreated once with the full plan; a session whose
 * instance cannot be created is left alone until [configure] or [retry].
 */
class RootlessEngine(
    private val factory: DynamicsEffect.Factory,
    /** The output rate the layout is designed for (bands keep their frequencies at another rate). */
    private val sampleRate: () -> Int,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    data class Attachment(
        val session: Int,
        /** The package that opened the session, if it said. */
        val label: String?,
        /** An instance is attached and this app controls it. */
        val active: Boolean,
        val error: String?,
    )

    data class Status(
        val attachments: List<Attachment> = emptyList(),
        val layout: SpectralLayout? = null,
    ) {
        val active: Boolean get() = attachments.any { it.active }
    }

    private class Instance(val effect: DynamicsEffect, plan: DynamicsPlan) {
        val layout = plan.layout
        var enabled = plan.enabled
        val inputGainDb = plan.inputGainDb.copyOf()
        val bandGainDb = plan.bandGainDb.map { it.copyOf() }
        var compressor: DynamicsCompressor = plan.compressor
        var limiter: DynamicsLimiter = plan.limiter

        /** Sends what [plan] changes, recording each value once the effect took it. */
        fun apply(plan: DynamicsPlan) {
            if (!plan.enabled && enabled) {
                effect.setEnabled(false)
                enabled = false
            }
            for (ch in 0 until DynamicsPlan.CHANNELS) {
                if (differs(plan.inputGainDb[ch], inputGainDb[ch])) {
                    effect.setInputGain(ch, plan.inputGainDb[ch])
                    inputGainDb[ch] = plan.inputGainDb[ch]
                }
                val want = plan.bandGainDb[ch]
                val have = bandGainDb[ch]
                for (b in want.indices) {
                    if (differs(want[b], have[b])) {
                        effect.setBand(ch, b, layout.cutoffsHz[b], want[b])
                        have[b] = want[b]
                    }
                }
            }
            if (plan.compressor != compressor) {
                for (ch in 0 until DynamicsPlan.CHANNELS) effect.setCompressor(ch, plan.compressor)
                compressor = plan.compressor
            }
            if (plan.limiter != limiter) {
                for (ch in 0 until DynamicsPlan.CHANNELS) effect.setLimiter(ch, plan.limiter)
                limiter = plan.limiter
            }
            if (plan.enabled && !enabled) {
                effect.setEnabled(true)
                enabled = true
            }
        }
    }

    private val mutex = Mutex()
    private var settings: EqSettings? = null
    private var frameMs = DEFAULT_FRAME_MS
    private var wanted: Map<Int, String?> = emptyMap()
    private val instances = LinkedHashMap<Int, Instance>()
    private val failures = HashMap<Int, String>()
    private var plan: DynamicsPlan? = null

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Makes every instance sound like [settings]. */
    suspend fun update(settings: EqSettings) = locked {
        if (settings != this.settings) {
            this.settings = settings
            plan = null
        }
        sync()
    }

    /**
     * Attaches to exactly [sessions] (session to the package that opened it), with FFT frames of
     * [frameMs] (see [SpectralLayout]); sessions that failed before are tried again.
     */
    suspend fun configure(sessions: Map<Int, String?>, frameMs: Float) = locked {
        wanted = sessions.toMap()
        this.frameMs = frameMs
        failures.clear()
        sync()
    }

    /** Tries the sessions whose instance could not be created again. */
    suspend fun retry() = locked {
        failures.clear()
        sync()
    }

    /** Releases every instance. */
    suspend fun stop() = locked {
        wanted = emptyMap()
        sync()
    }

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(io) { block() } }

    private fun sync() {
        for (session in instances.keys - wanted.keys) instances.remove(session)?.let(::release)
        failures.keys.retainAll(wanted.keys)
        val s = settings
        if (s != null && wanted.isNotEmpty()) {
            val layout = SpectralLayout(sampleRate(), frameMs)
            val p = plan?.takeIf { it.layout == layout } ?: DynamicsPlan.of(s, layout).also { plan = it }
            for (session in wanted.keys) {
                if (session in failures) continue
                val existing = instances[session]
                if (existing != null && existing.layout == layout) {
                    try {
                        existing.apply(p)
                        continue
                    } catch (_: RuntimeException) {
                        // Dead or taken over: start again with a new instance.
                    }
                }
                existing?.let(::release)
                instances.remove(session)
                try {
                    instances[session] = Instance(factory.create(session, p), p)
                } catch (e: RuntimeException) {
                    failures[session] = e.message ?: e.javaClass.simpleName
                }
            }
        }
        publish()
    }

    private fun release(instance: Instance) {
        try {
            instance.effect.release()
        } catch (_: RuntimeException) {
        }
    }

    private fun publish() {
        _status.value = Status(
            attachments = wanted.map { (session, label) ->
                val instance = instances[session]
                val control = instance != null && try {
                    instance.effect.hasControl
                } catch (_: RuntimeException) {
                    false
                }
                Attachment(session, label, control, failures[session] ?: if (instance != null && !control) LOST_CONTROL else null)
            },
            layout = instances.values.firstOrNull()?.layout,
        )
    }

    companion object {
        const val GLOBAL_SESSION = 0

        /** 2048-point FFT at 44.1 and 48 kHz: bins 22-23 Hz apart, a 43 ms delay. */
        const val DEFAULT_FRAME_MS = 40f

        const val LOST_CONTROL = "another app controls the effect"

        /**
         * Smaller gain changes are not sent: far below what can be heard, and it spares the binder
         * calls for the long tails of a peak, where the gain barely moves.
         */
        private const val GAIN_STEP_DB = 0.05f

        private fun differs(a: Float, b: Float) = abs(a - b) > GAIN_STEP_DB
    }
}
