package io.github.eliasmeireles.obdblackbox.ui

import io.github.eliasmeireles.obdblackbox.ui.diagnostics.PreferenceSettings
import io.github.eliasmeireles.obdblackbox.ui.diagnostics.ServerSettings
import io.github.eliasmeireles.obdblackbox.ui.diagnostics.VehicleSettings
import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.ui.diagnostics.EngineDiagnostics
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eliasmeireles.obdblackbox.data.SourceStatus
import io.github.eliasmeireles.obdblackbox.data.bridge.DtcReading
import io.github.eliasmeireles.obdblackbox.data.upload.UploadStatus
import io.github.eliasmeireles.obdblackbox.data.video.BlackBoxState
import io.github.eliasmeireles.obdblackbox.session.DashboardState
import io.github.eliasmeireles.obdblackbox.ui.dashboard.DashboardScreen
import io.github.eliasmeireles.obdblackbox.ui.diagnostics.AdapterSettings
import io.github.eliasmeireles.obdblackbox.ui.diagnostics.BridgeDtcs
import io.github.eliasmeireles.obdblackbox.ui.diagnostics.DiagnosticsScreen
import io.github.eliasmeireles.obdblackbox.ui.diagnostics.Settings
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors
import io.github.eliasmeireles.obdblackbox.ui.videos.VideoActions
import io.github.eliasmeireles.obdblackbox.ui.videos.VideosScreen
import io.github.eliasmeireles.obdblackbox.data.video.RecentRecording
import io.github.eliasmeireles.obdblackbox.data.video.SavedVideo

@Composable
fun MainScreen(
    engine: EngineDiagnostics,
    state: DashboardState,
    upload: UploadStatus,
    source: SourceStatus,
    dtcs: DtcReading?,
    canReadDtcs: Boolean,
    onReadDtcs: () -> Unit,
    server: ServerSettings,
    vehicle: VehicleSettings,
    preferences: PreferenceSettings,
    canSimulateStall: Boolean,
    simulation: Boolean,
    onSimulationChange: (Boolean) -> Unit,
    orientation: ScreenOrientation,
    onOrientationChange: (ScreenOrientation) -> Unit,
    adapter: AdapterSettings,
    onMarkEvent: () -> Unit,
    onSimulateStall: () -> Unit,
    video: BlackBoxState,
    onVideoToggle: () -> Unit,
    videos: List<SavedVideo>,
    recent: List<RecentRecording>,
    videoActions: VideoActions,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
    Column(
        Modifier.fillMaxSize().background(AppColors.Background).safeDrawingPadding().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val actions = Actions(state.lastEvent, canSimulateStall, onMarkEvent, onSimulateStall, video, onVideoToggle)
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        BackHandler(enabled = tab != Tab.DASHBOARD) { tab = Tab.DASHBOARD }
        TopBar(state, source, video = video) {
            if (landscape && tab == Tab.DASHBOARD) {
                val (label, color) = uploadLabel(upload)
                Text(label, color = color, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(end = 6.dp))
                MarkEventButton(onMarkEvent, height = 34.dp)
            }
            MainMenu(tab, onScreen = { tab = it }, actions = actions, videoCount = videos.size)
        }
        AlertBanner(state.alerts)
        if (tab != Tab.DASHBOARD) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TabChip(text(R.string.msg_painel_298), selected = false) { tab = Tab.DASHBOARD }
                Text(tab.label, color = AppColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.DASHBOARD -> DashboardScreen(state)
                Tab.VIDEOS -> VideosScreen(videos, recent, videoActions)
                Tab.DIAGNOSTICS -> DiagnosticsScreen(state, upload, server, vehicle, source, BridgeDtcs(dtcs, canReadDtcs, onReadDtcs), Settings(simulation, onSimulationChange, orientation, onOrientationChange, adapter, preferences), engine)
            }
        }
        // Marcar evento and the upload status belong to the Painel; Dados and Vídeos keep the whole screen.
        if (!landscape && tab == Tab.DASHBOARD) ActionBar(actions, upload)
    }
}
