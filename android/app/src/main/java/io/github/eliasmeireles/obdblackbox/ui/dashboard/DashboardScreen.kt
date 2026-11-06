package io.github.eliasmeireles.obdblackbox.ui.dashboard

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.format.formatPtOrDash
import io.github.eliasmeireles.obdblackbox.session.DashboardState
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors

private val Gap = 8.dp

@Composable
fun DashboardScreen(state: DashboardState, modifier: Modifier = Modifier) {
    val speed = state.snapshot?.get(Signal.SPEED)
    val rpm = state.snapshot?.get(Signal.RPM)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap)) {
            if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SpeedGauge(speed, rpm, Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true))
                    TileGrid(state, Modifier.weight(1f).fillMaxHeight(), columns = 3)
                }
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SpeedGauge(speed, rpm, Modifier.size(minOf(maxWidth * 0.82f, maxHeight)))
                }
                TileGrid(state, Modifier.weight(1f).fillMaxWidth(), columns = 2)
            }
            FuelTile(state.fuelRange, Modifier.height(if (landscape) 94.dp else 108.dp))
            TripRow(state)
        }
    }
}

@Composable
private fun TileGrid(state: DashboardState, modifier: Modifier, columns: Int) {
    val s = state.snapshot
    val running = s?.isEngineRunning() == true
    val coolant = s?.get(Signal.COOLANT_TEMP)
    val volts = s?.get(Signal.MODULE_VOLTAGE)
    val average = state.trip.averageKmPerLiter
    val tiles: List<@Composable (Modifier) -> Unit> = listOf(
        { m -> ConsumptionTile(state, m) },
        { m -> MetricTile(text(R.string.msg_media), average.formatPtOrDash(1), text(R.string.unit_economy), Levels.known(average), m) },
        { m -> MetricTile(text(R.string.msg_motor_322), coolant.formatPtOrDash(0), text(R.string.unit_temperature), Levels.coolant(coolant), m) },
        { m -> MetricTile(text(R.string.msg_bateria), volts.formatPtOrDash(1), text(R.string.unit_voltage), Levels.voltage(volts, running), m) },
        { m -> PercentTile(text(R.string.msg_carga_324), s?.get(Signal.ENGINE_LOAD), m) },
        { m -> PercentTile(text(R.string.msg_borboleta_325), s?.get(Signal.THROTTLE), m) },
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Gap)) {
        tiles.chunked(columns).forEach { row ->
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Gap)) {
                row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

/** km/l while moving; stopped, km/l is meaningless, so it shows L/h. */
@Composable
private fun ConsumptionTile(state: DashboardState, modifier: Modifier) {
    val kmpl = state.instantKmPerLiter
    if (kmpl != null) {
        MetricTile(text(R.string.msg_consumo), kmpl.formatPtOrDash(1), text(R.string.unit_economy), Level.NORMAL, modifier)
    } else {
        MetricTile(text(R.string.msg_consumo), state.fuelRateLph.formatPtOrDash(1), text(R.string.unit_fuel_rate), Levels.known(state.fuelRateLph), modifier)
    }
}

@Composable
private fun PercentTile(label: String, value: Double?, modifier: Modifier) {
    MetricTile(label, value.formatPtOrDash(0), text(R.string.unit_percent), Levels.known(value), modifier, progress = ((value ?: 0.0) / 100.0).toFloat())
}

@Composable
private fun TripRow(state: DashboardState) {
    val trip = state.trip
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.Surface).padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        StatCell(text(R.string.msg_dist), text(R.string.msg_value_km, (trip.distanceKm.formatPtOrDash(1)).toString()), Modifier.weight(1f))
        StatCell(text(R.string.msg_comb), text(R.string.msg_value_l, (trip.fuelUsedL.formatPtOrDash(2)).toString()), Modifier.weight(1f))
        StatCell(text(R.string.unit_fuel_rate), state.fuelRateLph.formatPtOrDash(1), Modifier.weight(1f))
        StatCell(text(R.string.msg_max), text(R.string.msg_value_km_h, (trip.maxSpeedKmh.formatPtOrDash(0)).toString()), Modifier.weight(1f))
    }
}
