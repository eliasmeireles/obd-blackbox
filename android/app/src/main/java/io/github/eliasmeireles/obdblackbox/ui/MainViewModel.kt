package io.github.eliasmeireles.obdblackbox.ui

import io.github.eliasmeireles.obdblackbox.domain.vehicle.VehicleProfile
import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import io.github.eliasmeireles.obdblackbox.data.elm.ProbeResult
import io.github.eliasmeireles.obdblackbox.data.elm.FoundAdapter
import io.github.eliasmeireles.obdblackbox.data.logs.LogLevel
import io.github.eliasmeireles.obdblackbox.data.video.RecentRecording
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import io.github.eliasmeireles.obdblackbox.data.video.SavedVideo
import android.content.Intent
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.eliasmeireles.obdblackbox.AppContainer
import io.github.eliasmeireles.obdblackbox.ObdBlackBoxApp
import io.github.eliasmeireles.obdblackbox.data.SourceKind
import io.github.eliasmeireles.obdblackbox.data.SourceStatus
import io.github.eliasmeireles.obdblackbox.data.StallSimulator
import io.github.eliasmeireles.obdblackbox.data.bridge.BluetoothTarget
import io.github.eliasmeireles.obdblackbox.data.bridge.DiagnosticsReader
import io.github.eliasmeireles.obdblackbox.data.bridge.DiagnosticResult
import io.github.eliasmeireles.obdblackbox.data.elm.ReceivedDiagnostic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import io.github.eliasmeireles.obdblackbox.data.bridge.DtcReader
import io.github.eliasmeireles.obdblackbox.data.bridge.DtcReading
import io.github.eliasmeireles.obdblackbox.data.elm.FinderState
import io.github.eliasmeireles.obdblackbox.data.upload.UploadStatus
import io.github.eliasmeireles.obdblackbox.session.DashboardState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Follows whichever source/session is active, so switching simulation on or off updates the whole screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val monitor = container.monitor
    private fun <T> Flow<T>.keep(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    val state: StateFlow<DashboardState> = monitor.flatMapLatest { it.session.state }.keep(monitor.value.session.state.value)
    val sourceStatus: StateFlow<SourceStatus> = monitor.flatMapLatest { it.source.status }.keep(monitor.value.source.status.value)
    val diagnostics: StateFlow<ReceivedDiagnostic?> = monitor.flatMapLatest { (it.source as? DiagnosticsReader)?.diagnostics ?: flowOf(null) }.keep(null)
    val supportsDiagnostics = monitor.map { it.source is DiagnosticsReader }.keep(monitor.value.source is DiagnosticsReader)
    private val _diagnosticBusy = MutableStateFlow(false)
    val diagnosticBusy: StateFlow<Boolean> = _diagnosticBusy
    private val _diagnosticResult = MutableStateFlow<String?>(null)
    val diagnosticResult: StateFlow<String?> = _diagnosticResult
    val dtcs: StateFlow<DtcReading?> = monitor.flatMapLatest { (it.source as? DtcReader)?.dtcs ?: flowOf(null) }.keep(null)
    val canSimulateStall: StateFlow<Boolean> = monitor.map { it.source is StallSimulator }.keep(monitor.value.source is StallSimulator)
    val canReadDtcs: StateFlow<Boolean> = monitor.map { it.source is DtcReader }.keep(monitor.value.source is DtcReader)
    val simulationEnabled: StateFlow<Boolean> =
        monitor.map { it.source.kind == SourceKind.SIMULATION }.keep(monitor.value.source.kind == SourceKind.SIMULATION)
    val uploadStatus: StateFlow<UploadStatus> = container.uploader.status
    val video = container.blackBox.state
    private val _videos = MutableStateFlow<List<SavedVideo>>(emptyList())
    val videos: StateFlow<List<SavedVideo>> = _videos.asStateFlow()

    init {
        // The menu shows how many clips are saved, so read them once at start (the Vídeos screen keeps it fresh).
        refreshVideos()
    }

    private val _recent = MutableStateFlow<List<RecentRecording>>(emptyList())
    val recent: StateFlow<List<RecentRecording>> = _recent.asStateFlow()

    fun refreshVideos() {
        viewModelScope.launch(Dispatchers.IO) {
            _videos.value = container.savedVideos.list()
            _recent.value = container.recentRecordings.list()
        }
    }

    fun deleteVideo(video: SavedVideo) {
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = container.savedVideos.delete(video)
            container.log.log(LogLevel.INFO, "video", "video deleted from gallery by the user: ${video.name}" + if (deleted) "" else " (failed)")
            _videos.value = container.savedVideos.list()
        }
    }

    fun saveRecent(recording: RecentRecording) {
        viewModelScope.launch(Dispatchers.IO) {
            if (container.savedVideos.list().any { it.name == recording.galleryName }) return@launch
            runCatching { container.recentRecordings.saveToGallery(recording) }
                .onSuccess { container.log.log(LogLevel.INFO, "video", "recent recording saved to gallery: ${recording.id}") }
                .onFailure { container.log.log(LogLevel.ERROR, "video", "could not save ${recording.id}: ${it.message}") }
            _videos.value = container.savedVideos.list()
        }
    }

    /** Copies every recent recording not yet in the gallery (same name = already saved). */
    fun saveAllRecent() {
        viewModelScope.launch(Dispatchers.IO) {
            val inGallery = container.savedVideos.list().map { it.name }.toSet()
            val pending = container.recentRecordings.list().filter { it.galleryName !in inGallery }
            val saved = pending.count { recording -> runCatching { container.recentRecordings.saveToGallery(recording) }.isSuccess }
            container.log.log(LogLevel.INFO, "video", "save all: $saved of ${pending.size} recent recordings saved to gallery")
            refreshVideos()
        }
    }

    /** Deletes every finished recent recording; the gallery copies are not touched. */
    fun deleteAllRecent() {
        viewModelScope.launch(Dispatchers.IO) {
            val recent = container.recentRecordings.list()
            val deleted = recent.count { container.recentRecordings.delete(it) }
            container.log.log(LogLevel.INFO, "video", "delete all: $deleted of ${recent.size} recent recordings deleted by the user")
            refreshVideos()
        }
    }

    fun playRecent(recording: RecentRecording): Intent = container.recentRecordings.viewIntent(recording)

    fun shareRecent(recording: RecentRecording): Intent = container.recentRecordings.shareIntent(recording)

    fun shareVideos(videos: List<SavedVideo>): Intent = container.savedVideos.shareIntent(videos)

    fun playVideo(video: SavedVideo): Intent = container.savedVideos.viewIntent(video)
    val orientation: StateFlow<ScreenOrientation> = container.display.orientation
    val adapter: StateFlow<BluetoothTarget?> = monitor.map { it.config.bluetooth }.keep(monitor.value.config.bluetooth)
    val finder: StateFlow<FinderState> = container.adapters.state

    fun searchAdapters() = container.adapters.start()

    fun stopAdapterSearch() = container.adapters.stop()

    /** Reading a real adapter: the fake bridge and the simulator are switched off so data is never mixed. */
    fun selectAdapter(target: BluetoothTarget) {
        container.adapters.stop()
        container.updateSource(container.sourceConfig.load().copy(bluetooth = target, tcpAddress = null, simulation = false))
    }

    fun forgetAdapter() = container.updateSource(container.sourceConfig.load().copy(bluetooth = null))

    private val _probe = MutableStateFlow<ProbeState>(ProbeState.Idle)
    val probe: StateFlow<ProbeState> = _probe.asStateFlow()

    /**
     * Tries the picked device before keeping it: releases the current adapter (it accepts one app at a time), tests
     * the new one and keeps it only if it answers as an ELM327; otherwise the previous source comes back.
     */
    fun testAdapter(found: FoundAdapter) {
        if (_probe.value is ProbeState.Testing) return
        container.adapters.stop()
        _probe.value = ProbeState.Testing(found.displayName)
        viewModelScope.launch {
            val previous = container.sourceConfig.load()
            container.updateSource(previous.copy(bluetooth = null))
            val result = withContext(Dispatchers.IO) {
                delay(RELEASE_MS)
                container.adapterProbe.probe(found)
            }
            when (result) {
                is ProbeResult.Compatible -> container.updateSource(previous.copy(bluetooth = result.target, tcpAddress = null, simulation = false))
                is ProbeResult.Failed -> container.updateSource(previous)
            }
            val outcome = when (result) {
                is ProbeResult.Compatible -> "compatible: ${result.identity} (${result.target.mode.tag})"
                is ProbeResult.Failed -> "failed: ${result.reason}"
            }
            container.log.log(if (result is ProbeResult.Failed) LogLevel.WARN else LogLevel.INFO, "bluetooth", "test of ${found.displayName} ${found.address}: $outcome")
            _probe.value = ProbeState.Done(found.displayName, result)
        }
    }

    fun clearProbe() {
        if (_probe.value !is ProbeState.Testing) _probe.value = ProbeState.Idle
    }

    fun setOrientation(value: ScreenOrientation) = container.display.setOrientation(value)

    fun markEvent() = monitor.value.session.markEvent()

    fun simulateStall() {
        (monitor.value.source as? StallSimulator)?.simulateStall()
    }

    fun readDtcs() {
        (monitor.value.source as? DtcReader)?.requestDtcs()
    }

    fun readDiagnostics() = runDiagnostic(false)
    fun clearDtcs() = runDiagnostic(true)

    private fun runDiagnostic(clear: Boolean) {
        if (_diagnosticBusy.value) return
        _diagnosticBusy.value = true
        _diagnosticResult.value = null
        viewModelScope.launch {
            try {
                val reader = monitor.value.source as? DiagnosticsReader
                val result = when {
                    reader == null -> DiagnosticResult(false, text(R.string.msg_o_app_nao_esta_conectado_ao_carro))
                    clear -> reader.clearDtcs()
                    else -> reader.requestDiagnostics()
                }
                _diagnosticResult.value = result.message
            } finally { _diagnosticBusy.value = false }
        }
    }

    fun setSimulation(enabled: Boolean) {
        container.updateSource(container.sourceConfig.load().copy(simulation = enabled))
    }

    val theme = container.display.theme
    val vehicle = container.vehicle.profile
    fun setTheme(value: AppTheme) = container.display.setTheme(value)
    fun setVehicle(value: VehicleProfile) = container.updateVehicle(value)
    fun saveApiKey(value: String) = container.serverConfig.save(container.serverConfig.load().copy(apiKey = value))
    fun apiKey(): String = container.serverConfig.load().apiKey

    fun serverUrl(): String = container.serverConfig.load().baseUrl

    companion object {
        /** Time for the released adapter connection to close before the test opens a new one. */
        private const val RELEASE_MS = 800L

        val Factory = viewModelFactory {
            initializer { MainViewModel((this[APPLICATION_KEY] as ObdBlackBoxApp).container) }
        }
    }
}

/** The adapter test shown in the picker. */
sealed interface ProbeState {
    data object Idle : ProbeState

    data class Testing(val name: String) : ProbeState

    data class Done(val name: String, val result: ProbeResult) : ProbeState
}
