// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.protocol

import java.util.UUID

/** Constants of protocol/include/soundpower/protocol.h; the two must stay in sync. */
object Protocol {
    const val VERSION_MAJOR = 1
    const val VERSION_MINOR = 1

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
    const val FREQ_MAX_FS_RATIO = 0.49f
    const val GAIN_MIN_DB = -30f
    const val GAIN_MAX_DB = 30f
    const val Q_MIN = 0.05f
    const val Q_MAX = 40f
    const val PREAMP_MIN_DB = -30f
    const val PREAMP_MAX_DB = 30f
    const val LIMITER_CEILING_MIN_DB = -24f
    const val LIMITER_CEILING_MAX_DB = 0f
    const val LIMITER_RELEASE_MIN_MS = 1f
    const val LIMITER_RELEASE_MAX_MS = 1000f

    // Since 1.1.
    const val TONE_BASS_FREQ_DEFAULT_HZ = 100f
    const val TONE_TREBLE_FREQ_DEFAULT_HZ = 8000f
    const val TONE_SHELF_Q = 0.70710678f
    const val BALANCE_MIN = -1f
    const val BALANCE_MAX = 1f
    const val WIDTH_MIN = 0f
    const val WIDTH_MAX = 2f
    const val COMP_THRESHOLD_MIN_DB = -60f
    const val COMP_THRESHOLD_MAX_DB = 0f
    const val COMP_RATIO_MIN = 1f
    const val COMP_RATIO_MAX = 20f
    const val COMP_ATTACK_MIN_MS = 0.1f
    const val COMP_ATTACK_MAX_MS = 200f
    const val COMP_RELEASE_MIN_MS = 5f
    const val COMP_RELEASE_MAX_MS = 2000f
    const val COMP_KNEE_MIN_DB = 0f
    const val COMP_KNEE_MAX_DB = 24f
    const val COMP_MAKEUP_MIN_DB = 0f
    const val COMP_MAKEUP_MAX_DB = 24f
}

/** Parameter IDs (`enum sp_param_id`). */
object ParamId {
    const val PROTOCOL_INFO = 0x0001
    const val STATUS = 0x0002
    const val SPECTRUM = 0x0003
    const val MASTER = 0x0100
    const val EQ_STATE = 0x0200
    const val LIMITER = 0x0300
    const val TONE = 0x0400
    const val STEREO = 0x0500
    const val COMPRESSOR = 0x0600

    /** The `param` bytes of `AudioEffect.setParameter/getParameter`: the ID as a little-endian u32. */
    fun bytes(id: Int): ByteArray = Wire.writer(4).putInt(id).array()
}

/** Capability bits of [ProtocolInfo.capabilities]. */
object Capability {
    const val PARAMETRIC_EQ = 1 shl 0
    const val LIMITER = 1 shl 1
    const val SPECTRUM = 1 shl 2
    const val TONE = 1 shl 9
    const val STEREO = 1 shl 10
    const val COMPRESSOR = 1 shl 11

    /** The effect understands [MasterParams.FLAG_LIMITER_OFF]. */
    const val LIMITER_SWITCH = 1 shl 12

    /** The effect routes bands by [Band.channel]. */
    const val BAND_CHANNEL = 1 shl 13
}

/** A payload that does not follow docs/protocol.md. */
class ProtocolException(message: String) : Exception(message)
