// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.engine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.reinanbr.soundpower.R
import com.reinanbr.soundpower.effect.SessionInstances
import com.reinanbr.soundpower.SoundPowerApplication
import com.reinanbr.soundpower.model.AudioOutput
import com.reinanbr.soundpower.model.OutputKind
import com.reinanbr.soundpower.players.Players
import com.reinanbr.soundpower.presets.AutoEqSuggestion
import com.reinanbr.soundpower.presets.CurrentPreset
import com.reinanbr.soundpower.rootless.RootlessEngine
import com.reinanbr.soundpower.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Keeps the equalizer working while the app is in the background, for three jobs:
 *
 * - Without root, it holds Android's audio effect: Android removes an app's effects when its
 *   process dies. It attaches the engine to the global output or to the players' sessions.
 * - With root and the per-session fallback, it holds the SoundPower instances the app attaches to
 *   the players' sessions.
 * - With assignments, it applies the preset assigned to an output when music moves there, in
 *   either mode.
 *
 * It runs while either job needs it ([com.reinanbr.soundpower.AppContainer.serviceWanted]) and
 * stops itself a few seconds after neither does; a quick off and on keeps the effect instances.
 */
class EqService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as SoundPowerApplication).container
        createChannels()
        if (!goForeground(notification(getString(R.string.service_notification_starting), null))) {
            stopSelf()
            return
        }
        val engine = container.rootless
        val players = container.players
        val fallback = container.fallback
        val eq = container.eq
        val prefs = container.uiPrefs
        val presets = container.presets
        presets.start()

        scope.launch {
            container.serviceWanted.distinctUntilChanged().collectLatest { wanted ->
                if (!wanted) {
                    delay(STOP_DELAY_MS)
                    stopSelf()
                }
            }
        }
        val eqOn = eq.settings.filterNotNull().map { it.enabled }
        // Android's effect only without root, and only while the equalizer is on.
        val rootlessOn = combine(prefs.engine, eqOn) { mode, on -> mode == EngineMode.ROOTLESS && on }
        val fallbackOn = combine(prefs.engine, eqOn, prefs.rootFallback) { mode, on, f -> mode == EngineMode.ROOT && on && f }
        // The players' sessions, minus the players the user turned off; collected only when needed,
        // since collecting keeps AudioFlinger's dump being read.
        val playerTargets = combine(players.sessions, players.known) { s, k -> Players.targets(s, k.disabled) }
        scope.launch {
            combine(rootlessOn, prefs.rootless) { on, o -> on to o }.collectLatest { (on, options) ->
                when {
                    !on -> engine.configure(emptyMap(), options.precision.frameMs)
                    options.scope == RootlessScope.GLOBAL ->
                        engine.configure(mapOf(RootlessEngine.GLOBAL_SESSION to null), options.precision.frameMs)
                    else -> playerTargets.collect { engine.configure(it, options.precision.frameMs) }
                }
            }
        }
        scope.launch {
            fallbackOn.collectLatest { on ->
                if (on) playerTargets.collect { withContext(Dispatchers.Default) { fallback.configure(it) } } else fallback.stop()
            }
        }
        scope.launch {
            combine(rootlessOn, fallbackOn, prefs.rootless, engine.status, fallback.status) { r, f, o, st, fs -> Engines(r, f, o, st, fs) }
                .combine(combine(presets.current, container.output.output) { p, o -> p to o }) { e, (preset, out) -> Status(e, preset, out) }
                .collect(::show)
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
        scope.launch {
            fallback.status.map { it.failed.isNotEmpty() }.distinctUntilChanged().collectLatest { failing ->
                var wait = RETRY_FIRST_MS
                while (failing) {
                    delay(wait)
                    withContext(Dispatchers.Default) { fallback.retry() }
                    wait = min(wait * 2, RETRY_MAX_MS)
                }
            }
        }
        // AutoEQ suggestions for headphones that connect while the app is in the background.
        scope.launch {
            presets.suggestion.map { it?.takeIf { s -> s.output.name != null } }.distinctUntilChanged { a, b -> a?.output == b?.output }
                .collect { s -> if (s != null) suggest(s) else cancelSuggestion() }
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
        val container = (application as SoundPowerApplication).container
        // The app's scope outlives the service; the instances go with it.
        container.appScope.launch {
            container.rootless.stop()
            container.fallback.stop()
        }
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

    private class Engines(
        val rootless: Boolean,
        val fallback: Boolean,
        val options: RootlessOptions,
        val engine: RootlessEngine.Status,
        val instances: SessionInstances.Status,
    )

    private class Status(val engines: Engines, val preset: CurrentPreset?, val output: AudioOutput)

    private fun show(s: Status) {
        val e = s.engines
        val lines = buildList {
            if (e.rootless) {
                val active = e.engine.attachments.filter { it.active }
                add(
                    when {
                        e.options.scope == RootlessScope.GLOBAL && active.isNotEmpty() -> getString(R.string.rootless_notification_global)
                        e.options.scope == RootlessScope.GLOBAL -> getString(R.string.rootless_notification_failed)
                        active.isEmpty() -> getString(R.string.rootless_notification_waiting)
                        else -> resources.getQuantityString(R.plurals.rootless_notification_sessions, active.size, active.size)
                    },
                )
            }
            if (e.fallback) {
                val n = e.instances.attached.size
                add(if (n == 0) getString(R.string.rootless_notification_waiting) else resources.getQuantityString(R.plurals.rootless_notification_sessions, n, n))
            }
            add(outputName(s.output))
        }
        val title = s.preset?.preset?.name ?: getString(R.string.preset_none)
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(lines.joinToString(" · "), title))
    }

    private fun outputName(o: AudioOutput): String = o.name ?: getString(
        when (o.key.kind) {
            OutputKind.SPEAKER -> R.string.output_speaker
            OutputKind.WIRED -> R.string.output_wired
            OutputKind.BLUETOOTH -> R.string.output_bluetooth
            OutputKind.USB -> R.string.output_usb
            OutputKind.OTHER -> R.string.output_other
        },
    )

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        // The first version's channel, named for the mode without root only.
        nm.deleteNotificationChannel(OLD_CHANNEL)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.service_channel_description)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(SUGGESTION_CHANNEL, getString(R.string.suggestion_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.suggestion_channel_description)
            },
        )
    }

    private fun notification(text: String, title: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val off = PendingIntent.getService(
            this, 1, Intent(this, EqService::class.java).setAction(ACTION_TURN_OFF), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title ?: getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.service_turn_off), off)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Only while the app is not on screen: there, the equalizer page shows the suggestion. */
    private fun suggest(s: AutoEqSuggestion) {
        if ((application as SoundPowerApplication).container.visible.value) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val name = checkNotNull(s.output.name)
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_PRESET_SEARCH, s.entries.first().name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, SUGGESTION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.suggestion_title, name))
            .setContentText(resources.getQuantityString(R.plurals.suggestion_text, s.entries.size, s.entries.size))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(SUGGESTION_ID, n)
    }

    private fun cancelSuggestion() {
        getSystemService(NotificationManager::class.java)?.cancel(SUGGESTION_ID)
    }

    companion object {
        private const val TAG = "SoundPower"
        private const val OLD_CHANNEL = "rootless"
        private const val CHANNEL = "equalizer"
        private const val SUGGESTION_CHANNEL = "suggestions"
        private const val NOTIFICATION_ID = 1
        private const val SUGGESTION_ID = 2
        private const val ACTION_TURN_OFF = "com.reinanbr.soundpower.action.TURN_OFF"
        private const val STOP_DELAY_MS = 10_000L
        private const val RETRY_FIRST_MS = 2_000L
        private const val RETRY_MAX_MS = 60_000L

        /** Starts the service; false where Android does not allow it now (the app is in the background). */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, EqService::class.java))
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot start the equalizer service", e)
            false
        }
    }
}
