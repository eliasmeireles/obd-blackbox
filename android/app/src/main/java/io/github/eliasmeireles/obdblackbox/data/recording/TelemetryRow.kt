package io.github.eliasmeireles.obdblackbox.data.recording

import io.github.eliasmeireles.obdblackbox.data.SourceKind
import io.github.eliasmeireles.obdblackbox.domain.VehicleSnapshot
import io.github.eliasmeireles.obdblackbox.domain.alerts.Alert
import io.github.eliasmeireles.obdblackbox.domain.events.RecordedEvent

/** One recorded sample: raw signals plus what the app derived from them. */
data class TelemetryRow(
    val snapshot: VehicleSnapshot,
    val fuelRateLph: Double?,
    val alerts: List<Alert>,
    val event: RecordedEvent?,
    val fuelLitersEst: Double? = null,
    val fuelRangeKmEst: Double? = null,
)

/** Identity of one monitoring session (one app start or one reconnection). */
data class SessionInfo(val id: String, val kind: SourceKind, val startedAtMs: Long, val vehicle: String = "")

interface SessionRecorder : AutoCloseable {
    val name: String

    fun record(row: TelemetryRow)
}

fun interface RecorderFactory {
    fun create(session: SessionInfo): SessionRecorder
}
