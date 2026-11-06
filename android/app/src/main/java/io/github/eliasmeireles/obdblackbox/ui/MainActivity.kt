package io.github.eliasmeireles.obdblackbox.ui

import io.github.eliasmeireles.obdblackbox.i18n.AppLocales
import io.github.eliasmeireles.obdblackbox.service.VideoNotifications
import io.github.eliasmeireles.obdblackbox.ui.diagnostics.PreferenceSettings
import io.github.eliasmeireles.obdblackbox.ui.diagnostics.ServerSettings
import io.github.eliasmeireles.obdblackbox.ui.diagnostics.VehicleSettings
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors
import io.github.eliasmeireles.obdblackbox.ui.diagnostics.EngineDiagnostics
import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eliasmeireles.obdblackbox.ObdBlackBoxApp
import io.github.eliasmeireles.obdblackbox.ui.videos.VideoActions
import io.github.eliasmeireles.obdblackbox.data.bridge.BluetoothTarget
import io.github.eliasmeireles.obdblackbox.data.bridge.LinkMode
import io.github.eliasmeireles.obdblackbox.ui.diagnostics.AdapterSettings
import io.github.eliasmeireles.obdblackbox.data.bridge.SourceConfig
import io.github.eliasmeireles.obdblackbox.data.upload.ServerConfig
import io.github.eliasmeireles.obdblackbox.service.MonitorService
import io.github.eliasmeireles.obdblackbox.ui.theme.ObdBlackBoxTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val videoConsent = ScreenRecordingConsent(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        applyDebugServerConfig(intent)
        requestNotificationPermission()
        MonitorService.start(this)
        setContent {
            val theme by viewModel.theme.collectAsStateWithLifecycle()
            val vehicle by viewModel.vehicle.collectAsStateWithLifecycle()
            ObdBlackBoxTheme(theme) {
                val dark = AppColors.dark
                androidx.compose.runtime.SideEffect {
                    @Suppress("DEPRECATION")
                    window.apply {
                        statusBarColor = AppColors.Background.toArgb()
                        navigationBarColor = AppColors.Background.toArgb()
                        if (Build.VERSION.SDK_INT >= 29) {
                            isStatusBarContrastEnforced = false
                            isNavigationBarContrastEnforced = false
                        }
                    }
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                val state by viewModel.state.collectAsStateWithLifecycle()
                val upload by viewModel.uploadStatus.collectAsStateWithLifecycle()
                val source by viewModel.sourceStatus.collectAsStateWithLifecycle()
                val diagnostic by viewModel.diagnostics.collectAsStateWithLifecycle()
                val diagnosticBusy by viewModel.diagnosticBusy.collectAsStateWithLifecycle()
                val diagnosticResult by viewModel.diagnosticResult.collectAsStateWithLifecycle()
                val supportsDiagnostics by viewModel.supportsDiagnostics.collectAsStateWithLifecycle()
                val dtcs by viewModel.dtcs.collectAsStateWithLifecycle()
                val canSimulateStall by viewModel.canSimulateStall.collectAsStateWithLifecycle()
                val canReadDtcs by viewModel.canReadDtcs.collectAsStateWithLifecycle()
                val simulation by viewModel.simulationEnabled.collectAsStateWithLifecycle()
                val orientation by viewModel.orientation.collectAsStateWithLifecycle()
                val adapter by viewModel.adapter.collectAsStateWithLifecycle()
                val finder by viewModel.finder.collectAsStateWithLifecycle()
                val probe by viewModel.probe.collectAsStateWithLifecycle()
                val video by viewModel.video.collectAsStateWithLifecycle()
                val videos by viewModel.videos.collectAsStateWithLifecycle()
                val recent by viewModel.recent.collectAsStateWithLifecycle()
                var showVideoNotice by rememberSaveable { mutableStateOf(false) }
                val videoPreferences = getSharedPreferences("video_notice", MODE_PRIVATE)
                if (showVideoNotice) VideoRecordingNotice(
                    onDismiss = { showVideoNotice = false },
                    onStart = {
                        showVideoNotice = false
                        videoPreferences.edit().putBoolean("accepted", true).apply()
                        videoConsent.toggle()
                    },
                )
                LaunchedEffect(orientation) { requestedOrientation = orientation.activityInfo }
                MainScreen(
                    engine = EngineDiagnostics(
                        supportsDiagnostics, diagnostic?.snapshot, diagnosticBusy, diagnosticResult,
                        viewModel::readDiagnostics, viewModel::clearDtcs,
                    ),
                    state = state,
                    upload = upload,
                    source = source,
                    dtcs = dtcs,
                    canReadDtcs = canReadDtcs,
                    onReadDtcs = viewModel::readDtcs,
                    server = ServerSettings(viewModel.serverUrl(), viewModel.apiKey(), viewModel::saveApiKey),
                    vehicle = VehicleSettings(vehicle, viewModel::setVehicle),
                    preferences = PreferenceSettings(
                        AppLocales.current(this@MainActivity),
                        { language ->
                            AppLocales.apply(this@MainActivity, language)
                            val container = (application as ObdBlackBoxApp).container
                            container.updateSource(container.sourceConfig.load())
                            MonitorService.start(this@MainActivity)
                            if (container.blackBox.state.value.recording) {
                                VideoNotifications(this@MainActivity).refreshRecording()
                            }
                            recreate()
                        }, theme, viewModel::setTheme,
                    ),
                    canSimulateStall = canSimulateStall,
                    simulation = simulation,
                    onSimulationChange = viewModel::setSimulation,
                    orientation = orientation,
                    onOrientationChange = viewModel::setOrientation,
                    adapter = AdapterSettings(
                        selected = adapter,
                        finder = finder,
                        probe = probe,
                        onSearch = viewModel::searchAdapters,
                        onStop = viewModel::stopAdapterSearch,
                        onTest = viewModel::testAdapter,
                        onProbeSeen = viewModel::clearProbe,
                        onForget = viewModel::forgetAdapter,
                    ),
                    onMarkEvent = viewModel::markEvent,
                    onSimulateStall = viewModel::simulateStall,
                    video = video,
                    onVideoToggle = {
                        if (video.recording || videoPreferences.getBoolean("accepted", false)) videoConsent.toggle()
                        else showVideoNotice = true
                    },
                    videos = videos,
                    recent = recent,
                    videoActions = VideoActions(
                        onRefresh = viewModel::refreshVideos,
                        onPlay = { runCatching { startActivity(viewModel.playVideo(it)) } },
                        onShare = { runCatching { startActivity(viewModel.shareVideos(it)) } },
                        onDelete = viewModel::deleteVideo,
                        onPlayRecent = { runCatching { startActivity(viewModel.playRecent(it)) } },
                        onShareRecent = { runCatching { startActivity(viewModel.shareRecent(it)) } },
                        onSaveRecent = viewModel::saveRecent,
                        onSaveAllRecent = viewModel::saveAllRecent,
                        onDeleteAllRecent = viewModel::deleteAllRecent,
                    ),
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Full screen in the car: status and navigation bars stay hidden; a swipe from the edge shows them briefly. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyDebugServerConfig(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Debug builds accept the server config from adb, so the long API key never has to be typed on the phone:
     * `adb shell am start -n <pkg>/.ui.MainActivity --es api_key <key>`.
     * `--es bridge_tcp localhost:35000` reads the fake bridge (`off` removes it); applied immediately.
     * `--es bridge_bt ble/<address>[/<name>]` (or `spp/…`) picks the ELM327 adapter (`off` removes it).
     */
    private fun applyDebugServerConfig(intent: Intent?) {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable || intent == null) return
        val container = (application as ObdBlackBoxApp).container
        intent.getStringExtra(EXTRA_BRIDGE_TCP)?.let { container.updateSource(container.sourceConfig.load().copy(tcpAddress = it.takeUnless { v -> v == "off" })) }
        intent.getStringExtra(EXTRA_BRIDGE_BT)?.let { container.updateSource(container.sourceConfig.load().copy(bluetooth = bluetoothTarget(it))) }
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val store = container.serverConfig
        store.save(store.load().copy(apiKey = key))
    }

    private companion object {
        const val EXTRA_KEY = "api_key"
        const val EXTRA_BRIDGE_TCP = "bridge_tcp"
        const val EXTRA_BRIDGE_BT = "bridge_bt"

        /** `ble/AA:BB:CC:DD:EE:FF[/name]` or `spp/…`; anything else (e.g. `off`) removes the adapter. */
        fun bluetoothTarget(value: String): BluetoothTarget? {
            val parts = value.split('/', limit = 3)
            val mode = LinkMode.fromTag(parts.getOrNull(0)) ?: return null
            val address = parts.getOrNull(1)?.uppercase() ?: return null
            return BluetoothTarget(address, parts.getOrNull(2) ?: address, mode)
        }
    }
}
