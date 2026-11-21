// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.reinanbr.soundpower.R
import com.reinanbr.soundpower.SoundPowerApplication
import com.reinanbr.soundpower.rootless.RootlessEngine
import com.reinanbr.soundpower.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Holds the equalizer of the mode without root. Android removes an app's audio effects when its
 * process dies, so the process stays in the foreground while the equalizer is on. The service
 * attaches the engine to the global output or to the sessions players announce, and stops itself a
 * few seconds after the equalizer is switched off or the mode changes; a quick off and on keeps
 * the instances.
 */
class RootlessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Sessions players opened, with their package. */
    private val sessions = MutableStateFlow<Map<Int, String?>>(emptyMap())

    private var receiving = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            if (session <= 0) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION ->
                    sessions.update { it + (session to intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)) }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> sessions.update { it - session }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as SoundPowerApplication).container
        if (!goForeground(notification(getString(R.string.rootless_notification_starting)))) {
            stopSelf()
            return
        }
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        // Players are other apps.
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiving = true
        val engine = container.rootless
        val eq = container.eq
        val prefs = container.uiPrefs
        eq.start()

        val wanted = combine(prefs.engine, eq.settings.filterNotNull().map { it.enabled }) { mode, on -> mode == EngineMode.ROOTLESS && on }
        scope.launch {
            wanted.distinctUntilChanged().collectLatest { on ->
                if (!on) {
                    delay(STOP_DELAY_MS)
                    stopSelf()
                }
            }
        }
        scope.launch {
            combine(prefs.rootless, sessions) { o, s -> o to s }.collectLatest { (options, open) ->
                val targets = if (options.scope == RootlessScope.GLOBAL) mapOf(RootlessEngine.GLOBAL_SESSION to null) else open
                engine.configure(targets, options.precision.frameMs)
            }
        }
        scope.launch {
            combine(prefs.rootless, engine.status) { o, st -> o to st }.collect { (options, st) -> showStatus(options, st) }
        }
        // Instances that could not be created (audioserver restarting) are tried again, less and less often.
        scope.launch {
            engine.status.map { st -> st.attachments.any { it.error != null && it.error != RootlessEngine.LOST_CONTROL } }
                .distinctUntilChanged()
                .collectLatest { failing ->
                    var wait = RETRY_FIRST_MS
                    while (failing) {
                        delay(wait)
                        engine.retry()
                        wait = min(wait * 2, RETRY_MAX_MS)
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TURN_OFF) {
            (application as SoundPowerApplication).container.eq.update { it.copy(enabled = false) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (receiving) unregisterReceiver(receiver)
        val container = (application as SoundPowerApplication).container
        // The app's scope outlives the service; the instances go with it.
        container.appScope.launch { container.rootless.stop() }
        super.onDestroy()
    }

    private fun goForeground(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: RuntimeException) {
        // Started from the background where Android does not allow it.
        Log.w(TAG, "cannot run in the foreground", e)
        false
    }

    private fun showStatus(options: RootlessOptions, status: RootlessEngine.Status) {
        val active = status.attachments.filter { it.active }
        val text = when {
            options.scope == RootlessScope.GLOBAL && active.isNotEmpty() -> getString(R.string.rootless_notification_global)
            options.scope == RootlessScope.GLOBAL -> getString(R.string.rootless_notification_failed)
            active.isEmpty() -> getString(R.string.rootless_notification_waiting)
            else -> resources.getQuantityString(R.plurals.rootless_notification_sessions, active.size, active.size)
        }
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.rootless_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.rootless_channel_description)
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val off = PendingIntent.getService(
            this, 1, Intent(this, RootlessService::class.java).setAction(ACTION_TURN_OFF), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.rootless_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.rootless_turn_off), off)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "SoundPower"
        private const val CHANNEL = "rootless"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_TURN_OFF = "com.reinanbr.soundpower.action.TURN_OFF"
        private const val STOP_DELAY_MS = 10_000L
        private const val RETRY_FIRST_MS = 2_000L
        private const val RETRY_MAX_MS = 60_000L

        /** Starts the service; false where Android does not allow it now (the app is in the background). */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, RootlessService::class.java))
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot start the equalizer service", e)
            false
        }
    }
}
