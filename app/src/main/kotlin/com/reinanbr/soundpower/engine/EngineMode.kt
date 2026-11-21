// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.engine

import com.reinanbr.soundpower.eq.EqStatus
import com.reinanbr.soundpower.protocol.BandChannel
import com.reinanbr.soundpower.protocol.Capability
import com.reinanbr.soundpower.rootless.RootlessEngine

/** How the app makes the sound. */
enum class EngineMode {
    /** SoundPower's own effect, installed by the Magisk module: every feature, from boot, in every app. */
    ROOT,

    /** Android's Dynamics Processing effect, held by the app's foreground service. */
    ROOTLESS,
}

/** Where the mode without root attaches its effect. */
enum class RootlessScope {
    /** The global output mix (session 0): every app, on devices that allow it. */
    GLOBAL,

    /** The sessions players announce (`ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`). */
    SESSIONS,
}

/** FFT frame of the mode without root: finer frequency steps cost delay. */
enum class Precision(val frameMs: Float) {
    FAST(20f),
    BALANCED(RootlessEngine.DEFAULT_FRAME_MS),
    FINE(80f),
}

data class RootlessOptions(
    val scope: RootlessScope = RootlessScope.GLOBAL,
    val precision: Precision = Precision.BALANCED,
)

/** What the current engine can do; the screens disable the rest and say why. */
data class EngineFeatures(
    val mode: EngineMode?,
    /** Bands routed to the left or right channel. */
    val leftRight: Boolean,
    /** Bands routed to mid or side. */
    val midSide: Boolean,
    /** Stereo width and mono, which mix the channels. */
    val stereoMix: Boolean,
) {
    fun supports(channel: BandChannel): Boolean = when (channel) {
        BandChannel.ALL -> true
        BandChannel.LEFT, BandChannel.RIGHT -> leftRight
        BandChannel.MID, BandChannel.SIDE -> midSide
    }

    companion object {
        /** Android's effect works per channel: it cannot mix them. */
        val ROOTLESS = EngineFeatures(EngineMode.ROOTLESS, leftRight = true, midSide = false, stereoMix = false)

        fun of(mode: EngineMode?, status: EqStatus?): EngineFeatures {
            if (mode == EngineMode.ROOTLESS) return ROOTLESS
            // Until the effect answers, assume it can do everything.
            val caps = status?.capabilities ?: return EngineFeatures(mode, leftRight = true, midSide = true, stereoMix = true)
            val routing = caps and Capability.BAND_CHANNEL != 0
            return EngineFeatures(mode, routing, routing, caps and Capability.STEREO != 0)
        }
    }
}
