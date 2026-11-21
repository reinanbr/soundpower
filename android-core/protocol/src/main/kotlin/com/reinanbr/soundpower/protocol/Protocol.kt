// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.protocol

import java.util.UUID

/** Constants of protocol/include/soundpower/protocol.h; the two must stay in sync. */
object Protocol {
    const val VERSION_MAJOR = 1
    const val VERSION_MINOR = 0

    const val EFFECT_NAME = "SoundPower"
    val EFFECT_TYPE_UUID: UUID = UUID.fromString("69dc09f7-12a9-471d-89a1-603608c452c9")
    val EFFECT_IMPL_UUID: UUID = UUID.fromString("23e79ffb-ab65-4cd7-a768-b493ded3fabe")

    const val DATA_DIR = "/data/vendor/soundpower"
    const val STATE_FILE = "$DATA_DIR/state.bin"
    const val STATE_FILE_MAGIC = "SPST"
    const val STATE_FILE_MAX_BYTES = 65536

    const val MAX_BANDS = 32
    const val MAX_MESSAGE_BYTES = 1024

    const val FREQ_MIN_HZ = 10f
    const val GAIN_MIN_DB = -30f
    const val GAIN_MAX_DB = 30f
    const val Q_MIN = 0.05f
    const val Q_MAX = 40f
    const val PREAMP_MIN_DB = -30f
    const val PREAMP_MAX_DB = 30f
    const val LIMITER_CEILING_MIN_DB = -24f
    const val LIMITER_CEILING_MAX_DB = 0f
}

/** Parameter IDs (`enum sp_param_id`). */
object ParamId {
    const val PROTOCOL_INFO = 0x0001
    const val STATUS = 0x0002
    const val SPECTRUM = 0x0003
    const val MASTER = 0x0100
    const val EQ_STATE = 0x0200
    const val LIMITER = 0x0300

    /** The `param` bytes of `AudioEffect.setParameter/getParameter`: the ID as a little-endian u32. */
    fun bytes(id: Int): ByteArray = Wire.writer(4).putInt(id).array()
}

/** Capability bits of [ProtocolInfo.capabilities]. */
object Capability {
    const val PARAMETRIC_EQ = 1 shl 0
    const val LIMITER = 1 shl 1
    const val SPECTRUM = 1 shl 2
}

/** A payload that does not follow docs/protocol.md. */
class ProtocolException(message: String) : Exception(message)
