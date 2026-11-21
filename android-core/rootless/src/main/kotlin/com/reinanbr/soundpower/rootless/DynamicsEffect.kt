// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.rootless

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import com.reinanbr.soundpower.model.DynamicsCompressor
import com.reinanbr.soundpower.model.DynamicsLimiter
import com.reinanbr.soundpower.model.DynamicsPlan

/**
 * One Dynamics Processing instance on an audio session, the calls [RootlessEngine] makes on it.
 * Every call is a binder transaction to audioserver; they throw a [RuntimeException] when it
 * refuses (another app took control, the instance died with audioserver, it was released).
 */
interface DynamicsEffect {
    fun setEnabled(enabled: Boolean)

    fun setInputGain(channel: Int, db: Float)

    fun setBand(channel: Int, band: Int, cutoffHz: Float, gainDb: Float)

    fun setCompressor(channel: Int, compressor: DynamicsCompressor)

    fun setLimiter(channel: Int, limiter: DynamicsLimiter)

    /** False while another app with a higher priority controls the effect. */
    val hasControl: Boolean

    fun release()

    fun interface Factory {
        /** An instance on [session] (0: the global output mix) already set to [plan]. Throws if it cannot be created. */
        fun create(session: Int, plan: DynamicsPlan): DynamicsEffect
    }
}

/** [DynamicsEffect] on `android.media.audiofx.DynamicsProcessing` (public API since Android 9). */
class AndroidDynamicsEffect private constructor(private val dp: DynamicsProcessing) : DynamicsEffect {
    override fun setEnabled(enabled: Boolean) {
        val code = dp.setEnabled(enabled)
        if (code != AudioEffect.SUCCESS) throw IllegalStateException("enabling the effect failed ($code)")
    }

    override fun setInputGain(channel: Int, db: Float) = dp.setInputGainbyChannel(channel, db)

    override fun setBand(channel: Int, band: Int, cutoffHz: Float, gainDb: Float) =
        dp.setPreEqBandByChannelIndex(channel, band, DynamicsProcessing.EqBand(true, cutoffHz, gainDb))

    override fun setCompressor(channel: Int, compressor: DynamicsCompressor) = dp.setMbcByChannelIndex(channel, mbc(compressor))

    override fun setLimiter(channel: Int, limiter: DynamicsLimiter) = dp.setLimiterByChannelIndex(channel, limiter(limiter))

    override val hasControl: Boolean get() = dp.hasControl()

    override fun release() = dp.release()

    companion object {
        /**
         * Higher than any other app's, so this app keeps control of an instance it shares (an
         * effect of one type exists once per session); AudioFlinger hands control over only to a
         * strictly higher priority.
         */
        private const val PRIORITY = Int.MAX_VALUE

        /** No noise gate: with an expander ratio of 1 the effect leaves quiet signals alone. */
        private const val GATE_DB = -90f
        private const val EXPANDER_RATIO = 1f

        val FACTORY = DynamicsEffect.Factory { session, plan -> AndroidDynamicsEffect(create(session, plan)) }

        /**
         * The frequency-domain variant with a pre-EQ of one band per layout band, a one-band
         * compressor and the limiter; the post-EQ is not used. The configuration carries the whole
         * plan, so the instance starts with the right sound.
         */
        private fun create(session: Int, plan: DynamicsPlan): DynamicsProcessing {
            val layout = plan.layout
            val builder = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                DynamicsPlan.CHANNELS,
                true, layout.bandCount,
                true, 1,
                false, 0,
                true,
            ).setPreferredFrameDuration(layout.frameMs)
            for (ch in 0 until DynamicsPlan.CHANNELS) {
                val eq = DynamicsProcessing.Eq(true, true, layout.bandCount)
                val gains = plan.bandGainDb[ch]
                for (b in 0 until layout.bandCount) eq.setBand(b, DynamicsProcessing.EqBand(true, layout.cutoffsHz[b], gains[b]))
                builder.setInputGainByChannelIndex(ch, plan.inputGainDb[ch])
                builder.setPreEqByChannelIndex(ch, eq)
                builder.setMbcByChannelIndex(ch, mbc(plan.compressor))
                builder.setLimiterByChannelIndex(ch, limiter(plan.limiter))
            }
            val dp = DynamicsProcessing(PRIORITY, session, builder.build())
            try {
                val code = dp.setEnabled(plan.enabled)
                if (code != AudioEffect.SUCCESS) throw IllegalStateException("enabling the effect failed ($code)")
            } catch (e: RuntimeException) {
                dp.release()
                throw e
            }
            return dp
        }

        private fun mbc(c: DynamicsCompressor) = DynamicsProcessing.Mbc(true, c.enabled, 1).apply {
            setBand(
                0,
                DynamicsProcessing.MbcBand(
                    true, c.cutoffHz, c.attackMs, c.releaseMs, c.ratio, c.thresholdDb, c.kneeDb,
                    GATE_DB, EXPANDER_RATIO, 0f, c.postGainDb,
                ),
            )
        }

        /** Both channels in link group 0: they limit together, which keeps the stereo image. */
        private fun limiter(l: DynamicsLimiter) =
            DynamicsProcessing.Limiter(true, l.enabled, 0, l.attackMs, l.releaseMs, l.ratio, l.thresholdDb, 0f)
    }
}
