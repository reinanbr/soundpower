// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.diagnostics

import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.reinanbr.soundpower.BuildConfig
import com.reinanbr.soundpower.SoundPowerApplication
import com.reinanbr.soundpower.diagnostics.Diagnosis
import com.reinanbr.soundpower.diagnostics.DiagnosticsInput
import com.reinanbr.soundpower.diagnostics.DiagnosticsReport
import com.reinanbr.soundpower.diagnostics.DiagnosticsRepository
import com.reinanbr.soundpower.diagnostics.Verdict
import com.reinanbr.soundpower.root.ModuleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticsUi(
    val input: DiagnosticsInput? = null,
    val diagnosis: Diagnosis = Diagnosis(Verdict.Checking, emptyList()),
    /** The slow checks are running. */
    val checking: Boolean = true,
    val restarting: Boolean = false,
    val restartError: ModuleManager.AudioRestart? = null,
    /** App names by package, for the packages the screen shows. */
    val labels: Map<String, String> = emptyMap(),
)

/** A report ready to share, save or copy. */
data class ReportFile(val name: String, val text: String)

/**
 * The diagnostics screen: the slow checks when it opens and on [recheck], the live part every
 * [LIVE_INTERVAL_MS] while it is visible. Everything is judged by [Diagnosis], outside the UI.
 */
class DiagnosticsViewModel(
    private val repo: DiagnosticsRepository,
    private val packages: PackageManager,
) : ViewModel() {
    private val checks = MutableStateFlow<DiagnosticsRepository.Checks?>(null)
    private val local = MutableStateFlow(DiagnosticsUi())
    /** App names by package name; null for names that are not an installed package. */
    private val labels = HashMap<String, String?>()

    /** While new checks run, the live part goes on with the previous ones. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val live = checks.filterNotNull().flatMapLatest { c ->
        flow<DiagnosticsInput?> {
            while (true) {
                emit(repo.read(c))
                delay(LIVE_INTERVAL_MS)
            }
        }
    }.onStart { emit(null) }

    val state: StateFlow<DiagnosticsUi> = combine(live, local, checks) { input, l, c ->
        if (input == null) {
            l.copy(checking = c == null)
        } else {
            val diagnosis = withContext(Dispatchers.Default) { Diagnosis.of(input) }
            l.copy(input = input, diagnosis = diagnosis, checking = c == null, labels = labelsFor(input, diagnosis))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUi())

    init {
        recheck()
    }

    fun recheck() {
        checks.value = null
        viewModelScope.launch { checks.value = repo.checks() }
    }

    fun restartAudio() {
        if (local.value.restarting) return
        local.update { it.copy(restarting = true, restartError = null) }
        viewModelScope.launch {
            val r = repo.restartAudio()
            local.update { it.copy(restarting = false, restartError = r.takeIf { x -> x !is ModuleManager.AudioRestart.Restarted }) }
            recheck()
        }
    }

    fun turnOnLimiter() = repo.turnOnLimiter()

    fun turnOnEqualizer() = repo.turnOnEqualizer()

    fun retry() = repo.retry()

    /** The report of what the screen shows now, with the module's diag.sh and the app's log. */
    suspend fun report(): ReportFile? {
        val s = state.value
        val input = s.input ?: return null
        val (script, log) = repo.extras(input.engine)
        val now = Date()
        val about = DiagnosticsReport.About(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}${if (BuildConfig.DEBUG) ", debug" else ""})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                Build.SUPPORTED_ABIS.joinToString(","),
            time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(now),
        )
        val text = withContext(Dispatchers.Default) { DiagnosticsReport.text(about, input, s.diagnosis, script, log) }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(now)
        return ReportFile("soundpower-diagnostics-$stamp.txt", text)
    }

    /** The app names of the players and effect owners in [diagnosis]; package names stay as they are. */
    private suspend fun labelsFor(input: DiagnosticsInput, diagnosis: Diagnosis): Map<String, String> = withContext(Dispatchers.IO) {
        val names = buildSet {
            diagnosis.streams.forEach { s -> addAll(s.players); s.others.mapNotNullTo(this) { it.owner } }
            diagnosis.unprocessed.mapNotNullTo(this) { it.player }
            input.rootless?.attachments?.mapNotNullTo(this) { it.label }
        }
        names.mapNotNull { name -> label(name)?.let { name to it } }.toMap()
    }

    private fun label(name: String): String? {
        if (name in labels) return labels[name]
        val pkg = name.substringBefore(':')
        val label = if (!pkg.contains('.')) {
            null
        } else {
            try {
                packages.getApplicationLabel(packages.getApplicationInfo(pkg, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
        labels[name] = label
        return label
    }

    companion object {
        const val LIVE_INTERVAL_MS = 2_000L

        val Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as SoundPowerApplication
                DiagnosticsViewModel(app.container.diagnostics, app.packageManager)
            }
        }
    }
}
