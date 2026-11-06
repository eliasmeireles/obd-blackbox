package io.github.eliasmeireles.obdblackbox.session

import io.github.eliasmeireles.obdblackbox.data.SourceKind
import io.github.eliasmeireles.obdblackbox.domain.VehicleSnapshot
import io.github.eliasmeireles.obdblackbox.domain.alerts.Alert
import io.github.eliasmeireles.obdblackbox.domain.events.RecordedEvent
import io.github.eliasmeireles.obdblackbox.domain.trip.TripStats
import io.github.eliasmeireles.obdblackbox.domain.fuel.FuelRange

data class DashboardState(
    val sourceKind: SourceKind,
    val snapshot: VehicleSnapshot? = null,
    val fuelRateLph: Double? = null,
    val fuelRange: FuelRange = FuelRange(),
    val instantKmPerLiter: Double? = null,
    val trip: TripStats = TripStats(),
    val alerts: List<Alert> = emptyList(),
    val eventCount: Int = 0,
    val lastEvent: RecordedEvent? = null,
    val recordingName: String? = null,
)
