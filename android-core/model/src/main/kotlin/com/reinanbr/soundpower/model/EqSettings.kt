// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.model

import com.reinanbr.soundpower.protocol.Band
import com.reinanbr.soundpower.protocol.BandChannel
import com.reinanbr.soundpower.protocol.Capability
import com.reinanbr.soundpower.protocol.CompressorParams
import com.reinanbr.soundpower.protocol.EqState
import com.reinanbr.soundpower.protocol.LimiterParams
import com.reinanbr.soundpower.protocol.MasterParams
import com.reinanbr.soundpower.protocol.ParamId
import com.reinanbr.soundpower.protocol.ProtocolException
import com.reinanbr.soundpower.protocol.StateFile
import com.reinanbr.soundpower.protocol.StereoParams
import com.reinanbr.soundpower.protocol.ToneParams

/** Everything the effect needs, as protocol payloads: what the app sends and writes to state.bin. */
data class EqSettings(
    val bypass: Boolean = false,
    val autoPreamp: Boolean = true,
    val preampDb: Float = 0f,
    val bands: List<Band> = GraphicBands().bands(),
    val limiter: LimiterParams = LimiterParams(),
    val limiterEnabled: Boolean = true,
    val tone: ToneParams = ToneParams(),
    val stereo: StereoParams = StereoParams(),
    val compressor: CompressorParams = CompressorParams(),
) {
    val master: MasterParams get() = MasterParams(bypass, autoPreamp, preampDb, limiterEnabled)
    val eqState: EqState get() = EqState(bands)

    /**
     * [eqState] for an effect with [capabilities]. One that cannot route bands would apply a
     * left-only boost to both channels, so routed bands are sent disabled instead.
     */
    fun eqState(capabilities: Int): EqState =
        if (capabilities and Capability.BAND_CHANNEL != 0) {
            eqState
        } else {
            EqState(bands.map { if (it.channel == BandChannel.ALL) it else it.copy(enabled = false, channel = BandChannel.ALL) })
        }

    /**
     * The `SET_PARAM` messages that put an effect in this state, in the order to send them. IDs
     * added in protocol 1.1 are left out unless [capabilities] (from `PROTOCOL_INFO`) lists them.
     */
    fun messages(capabilities: Int = ALL_CAPABILITIES): List<StateFile.Entry> = buildList {
        add(StateFile.Entry(ParamId.MASTER, master.encode()))
        add(StateFile.Entry(ParamId.EQ_STATE, eqState(capabilities).encode()))
        add(StateFile.Entry(ParamId.LIMITER, limiter.encode()))
        if (capabilities and Capability.TONE != 0) add(StateFile.Entry(ParamId.TONE, tone.encode()))
        if (capabilities and Capability.STEREO != 0) add(StateFile.Entry(ParamId.STEREO, stereo.encode()))
        if (capabilities and Capability.COMPRESSOR != 0) add(StateFile.Entry(ParamId.COMPRESSOR, compressor.encode()))
    }

    /**
     * The `state.bin` the effect loads at boot, for an effect with [capabilities] (all of them if
     * unknown). A 1.0 effect skips the entries it does not know.
     */
    fun toStateFile(capabilities: Int = ALL_CAPABILITIES): ByteArray = StateFile.encode(messages(capabilities))

    companion object {
        val DEFAULT = EqSettings()

        /** Every capability whose ID [messages] can send. */
        const val ALL_CAPABILITIES = Capability.TONE or Capability.STEREO or Capability.COMPRESSOR or Capability.BAND_CHANNEL

        /**
         * Reads a state file: known entries are applied over the defaults, unknown ones (from a
         * newer minor version) skipped. Throws [ProtocolException] if the effect would reject it.
         */
        fun fromStateFile(bytes: ByteArray): EqSettings {
            var s = DEFAULT
            for (e in StateFile.decode(bytes).entries) {
                s = when (e.id) {
                    ParamId.MASTER -> MasterParams.decode(e.payload).let {
                        s.copy(
                            bypass = it.bypass, autoPreamp = it.autoPreamp, preampDb = it.preampDb,
                            limiterEnabled = it.limiterEnabled,
                        )
                    }
                    ParamId.EQ_STATE -> s.copy(bands = EqState.decode(e.payload).bands)
                    ParamId.LIMITER -> s.copy(limiter = LimiterParams.decode(e.payload))
                    ParamId.TONE -> s.copy(tone = ToneParams.decode(e.payload))
                    ParamId.STEREO -> s.copy(stereo = StereoParams.decode(e.payload))
                    ParamId.COMPRESSOR -> s.copy(compressor = CompressorParams.decode(e.payload))
                    else -> s
                }
            }
            return s
        }
    }
}
