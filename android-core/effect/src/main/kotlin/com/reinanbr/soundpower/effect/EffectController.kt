// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.effect

import com.reinanbr.soundpower.model.EqSettings
import com.reinanbr.soundpower.protocol.EffectStatus
import com.reinanbr.soundpower.protocol.ParamId
import com.reinanbr.soundpower.protocol.ProtocolInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The two `AudioEffect` calls the controller needs, returning `AudioEffect` status codes. */
interface EffectTransport {
    fun setParameter(param: ByteArray, value: ByteArray): Int

    /** The number of bytes written to [value], or a negative status code. */
    fun getParameter(param: ByteArray, value: ByteArray): Int

    fun release()
}

/** Status codes of `android.media.audiofx.AudioEffect`. */
object EffectCodes {
    const val SUCCESS = 0
    const val ERROR = -1
    const val ALREADY_EXISTS = -2
    const val ERROR_NO_INIT = -3
    const val ERROR_BAD_VALUE = -4
    const val ERROR_INVALID_OPERATION = -5
    const val ERROR_NO_MEMORY = -6
    const val ERROR_DEAD_OBJECT = -7

    fun name(code: Int): String = when (code) {
        SUCCESS -> "SUCCESS"
        ERROR -> "ERROR"
        ALREADY_EXISTS -> "ALREADY_EXISTS"
        ERROR_NO_INIT -> "ERROR_NO_INIT"
        ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
        ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
        ERROR_NO_MEMORY -> "ERROR_NO_MEMORY"
        ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
        else -> "code $code"
    }
}

class EffectException(message: String) : Exception(message)

/**
 * Talks to the effect through a control instance that [open] creates. All instances in the
 * audio HAL share one parameter state, so what is set here reaches every stream. Calls are
 * serialised; if audioserver restarts (`ERROR_DEAD_OBJECT`), the instance is recreated once.
 */
class EffectController(
    private val open: () -> EffectTransport,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface Connection {
        data class Connected(val info: ProtocolInfo) : Connection

        /** The effect speaks another major protocol version: the module and app don't match. */
        data class Incompatible(val info: ProtocolInfo) : Connection

        data class Failed(val reason: String) : Connection
    }

    private val mutex = Mutex()
    private var transport: EffectTransport? = null
    private var info: ProtocolInfo? = null

    /** Payloads sent on the current connection, by ID. */
    private val sent = HashMap<Int, ByteArray>()

    /** Counts connections, so [apply] notices a reconnect. */
    private var generation = 0

    /** Capabilities of the effect last connected to, kept after it disconnects; null before. */
    @Volatile
    var lastCapabilities: Int? = null
        private set

    suspend fun connect(): Connection = locked { connectLocked() }

    /**
     * Sends [settings]: MASTER, EQ_STATE and LIMITER, plus the protocol 1.1 IDs the effect lists in
     * its capabilities. Only messages whose bytes differ from what this connection last sent go
     * out (a slider drag sends one EQ_STATE, not six messages); [force] sends all of them, and so
     * does a new connection. Throws [EffectException].
     */
    suspend fun apply(settings: EqSettings, force: Boolean = false) = locked {
        if (force) sent.clear()
        // A reconnect in the middle (audioserver restarted) loses what was sent before it: start over.
        for (attempt in 0..MAX_RESENDS) {
            val generation = this.generation
            val capabilities = when (val c = connectLocked()) {
                is Connection.Connected -> c.info.capabilities
                is Connection.Incompatible -> throw EffectException("the effect speaks protocol ${c.info.major}.${c.info.minor}")
                is Connection.Failed -> throw EffectException(c.reason)
            }
            for (m in settings.messages(capabilities)) {
                if (sent[m.id]?.contentEquals(m.payload) == true) continue
                sent.remove(m.id)
                val code = call { it.setParameter(ParamId.bytes(m.id), m.payload) }
                if (code != EffectCodes.SUCCESS) {
                    throw EffectException("setting parameter 0x%04x failed: %s".format(m.id, EffectCodes.name(code)))
                }
                sent[m.id] = m.payload
            }
            if (generation == this.generation) break
        }
    }

    /** `STATUS` of the control instance, with the process-wide counters. Throws [EffectException]. */
    suspend fun status(): EffectStatus = locked {
        val value = ByteArray(EffectStatus.SIZE)
        val n = call { it.getParameter(ParamId.bytes(ParamId.STATUS), value) }
        if (n < EffectStatus.SIZE) throw EffectException("reading STATUS failed: ${EffectCodes.name(n)}")
        EffectStatus.decode(value)
    }

    suspend fun close() = locked { dropLocked() }

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(io) { block() } }

    private fun connectLocked(): Connection {
        info?.let { return Connection.Connected(it) }
        val t = try {
            open()
        } catch (e: Exception) {
            return Connection.Failed(e.message ?: e.javaClass.simpleName)
        }
        val value = ByteArray(ProtocolInfo.SIZE)
        val n = t.getParameter(ParamId.bytes(ParamId.PROTOCOL_INFO), value)
        if (n < ProtocolInfo.SIZE) {
            t.release()
            return Connection.Failed("reading PROTOCOL_INFO failed: ${EffectCodes.name(n)}")
        }
        val pi = ProtocolInfo.decode(value)
        if (!pi.isCompatible) {
            t.release()
            return Connection.Incompatible(pi)
        }
        transport = t
        info = pi
        lastCapabilities = pi.capabilities
        sent.clear()
        generation++
        return Connection.Connected(pi)
    }

    private fun call(op: (EffectTransport) -> Int): Int {
        var code = EffectCodes.ERROR
        for (attempt in 0..1) {
            when (val c = connectLocked()) {
                is Connection.Connected -> {}
                is Connection.Incompatible -> throw EffectException("the effect speaks protocol ${c.info.major}.${c.info.minor}")
                is Connection.Failed -> throw EffectException(c.reason)
            }
            code = op(checkNotNull(transport))
            if (code != EffectCodes.ERROR_DEAD_OBJECT) break
            dropLocked()
        }
        return code
    }

    private fun dropLocked() {
        transport?.release()
        transport = null
        info = null
        sent.clear()
    }

    private companion object {
        const val MAX_RESENDS = 2
    }
}
