package io.github.eliasmeireles.obdblackbox.data

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.domain.VehicleSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class SourceKind(private val labelId: Int, val fileTag: String) {
    SIMULATION(R.string.msg_simulacao, "sim"),
    OBD(R.string.msg_conectado, "obd");

    val label: String get() = text(labelId)
}

/** Where the data comes from right now; shown in the top bar. */
sealed interface SourceStatus {
    data object Simulated : SourceStatus

    data object Connecting : SourceStatus

    /** [simulatedCar] is true for the fake bridge on the PC; [bus] is the last CAN bus state reported. */
    data class Connected(val firmware: String, val simulatedCar: Boolean, val bus: String? = null) : SourceStatus

    data class Disconnected(val reason: String) : SourceStatus
}

interface VehicleDataSource {
    val kind: SourceKind
    val status: StateFlow<SourceStatus>

    fun snapshots(): Flow<VehicleSnapshot>
}

/** Implemented by sources that can play a scripted stall, for testing the alerts. */
fun interface StallSimulator {
    fun simulateStall()
}
