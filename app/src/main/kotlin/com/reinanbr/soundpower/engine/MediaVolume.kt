// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The media (music) stream volume, in the system's steps. With direct volume control the effect
 * applies it, but it is still the system's volume: the keys, the system panel and this control
 * all move the same value.
 */
class MediaVolume(context: Context) {
    private val app = context.applicationContext
    private val audio: AudioManager = checkNotNull(app.getSystemService(AudioManager::class.java))

    val steps: IntRange get() = audio.getStreamMinVolume(STREAM)..audio.getStreamMaxVolume(STREAM)

    /**
     * The current step. Every volume change is broadcast at once (the settings the system keeps
     * volumes in are written later); only the system can send that action.
     */
    val level: Flow<Int> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(audio.getStreamVolume(STREAM))
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(VOLUME_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED)
        trySend(audio.getStreamVolume(STREAM))
        awaitClose { app.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    fun set(step: Int) {
        audio.setStreamVolume(STREAM, step.coerceIn(steps), 0)
    }

    private companion object {
        const val STREAM = AudioManager.STREAM_MUSIC

        /** `AudioManager.VOLUME_CHANGED_ACTION`, hidden but stable since Android 4. */
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    }
}
