// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.diagnostics

import com.reinanbr.soundpower.effect.EffectController.Connection
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.engine.RootlessScope
import com.reinanbr.soundpower.model.AudioSettings
import com.reinanbr.soundpower.model.Limiter
import com.reinanbr.soundpower.protocol.Capability
import com.reinanbr.soundpower.protocol.EffectStatus
import com.reinanbr.soundpower.protocol.ProtocolInfo
import com.reinanbr.soundpower.root.AudioFlingerDump
import com.reinanbr.soundpower.root.AudioFlingerDump.ChainEffect
import com.reinanbr.soundpower.root.AudioFlingerDump.EffectChain
import com.reinanbr.soundpower.root.AudioFlingerDump.OutputThread
import com.reinanbr.soundpower.root.AudioFlingerDump.Track
import com.reinanbr.soundpower.root.EffectDump
import com.reinanbr.soundpower.root.MagiskInfo
import com.reinanbr.soundpower.root.ModuleProp
import com.reinanbr.soundpower.root.ModuleState
import com.reinanbr.soundpower.root.SystemCheck
import com.reinanbr.soundpower.rootless.RootlessEngine
import com.reinanbr.soundpower.setup.SetupReport
import com.reinanbr.soundpower.setup.SetupStep
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiagnosisTest {
    private val bundled = ModuleProp("v0.4.0", 400)
    private val dvcCaps = Capability.PARAMETRIC_EQ or Capability.LIMITER or Capability.DIRECT_VOLUME
    private fun ready(capabilities: Int = dvcCaps) = SetupReport(
        root = true,
        magisk = MagiskInfo("31.1", 31100),
        module = ModuleState(installed = bundled),
        registered = true,
        connection = Connection.Connected(ProtocolInfo(1, 2, 0x000400, 32, capabilities)),
    )

    private fun status(claimed: Boolean = false) = EffectStatus(
        sampleRate = 48000, channels = 2, format = 5, state = 1, cpuAvgPercent = 0f, cpuPeakPercent = 0f,
        instances = 2, sessionId = CONTROL_SESSION, flags = if (claimed) EffectStatus.FLAG_VOLUME_CTRL else 0,
        limiterGainReductionDb = 0f, framesProcessed = 0,
    )

    private fun dump(session: Int, active: Boolean = true, peakOut: Float = -3f, cpu: Float = 5f, flags: Int = 0) = EffectDump(
        libVersion = "0.4.0", sessionId = session, ioHandle = 13, state = if (active) "active" else "initialized",
        sampleRate = 48000, channels = 2, format = 5, cpuAvgPercent = cpu, cpuPeakPercent = cpu * 2, limiterDb = 0f,
        frames = 1000, flags = flags, instances = 2,
        volume = EffectDump.StreamVolume(0f, 0f, applied = false, stageDb = 0f, inputPeakDb = -6f, outputPeakDb = peakOut),
    )

    private fun sp(id: Int, session: Int, enabled: Boolean = true, client: Int = AUDIOSERVER, d: EffectDump? = dump(session)) =
        ChainEffect(id, "SoundPower", "SoundPower contributors", AudioFlingerDump.SOUNDPOWER_UUID, enabled, 0x250, client, d)

    private fun other(id: Int, name: String, enabled: Boolean = true, volume: Boolean = true, client: Int = FX_APP) =
        ChainEffect(id, name, "NXP Software Ltd.", null, enabled, if (volume) 0x250 else 0x290, client)

    private fun flinger(vararg threads: OutputThread) = AudioFlingerDump(
        threads.toList(),
        mapOf(AUDIOSERVER to "audioserver", PLAYER to "com.example.player", FX_APP to "com.example.soundfx", OWN to "com.reinanbr.soundpower", 900 to "com.example.game"),
        listOf("soundpower"),
        emptyList(),
    )

    private fun mixer(tracks: List<Track>, chains: List<EffectChain>, type: String = "MIXER", io: Int = 13) =
        OutputThread(io, "AudioOut_D", type, 48000, "0x8 (AUDIO_DEVICE_OUT_WIRED_HEADPHONE)", tracks, chains)

    private fun track(session: Int, client: Int = PLAYER, active: Boolean = true, fast: Boolean = false, stream: Int? = 3) =
        Track(70 + session % 10, active, fast, client, session, stream)

    private val control = EffectChain(CONTROL_SESSION, listOf(sp(75, CONTROL_SESSION, enabled = false, client = OWN, d = dump(CONTROL_SESSION, active = false))))

    private fun root(
        flinger: AudioFlingerDump?,
        settings: AudioSettings = AudioSettings(),
        status: EffectStatus? = status(),
        directVolume: Boolean = false,
        setup: SetupReport = ready(),
        system: SystemCheck? = SystemCheck(configs = mapOf("/vendor/etc/audio_effects.xml" to true), policyLoaded = true),
    ) = Diagnosis.of(
        DiagnosticsInput(
            engine = EngineMode.ROOT, settings = settings, directVolume = directVolume, setup = setup, bundled = bundled,
            status = status, flinger = flinger, system = system, ownPid = OWN,
        ),
    )

    /** The S9 case: another app's effects around SoundPower, one of them controlling the volume. */
    @Test
    fun anotherAppsEffectsOnThePlayer() {
        val chain = EffectChain(
            1449,
            listOf(other(11, "Equalizer"), other(19, "Dynamic Bass Boost"), other(35, "Insert Preset Reverb", enabled = false), sp(83, 1449, d = dump(1449, peakOut = 3f)), other(27, "Virtualizer")),
        )
        val d = root(
            flinger(mixer(listOf(track(1449)), listOf(control, chain))),
            settings = AudioSettings(limiter = Limiter(enabled = false)),
            status = status(claimed = true),
            directVolume = true,
        )
        assertEquals(Verdict.Working(1), d.verdict)
        val s = d.streams.single()
        assertEquals(1449, s.session)
        assertEquals(listOf("com.example.player"), s.players)
        assertFalse(s.controlsVolume)
        assertEquals(listOf(true, true, true, false), s.others.map { it.before })

        val others = d.findings.filterIsInstance<Finding.OtherEffects>().single()
        assertEquals(listOf("Equalizer", "Dynamic Bass Boost", "Virtualizer"), others.effects.map { it.effect.name })
        assertEquals(listOf("com.example.soundfx"), others.byApps)
        assertEquals(Severity.WARNING, others.severity)
        val taken = d.findings.filterIsInstance<Finding.VolumeTaken>().single()
        assertEquals("Virtualizer", taken.controller.name)
        assertEquals("com.example.soundfx", taken.owner)
        assertEquals(3f, d.findings.filterIsInstance<Finding.Clipping>().single().peakDb)
        assertTrue(d.findings.none { it is Finding.VolumeRestart || it is Finding.NothingPlaying })
    }

    @Test
    fun soundPowerControllingTheVolumeAndTheLimiterOnAreFine() {
        val chain = EffectChain(1449, listOf(other(11, "Equalizer", client = AUDIOSERVER, volume = false), sp(83, 1449, d = dump(1449, peakOut = 3f))))
        val d = root(flinger(mixer(listOf(track(1449)), listOf(chain))), status = status(claimed = true), directVolume = true)
        val s = d.streams.single()
        assertTrue(s.controlsVolume)
        assertTrue(d.findings.none { it is Finding.VolumeTaken || it is Finding.Clipping })
        // A system effect is only worth a note.
        assertEquals(Severity.INFO, d.findings.filterIsInstance<Finding.OtherEffects>().single().severity)
    }

    @Test
    fun directVolumeWaitingForARestart() {
        val f = flinger(mixer(emptyList(), listOf(control)))
        assertEquals(Finding.VolumeRestart(true), root(f, directVolume = true).findings.filterIsInstance<Finding.VolumeRestart>().single())
        assertEquals(Finding.VolumeRestart(false), root(f, status = status(claimed = true)).findings.filterIsInstance<Finding.VolumeRestart>().single())
        // A library without direct volume control never claims it: nothing to restart for.
        assertTrue(root(f, directVolume = true, setup = ready(capabilities = Capability.LIMITER)).findings.none { it is Finding.VolumeRestart })
    }

    @Test
    fun playersWithoutSoundPower() {
        val fast = mixer(listOf(track(2001, fast = true)), emptyList())
        val offload = mixer(listOf(track(2009)), emptyList(), type = "OFFLOAD", io = 29)
        val missing = mixer(
            listOf(
                track(2017),
                track(2025, client = 900, stream = 1), // not music
                track(0, client = AUDIOSERVER), // the system's own
                track(2033, client = OWN),
                track(2041, active = false),
            ),
            emptyList(),
            io = 37,
        )
        val d = root(flinger(fast, offload, missing))
        assertEquals(
            listOf(Unprocessed.Reason.FAST, Unprocessed.Reason.OUTPUT, Unprocessed.Reason.NOT_ATTACHED),
            d.unprocessed.map { it.reason },
        )
        assertEquals(listOf(2001, 2009, 2017), d.unprocessed.map { it.track.session })
        assertEquals(3, d.findings.count { it is Finding.NotProcessed })
        assertEquals(Verdict.Idle, d.verdict)
        assertTrue(d.findings.none { it is Finding.NothingPlaying })
    }

    @Test
    fun aSoundPowerOnTheOutputMixCoversEveryTrack() {
        val mix = EffectChain(0, listOf(sp(90, 0)))
        val d = root(flinger(mixer(listOf(track(2001)), listOf(mix))))
        assertTrue(d.unprocessed.isEmpty())
    }

    @Test
    fun nothingPlaying() {
        val idle = EffectChain(1449, listOf(sp(83, 1449, d = dump(1449, active = false))))
        val d = root(flinger(mixer(listOf(track(1449, active = false)), listOf(control, idle))))
        assertEquals(Verdict.Idle, d.verdict)
        assertTrue(Finding.NothingPlaying in d.findings)
        assertFalse(d.streams.single().playing)
    }

    @Test
    fun heavyProcessingAndInvalidInput() {
        val chain = EffectChain(1449, listOf(sp(83, 1449, d = dump(1449, cpu = 40f, flags = EffectStatus.FLAG_NONFINITE_INPUT))))
        val d = root(flinger(mixer(listOf(track(1449)), listOf(chain))))
        assertEquals(40f, d.findings.filterIsInstance<Finding.HighCpu>().single().averagePercent)
        assertEquals(1, d.findings.count { it is Finding.InvalidSamples })
    }

    @Test
    fun equalizerOff() {
        val chain = EffectChain(1449, listOf(sp(83, 1449, d = dump(1449, peakOut = 2f))))
        val d = root(flinger(mixer(listOf(track(1449)), listOf(chain))), settings = AudioSettings(enabled = false, limiter = Limiter(enabled = false)))
        assertEquals(Verdict.Off, d.verdict)
        assertTrue(Finding.EqOff in d.findings)
        // Bypassed audio is not SoundPower's to clip.
        assertTrue(d.findings.none { it is Finding.Clipping })
    }

    @Test
    fun setupProblems() {
        val notLoaded = ready().copy(connection = null, registered = false)
        val d = root(null, status = null, setup = notLoaded, system = SystemCheck(configs = mapOf("/odm/etc/audio_effects.xml" to false), policyLoaded = false, denials = listOf("avc: denied")))
        assertEquals(Verdict.Broken, d.verdict)
        assertEquals(Finding.Setup(SetupStep.NOT_LOADED), d.findings.first())
        assertTrue(Finding.LibraryNotLoaded in d.findings)
        assertTrue(Finding.ConfigNotPatched(listOf("/odm/etc/audio_effects.xml")) in d.findings)
        assertTrue(Finding.PolicyMissing in d.findings)
        assertTrue(d.findings.any { it is Finding.Denials })
        // Errors come first.
        assertEquals(d.findings.sortedBy { it.severity }, d.findings)

        val rejected = flinger().copy(librariesLoaded = emptyList(), librariesNotLoaded = listOf("soundpower"))
        val r = root(rejected, status = null, setup = notLoaded)
        assertTrue(Finding.LibraryRejected in r.findings)
        assertFalse(Finding.LibraryNotLoaded in r.findings)

        val reboot = ready().copy(module = ModuleState(installed = bundled, updateFlag = true))
        val pending = root(flinger(mixer(emptyList(), listOf(control))), setup = reboot)
        assertEquals(Severity.WARNING, pending.findings.filterIsInstance<Finding.Setup>().single().severity)
        assertEquals(Verdict.Idle, pending.verdict)

        val noRoot = SetupReport(root = false, registered = true, connection = ready().connection)
        val n = root(null, setup = noRoot, system = null)
        assertTrue(Finding.NoRoot in n.findings)
        assertEquals(Verdict.Idle, n.verdict)
    }

    @Test
    fun checking() {
        assertEquals(Verdict.Checking, Diagnosis.of(DiagnosticsInput(engine = null, settings = null)).verdict)
        assertEquals(Verdict.Checking, Diagnosis.of(DiagnosticsInput(engine = EngineMode.ROOT, settings = null)).verdict)
    }

    @Test
    fun withoutRoot() {
        fun of(vararg a: RootlessEngine.Attachment, scope: RootlessScope = RootlessScope.GLOBAL, module: Boolean = false, battery: Boolean? = false) =
            Diagnosis.of(
                DiagnosticsInput(
                    engine = EngineMode.ROOTLESS, settings = AudioSettings(), rootless = RootlessEngine.Status(a.toList()),
                    rootlessScope = scope, moduleRegistered = module, batteryOptimized = battery,
                ),
            )
        assertEquals(Verdict.Working(0), of(RootlessEngine.Attachment(RootlessEngine.GLOBAL_SESSION, null, true, null)).verdict)
        assertEquals(Verdict.Working(2), of(RootlessEngine.Attachment(7, "a", true, null), RootlessEngine.Attachment(8, "b", true, null), scope = RootlessScope.SESSIONS).verdict)

        val failed = of(RootlessEngine.Attachment(RootlessEngine.GLOBAL_SESSION, null, false, "ERROR_NO_INIT"))
        assertEquals(Verdict.Broken, failed.verdict)
        assertIs<Finding.RootlessFailed>(failed.findings.single())
        val taken = of(RootlessEngine.Attachment(9, "x", false, RootlessEngine.LOST_CONTROL), RootlessEngine.Attachment(7, "a", true, null))
        assertIs<Finding.RootlessTaken>(taken.findings.single())

        assertEquals(listOf<Finding>(Finding.RootlessIdle), of().findings)
        assertEquals(listOf<Finding>(Finding.RootlessWaiting), of(scope = RootlessScope.SESSIONS).findings)
        assertTrue(Finding.BothEngines in of(module = true).findings)
        assertTrue(Finding.BatteryOptimized in of(battery = true).findings)
    }

    @Test
    fun reportNamesWhatMatters() {
        val chain = EffectChain(1449, listOf(sp(83, 1449), other(27, "Virtualizer")))
        val input = DiagnosticsInput(
            engine = EngineMode.ROOT, settings = AudioSettings(), setup = ready(), bundled = bundled, status = status(claimed = true),
            flinger = flinger(mixer(listOf(track(1449)), listOf(control, chain))), system = SystemCheck(policyLoaded = true), ownPid = OWN,
            directVolume = true,
        )
        // The report is English whatever the phone's language, decimal points included.
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("pt-BR"))
        val text = try {
            DiagnosticsReport.text(
                DiagnosticsReport.About("0.4.0 (400)", "test device", "2026-11-21 12:00:00"),
                input, Diagnosis.of(input), moduleScript = listOf("== SoundPower diagnostics"), appLog = listOf("I app: started"),
            )
        } finally {
            Locale.setDefault(saved)
        }
        for (expected in listOf(
            "verdict working, 1 stream(s)",
            "[warning] session 1449: volume controlled by Virtualizer (NXP Software Ltd., volume, by com.example.soundfx)",
            "connection protocol 1.2, library 0.4.0",
            "chain: SoundPower (SoundPower contributors, volume, by audioserver) > Virtualizer",
            "volume controller: Virtualizer",
            "cpu 5.00 % peak 10.00 %",
            "== Module diag.sh",
            "I app: started",
        )) {
            assertTrue(expected in text, "missing \"$expected\" in:\n$text")
        }
    }

    private companion object {
        const val CONTROL_SESSION = 1569
        const val AUDIOSERVER = 24582
        const val PLAYER = 11541
        const val FX_APP = 2925
        const val OWN = 11543
    }
}
