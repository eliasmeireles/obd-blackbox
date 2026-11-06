package io.github.eliasmeireles.obdblackbox.ui.diagnostics

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eliasmeireles.obdblackbox.data.SourceStatus
import io.github.eliasmeireles.obdblackbox.data.upload.UploadStatus
import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.SignalGroup
import io.github.eliasmeireles.obdblackbox.domain.format.formatPtOrDash
import io.github.eliasmeireles.obdblackbox.session.DashboardState
import io.github.eliasmeireles.obdblackbox.ui.clockTime
import io.github.eliasmeireles.obdblackbox.ui.dashboard.level
import io.github.eliasmeireles.obdblackbox.ui.theme.TabularNumbers
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors

/** Every live value, grouped, plus active alerts and recording info. */
@Composable
fun DiagnosticsScreen(
    state: DashboardState,
    upload: UploadStatus,
    server: ServerSettings,
    vehicle: VehicleSettings,
    source: SourceStatus,
    dtcs: BridgeDtcs,
    settings: Settings,
    engine: EngineDiagnostics,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.alerts.isNotEmpty()) {
            item { SectionTitle(text(R.string.msg_alertas_ativos)) }
            items(state.alerts) { ValueRow(it.message, "", "", it.severity.level().color) }
        }
        settingsSection(settings)
        vehicleSection(vehicle)
        engineSection(engine, source is SourceStatus.Connected)
        bridgeSection(source, if (engine.supported && engine.snapshot != null) dtcs.copy(canRead = false) else dtcs)
        signalSections(state)
        item { SectionTitle(text(R.string.msg_calculados)) }
        item { ValueRow(text(R.string.msg_consumo_estimado), state.fuelRateLph.formatPtOrDash(2), text(R.string.unit_fuel_rate)) }
        item { ValueRow(text(R.string.msg_consumo_instantaneo), state.instantKmPerLiter.formatPtOrDash(1), text(R.string.unit_economy)) }
        item { SectionTitle(text(R.string.msg_gravacao_406)) }
        item {
            SettingCard(text(R.string.msg_arquivo)) { Text(state.recordingName ?: "—", color = AppColors.Muted, fontSize = 13.sp) }
        }
        item { ValueRow(text(R.string.msg_eventos_marcados), state.eventCount.toString(), "") }
        serverSection(upload, server)
    }
}

private fun LazyListScope.signalSections(state: DashboardState) {
    SignalGroup.entries.forEach { group ->
        item(key = "group-$group") { SectionTitle(group.label) }
        items(Signal.entries.filter { it.group == group }, key = { it.name }) { signal ->
            ValueRow(signal.label, state.snapshot?.get(signal).formatPtOrDash(signal.decimals), signal.unit)
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        color = AppColors.Accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp, start = 4.dp),
    )
}

@Composable
internal fun ValueRow(label: String, value: String, unit: String, labelColor: Color = AppColors.Text) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AppColors.Surface).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, color = labelColor, fontSize = 15.sp, modifier = Modifier.weight(1f))
        // Bounded so a long value (an error message) wraps instead of squeezing the label to one letter per line.
        Text(
            value,
            color = AppColors.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            style = TabularNumbers,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp).widthIn(max = 220.dp),
        )
        if (unit.isNotEmpty()) Text(text(R.string.msg_value_409, (unit).toString()), color = AppColors.Muted, fontSize = 15.sp)
    }
}
