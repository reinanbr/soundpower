// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Payload codecs. Decoders follow the effect's rules: a payload may be longer than the layout
 * this version knows (a newer minor version appended fields), never shorter; unknown flag bits
 * are ignored. Values are not clamped here; the effect clamps them.
 */
internal object Wire {
    fun writer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    fun reader(bytes: ByteArray, minSize: Int, what: String): ByteBuffer {
        if (bytes.size < minSize) throw ProtocolException("$what needs $minSize bytes, got ${bytes.size}")
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }
}

/** `MASTER` (0x0100). */
data class MasterParams(
    val bypass: Boolean = false,
    val autoPreamp: Boolean = true,
    val preampDb: Float = 0f,
    /** Since 1.1; a 1.0 effect ignores the flag and keeps limiting. */
    val limiterEnabled: Boolean = true,
    /**
     * Direct volume control, since 1.2. The effect reads it only when its library is loaded, so a
     * change takes effect after the audio HAL restarts ([EffectStatus.volumeControlClaimed] tells
     * what the running library decided). Older effects ignore it.
     */
    val directVolume: Boolean = false,
) {
    fun encode(): ByteArray {
        val flags = (if (bypass) FLAG_BYPASS else 0) or (if (autoPreamp) FLAG_AUTO_PREAMP else 0) or
            (if (limiterEnabled) 0 else FLAG_LIMITER_OFF) or (if (directVolume) FLAG_DIRECT_VOLUME else 0)
        return Wire.writer(SIZE).putInt(flags).putFloat(preampDb).array()
    }

    companion object {
        const val SIZE = 8
        const val FLAG_BYPASS = 1 shl 0
        const val FLAG_AUTO_PREAMP = 1 shl 1
        const val FLAG_LIMITER_OFF = 1 shl 2
        const val FLAG_DIRECT_VOLUME = 1 shl 3

        fun decode(bytes: ByteArray): MasterParams {
            val r = Wire.reader(bytes, SIZE, "MASTER")
            val flags = r.getInt()
            return MasterParams(
                bypass = flags and FLAG_BYPASS != 0,
                autoPreamp = flags and FLAG_AUTO_PREAMP != 0,
                preampDb = r.getFloat(),
                limiterEnabled = flags and FLAG_LIMITER_OFF == 0,
                directVolume = flags and FLAG_DIRECT_VOLUME != 0,
            )
        }
    }
}

/** Filter types (`enum sp_band_type`). */
enum class BandType(val code: Int) {
    PEAKING(0),
    LOW_SHELF(1),
    HIGH_SHELF(2),
    LOW_PASS(3),
    HIGH_PASS(4),
    NOTCH(5),
    BAND_PASS(6);

    companion object {
        fun fromCode(code: Int): BandType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The channels a band filters (`sp_band_channel`, protocol 1.1). Mid and side are (L+R)/2 and
 * (L−R)/2; a 1.0 effect applies every band to all channels.
 */
enum class BandChannel(val code: Int) {
    ALL(0),
    LEFT(1),
    RIGHT(2),
    MID(3),
    SIDE(4);

    companion object {
        fun fromCode(code: Int): BandChannel? = entries.firstOrNull { it.code == code }
    }
}

/** One EQ band (`sp_band_t`). */
data class Band(
    val type: BandType,
    val enabled: Boolean,
    val freqHz: Float,
    val gainDb: Float,
    val q: Float,
    val channel: BandChannel = BandChannel.ALL,
)

/** `EQ_STATE` (0x0200): a count, then exactly that many 16-byte bands. */
data class EqState(val bands: List<Band>) {
    init {
        require(bands.size <= Protocol.MAX_BANDS) { "at most ${Protocol.MAX_BANDS} bands" }
    }

    fun encode(): ByteArray {
        val w = Wire.writer(HEADER_SIZE + BAND_SIZE * bands.size).putInt(bands.size)
        for (b in bands) {
            w.put(b.type.code.toByte()).put(if (b.enabled) 1 else 0).put(b.channel.code.toByte()).put(0)
            w.putFloat(b.freqHz).putFloat(b.gainDb).putFloat(b.q)
        }
        return w.array()
    }

    companion object {
        const val HEADER_SIZE = 4
        const val BAND_SIZE = 16

        fun decode(bytes: ByteArray): EqState {
            val count = Wire.reader(bytes, HEADER_SIZE, "EQ_STATE").getInt()
            if (count < 0 || count > Protocol.MAX_BANDS) throw ProtocolException("EQ_STATE has $count bands")
            val r = Wire.reader(bytes, HEADER_SIZE + BAND_SIZE * count, "EQ_STATE with $count bands")
            r.position(HEADER_SIZE)
            val bands = List(count) { i ->
                val code = r.get().toInt() and 0xff
                val type = BandType.fromCode(code) ?: throw ProtocolException("band $i has unknown type $code")
                val enabled = r.get().toInt() != 0
                val channelCode = r.get().toInt() and 0xff
                val channel = BandChannel.fromCode(channelCode) ?: throw ProtocolException("band $i has unknown channel $channelCode")
                r.get() // reserved
                Band(type, enabled, r.getFloat(), r.getFloat(), r.getFloat(), channel)
            }
            return EqState(bands)
        }
    }
}

/** `LIMITER` (0x0300). */
data class LimiterParams(
    val ceilingDb: Float = -1f,
    val lookaheadMs: Float = 1.5f,
    val releaseMs: Float = 50f,
) {
    fun encode(): ByteArray = Wire.writer(SIZE).putFloat(ceilingDb).putFloat(lookaheadMs).putFloat(releaseMs).array()

    companion object {
        const val SIZE = 12

        fun decode(bytes: ByteArray): LimiterParams {
            val r = Wire.reader(bytes, SIZE, "LIMITER")
            return LimiterParams(r.getFloat(), r.getFloat(), r.getFloat())
        }
    }
}

/** `TONE` (0x0400, since 1.1): bass and treble shelves after the EQ bands. */
data class ToneParams(
    val enabled: Boolean = true,
    val bassDb: Float = 0f,
    val bassFreqHz: Float = Protocol.TONE_BASS_FREQ_DEFAULT_HZ,
    val trebleDb: Float = 0f,
    val trebleFreqHz: Float = Protocol.TONE_TREBLE_FREQ_DEFAULT_HZ,
) {
    fun encode(): ByteArray = Wire.writer(SIZE).putInt(if (enabled) FLAG_ENABLED else 0)
        .putFloat(bassDb).putFloat(bassFreqHz).putFloat(trebleDb).putFloat(trebleFreqHz).array()

    companion object {
        const val SIZE = 20
        const val FLAG_ENABLED = 1 shl 0

        fun decode(bytes: ByteArray): ToneParams {
            val r = Wire.reader(bytes, SIZE, "TONE")
            val flags = r.getInt()
            return ToneParams(flags and FLAG_ENABLED != 0, r.getFloat(), r.getFloat(), r.getFloat(), r.getFloat())
        }
    }
}

/** `STEREO` (0x0500, since 1.1): balance, mid/side width and mono downmix. */
data class StereoParams(
    val mono: Boolean = false,
    /** -1 = left only, 0 = centre, +1 = right only. */
    val balance: Float = 0f,
    /** 0 = mono, 1 = unchanged, 2 = side doubled. */
    val width: Float = 1f,
) {
    fun encode(): ByteArray =
        Wire.writer(SIZE).putInt(if (mono) FLAG_MONO else 0).putFloat(balance).putFloat(width).array()

    companion object {
        const val SIZE = 12
        const val FLAG_MONO = 1 shl 0

        fun decode(bytes: ByteArray): StereoParams {
            val r = Wire.reader(bytes, SIZE, "STEREO")
            val flags = r.getInt()
            return StereoParams(flags and FLAG_MONO != 0, r.getFloat(), r.getFloat())
        }
    }
}

/** `COMPRESSOR` (0x0600, since 1.1). */
data class CompressorParams(
    val enabled: Boolean = false,
    val thresholdDb: Float = -18f,
    val ratio: Float = 3f,
    val attackMs: Float = 10f,
    val releaseMs: Float = 150f,
    val kneeDb: Float = 6f,
    val makeupDb: Float = 0f,
) {
    fun encode(): ByteArray = Wire.writer(SIZE).putInt(if (enabled) FLAG_ENABLED else 0)
        .putFloat(thresholdDb).putFloat(ratio).putFloat(attackMs).putFloat(releaseMs)
        .putFloat(kneeDb).putFloat(makeupDb).array()

    companion object {
        const val SIZE = 28
        const val FLAG_ENABLED = 1 shl 0

        fun decode(bytes: ByteArray): CompressorParams {
            val r = Wire.reader(bytes, SIZE, "COMPRESSOR")
            val flags = r.getInt()
            return CompressorParams(
                flags and FLAG_ENABLED != 0,
                r.getFloat(), r.getFloat(), r.getFloat(), r.getFloat(), r.getFloat(), r.getFloat(),
            )
        }
    }
}

/** `PROTOCOL_INFO` (0x0001, read-only). */
data class ProtocolInfo(
    val major: Int,
    val minor: Int,
    /** `(major << 16) | (minor << 8) | patch` of the library. */
    val libVersion: Int,
    val maxBands: Int,
    val capabilities: Int,
) {
    val libVersionName: String
        get() = "${libVersion ushr 16}.${(libVersion ushr 8) and 0xff}.${libVersion and 0xff}"

    /** A controller must not send parameters to an effect with another major version. */
    val isCompatible: Boolean get() = major == Protocol.VERSION_MAJOR

    companion object {
        const val SIZE = 16

        fun decode(bytes: ByteArray): ProtocolInfo {
            val r = Wire.reader(bytes, SIZE, "PROTOCOL_INFO")
            val major = r.getShort().toInt() and 0xffff
            val minor = r.getShort().toInt() and 0xffff
            return ProtocolInfo(major, minor, r.getInt(), r.getInt(), r.getInt())
        }
    }
}

/** `STATUS` (0x0002, read-only): the instance that answered, plus process-wide counters. */
data class EffectStatus(
    val sampleRate: Int,
    val channels: Int,
    /** Android `audio_format_t`: 0x1 PCM 16-bit, 0x5 PCM float. */
    val format: Int,
    val state: Int,
    val cpuAvgPercent: Float,
    val cpuPeakPercent: Float,
    val instances: Int,
    val sessionId: Int,
    val flags: Int,
    val limiterGainReductionDb: Float,
    val framesProcessed: Long,
) {
    val bypassed: Boolean get() = flags and FLAG_BYPASSED != 0

    /** The running library claims volume control (direct volume control was on when it loaded). */
    val volumeControlClaimed: Boolean get() = flags and FLAG_VOLUME_CTRL != 0

    /** An enabled instance in the effect's process applies its stream's volume right now. */
    val volumeApplied: Boolean get() = flags and FLAG_VOLUME_APPLIED != 0

    companion object {
        const val SIZE = 48
        const val FLAG_BYPASSED = 1 shl 0
        const val FLAG_DUPLICATE_SESSION = 1 shl 1
        const val FLAG_NONFINITE_INPUT = 1 shl 2
        const val FLAG_VOLUME_CTRL = 1 shl 3 // since 1.2
        const val FLAG_VOLUME_APPLIED = 1 shl 4 // since 1.2

        const val STATE_UNINITIALIZED = 0
        const val STATE_INITIALIZED = 1
        const val STATE_ACTIVE = 2
        const val STATE_DISABLING = 3

        fun decode(bytes: ByteArray): EffectStatus {
            val r = Wire.reader(bytes, SIZE, "STATUS")
            return EffectStatus(
                sampleRate = r.getInt(),
                channels = r.getInt(),
                format = r.getInt(),
                state = r.getInt(),
                cpuAvgPercent = r.getFloat(),
                cpuPeakPercent = r.getFloat(),
                instances = r.getInt(),
                sessionId = r.getInt(),
                flags = r.getInt(),
                limiterGainReductionDb = r.getFloat(),
                framesProcessed = r.getLong(),
            )
        }
    }
}
