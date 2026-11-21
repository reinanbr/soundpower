// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.effect

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import com.reinanbr.soundpower.protocol.Protocol
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.UUID

/**
 * [EffectTransport] on a real `AudioEffect`. The constructor taking an effect type and
 * implementation UUID and `setParameter/getParameter(byte[], byte[])` are hidden APIs
 * (checked against the SDK stubs and the framework.jar of an Android 12 device), so the
 * class is exempted from the hidden API checks with HiddenApiBypass and used through
 * reflection.
 */
class AudioEffectTransport private constructor(private val effect: AudioEffect) : EffectTransport {
    override fun setParameter(param: ByteArray, value: ByteArray): Int = invoke(Hidden.setParameter, param, value)

    override fun getParameter(param: ByteArray, value: ByteArray): Int = invoke(Hidden.getParameter, param, value)

    override fun release() = effect.release()

    private fun invoke(m: Method, param: ByteArray, value: ByteArray): Int = try {
        m.invoke(effect, param, value) as Int
    } catch (e: InvocationTargetException) {
        // IllegalStateException: the instance was released or never initialised.
        if (e.cause is IllegalStateException) EffectCodes.ERROR_NO_INIT else throw e
    }

    /** The hidden members, looked up once after the exemption. */
    private object Hidden {
        init {
            check(HiddenApiBypass.addHiddenApiExemptions("Landroid/media/audiofx/AudioEffect;")) {
                "could not exempt AudioEffect from the hidden API checks"
            }
        }

        val constructor: Constructor<AudioEffect> = AudioEffect::class.java.getDeclaredConstructor(
            UUID::class.java,
            UUID::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val setParameter: Method = method("setParameter")
        val getParameter: Method = method("getParameter")

        private fun method(name: String): Method =
            AudioEffect::class.java.getDeclaredMethod(name, ByteArray::class.java, ByteArray::class.java)
                .apply { isAccessible = true }
    }

    companion object {
        /**
         * Creates a control instance on a new audio session that no track uses. AudioFlinger
         * attaches such an effect to the first output thread. Throws if the effect is not
         * registered or cannot be created.
         */
        fun open(context: Context): EffectTransport {
            val audio = checkNotNull(context.getSystemService(AudioManager::class.java)) { "no AudioManager" }
            val session = audio.generateAudioSessionId()
            check(session > 0) { "could not allocate an audio session ($session)" }
            val effect = try {
                Hidden.constructor.newInstance(Protocol.EFFECT_TYPE_UUID, Protocol.EFFECT_IMPL_UUID, 0, session)
            } catch (e: InvocationTargetException) {
                // IllegalArgumentException: not registered; UnsupportedOperationException: init failed.
                throw e.cause ?: e
            }
            return AudioEffectTransport(effect)
        }

        /** True if audioserver lists the SoundPower implementation (public API, no root needed). */
        fun isRegistered(): Boolean =
            AudioEffect.queryEffects().orEmpty().any { it.uuid == Protocol.EFFECT_IMPL_UUID }
    }
}
