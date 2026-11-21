// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.model

import com.reinanbr.soundpower.protocol.Band
import com.reinanbr.soundpower.protocol.EqState
import com.reinanbr.soundpower.protocol.LimiterParams
import com.reinanbr.soundpower.protocol.MasterParams
import com.reinanbr.soundpower.protocol.ParamId
import com.reinanbr.soundpower.protocol.ProtocolException
import com.reinanbr.soundpower.protocol.StateFile

/** Everything the effect needs, as the app stores it and sends it. */
data class EqSettings(
    val bypass: Boolean = false,
    val autoPreamp: Boolean = true,
    val preampDb: Float = 0f,
    val bands: List<Band> = GraphicEq.flatBands(),
    val limiter: LimiterParams = LimiterParams(),
) {
    val master: MasterParams get() = MasterParams(bypass, autoPreamp, preampDb)
    val eqState: EqState get() = EqState(bands)

    /** The `SET_PARAM` messages that put an effect in this state, in the order to send them. */
    fun messages(): List<StateFile.Entry> = listOf(
        StateFile.Entry(ParamId.MASTER, master.encode()),
        StateFile.Entry(ParamId.EQ_STATE, eqState.encode()),
        StateFile.Entry(ParamId.LIMITER, limiter.encode()),
    )

    /** The `state.bin` the effect loads at boot. */
    fun toStateFile(): ByteArray = StateFile.encode(messages())

    companion object {
        val DEFAULT = EqSettings()

        /**
         * Reads a state file: known entries are applied over the defaults, unknown ones (from a
         * newer minor version) skipped. Throws [ProtocolException] if the effect would reject it.
         */
        fun fromStateFile(bytes: ByteArray): EqSettings {
            var s = DEFAULT
            for (e in StateFile.decode(bytes).entries) {
                s = when (e.id) {
                    ParamId.MASTER -> MasterParams.decode(e.payload).let {
                        s.copy(bypass = it.bypass, autoPreamp = it.autoPreamp, preampDb = it.preampDb)
                    }
                    ParamId.EQ_STATE -> s.copy(bands = EqState.decode(e.payload).bands)
                    ParamId.LIMITER -> s.copy(limiter = LimiterParams.decode(e.payload))
                    else -> s
                }
            }
            return s
        }
    }
}
