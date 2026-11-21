// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.eq

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.reinanbr.soundpower.SoundPowerApplication
import com.reinanbr.soundpower.engine.EngineFeatures
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.engine.RootlessOptions
import com.reinanbr.soundpower.eq.EqRepository
import com.reinanbr.soundpower.eq.EqStatus
import com.reinanbr.soundpower.model.AudioSettings
import com.reinanbr.soundpower.model.BandLayout
import com.reinanbr.soundpower.model.Biquad
import com.reinanbr.soundpower.model.Compressor
import com.reinanbr.soundpower.model.EqMode
import com.reinanbr.soundpower.model.EqProfile
import com.reinanbr.soundpower.model.Limiter
import com.reinanbr.soundpower.model.ParametricBand
import com.reinanbr.soundpower.model.Ranges
import com.reinanbr.soundpower.model.ResponseCurve
import com.reinanbr.soundpower.model.Stereo
import com.reinanbr.soundpower.model.Tone
import com.reinanbr.soundpower.protocol.BandChannel
import com.reinanbr.soundpower.ui.prefs.ThemeMode
import com.reinanbr.soundpower.ui.prefs.UiPrefsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.max

/** The response curve as the screen draws it. */
class CurveUi(
    val frequencies: DoubleArray,
    /** What every channel gets. */
    val db: FloatArray,
    /** The preamp the effect applies, auto-preamp included. */
    val effectivePreampDb: Float,
    /** The curve of each channel that has bands of its own, that plus [db]. */
    val channels: Map<BandChannel, FloatArray> = emptyMap(),
)

data class EqUiState(
    val settings: AudioSettings? = null,
    val curve: CurveUi? = null,
    val selectedBand: Int = 0,
    val effectError: String? = null,
    val persistError: String? = null,
    /** Null until the first poll returns. */
    val status: EqStatus? = null,
    val engine: EngineMode? = null,
    val rootless: RootlessOptions = RootlessOptions(),
) {
    /** What the engine can do: an older module cannot route bands, Android's effect cannot mix channels. */
    val features: EngineFeatures get() = EngineFeatures.of(engine, status)
}

/**
 * The equalizer screen and its tools. Every value is clamped and snapped to the model's ranges
 * here; the curve is computed by the model, off the main thread.
 */
class EqViewModel(private val eq: EqRepository, private val prefs: UiPrefsStore) : ViewModel() {
    init {
        eq.start()
    }

    private val status = flow<EqStatus?> {
        emit(null)
        while (true) {
            emit(eq.readStatus())
            delay(STATUS_INTERVAL_MS)
        }
    }

    private val curve = eq.settings.filterNotNull().map { it.eq }.distinctUntilChanged().map<EqProfile, CurveUi?> { profile ->
        val c = profile.curve()
        val channels = c.routedChannels.associateWith { c.db(FREQUENCIES, it) }
        CurveUi(FREQUENCIES, c.db(FREQUENCIES), c.effectivePreampDb(profile.preampDb, profile.autoPreamp), channels)
    }.flowOn(Dispatchers.Default).onStart { emit(null) }

    private val selected = MutableStateFlow(0)

    private val errors = combine(eq.effectError, eq.persistError) { a, b -> a to b }

    private class Engine(val status: EqStatus?, val mode: EngineMode?, val options: RootlessOptions)

    private val engine = combine(status, prefs.engine, prefs.rootless, ::Engine)

    val state: StateFlow<EqUiState> =
        combine(eq.settings, curve, selected, errors, engine) { settings, curve, sel, (effectError, persistError), e ->
            val count = settings?.eq?.parametricBands?.size ?: 0
            EqUiState(settings, curve, sel.coerceIn(0, max(0, count - 1)), effectError, persistError, e.status, e.mode, e.options)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EqUiState())

    val theme: StateFlow<ThemeMode> = prefs.theme.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { prefs.setTheme(mode) }
    }

    fun setRootless(options: RootlessOptions) {
        viewModelScope.launch { prefs.setRootless(options) }
    }

    // ---- Main screen -----------------------------------------------------------------------

    fun setEnabled(enabled: Boolean) = eq.update { it.copy(enabled = enabled) }

    fun setMode(mode: EqMode) = updateEq { it.withMode(mode) }

    fun setPreamp(db: Float) = updateEq { it.copy(preampDb = Ranges.snapDb(db, Ranges.PREAMP_DB)) }

    fun setAutoPreamp(on: Boolean) = updateEq { it.copy(autoPreamp = on) }

    fun setBass(db: Float) = updateTone { it.copy(bassDb = Ranges.snapDb(db, Ranges.TONE_DB)) }

    fun setTreble(db: Float) = updateTone { it.copy(trebleDb = Ranges.snapDb(db, Ranges.TONE_DB)) }

    fun setGraphicGain(index: Int, db: Float) = updateEq { it.copy(graphic = it.graphic.withGain(index, db)) }

    private var beforeReset: EqProfile? = null

    /**
     * Flat bands, tone at 0 dB, preamp 0 dB. Layouts, band types and locked bands stay; the
     * previous state is kept for [undoReset].
     */
    fun resetEq() = updateEq { p ->
        beforeReset = p
        p.copy(
            graphic = p.graphic.flat(),
            parametric = p.parametric?.map { if (it.locked) it else it.copy(gainDb = 0f) },
            preampDb = 0f,
            tone = p.tone.copy(bassDb = 0f, trebleDb = 0f),
        )
    }

    /** Puts back what the last [resetEq] changed. */
    fun undoReset() {
        val previous = beforeReset ?: return
        beforeReset = null
        updateEq { previous }
    }

    // ---- Parametric ------------------------------------------------------------------------

    fun selectBand(index: Int) {
        selected.value = index
    }

    fun updateBand(index: Int, transform: (ParametricBand) -> ParametricBand) = updateEq { p ->
        val bands = p.parametricBands
        if (index !in bands.indices) return@updateEq p
        p.copy(parametric = bands.mapIndexed { i, b -> if (i == index) transform(b).normalized() else b })
    }

    /** Moves a band on the curve: frequency always, gain only for types that use it; locked bands stay. */
    fun dragBand(index: Int, freqHz: Float, gainDb: Float) = updateBand(index) { b ->
        if (b.locked) b else b.copy(freqHz = freqHz, gainDb = if (Biquad.usesGain(b.type)) gainDb else b.gainDb)
    }

    fun addBand() = updateEq { p ->
        val bands = p.parametricBands
        if (bands.size >= EqProfile.MAX_PARAMETRIC_BANDS) return@updateEq p
        // A new band halfway (in octaves) into the widest gap, or at 1 kHz.
        val sorted = bands.map { it.freqHz }.sorted()
        val freq = if (sorted.size < 2) 1000f else {
            val (lo, hi) = sorted.zipWithNext().maxBy { (a, b) -> b / a }
            kotlin.math.sqrt(lo * hi)
        }
        selected.value = bands.size
        p.copy(parametric = bands + ParametricBand(freqHz = freq).normalized())
    }

    fun removeBand(index: Int) = updateEq { p ->
        val bands = p.parametricBands
        if (index !in bands.indices || bands[index].locked) return@updateEq p
        selected.value = (index - 1).coerceAtLeast(0)
        p.copy(parametric = bands.filterIndexed { i, _ -> i != index })
    }

    // ---- Tools -----------------------------------------------------------------------------

    fun setLayout(layout: BandLayout) = updateEq { it.copy(graphic = it.graphic.withLayout(layout)) }

    fun setOverlap(on: Boolean) = updateEq { it.copy(graphic = it.graphic.copy(overlap = on)) }

    fun setTone(transform: (Tone) -> Tone) = updateTone { t ->
        transform(t).let {
            it.copy(
                bassDb = Ranges.snapDb(it.bassDb, Ranges.TONE_DB),
                trebleDb = Ranges.snapDb(it.trebleDb, Ranges.TONE_DB),
                bassFreqHz = it.bassFreqHz.coerceIn(Ranges.BASS_FREQ_HZ),
                trebleFreqHz = it.trebleFreqHz.coerceIn(Ranges.TREBLE_FREQ_HZ),
            )
        }
    }

    fun setStereo(transform: (Stereo) -> Stereo) = eq.update { s ->
        val next = transform(s.stereo)
        s.copy(
            stereo = next.copy(
                balance = round2(next.balance.coerceIn(Ranges.BALANCE)),
                width = round2(next.width.coerceIn(Ranges.WIDTH)),
            ),
        )
    }

    fun setLimiter(transform: (Limiter) -> Limiter) = eq.update { s ->
        val next = transform(s.limiter)
        s.copy(
            limiter = next.copy(
                ceilingDb = Ranges.snapDb(next.ceilingDb, Ranges.LIMITER_CEILING_DB),
                releaseMs = next.releaseMs.coerceIn(Ranges.LIMITER_RELEASE_MS),
            ),
        )
    }

    fun setCompressor(transform: (Compressor) -> Compressor) = eq.update { s ->
        val c = transform(s.compressor)
        s.copy(
            compressor = c.copy(
                thresholdDb = Ranges.snapDb(c.thresholdDb, Ranges.COMP_THRESHOLD_DB),
                ratio = round2(c.ratio.coerceIn(Ranges.COMP_RATIO)),
                attackMs = c.attackMs.coerceIn(Ranges.COMP_ATTACK_MS),
                releaseMs = c.releaseMs.coerceIn(Ranges.COMP_RELEASE_MS),
                kneeDb = Ranges.snapDb(c.kneeDb, Ranges.COMP_KNEE_DB),
                makeupDb = Ranges.snapDb(c.makeupDb, Ranges.COMP_MAKEUP_DB),
            ),
        )
    }

    fun retry() = eq.retry()

    private fun updateEq(transform: (EqProfile) -> EqProfile) = eq.update { it.copy(eq = transform(it.eq)) }

    private fun updateTone(transform: (Tone) -> Tone) = updateEq { it.copy(tone = transform(it.tone)) }

    private fun round2(v: Float) = kotlin.math.round(v * 100f) / 100f

    companion object {
        const val STATUS_INTERVAL_MS = 3_000L

        /** Where the curve is evaluated: 160 log-spaced points over the audible range. */
        val FREQUENCIES: DoubleArray = ResponseCurve.logFrequencies(160)

        val Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as SoundPowerApplication
                EqViewModel(app.container.eq, app.container.uiPrefs)
            }
        }
    }
}
