package io.github.eliasmeireles.obdblackbox

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.data.elm.ReceivedDiagnostic
import io.github.eliasmeireles.obdblackbox.data.bridge.DiagnosticsReader
import io.github.eliasmeireles.obdblackbox.data.bridge.DiagnosticResult
import android.content.Context
import android.os.Build
import io.github.eliasmeireles.obdblackbox.data.IdleSource
import io.github.eliasmeireles.obdblackbox.data.fuel.PreferencesFuelHistoryStore
import io.github.eliasmeireles.obdblackbox.data.SourceStatus
import io.github.eliasmeireles.obdblackbox.data.VehicleDataSource
import io.github.eliasmeireles.obdblackbox.data.bridge.BridgeSource
import io.github.eliasmeireles.obdblackbox.data.elm.AdapterFinder
import io.github.eliasmeireles.obdblackbox.data.elm.AdapterProbe
import io.github.eliasmeireles.obdblackbox.data.elm.ElmBluetoothTransport
import io.github.eliasmeireles.obdblackbox.data.elm.ElmTrace
import io.github.eliasmeireles.obdblackbox.data.elm.FileElmTrace
import io.github.eliasmeireles.obdblackbox.data.logs.CompositeElmTrace
import io.github.eliasmeireles.obdblackbox.data.logs.LogLevel
import io.github.eliasmeireles.obdblackbox.data.logs.RemoteElmTrace
import io.github.eliasmeireles.obdblackbox.data.logs.RemoteLog
import io.github.eliasmeireles.obdblackbox.data.video.BlackBoxController
import io.github.eliasmeireles.obdblackbox.data.video.RecentRecordings
import io.github.eliasmeireles.obdblackbox.data.video.SavedVideos
import io.github.eliasmeireles.obdblackbox.data.bridge.SourceConfig
import io.github.eliasmeireles.obdblackbox.data.bridge.SourceConfigStore
import io.github.eliasmeireles.obdblackbox.data.bridge.TcpBridgeTransport
import io.github.eliasmeireles.obdblackbox.data.recording.CompositeRecorder
import io.github.eliasmeireles.obdblackbox.data.recording.FileRecorderFactory
import io.github.eliasmeireles.obdblackbox.data.recording.RecorderFactory
import io.github.eliasmeireles.obdblackbox.data.sim.SimulatedSource
import io.github.eliasmeireles.obdblackbox.data.upload.FallbackMonitorApiClient
import io.github.eliasmeireles.obdblackbox.data.upload.HttpMonitorApiClient
import io.github.eliasmeireles.obdblackbox.data.upload.PreferencesServerConfigStore
import io.github.eliasmeireles.obdblackbox.data.upload.QueueRecorder
import io.github.eliasmeireles.obdblackbox.data.upload.ServerConfigStore
import io.github.eliasmeireles.obdblackbox.data.upload.SqliteUploadQueue
import io.github.eliasmeireles.obdblackbox.data.upload.UploadSignal
import io.github.eliasmeireles.obdblackbox.data.upload.UploadPolicy
import io.github.eliasmeireles.obdblackbox.data.upload.Uploader
import io.github.eliasmeireles.obdblackbox.data.vehicle.VehicleProfileStore
import io.github.eliasmeireles.obdblackbox.domain.vehicle.VehicleProfile
import io.github.eliasmeireles.obdblackbox.data.upload.WebSocketMonitorApiClient
import io.github.eliasmeireles.obdblackbox.session.MonitorSession
import io.github.eliasmeireles.obdblackbox.ui.DisplayPreferences
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The active source and the session reading it; replaced as a whole when the source settings change. */
data class Monitor(val config: SourceConfig, val source: VehicleDataSource, val session: MonitorSession)

/**
 * Wires the app. The source follows [SourceConfig]: simulator (opt-in), bridge over TCP (development), ELM327
 * adapter over Bluetooth, or idle. App logs go to [log] and from there to the server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val uploadPolicy = UploadPolicy(context.getString(R.string.server_api_url))
    private val queue = uploadPolicy.queue { SqliteUploadQueue(context) }
    private val started = AtomicBoolean(false)

    val logsDirectory: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
    val serverConfig: ServerConfigStore = PreferencesServerConfigStore(context)
    val sourceConfig = SourceConfigStore(context)
    val display = DisplayPreferences(context)
    val vehicle = VehicleProfileStore(context)
    private val uploadSignal = UploadSignal()
    private val apiClient = FallbackMonitorApiClient(
        primary = WebSocketMonitorApiClient(appVersion(context), commandHandler = { name -> executeDiagnosticCommand(name) }),
        fallback = HttpMonitorApiClient(appVersion(context)),
    )
    val uploader = Uploader(queue, apiClient, serverConfig, signal = uploadSignal, enabled = uploadPolicy.enabled)
    val log = RemoteLog(queue, uploadSignal, enabled = uploadPolicy.enabled)
    val blackBox = BlackBoxController()
    val savedVideos = SavedVideos(appContext)
    val recentRecordings = RecentRecordings(appContext)
    val adapters = AdapterFinder(appContext)
    private val elmTrace: ElmTrace = CompositeElmTrace(FileElmTrace(logsDirectory), RemoteElmTrace(log))
    val adapterProbe = AdapterProbe(appContext, elmTrace)
    private val recorders: RecorderFactory =
        if (uploadPolicy.enabled) CompositeRecorder.factory(FileRecorderFactory(logsDirectory), QueueRecorder.factory(queue, uploadSignal))
        else FileRecorderFactory(logsDirectory)

    private val _monitor = MutableStateFlow(newMonitor(sourceConfig.load()))
    val monitor: StateFlow<Monitor> = _monitor.asStateFlow()

    /** Starts reading/recording and the upload loop once per process. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        log.log(LogLevel.INFO, "app", "app started: version ${appVersion(appContext)}, Android ${Build.VERSION.RELEASE}, ${Build.MODEL}, source ${describe(_monitor.value.config)}")
        _monitor.value.session.start()
        uploadPolicy.startUploader { scope.launch(Dispatchers.IO) { uploader.run() } }
        scope.launch { logSourceChanges() }
        scope.launch {
            uploader.status.map { it.lastError }.distinctUntilChanged().filterNotNull().collect { log.log(LogLevel.WARN, "upload", it) }
        }
    }

    private suspend fun logSourceChanges() {
        _monitor.flatMapLatest { it.source.status }.distinctUntilChanged().collect { status ->
            val level = if (status is SourceStatus.Disconnected) LogLevel.WARN else LogLevel.INFO
            log.log(level, "source", status.toString())
        }
    }

    /** Saves the source settings and swaps the running session; the old one is closed first. */
    @Synchronized
    fun updateSource(config: SourceConfig) {
        sourceConfig.save(config)
        log.log(LogLevel.INFO, "source", "source changed: ${describe(config)}")
        val previous = _monitor.value
        previous.session.stop()
        val next = newMonitor(config)
        _monitor.value = next
        if (started.get()) next.session.start()
    }

    fun updateVehicle(profile: VehicleProfile) {
        vehicle.save(profile)
        updateSource(sourceConfig.load())
    }

    private suspend fun executeDiagnosticCommand(name: String): DiagnosticResult {
        val reader = _monitor.value.source as? DiagnosticsReader
        if (reader == null) {
            log.log(LogLevel.INFO, "diag", "remote request: $name")
            log.log(LogLevel.WARN, "diag", "$name: the app is not connected to the car")
            return DiagnosticResult(false, text(R.string.msg_o_app_nao_esta_conectado_ao_carro))
        }
        return when (name) {
            "clear_dtcs" -> reader.clearDtcs()
            "read_diagnostics" -> reader.requestDiagnostics()
            else -> DiagnosticResult(false, text(R.string.msg_comando_desconhecido))
        }
    }

    private fun newMonitor(config: SourceConfig): Monitor {
        lateinit var session: MonitorSession
        val source = createSource(config) { reading ->
            if (!uploadPolicy.enabled) return@createSource
            val recording = session.recordingSession()
            queue.enqueueDiagnostic(recording.id, reading.json)
            uploadSignal.notifyQueued()
        }
        val profile = vehicle.load()
        session = MonitorSession(source, recorders, scope, vehicle = profile, fuelHistoryStore = PreferencesFuelHistoryStore(appContext, profile.historyKey), onEvent = blackBox::protect)
        return Monitor(config, source, session)
    }

    private fun createSource(config: SourceConfig, save: (ReceivedDiagnostic) -> Unit): VehicleDataSource {
        if (config.simulation) return SimulatedSource()
        config.tcpEndpoint()?.let { (host, port) -> return BridgeSource(TcpBridgeTransport(host, port), saveDiagnostic = save, log = log) }
        config.bluetooth?.let { return BridgeSource(ElmBluetoothTransport(appContext, it, elmTrace), saveDiagnostic = save, log = log) }
        return IdleSource()
    }
}

private fun describe(config: SourceConfig): String = when {
    config.simulation -> "simulation"
    config.tcpAddress != null -> "TCP bridge ${config.tcpAddress}"
    config.bluetooth != null -> "adapter ${config.bluetooth.name} (${config.bluetooth.address}, ${config.bluetooth.mode.tag})"
    else -> "none"
}

private fun appVersion(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
