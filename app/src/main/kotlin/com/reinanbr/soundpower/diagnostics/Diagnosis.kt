// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.diagnostics

import com.reinanbr.soundpower.effect.EffectController
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.engine.RootlessScope
import com.reinanbr.soundpower.model.AudioSettings
import com.reinanbr.soundpower.players.Players
import com.reinanbr.soundpower.protocol.Capability
import com.reinanbr.soundpower.protocol.EffectStatus
import com.reinanbr.soundpower.root.AudioFlingerDump
import com.reinanbr.soundpower.root.AudioFlingerDump.ChainEffect
import com.reinanbr.soundpower.root.AudioFlingerDump.EffectChain
import com.reinanbr.soundpower.root.AudioFlingerDump.OutputThread
import com.reinanbr.soundpower.root.AudioFlingerDump.Track
import com.reinanbr.soundpower.root.ModuleProp
import com.reinanbr.soundpower.root.SystemCheck
import com.reinanbr.soundpower.rootless.RootlessEngine
import com.reinanbr.soundpower.setup.SetupReport
import com.reinanbr.soundpower.setup.SetupStep

/** Everything the checks look at, read at one moment. Null fields were not read. */
data class DiagnosticsInput(
    val engine: EngineMode?,
    val settings: AudioSettings?,
    /** The direct volume control preference (root mode). */
    val directVolume: Boolean = false,
    /** Root, Magisk, the module and the connection to the effect (root mode). */
    val setup: SetupReport? = null,
    /** The module the APK bundles. */
    val bundled: ModuleProp? = null,
    /** STATUS of the app's control instance. */
    val status: EffectStatus? = null,
    /** `dumpsys media.audio_flinger` (root). */
    val flinger: AudioFlingerDump? = null,
    val system: SystemCheck? = null,
    val rootless: RootlessEngine.Status? = null,
    val rootlessScope: RootlessScope? = null,
    /** SoundPower's own effect is registered, so the Magisk module is active (public API). */
    val moduleRegistered: Boolean = false,
    /** The app's process: its control instance is not a player's stream. */
    val ownPid: Int = -1,
    /** Android may stop the app in the background to save battery; null if unknown. */
    val batteryOptimized: Boolean? = null,
    val players: PlayerSettings? = null,
)

/** How the app reaches the players, for the report. */
data class PlayerSettings(
    /** Root mode's per-session fallback. */
    val rootFallback: Boolean,
    /** Without root: players found from AudioFlinger's dump. */
    val advancedTracking: Boolean,
    val dumpPermission: Boolean,
    val turnedOff: Set<String>,
    /** Sessions the app attached SoundPower to itself (the fallback), with their players. */
    val attached: Map<Int, String?>,
)

enum class Severity { ERROR, WARNING, INFO }

/** A player's audio session that SoundPower is attached to. */
data class Stream(
    val session: Int,
    /** Names of the processes playing in the session (package names for apps). */
    val players: List<String>,
    val thread: OutputThread,
    val chain: EffectChain,
    /** SoundPower's instance in the chain. */
    val effect: ChainEffect,
    /** A track of the session is playing. */
    val playing: Boolean,
    /** The chain's other effects, in order, then the ones on the whole output (session 0). */
    val others: List<OtherEffect>,
) {
    /** SoundPower controls the volume here (direct volume control can work). */
    val controlsVolume: Boolean get() = chain.volumeController?.id == effect.id
}

data class OtherEffect(
    val effect: ChainEffect,
    /** The process that holds it; for effects the system attaches, audioserver. */
    val owner: String?,
    /** It processes the audio before SoundPower does. */
    val before: Boolean,
    /** It sits on the whole output (session 0) rather than on the player's session. */
    val global: Boolean,
) {
    /** Attached by another app, rather than by the system or the device's vendor configuration. */
    val byApp: Boolean get() = owner != null && owner !in SYSTEM_OWNERS

    companion object {
        val SYSTEM_OWNERS = Players.SYSTEM_PROCESSES
    }
}

/** A player that plays without SoundPower. */
data class Unprocessed(val track: Track, val player: String?, val thread: OutputThread, val reason: Reason) {
    enum class Reason {
        /** A low-latency track: Android lets no processing effect into its session. */
        FAST,

        /** An output that does not mix in software (offloaded, direct, MMAP): effects do not run there. */
        OUTPUT,

        /** A normal output, but no instance is attached (the module's postprocess did not apply). */
        NOT_ATTACHED,
    }
}

/** Something worth knowing, with what to do about it where there is something. */
sealed interface Finding {
    val severity: Severity

    // ---- Root mode: getting the effect to run --------------------------------------------------

    /** Setup is not finished (root, Magisk, module, reboot). */
    data class Setup(val step: SetupStep) : Finding {
        override val severity get() = if (step == SetupStep.REBOOT) Severity.WARNING else Severity.ERROR
    }

    /** The effect answers, but root was refused: changes are not saved for the next boot. */
    data object NoRoot : Finding {
        override val severity get() = Severity.WARNING
    }

    /** These effects configurations are not replaced by the module's patched copies. */
    data class ConfigNotPatched(val paths: List<String>) : Finding {
        override val severity get() = Severity.ERROR
    }

    /** The effects factory tried the library and could not load it. */
    data object LibraryRejected : Finding {
        override val severity get() = Severity.ERROR
    }

    /** No process has the library loaded, although the module is installed and enabled. */
    data object LibraryNotLoaded : Finding {
        override val severity get() = Severity.ERROR
    }

    /** The data directory's SELinux type is not in the policy: the effect cannot read the saved state at boot. */
    data object PolicyMissing : Finding {
        override val severity get() = Severity.WARNING
    }

    data class Denials(val lines: List<String>) : Finding {
        override val severity get() = Severity.WARNING
    }

    // ---- Playback -------------------------------------------------------------------------------

    data object EqOff : Finding {
        override val severity get() = Severity.INFO
    }

    data object NothingPlaying : Finding {
        override val severity get() = Severity.INFO
    }

    data class NotProcessed(val item: Unprocessed) : Finding {
        override val severity get() = Severity.WARNING
    }

    /** Other effects process the same audio; [byApps] names the apps that attached some of them. */
    data class OtherEffects(val stream: Stream, val effects: List<OtherEffect>, val byApps: List<String>) : Finding {
        override val severity get() = if (byApps.isEmpty()) Severity.INFO else Severity.WARNING
    }

    /** Direct volume control is on, but another effect after SoundPower controls this stream's volume. */
    data class VolumeTaken(val stream: Stream, val controller: ChainEffect, val owner: String?) : Finding {
        override val severity get() = Severity.WARNING
    }

    /** The direct volume control preference differs from what the running library decided at load. */
    data class VolumeRestart(val wanted: Boolean) : Finding {
        override val severity get() = Severity.WARNING
    }

    /** The output goes over 0 dBFS with the limiter off: it clips later on. */
    data class Clipping(val stream: Stream, val peakDb: Float) : Finding {
        override val severity get() = Severity.WARNING
    }

    data class HighCpu(val stream: Stream, val averagePercent: Float, val peakPercent: Float) : Finding {
        override val severity get() = Severity.WARNING
    }

    /** The player sent samples that are not numbers; SoundPower silenced them. */
    data class InvalidSamples(val stream: Stream) : Finding {
        override val severity get() = Severity.WARNING
    }

    // ---- Mode without root ----------------------------------------------------------------------

    /** The equalizer is on, but nothing is attached: the service does not run. */
    data object RootlessIdle : Finding {
        override val severity get() = Severity.WARNING
    }

    /** Per-player mode, and no player has announced a session. */
    data object RootlessWaiting : Finding {
        override val severity get() = Severity.INFO
    }

    data class RootlessFailed(val label: String?, val session: Int, val error: String) : Finding {
        override val severity get() = Severity.ERROR
    }

    /** Another app took the effect over on this session. */
    data class RootlessTaken(val label: String?, val session: Int) : Finding {
        override val severity get() = Severity.WARNING
    }

    /** The Magisk module is active too, so the settings are applied twice. */
    data object BothEngines : Finding {
        override val severity get() = Severity.WARNING
    }

    data object BatteryOptimized : Finding {
        override val severity get() = Severity.INFO
    }
}

/** The one-line answer at the top. */
sealed interface Verdict {
    /** Nothing read yet. */
    data object Checking : Verdict

    /** Something prevents the equalizer from working. */
    data object Broken : Verdict

    data object Off : Verdict

    /** Ready, and [streams] players (or the whole output, for 0 in the mode without root) are equalized. */
    data class Working(val streams: Int) : Verdict

    /** Ready, but nothing is playing through it. */
    data object Idle : Verdict
}

data class Diagnosis(
    val verdict: Verdict,
    val findings: List<Finding>,
    /** Root mode: the players' sessions SoundPower is attached to, playing ones first. */
    val streams: List<Stream> = emptyList(),
    /** Root mode: players that play without SoundPower. */
    val unprocessed: List<Unprocessed> = emptyList(),
) {
    companion object {
        const val HIGH_CPU_AVERAGE = 30f
        const val HIGH_CPU_PEAK = 90f

        /** Peaks above this (dBFS) count as clipping; below, rounding. */
        const val CLIP_DB = 0.05f

        fun of(input: DiagnosticsInput): Diagnosis = when (input.engine) {
            null -> Diagnosis(Verdict.Checking, emptyList())
            EngineMode.ROOT -> root(input)
            EngineMode.ROOTLESS -> rootless(input)
        }

        private fun root(input: DiagnosticsInput): Diagnosis {
            val setup = input.setup ?: return Diagnosis(Verdict.Checking, emptyList())
            val step = input.bundled?.let { setup.step(it) } ?: SetupStep.CHECKING
            if (step == SetupStep.CHECKING) return Diagnosis(Verdict.Checking, emptyList())
            val findings = ArrayList<Finding>()
            if (step != SetupStep.READY) findings += Finding.Setup(step)
            if (setup.root == false && step == SetupStep.READY) findings += Finding.NoRoot

            val system = input.system
            val flinger = input.flinger
            if (system != null) {
                val unpatched = system.configs.filterValues { !it }.keys.toList()
                if (unpatched.isNotEmpty()) findings += Finding.ConfigNotPatched(unpatched)
                if (system.policyLoaded == false) findings += Finding.PolicyMissing
                if (system.denials.isNotEmpty()) findings += Finding.Denials(system.denials)
            }
            if (flinger != null && flinger.librariesNotLoaded.any { it.equals("soundpower", ignoreCase = true) }) {
                findings += Finding.LibraryRejected
            } else if (step == SetupStep.NOT_LOADED && system != null && system.loadedIn.isEmpty()) {
                findings += Finding.LibraryNotLoaded
            }

            val streams = flinger?.let { streams(it, input) }.orEmpty()
            val unprocessed = flinger?.let { unprocessed(it, input.ownPid) }.orEmpty()
            val settings = input.settings
            val on = settings?.enabled != false
            if (step == SetupStep.READY) {
                if (!on) findings += Finding.EqOff
                val status = input.status
                val capabilities = (setup.connection as? EffectController.Connection.Connected)?.info?.capabilities ?: 0
                val dvcSupported = capabilities and Capability.DIRECT_VOLUME != 0
                if (dvcSupported && status != null && status.volumeControlClaimed != input.directVolume) {
                    findings += Finding.VolumeRestart(input.directVolume)
                }
                val playing = streams.filter { it.playing }
                for (s in playing) {
                    val byApps = s.others.filter { it.effect.enabled && it.byApp }.mapNotNull { it.owner }.distinct()
                    val enabledOthers = s.others.filter { it.effect.enabled }
                    if (enabledOthers.isNotEmpty()) findings += Finding.OtherEffects(s, enabledOthers, byApps)
                    val controller = s.chain.volumeController
                    if (status?.volumeControlClaimed == true && controller != null && !s.controlsVolume) {
                        findings += Finding.VolumeTaken(s, controller, s.others.firstOrNull { it.effect.id == controller.id }?.owner)
                    }
                    val dump = s.effect.dump ?: continue
                    val peak = dump.volume?.outputPeakDb
                    if (on && settings?.limiter?.enabled == false && peak != null && peak > CLIP_DB) findings += Finding.Clipping(s, peak)
                    if (dump.cpuAvgPercent >= HIGH_CPU_AVERAGE || dump.cpuPeakPercent >= HIGH_CPU_PEAK) {
                        findings += Finding.HighCpu(s, dump.cpuAvgPercent, dump.cpuPeakPercent)
                    }
                    if (dump.flags and EffectStatus.FLAG_NONFINITE_INPUT != 0) findings += Finding.InvalidSamples(s)
                }
                unprocessed.forEach { findings += Finding.NotProcessed(it) }
                if (flinger != null && playing.isEmpty() && unprocessed.isEmpty()) findings += Finding.NothingPlaying
            }
            val verdict = when {
                findings.any { it.severity == Severity.ERROR } -> Verdict.Broken
                !on -> Verdict.Off
                streams.any { it.playing } -> Verdict.Working(streams.count { it.playing })
                else -> Verdict.Idle
            }
            return Diagnosis(verdict, findings.sortedBy { it.severity }, streams, unprocessed)
        }

        private fun rootless(input: DiagnosticsInput): Diagnosis {
            val findings = ArrayList<Finding>()
            val status = input.rootless ?: RootlessEngine.Status()
            val on = input.settings?.enabled != false
            if (!on) findings += Finding.EqOff
            for (a in status.attachments) {
                val error = a.error
                when {
                    a.active || error == null -> {}
                    error == RootlessEngine.LOST_CONTROL -> findings += Finding.RootlessTaken(a.label, a.session)
                    else -> findings += Finding.RootlessFailed(a.label, a.session, error)
                }
            }
            if (on && status.attachments.isEmpty()) {
                findings += if (input.rootlessScope == RootlessScope.SESSIONS) Finding.RootlessWaiting else Finding.RootlessIdle
            }
            if (input.moduleRegistered) findings += Finding.BothEngines
            if (input.batteryOptimized == true) findings += Finding.BatteryOptimized
            val active = status.attachments.filter { it.active }
            val verdict = when {
                findings.any { it.severity == Severity.ERROR } && active.isEmpty() -> Verdict.Broken
                !on -> Verdict.Off
                active.isNotEmpty() -> Verdict.Working(active.count { it.session != RootlessEngine.GLOBAL_SESSION })
                else -> Verdict.Idle
            }
            return Diagnosis(verdict, findings.sortedBy { it.severity })
        }

        /**
         * SoundPower's instances on players' sessions, not the app's control instance: the app's
         * own instances count only on a session with tracks (the per-session fallback).
         */
        private fun streams(flinger: AudioFlingerDump, input: DiagnosticsInput): List<Stream> {
            val controlSession = input.status?.sessionId
            return flinger.soundPowerInstances
                .filter { it.chain.session != controlSession }
                .filter { p -> p.effect.clientPid != input.ownPid || p.thread.tracks.any { it.session == p.chain.session } }
                .map { (thread, chain, effect) ->
                    val tracks = thread.tracks.filter { it.session == chain.session }
                    val position = chain.effects.indexOf(effect)
                    val inChain = chain.effects.mapIndexedNotNull { i, e ->
                        if (e.isSoundPower) null else OtherEffect(e, flinger.processes[e.clientPid], before = i < position, global = false)
                    }
                    val global = thread.chain(0)?.takeIf { chain.session != 0 }?.effects.orEmpty()
                        .map { OtherEffect(it, flinger.processes[it.clientPid], before = false, global = true) }
                    Stream(
                        session = chain.session,
                        players = tracks.mapNotNull { flinger.processes[it.clientPid] ?: "pid ${it.clientPid}" }.distinct(),
                        thread = thread,
                        chain = chain,
                        effect = effect,
                        playing = tracks.any { it.active } || effect.dump?.isActive == true && tracks.isEmpty(),
                        others = inChain + global,
                    )
                }
                .sortedByDescending { it.playing }
        }

        /** Active music tracks of other apps that no enabled SoundPower instance processes. */
        private fun unprocessed(flinger: AudioFlingerDump, ownPid: Int): List<Unprocessed> = flinger.threads.flatMap { thread ->
            val mixProcessed = thread.chain(0)?.soundPower?.enabled == true
            thread.tracks
                .filter { it.active && it.isMusic && it.session != 0 && it.clientPid != ownPid }
                .filter { flinger.processes[it.clientPid] !in OtherEffect.SYSTEM_OWNERS }
                .filter { !mixProcessed && thread.chain(it.session)?.soundPower?.enabled != true }
                .map { track ->
                    val reason = when {
                        !thread.mixesInSoftware -> Unprocessed.Reason.OUTPUT
                        track.fast -> Unprocessed.Reason.FAST
                        else -> Unprocessed.Reason.NOT_ATTACHED
                    }
                    Unprocessed(track, flinger.processes[track.clientPid], thread, reason)
                }
        }
    }
}
