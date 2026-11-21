// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.model

import com.reinanbr.soundpower.protocol.Band
import com.reinanbr.soundpower.protocol.BandChannel
import com.reinanbr.soundpower.protocol.BandType
import com.reinanbr.soundpower.protocol.CompressorParams
import com.reinanbr.soundpower.protocol.LimiterParams
import com.reinanbr.soundpower.protocol.StereoParams
import com.reinanbr.soundpower.protocol.ToneParams
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/** Value ranges the app offers. The effect accepts wider ones (docs/protocol.md). */
object Ranges {
    val BAND_GAIN_DB = -15f..15f
    val PREAMP_DB = -15f..15f
    val TONE_DB = -15f..15f
    val BASS_FREQ_HZ = 30f..400f
    val TREBLE_FREQ_HZ = 1500f..16000f
    val FREQ_HZ = 20f..20000f
    val Q = 0.1f..12f
    val BALANCE = -1f..1f
    val WIDTH = 0f..2f
    val LIMITER_CEILING_DB = -12f..0f
    val LIMITER_RELEASE_MS = 5f..500f
    val COMP_THRESHOLD_DB = -60f..0f
    val COMP_RATIO = 1f..20f
    val COMP_ATTACK_MS = 0.1f..200f
    val COMP_RELEASE_MS = 5f..2000f
    val COMP_KNEE_DB = 0f..24f
    val COMP_MAKEUP_DB = 0f..24f

    /** Gains and levels are kept to this resolution, so stored values stay short and exact. */
    const val DB_STEP = 0.1f

    fun snapDb(db: Float, range: ClosedFloatingPointRange<Float>): Float =
        ((db.coerceIn(range) / DB_STEP).roundToInt() * DB_STEP).let { (it * 10f).roundToInt() / 10f }
}

enum class EqMode { GRAPHIC, PARAMETRIC }

/** Gains of a [BandLayout]. */
@Serializable
data class GraphicBands(
    val layout: BandLayout = BandLayout.TEN,
    val gainsDb: List<Float> = List(layout.size) { 0f },
    val overlap: Boolean = false,
) {
    /** One gain per band (missing gains read as 0, extra ones are ignored). */
    fun gain(index: Int): Float = gainsDb.getOrElse(index) { 0f }

    fun withGain(index: Int, db: Float): GraphicBands {
        val g = List(layout.size) { if (it == index) Ranges.snapDb(db, Ranges.BAND_GAIN_DB) else gain(it) }
        return copy(gainsDb = g)
    }

    /** Switches to [next], resampling the current curve at the new frequencies. */
    fun withLayout(next: BandLayout): GraphicBands {
        if (next == layout) return this
        val from = layout.frequencies
        val values = List(from.size) { gain(it) }
        val gains = next.frequencies.map { Ranges.snapDb(BandLayout.interpolate(from, values, it), Ranges.BAND_GAIN_DB) }
        return copy(layout = next, gainsDb = gains)
    }

    val isFlat: Boolean get() = gainsDb.all { it == 0f }

    fun flat(): GraphicBands = copy(gainsDb = List(layout.size) { 0f })

    /** Peaking bands at the layout's frequencies, Q from the spacing (see [BandLayout.q]). */
    fun bands(): List<Band> = layout.frequencies.mapIndexed { i, f ->
        Band(BandType.PEAKING, enabled = true, freqHz = f, gainDb = gain(i), q = layout.q(i, overlap))
    }
}

/** One band of the parametric equalizer. */
@Serializable
data class ParametricBand(
    val type: BandType = BandType.PEAKING,
    val freqHz: Float = 1000f,
    val gainDb: Float = 0f,
    val q: Float = 1f,
    val enabled: Boolean = true,
    /** The channels the band filters. */
    val channel: BandChannel = BandChannel.ALL,
    /** Index into the app's band palette; null picks one from the band's position. */
    val color: Int? = null,
    /** A locked band ignores drags and edits until unlocked. */
    val locked: Boolean = false,
) {
    fun band(): Band = Band(type, enabled, freqHz, gainDb, q, channel)

    /** Clamped to the ranges the app offers. */
    fun normalized(): ParametricBand = copy(
        freqHz = freqHz.coerceIn(Ranges.FREQ_HZ),
        gainDb = Ranges.snapDb(gainDb, Ranges.BAND_GAIN_DB),
        q = q.coerceIn(Ranges.Q),
        color = color?.takeIf { it in 0 until BAND_COLORS },
    )

    companion object {
        /** Colours in the app's band palette. */
        const val BAND_COLORS = 8

        fun of(band: Band) = ParametricBand(band.type, band.freqHz, band.gainDb, band.q, band.enabled, band.channel)
    }
}

/** Bass and treble shelves. */
@Serializable
data class Tone(
    val enabled: Boolean = true,
    val bassDb: Float = 0f,
    val bassFreqHz: Float = 100f,
    val trebleDb: Float = 0f,
    val trebleFreqHz: Float = 8000f,
) {
    fun params() = ToneParams(enabled, bassDb, bassFreqHz, trebleDb, trebleFreqHz)
}

/** Balance, width and mono. */
@Serializable
data class Stereo(
    val balance: Float = 0f,
    val width: Float = 1f,
    val mono: Boolean = false,
) {
    fun params() = StereoParams(mono, balance, width)
}

@Serializable
data class Limiter(
    val enabled: Boolean = true,
    val ceilingDb: Float = -1f,
    val releaseMs: Float = 50f,
) {
    /** The lookahead is not exposed in the app; the effect's default is kept. */
    fun params() = LimiterParams(ceilingDb = ceilingDb, releaseMs = releaseMs)
}

@Serializable
data class Compressor(
    val enabled: Boolean = false,
    val thresholdDb: Float = -18f,
    val ratio: Float = 3f,
    val attackMs: Float = 10f,
    val releaseMs: Float = 150f,
    val kneeDb: Float = 6f,
    val makeupDb: Float = 0f,
) {
    fun params() = CompressorParams(enabled, thresholdDb, ratio, attackMs, releaseMs, kneeDb, makeupDb)
}

/**
 * The equalizer itself: what a preset holds. The graphic and parametric bands are kept side by
 * side, so switching modes never loses either.
 */
@Serializable
data class EqProfile(
    val mode: EqMode = EqMode.GRAPHIC,
    val graphic: GraphicBands = GraphicBands(),
    /** Null until parametric mode is first opened; it then starts as a copy of the graphic bands. */
    val parametric: List<ParametricBand>? = null,
    val preampDb: Float = 0f,
    val autoPreamp: Boolean = true,
    val tone: Tone = Tone(),
) {
    /** The parametric bands, or the graphic bands as parametric ones if there are none yet. */
    val parametricBands: List<ParametricBand>
        get() = parametric ?: graphic.bands().map(ParametricBand::of)

    fun withMode(next: EqMode): EqProfile =
        if (next == EqMode.PARAMETRIC) copy(mode = next, parametric = parametricBands) else copy(mode = next)

    /** The bands of the current mode. */
    fun bands(): List<Band> = when (mode) {
        EqMode.GRAPHIC -> graphic.bands()
        EqMode.PARAMETRIC -> parametricBands.map { it.band() }
    }

    fun curve(sampleRate: Double = ResponseCurve.DEFAULT_SAMPLE_RATE) = ResponseCurve(bands(), tone, sampleRate)

    companion object {
        const val MAX_PARAMETRIC_BANDS = 32
    }
}

/** Everything the app stores: the equalizer plus the global switch, stereo and dynamics. */
@Serializable
data class AudioSettings(
    /** The global on/off switch (the effect's A/B bypass). */
    val enabled: Boolean = true,
    val eq: EqProfile = EqProfile(),
    val stereo: Stereo = Stereo(),
    val limiter: Limiter = Limiter(),
    val compressor: Compressor = Compressor(),
) {
    /** What the effect needs to sound like this. */
    fun effect(): EqSettings = EqSettings(
        bypass = !enabled,
        autoPreamp = eq.autoPreamp,
        preampDb = eq.preampDb,
        bands = eq.bands(),
        limiter = limiter.params(),
        limiterEnabled = limiter.enabled,
        tone = eq.tone.params(),
        stereo = stereo.params(),
        compressor = compressor.params(),
    )

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        val DEFAULT = AudioSettings()

        /** Lenient on input (unknown keys from newer versions are ignored), explicit on output. */
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }

        fun fromJson(text: String): AudioSettings = JSON.decodeFromString(serializer(), text)

        /**
         * Settings of the first app version, which only had a 10-band graphic EQ: its bands become
         * the graphic EQ if they sit on the 10-band layout, and the parametric EQ otherwise.
         */
        fun fromLegacy(old: EqSettings): AudioSettings {
            val freqs = old.bands.map { it.freqHz }
            val eq = if (freqs == BandLayout.TEN.frequencies && old.bands.all { it.type == BandType.PEAKING }) {
                EqProfile(graphic = GraphicBands(BandLayout.TEN, old.bands.map { Ranges.snapDb(it.gainDb, Ranges.BAND_GAIN_DB) }))
            } else {
                EqProfile(
                    mode = EqMode.PARAMETRIC,
                    parametric = old.bands.take(EqProfile.MAX_PARAMETRIC_BANDS)
                        .map { ParametricBand.of(it).normalized() },
                )
            }
            return AudioSettings(
                enabled = !old.bypass,
                eq = eq.copy(preampDb = Ranges.snapDb(old.preampDb, Ranges.PREAMP_DB), autoPreamp = old.autoPreamp),
                limiter = Limiter(ceilingDb = old.limiter.ceilingDb, releaseMs = old.limiter.releaseMs),
            )
        }
    }
}
