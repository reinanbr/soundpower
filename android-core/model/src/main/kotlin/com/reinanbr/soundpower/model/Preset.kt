// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import java.util.UUID

enum class PresetKind { BUILT_IN, USER, AUTOEQ }

/**
 * A named equalizer: the bands of its mode, the preamp and the tone. Built-in and AutoEQ presets
 * ship with the app and never change. A user preset follows the edits made while it is the
 * current one (autosave), unless it is locked.
 */
@Serializable
data class Preset(
    val id: String,
    val name: String,
    val eq: EqProfile,
    val kind: PresetKind = PresetKind.USER,
    val locked: Boolean = false,
    /** AutoEQ: the measurement it was computed from, e.g. "oratory1990 · over-ear". */
    val source: String? = null,
) {
    val mode: EqMode get() = eq.mode

    /** Edits made while it is current do not change it. */
    val readOnly: Boolean get() = kind != PresetKind.USER || locked

    /**
     * [current] with this preset applied: its mode, the bands of that mode, the preamp and the tone.
     * The band configuration (layout, overlap) and the other mode's bands stay, so applying a
     * preset never loses them; graphic gains are resampled onto the current layout.
     */
    fun applyTo(current: EqProfile): EqProfile = when (eq.mode) {
        EqMode.GRAPHIC -> current.copy(
            mode = EqMode.GRAPHIC,
            graphic = current.graphic.copy(gainsDb = eq.graphic.withLayout(current.graphic.layout).gainsDb),
            preampDb = eq.preampDb,
            autoPreamp = eq.autoPreamp,
            tone = eq.tone,
        )
        EqMode.PARAMETRIC -> current.copy(
            mode = EqMode.PARAMETRIC,
            parametric = eq.parametricBands.take(EqProfile.MAX_PARAMETRIC_BANDS),
            preampDb = eq.preampDb,
            autoPreamp = eq.autoPreamp,
            tone = eq.tone,
        )
    }

    /** True while [current] still sounds like this preset. */
    fun matches(current: EqProfile): Boolean = applyTo(current) == current

    companion object {
        const val USER_PREFIX = "user:"

        fun newId(): String = USER_PREFIX + UUID.randomUUID()

        fun user(name: String, eq: EqProfile) = Preset(newId(), name.trim(), eq)
    }
}

/** Presets as a file (share, export, import). */
@Serializable
data class PresetFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val presets: List<Preset>,
) {
    companion object {
        const val FORMAT = "soundpower-presets"
        const val VERSION = 1

        fun encode(presets: List<Preset>): String = AudioSettings.JSON.encodeToString(serializer(), PresetFile(presets = presets))

        /** The presets in [text], as new unlocked user presets; throws [IllegalArgumentException] if it is not a preset file. */
        fun decode(text: String): List<Preset> {
            val file = try {
                AudioSettings.JSON.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("not a SoundPower preset file", e)
            }
            require(file.format == FORMAT) { "not a SoundPower preset file" }
            require(file.version <= VERSION) { "made by a newer version of SoundPower" }
            return file.presets.map { it.copy(id = Preset.newId(), kind = PresetKind.USER, locked = false, eq = it.eq.normalized()) }
        }
    }
}

/** Values from a file brought back to the app's ranges. */
internal fun EqProfile.normalized(): EqProfile = copy(
    graphic = graphic.copy(gainsDb = List(graphic.layout.size) { Ranges.snapDb(graphic.gain(it), Ranges.BAND_GAIN_DB) }),
    parametric = parametric?.take(EqProfile.MAX_PARAMETRIC_BANDS)?.map { it.normalized() },
    preampDb = Ranges.snapDb(preampDb, Ranges.PREAMP_DB),
    tone = tone.copy(
        bassDb = Ranges.snapDb(tone.bassDb, Ranges.TONE_DB),
        trebleDb = Ranges.snapDb(tone.trebleDb, Ranges.TONE_DB),
        bassFreqHz = tone.bassFreqHz.coerceIn(Ranges.BASS_FREQ_HZ),
        trebleFreqHz = tone.trebleFreqHz.coerceIn(Ranges.TREBLE_FREQ_HZ),
    ),
)

/**
 * Presets that ship with the app: graphic curves on the 10-band layout, resampled onto the user's
 * layout when applied. Starting points, not corrections for a particular device (that is AutoEQ).
 */
object BuiltInPresets {
    const val PREFIX = "builtin:"

    //                                31.5  63  125  250  500   1k   2k   4k   8k  16k
    val ALL: List<Preset> = listOf(
        graphic("flat", "Flat", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        graphic("bass_boost", "Bass boost", 6, 5, 3.5, 1.5, 0, 0, 0, 0, 0, 0),
        graphic("deep_bass", "Deep bass", 8, 6.5, 3, 0, -0.5, 0, 0, 0, 0, 0),
        graphic("bass_cut", "Less bass", -6, -5, -3.5, -1.5, 0, 0, 0, 0, 0, 0),
        graphic("treble_boost", "Treble boost", 0, 0, 0, 0, 0, 0.5, 1.5, 3, 4.5, 5.5),
        graphic("treble_cut", "Less treble", 0, 0, 0, 0, 0, -0.5, -1.5, -3, -4.5, -5.5),
        graphic("v_shape", "V-shape", 5, 4, 2, 0, -2, -2, 0, 2, 4, 5),
        graphic("vocal", "Vocal", -2, -2, -1, 0.5, 2, 3, 3, 2, 0.5, -0.5),
        graphic("speech", "Speech", -6, -4, -1, 1, 2.5, 3, 3, 2, 0, -2),
        graphic("rock", "Rock", 4.5, 3.5, 2, -0.5, -1.5, -0.5, 1.5, 3, 3.5, 3.5),
        graphic("pop", "Pop", -1, 0.5, 2, 3, 3, 1.5, 0, -0.5, -0.5, -1),
        graphic("jazz", "Jazz", 3, 2.5, 1.5, 1, -1, -1, 0, 1, 2, 2.5),
        graphic("classical", "Classical", 3.5, 3, 2, 1, 0, 0, -0.5, 1, 2, 3),
        graphic("electronic", "Electronic", 5, 4.5, 1.5, 0, -1.5, 0.5, 1, 1.5, 3.5, 4),
        graphic("hip_hop", "Hip-hop", 5.5, 5, 2.5, 1, -1, -0.5, 0.5, 0, 1.5, 2),
        graphic("acoustic", "Acoustic", 2.5, 2, 1.5, 0.5, 1, 1, 1.5, 2, 2, 1.5),
        graphic("late_night", "Late night", 6, 4.5, 2, 0, -0.5, 0, 0, 1, 3, 4.5),
        graphic("small_speakers", "Small speakers", -6, -4, 0, 2, 1, 0, 1, 2, 2, 1),
    )

    private fun graphic(key: String, name: String, vararg gains: Number) = Preset(
        id = PREFIX + key,
        name = name,
        eq = EqProfile(graphic = GraphicBands(BandLayout.TEN, gains.map { it.toFloat() })),
        kind = PresetKind.BUILT_IN,
    )
}
