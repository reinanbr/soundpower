// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.diagnostics

import com.reinanbr.soundpower.effect.EffectController.Connection
import com.reinanbr.soundpower.root.AudioFlingerDump.ChainEffect
import com.reinanbr.soundpower.root.EffectDump
import java.util.Locale

/**
 * The plain-text report a user shares when asking for help: what the screen shows plus the raw
 * material behind it. Written in English for whoever reads it, whatever the app's language.
 */
object DiagnosticsReport {
    /** Facts about the app and the device that the Android side supplies. */
    data class About(val appVersion: String, val device: String, val time: String)

    fun text(
        about: About,
        input: DiagnosticsInput,
        diagnosis: Diagnosis,
        moduleScript: List<String>?,
        appLog: List<String>,
    ): String = buildString {
        line("SoundPower diagnostics, ${about.time}")
        line("app ${about.appVersion}, engine ${input.engine?.name?.lowercase() ?: "not chosen"}")
        line("device ${about.device}")
        line("verdict ${verdict(diagnosis.verdict)}")

        section("Findings")
        if (diagnosis.findings.isEmpty()) line("none")
        diagnosis.findings.forEach { line("[${it.severity.name.lowercase()}] ${finding(it)}") }

        input.settings?.let { s ->
            section("Settings")
            line("equalizer ${onOff(s.enabled)}, limiter ${onOff(s.limiter.enabled)}, direct volume ${onOff(input.directVolume)}")
            input.players?.let { p ->
                line(
                    "players: root fallback ${onOff(p.rootFallback)}, advanced tracking ${onOff(p.advancedTracking)}, " +
                        "DUMP permission ${yesNo(p.dumpPermission)}, turned off ${p.turnedOff.sorted().ifEmpty { listOf("none") }.joinToString()}",
                )
                if (p.attached.isNotEmpty()) {
                    line("attached by the app: " + p.attached.entries.joinToString { (session, pkg) -> "$session (${pkg ?: "unknown app"})" })
                }
            }
            line(s.toJson())
        }

        input.setup?.let { setup ->
            section("Setup")
            line("root ${setup.root?.let(::yesNo) ?: "unknown"}")
            line("magisk ${setup.magisk?.let { "${it.versionName} (${it.versionCode})" } ?: "not found"}")
            val m = setup.module
            val installed = m?.installed
            line(
                "module " + when {
                    m == null -> "unknown"
                    installed == null -> "not installed"
                    else -> "${installed.version} (${installed.versionCode})" +
                        (m.staged?.let { ", staged ${it.version} (${it.versionCode})" } ?: "") +
                        (if (m.disabled) ", disabled" else "") + (if (m.removing) ", removing" else "") +
                        (if (m.updateFlag) ", updated since boot" else "")
                },
            )
            input.bundled?.let { line("bundled module ${it.version} (${it.versionCode})") }
            line("effect registered ${yesNo(setup.registered)}")
            line(
                "connection " + when (val c = setup.connection) {
                    null -> "not tried"
                    is Connection.Connected -> "protocol ${c.info.major}.${c.info.minor}, library ${c.info.libVersionName}, " +
                        "capabilities 0x%x".fmt(c.info.capabilities)
                    is Connection.Incompatible -> "incompatible, protocol ${c.info.major}.${c.info.minor}"
                    is Connection.Failed -> "failed: ${c.reason}"
                },
            )
        }

        input.status?.let { s ->
            section("Control instance")
            line(
                "session ${s.sessionId}, state ${s.state}, ${s.sampleRate} Hz, ${s.channels} ch, format 0x%x, %d instances, flags 0x%x"
                    .fmt(s.format, s.instances, s.flags),
            )
        }

        if (input.flinger != null) {
            section("Streams")
            if (diagnosis.streams.isEmpty()) line("none")
            for (st in diagnosis.streams) {
                line(
                    "session ${st.session} on ${st.thread.name} (io ${st.thread.io}, ${st.thread.type}, ${st.thread.sampleRate ?: "?"} Hz, " +
                        "${st.thread.devices ?: "no device"}), ${if (st.playing) "playing" else "idle"}, players ${st.players.ifEmpty { listOf("none") }.joinToString()}",
                )
                st.effect.dump?.let { line("  " + dump(it)) }
                line("  chain: " + st.chain.effects.joinToString(" > ") { effect(it, input.flinger.processes[it.clientPid]) })
                st.others.filter { it.global }.takeIf { it.isNotEmpty() }?.let { g ->
                    line("  output: " + g.joinToString(" > ") { effect(it.effect, it.owner) })
                }
                line("  volume controller: ${st.chain.volumeController?.name ?: "none (the mixer)"}")
            }
            section("Not equalized")
            if (diagnosis.unprocessed.isEmpty()) line("none")
            for (u in diagnosis.unprocessed) {
                line("session ${u.track.session} (${u.player ?: "pid ${u.track.clientPid}"}) on ${u.thread.name} (${u.thread.type}): ${u.reason.name.lowercase()}")
            }
        }

        input.system?.let { sys ->
            section("System")
            if (sys.loadedIn.isEmpty()) line("library loaded by no process")
            sys.loadedIn.forEach { line("library loaded by pid ${it.pid} (${it.service ?: "not an init service"}): ${it.command}") }
            sys.configs.forEach { (path, patched) -> line("$path: ${if (patched) "patched" else "NOT PATCHED"}") }
            line("policy ${sys.policyLoaded?.let { if (it) "loaded" else "NOT LOADED" } ?: "unknown"}")
            if (sys.denials.isEmpty()) line("no SELinux denials")
            sys.denials.forEach { line(it) }
            if (sys.bootLog.isNotEmpty()) {
                line("boot log:")
                sys.bootLog.forEach { line("  $it") }
            }
        }

        input.rootless?.let { r ->
            section("Mode without root")
            line("scope ${input.rootlessScope?.name?.lowercase() ?: "?"}, module registered ${yesNo(input.moduleRegistered)}, battery optimized ${input.batteryOptimized?.let(::yesNo) ?: "unknown"}")
            r.layout?.let { line("layout ${it.bandCount} bands at ${it.sampleRate} Hz, %.1f Hz bins, %.1f ms".fmt(it.binHz, it.latencyMs)) }
            if (r.attachments.isEmpty()) line("no attachment")
            r.attachments.forEach { a ->
                line("session ${a.session} (${a.label ?: "unknown app"}): ${if (a.active) "active" else a.error ?: "pending"}")
            }
        }

        moduleScript?.let {
            section("Module diag.sh")
            it.forEach { l -> line(l) }
        }
        if (appLog.isNotEmpty()) {
            section("App log")
            appLog.forEach { l -> line(l) }
        }
    }

    fun verdict(v: Verdict): String = when (v) {
        Verdict.Checking -> "checking"
        Verdict.Broken -> "not working"
        Verdict.Off -> "equalizer off"
        Verdict.Idle -> "ready, nothing playing"
        is Verdict.Working -> "working, ${v.streams} stream(s)"
    }

    fun finding(f: Finding): String = when (f) {
        is Finding.Setup -> "setup: ${f.step.name.lowercase()}"
        Finding.NoRoot -> "root refused: changes are not saved for the next boot"
        is Finding.ConfigNotPatched -> "not patched: ${f.paths.joinToString()}"
        Finding.LibraryRejected -> "the effects factory could not load the library"
        Finding.LibraryNotLoaded -> "no process has the library loaded"
        Finding.PolicyMissing -> "SELinux type soundpower_data_file not in the policy"
        is Finding.Denials -> "${f.lines.size} SELinux denial(s)"
        Finding.EqOff -> "equalizer off"
        Finding.NothingPlaying -> "nothing playing"
        is Finding.NotProcessed -> "not equalized: session ${f.item.track.session} (${f.item.player ?: "pid ${f.item.track.clientPid}"}), ${f.item.reason.name.lowercase()}"
        is Finding.OtherEffects -> "session ${f.stream.session}: other effects " +
            f.effects.joinToString { effect(it.effect, it.owner) } + (if (f.byApps.isEmpty()) "" else "; by apps ${f.byApps.joinToString()}")
        is Finding.VolumeTaken -> "session ${f.stream.session}: volume controlled by ${effect(f.controller, f.owner)}"
        is Finding.VolumeRestart -> "direct volume control ${if (f.wanted) "on" else "off"} waits for an audio restart"
        is Finding.Clipping -> "session ${f.stream.session}: output peak %+.1f dBFS with the limiter off".fmt(f.peakDb)
        is Finding.HighCpu -> "session ${f.stream.session}: CPU %.1f %% (peak %.1f %%)".fmt(f.averagePercent, f.peakPercent)
        is Finding.InvalidSamples -> "session ${f.stream.session}: non-finite input"
        Finding.RootlessIdle -> "equalizer on but not attached"
        Finding.RootlessWaiting -> "waiting for a player to announce its session"
        is Finding.RootlessFailed -> "session ${f.session} (${f.label ?: "unknown app"}): ${f.error}"
        is Finding.RootlessTaken -> "session ${f.session} (${f.label ?: "unknown app"}): taken over by another app"
        Finding.BothEngines -> "the Magisk module is active too"
        Finding.BatteryOptimized -> "battery optimization is on for the app"
    }

    private fun effect(e: ChainEffect, owner: String?): String = buildString {
        append(e.name.ifEmpty { "effect ${e.id}" })
        val details = listOfNotNull(
            e.implementor,
            if (e.enabled) null else "disabled",
            if (e.controlsVolume) "volume" else null,
            owner?.let { "by $it" },
        )
        if (details.isNotEmpty()) append(" (").append(details.joinToString()).append(')')
    }

    private fun dump(d: EffectDump): String = buildString {
        append("SoundPower ${d.libVersion}, ${d.state}, ${d.sampleRate} Hz, ${d.channels} ch, format 0x%x, ".fmt(d.format))
        append("cpu %.2f %% peak %.2f %%, limiter %.2f dB, ${d.frames} frames, flags 0x%x".fmt(d.cpuAvgPercent, d.cpuPeakPercent, d.limiterDb, d.flags))
        d.volume?.let { v ->
            append(", volume %.1f/%.1f dB %s (stage %.1f dB), peak in %.1f out %.1f dBFS".fmt(
                v.leftDb, v.rightDb, if (v.applied) "applied" else "by mixer", v.stageDb, v.inputPeakDb, v.outputPeakDb,
            ))
        }
    }

    /** Numbers as the reader expects them in English, whatever the phone's language. */
    private fun String.fmt(vararg args: Any?): String = String.format(Locale.ROOT, this, *args)

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    private fun StringBuilder.line(s: String) {
        append(s).append('\n')
    }

    private fun StringBuilder.section(title: String) {
        append("\n== ").append(title).append('\n')
    }
}
